-- 007: Device linking and sync code functions

--------------------------------------------------------------------------------
-- generate_sync_code(p_pin text)
-- Client: SyncRepositoryImpl.generateSyncCode()
-- Returns: decodeList<SyncCodeResult>().firstOrNull() → {code: "ABC123"}
-- Generates a 6-character alphanumeric code, valid for 15 minutes
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.generate_sync_code(p_pin text)
RETURNS TABLE (code text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_code text;
    v_pin_hash text;
BEGIN
    -- Generate a random 6-character uppercase code
    v_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 6));
    v_pin_hash := crypt(p_pin, gen_salt('bf'));

    -- Delete any existing unexpired codes for this user
    DELETE FROM public.sync_codes WHERE user_id = v_uid AND claimed = false;

    INSERT INTO public.sync_codes (user_id, code, pin_hash, created_at, expires_at, claimed)
    VALUES (v_uid, v_code, v_pin_hash, now(), now() + interval '15 minutes', false);

    RETURN QUERY SELECT v_code;
END;
$$;

--------------------------------------------------------------------------------
-- get_sync_code(p_pin text)
-- Client: SyncRepositoryImpl.getSyncCode()
-- Returns existing active code for the user
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_sync_code(p_pin text)
RETURNS TABLE (code text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    RETURN QUERY
    SELECT sc.code
    FROM public.sync_codes sc
    WHERE sc.user_id = v_uid
      AND sc.claimed = false
      AND sc.expires_at > now()
      AND sc.pin_hash = crypt(p_pin, sc.pin_hash)
    ORDER BY sc.created_at DESC
    LIMIT 1;
END;
$$;

--------------------------------------------------------------------------------
-- claim_sync_code(p_code text, p_pin text, p_device_name text)
-- Client: SyncRepositoryImpl.claimSyncCode()
-- Returns: decodeList<ClaimSyncResult>().firstOrNull()
-- Columns: success (bool), message (text), result_owner_id (text)
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.claim_sync_code(
    p_code text,
    p_pin text,
    p_device_name text DEFAULT NULL
)
RETURNS TABLE (
    success boolean,
    message text,
    result_owner_id text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_sc record;
BEGIN
    -- Find the sync code
    SELECT * INTO v_sc
    FROM public.sync_codes sc
    WHERE sc.code = upper(p_code)
      AND sc.claimed = false
      AND sc.expires_at > now()
    LIMIT 1;

    IF v_sc IS NULL THEN
        RETURN QUERY SELECT false, 'Invalid or expired sync code'::text, NULL::text;
        RETURN;
    END IF;

    -- Verify PIN
    IF v_sc.pin_hash != crypt(p_pin, v_sc.pin_hash) THEN
        RETURN QUERY SELECT false, 'Incorrect PIN'::text, NULL::text;
        RETURN;
    END IF;

    -- Cannot link to yourself
    IF v_sc.user_id = v_uid THEN
        RETURN QUERY SELECT false, 'Cannot link to your own account'::text, NULL::text;
        RETURN;
    END IF;

    -- Create the link
    INSERT INTO public.linked_devices (owner_id, device_user_id, device_name)
    VALUES (v_sc.user_id, v_uid, p_device_name)
    ON CONFLICT (owner_id, device_user_id) DO UPDATE SET
        device_name = COALESCE(p_device_name, linked_devices.device_name);

    -- Mark code as claimed
    UPDATE public.sync_codes SET claimed = true WHERE id = v_sc.id;

    RETURN QUERY SELECT true, 'Device linked successfully'::text, v_sc.user_id::text;
END;
$$;

--------------------------------------------------------------------------------
-- unlink_device(p_device_user_id text)
-- Client: SyncRepositoryImpl.unlinkDevice()
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.unlink_device(p_device_user_id text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    DELETE FROM public.linked_devices
    WHERE owner_id = v_uid AND device_user_id = p_device_user_id::uuid;
END;
$$;

--------------------------------------------------------------------------------
-- get_sync_overview()
-- Client: AccountViewModel.loadSyncOverview()
-- Returns: decodeAs<SyncOverviewResponse>() (single JSON object, NOT a list)
-- Shape: { addons: {pid: count}, plugins: {...}, library_items: {...},
--          watch_progress: {...}, watched_items: {...},
--          profiles: {pid: {name, color}} }
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_sync_overview()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_result jsonb;
    v_addons jsonb := '{}'::jsonb;
    v_plugins jsonb := '{}'::jsonb;
    v_library jsonb := '{}'::jsonb;
    v_progress jsonb := '{}'::jsonb;
    v_watched jsonb := '{}'::jsonb;
    v_profiles jsonb := '{}'::jsonb;
    v_row record;
BEGIN
    -- Count addons per profile
    FOR v_row IN
        SELECT profile_id::text AS pid, count(*)::int AS cnt
        FROM public.addons WHERE user_id = v_uid
        GROUP BY profile_id
    LOOP
        v_addons := v_addons || jsonb_build_object(v_row.pid, v_row.cnt);
    END LOOP;

    -- Count plugins per profile
    FOR v_row IN
        SELECT profile_id::text AS pid, count(*)::int AS cnt
        FROM public.plugins WHERE user_id = v_uid
        GROUP BY profile_id
    LOOP
        v_plugins := v_plugins || jsonb_build_object(v_row.pid, v_row.cnt);
    END LOOP;

    -- Count library items per profile
    FOR v_row IN
        SELECT profile_id::text AS pid, count(*)::int AS cnt
        FROM public.library_items WHERE user_id = v_uid
        GROUP BY profile_id
    LOOP
        v_library := v_library || jsonb_build_object(v_row.pid, v_row.cnt);
    END LOOP;

    -- Count watch progress per profile
    FOR v_row IN
        SELECT profile_id::text AS pid, count(*)::int AS cnt
        FROM public.watch_progress WHERE user_id = v_uid
        GROUP BY profile_id
    LOOP
        v_progress := v_progress || jsonb_build_object(v_row.pid, v_row.cnt);
    END LOOP;

    -- Count watched items per profile
    FOR v_row IN
        SELECT profile_id::text AS pid, count(*)::int AS cnt
        FROM public.watched_items WHERE user_id = v_uid
        GROUP BY profile_id
    LOOP
        v_watched := v_watched || jsonb_build_object(v_row.pid, v_row.cnt);
    END LOOP;

    -- Profile info
    FOR v_row IN
        SELECT profile_index::text AS pid, name, avatar_color_hex AS color
        FROM public.profiles WHERE user_id = v_uid
        ORDER BY profile_index
    LOOP
        v_profiles := v_profiles || jsonb_build_object(
            v_row.pid,
            jsonb_build_object('name', v_row.name, 'color', v_row.color)
        );
    END LOOP;

    v_result := jsonb_build_object(
        'addons', v_addons,
        'plugins', v_plugins,
        'library_items', v_library,
        'watch_progress', v_progress,
        'watched_items', v_watched,
        'profiles', v_profiles
    );

    RETURN v_result;
END;
$$;
