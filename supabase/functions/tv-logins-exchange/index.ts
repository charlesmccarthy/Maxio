import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

// Admin client (service role) for DB operations and user creation
const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey, {
  auth: { autoRefreshToken: false, persistSession: false },
});

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // GET — serve the web login page
    if (req.method === "GET") {
      const url = new URL(req.url);
      const code = url.searchParams.get("code") || "";
      return new Response(getLoginPageHtml(code), {
        headers: { ...corsHeaders, "Content-Type": "text/html; charset=utf-8" },
      });
    }

    // POST — approve or exchange
    const body = await req.json();

    if (body.action === "approve") {
      return await handleApprove(body);
    } else {
      return await handleExchange(body);
    }
  } catch (e) {
    console.error("Edge function error:", e);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});

// Called by the web page after the phone user signs in
async function handleApprove(body: {
  code: string;
  user_access_token: string;
}) {
  const { code, user_access_token } = body;
  if (!code || !user_access_token) {
    return jsonResponse({ error: "Missing code or token" }, 400);
  }

  // Verify the phone user's token
  const {
    data: { user: phoneUser },
    error: authErr,
  } = await supabaseAdmin.auth.getUser(user_access_token);
  if (authErr || !phoneUser) {
    return jsonResponse({ error: "Invalid or expired token" }, 401);
  }

  // Look up the TV login session
  const { data: session, error: sessionErr } = await supabaseAdmin
    .from("tv_login_sessions")
    .select("*")
    .eq("code", code)
    .single();

  if (sessionErr || !session) {
    return jsonResponse({ error: "Session not found" }, 404);
  }
  if (session.status !== "pending") {
    return jsonResponse({ error: `Session already ${session.status}` }, 400);
  }
  if (new Date(session.expires_at) < new Date()) {
    return jsonResponse({ error: "Session expired" }, 400);
  }

  // Create a device user for the TV
  const tempEmail = `tv-${crypto.randomUUID()}@device.local`;
  const tempPassword = crypto.randomUUID();

  const { data: newUserData, error: createErr } =
    await supabaseAdmin.auth.admin.createUser({
      email: tempEmail,
      password: tempPassword,
      email_confirm: true,
      user_metadata: { is_tv_device: true, linked_to: phoneUser.id },
    });

  if (createErr || !newUserData?.user) {
    console.error("Failed to create device user:", createErr);
    return jsonResponse({ error: "Failed to create device user" }, 500);
  }

  // Sign in as the device user to get tokens
  const anonClient = createClient(supabaseUrl, anonKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: signInData, error: signInErr } =
    await anonClient.auth.signInWithPassword({
      email: tempEmail,
      password: tempPassword,
    });

  if (signInErr || !signInData?.session) {
    console.error("Failed to sign in device user:", signInErr);
    return jsonResponse({ error: "Failed to generate device session" }, 500);
  }

  // Link the device user to the phone user
  const { error: linkErr } = await supabaseAdmin.from("linked_devices").upsert(
    {
      owner_id: phoneUser.id,
      device_user_id: newUserData.user.id,
      device_name: session.device_name,
    },
    { onConflict: "owner_id,device_user_id" }
  );

  if (linkErr) {
    console.error("Failed to link device:", linkErr);
  }

  // Store tokens and mark session as approved
  const { error: updateErr } = await supabaseAdmin
    .from("tv_login_sessions")
    .update({
      status: "approved",
      authorized_user_id: phoneUser.id,
      access_token: signInData.session.access_token,
      refresh_token: signInData.session.refresh_token,
    })
    .eq("code", code);

  if (updateErr) {
    console.error("Failed to update session:", updateErr);
    return jsonResponse({ error: "Failed to approve session" }, 500);
  }

  return jsonResponse({ success: true, message: "TV authorized" });
}

