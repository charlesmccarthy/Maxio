-- 008: TV login session functions (optional)
-- These support QR-code-based login from a TV device.
-- For personal use with email/password auth, these are not strictly needed
-- but are included so the app doesn't crash if the UI is triggered.

--------------------------------------------------------------------------------
-- start_tv_login_session(p_device_nonce, p_redirect_base_url, p_device_name)
-- Client: AuthManager.startTvLoginSession()
-- Returns: decodeList<TvLoginStartResult>().firstOrNull()
-- Columns: code, web_url, expires_at, poll_interval_seconds
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.start_tv_login_session(
    p_device_nonce text,
    p_redirect_base_url text,
    p_device_name text DEFAULT NULL
)
RETURNS TABLE (
    code text,
    web_url text,
    expires_at timestamptz,
    poll_interval_seconds int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_code text;
    v_expires_at timestamptz;
BEGIN
    v_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8));
    v_expires_at := now() + interval '10 minutes';

    INSERT INTO public.tv_login_sessions (
        code, device_nonce, device_name, redirect_base_url,
        status, expires_at
    ) VALUES (
        v_code, p_device_nonce, p_device_name, p_redirect_base_url,
        'pending', v_expires_at
    );

    RETURN QUERY SELECT
        v_code,
        p_redirect_base_url || '?code=' || v_code,
        v_expires_at,
        3;
END;
$$;

--------------------------------------------------------------------------------
-- poll_tv_login_session(p_code, p_device_nonce)
-- Client: AuthManager.pollTvLoginSession()
-- Returns: decodeList<TvLoginPollResult>().firstOrNull()
-- Columns: status, expires_at, poll_interval_seconds
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.poll_tv_login_session(
    p_code text,
    p_device_nonce text
)
RETURNS TABLE (
    status text,
    expires_at timestamptz,
    poll_interval_seconds int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_session record;
BEGIN
    SELECT * INTO v_session
    FROM public.tv_login_sessions tls
    WHERE tls.code = p_code AND tls.device_nonce = p_device_nonce
    LIMIT 1;

    IF v_session IS NULL THEN
        RETURN QUERY SELECT 'not_found'::text, NULL::timestamptz, 3;
        RETURN;
    END IF;

    IF v_session.expires_at < now() THEN
        RETURN QUERY SELECT 'expired'::text, v_session.expires_at, 3;
        RETURN;
    END IF;

    RETURN QUERY SELECT v_session.status, v_session.expires_at, 3;
END;
$$;