// Called by the TV to retrieve the device tokens
async function handleExchange(body: { code: string; device_nonce: string }) {
  const { code, device_nonce } = body;
  if (!code || !device_nonce) {
    return jsonResponse({ error: "Missing code or device_nonce" }, 400);
  }

  const { data: session, error: sessionErr } = await supabaseAdmin
    .from("tv_login_sessions")
    .select("*")
    .eq("code", code)
    .eq("device_nonce", device_nonce)
    .single();

  if (sessionErr || !session) {
    return jsonResponse({ error: "Session not found" }, 404);
  }
  if (session.status !== "approved") {
    return jsonResponse(
      { error: `Session not approved (status: ${session.status})` },
      400
    );
  }
  if (!session.access_token || !session.refresh_token) {
    return jsonResponse({ error: "Tokens not ready" }, 400);
  }

  // Mark session as used
  await supabaseAdmin
    .from("tv_login_sessions")
    .update({ status: "used" })
    .eq("code", code);

  return jsonResponse({
    access_token: session.access_token,
    refresh_token: session.refresh_token,
    token_type: "Bearer",
    expires_in: 3600,
  });
}

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function getLoginPageHtml(code: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Maxio TV Login</title>
  <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.min.js"></script>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: #0a0a0f;
      color: #e0e0e0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .card {
      background: #16161f;
      border: 1px solid #2a2a3a;
      border-radius: 16px;
      padding: 32px;
      width: 100%;
      max-width: 380px;
      text-align: center;
    }
    h1 {
      font-size: 24px;
      font-weight: 700;
      margin-bottom: 4px;
      color: #fff;
    }
    .subtitle {
      color: #888;
      font-size: 14px;
      margin-bottom: 24px;
    }
    .code-badge {
      display: inline-block;
      background: #1e1e2e;
      border: 1px solid #3a3a4a;
      border-radius: 8px;
      padding: 6px 16px;
      font-family: monospace;
      font-size: 18px;
      letter-spacing: 2px;
      color: #7c9aff;
      margin-bottom: 24px;
    }
    input {
      width: 100%;
      padding: 12px 16px;
      background: #1e1e2e;
      border: 1px solid #3a3a4a;
      border-radius: 10px;
      color: #fff;
      font-size: 16px;
      margin-bottom: 12px;
      outline: none;
      transition: border-color 0.2s;
    }
    input:focus { border-color: #7c9aff; }
    input::placeholder { color: #555; }
    button {
      width: 100%;
      padding: 12px;
      background: #5b6eea;
      color: #fff;
      border: none;
      border-radius: 10px;
      font-size: 16px;
      font-weight: 600;
      cursor: pointer;
      margin-top: 4px;
      transition: background 0.2s;
    }
    button:hover { background: #4a5cd4; }
    button:disabled { background: #333; cursor: not-allowed; }
    .status {
      margin-top: 16px;
      padding: 10px;
      border-radius: 8px;
      font-size: 14px;
      display: none;
    }
    .status.error { display: block; background: #2a1515; color: #ff6e6e; border: 1px solid #4a2020; }
    .status.success { display: block; background: #152a15; color: #7cff9b; border: 1px solid #204a20; }
    .status.info { display: block; background: #15152a; color: #7c9aff; border: 1px solid #20204a; }
    .toggle {
      margin-top: 16px;
      color: #888;
      font-size: 13px;
    }
    .toggle a {
      color: #7c9aff;
      cursor: pointer;
      text-decoration: none;
    }
    .toggle a:hover { text-decoration: underline; }
    .success-icon {
      font-size: 48px;
      margin-bottom: 12px;
    }
  </style>
</head>
<body>
  <div class="card" id="loginCard">
    <h1>Maxio</h1>
    <p class="subtitle">Sign in to authorize your TV</p>
    ${code ? `<div class="code-badge">${escapeHtml(code)}</div>` : ""}
    <form id="authForm">
      <input type="email" id="email" placeholder="Email" required autocomplete="email">
      <input type="password" id="password" placeholder="Password" required autocomplete="current-password">
      <button type="submit" id="submitBtn">Sign In</button>
    </form>
    <div id="status" class="status"></div>
    <div class="toggle">
      <span id="toggleText">No account?</span>
      <a id="toggleLink" onclick="toggleMode()">Create one</a>
    </div>
  </div>
  <div class="card" id="successCard" style="display:none">
    <div class="success-icon">&#10003;</div>
    <h1>TV Authorized</h1>
    <p class="subtitle">Your TV should sign in automatically.<br>You can close this page.</p>
  </div>
  <script>
    const SUPABASE_URL = '${supabaseUrl}';
    const SUPABASE_ANON_KEY = '${anonKey}';
    const EDGE_FN_URL = SUPABASE_URL + '/functions/v1/tv-logins-exchange';
    const CODE = '${escapeHtml(code)}';

    const sb = supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
    let isSignUp = false;

    function toggleMode() {
      isSignUp = !isSignUp;
      document.getElementById('submitBtn').textContent = isSignUp ? 'Create Account' : 'Sign In';
      document.getElementById('toggleText').textContent = isSignUp ? 'Have an account?' : 'No account?';
      document.getElementById('toggleLink').textContent = isSignUp ? 'Sign in' : 'Create one';
      hideStatus();
    }

    function showStatus(msg, type) {
      const el = document.getElementById('status');
      el.textContent = msg;
      el.className = 'status ' + type;
    }
    function hideStatus() {
      document.getElementById('status').className = 'status';
    }

    document.getElementById('authForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const email = document.getElementById('email').value.trim();
      const password = document.getElementById('password').value;
      const btn = document.getElementById('submitBtn');
      btn.disabled = true;
      showStatus('Signing in...', 'info');

      try {
        let result;
        if (isSignUp) {
          result = await sb.auth.signUp({ email, password });
        } else {
          result = await sb.auth.signInWithPassword({ email, password });
        }

        if (result.error) throw result.error;
        if (!result.data?.session) throw new Error('No session returned');

        showStatus('Authorizing TV...', 'info');

        const approveRes = await fetch(EDGE_FN_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            action: 'approve',
            code: CODE,
            user_access_token: result.data.session.access_token
          })
        });
        const approveData = await approveRes.json();
        if (!approveRes.ok) throw new Error(approveData.error || 'Approval failed');

        document.getElementById('loginCard').style.display = 'none';
        document.getElementById('successCard').style.display = 'block';
      } catch (err) {
        showStatus(err.message || 'Something went wrong', 'error');
        btn.disabled = false;
      }
    });

    if (!CODE) {
      showStatus('No login code found. Scan the QR code on your TV.', 'error');
      document.getElementById('submitBtn').disabled = true;
    }
  </script>
</body>
</html>`;
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
