-- 006: Profile, PIN, and settings functions

--------------------------------------------------------------------------------
-- sync_push_profiles(p_profiles jsonb)
-- Client: ProfileSyncService.pushToRemote()
-- Upserts profiles by (user_id, profile_index)
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_profiles(p_profiles jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_profile jsonb;
BEGIN
    FOR v_profile IN SELECT * FROM jsonb_array_elements(p_profiles)
    LOOP
        INSERT INTO public.profiles (
            user_id, profile_index, name, avatar_color_hex,
            uses_primary_addons, uses_primary_plugins, avatar_id, updated_at
        ) VALUES (
            v_uid,
            (v_profile->>'profile_index')::int,
            COALESCE(v_profile->>'name', ''),
            COALESCE(v_profile->>'avatar_color_hex', '#1E88E5'),
            COALESCE((v_profile->>'uses_primary_addons')::boolean, false),
            COALESCE((v_profile->>'uses_primary_plugins')::boolean, false),
            v_profile->>'avatar_id',
            now()
        )
        ON CONFLICT (user_id, profile_index)
        DO UPDATE SET
            name = EXCLUDED.name,
            avatar_color_hex = EXCLUDED.avatar_color_hex,
            uses_primary_addons = EXCLUDED.uses_primary_addons,
            uses_primary_plugins = EXCLUDED.uses_primary_plugins,
            avatar_id = EXCLUDED.avatar_id,
            updated_at = now();
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_profiles()
-- Client: ProfileSyncService.pullFromRemote()
-- Returns: decodeList<SupabaseProfile>()
-- Column names must match @SerialName in SupabaseProfile
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_pull_profiles()
RETURNS TABLE (
    id uuid,
    user_id uuid,
    profile_index int,
    name text,
    avatar_color_hex text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean,
    avatar_id text,
    created_at timestamptz,
    updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT p.id, p.user_id, p.profile_index, p.name, p.avatar_color_hex,
           p.uses_primary_addons, p.uses_primary_plugins, p.avatar_id,
           p.created_at, p.updated_at
    FROM public.profiles p
    WHERE p.user_id = v_uid
    ORDER BY p.profile_index;
END;
$$;

--------------------------------------------------------------------------------
-- sync_delete_profile_data(p_profile_id int)
-- Client: ProfileSyncService.deleteProfileData()
-- Deletes data from ALL profile-scoped tables for the given profile
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_delete_profile_data(p_profile_id int)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    DELETE FROM public.watch_progress WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.library_items WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.watched_items WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.addons WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.plugins WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.profile_settings_blobs WHERE user_id = v_uid AND profile_id = p_profile_id;
    DELETE FROM public.profiles WHERE user_id = v_uid AND profile_index = p_profile_id;
    DELETE FROM public.profile_pins WHERE user_id = v_uid AND profile_index = p_profile_id;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_profile_locks()
-- Client: ProfileSyncService.pullProfileLockStates()
-- Returns: decodeList<SupabaseProfileLockState>()
-- Column names: profile_index, pin_enabled, pin_locked_until
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_pull_profile_locks()
RETURNS TABLE (
    profile_index int,
    pin_enabled boolean,
    pin_locked_until timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT
        p.profile_index,
        EXISTS(
            SELECT 1 FROM public.profile_pins pp
            WHERE pp.user_id = v_uid AND pp.profile_index = p.profile_index
        ) AS pin_enabled,
        (
            SELECT pp.locked_until FROM public.profile_pins pp
            WHERE pp.user_id = v_uid AND pp.profile_index = p.profile_index
        ) AS pin_locked_until
    FROM public.profiles p
    WHERE p.user_id = v_uid
    ORDER BY p.profile_index;
END;
$$;

--------------------------------------------------------------------------------
-- set_profile_pin(p_profile_id int, p_pin text, p_current_pin text)
-- Client: ProfileSyncService.setProfilePin()
-- Stores bcrypt hash of the PIN. If current_pin provided, validates it first.
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.set_profile_pin(
    p_profile_id int,
    p_pin text,
    p_current_pin text DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_existing_hash text;
BEGIN
    -- If a PIN already exists and current_pin is provided, validate it
    SELECT pin_hash INTO v_existing_hash
    FROM public.profile_pins
    WHERE user_id = v_uid AND profile_index = p_profile_id;

    IF v_existing_hash IS NOT NULL AND p_current_pin IS NOT NULL THEN
        IF v_existing_hash != crypt(p_current_pin, v_existing_hash) THEN
            RAISE EXCEPTION 'Current PIN is incorrect';
        END IF;
    END IF;

    INSERT INTO public.profile_pins (user_id, profile_index, pin_hash, failed_attempts, updated_at)
    VALUES (v_uid, p_profile_id, crypt(p_pin, gen_salt('bf')), 0, now())
    ON CONFLICT (user_id, profile_index)
    DO UPDATE SET
        pin_hash = crypt(p_pin, gen_salt('bf')),
        failed_attempts = 0,
        locked_until = NULL,
        updated_at = now();
END;
$$;

--------------------------------------------------------------------------------
-- clear_profile_pin(p_profile_id int, p_current_pin text)
-- Client: ProfileSyncService.clearProfilePin()
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.clear_profile_pin(
    p_profile_id int,
    p_current_pin text DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_existing_hash text;
BEGIN
    IF p_current_pin IS NOT NULL THEN
        SELECT pin_hash INTO v_existing_hash
        FROM public.profile_pins
        WHERE user_id = v_uid AND profile_index = p_profile_id;

        IF v_existing_hash IS NOT NULL AND v_existing_hash != crypt(p_current_pin, v_existing_hash) THEN
            RAISE EXCEPTION 'Current PIN is incorrect';
        END IF;
    END IF;

    DELETE FROM public.profile_pins
    WHERE user_id = v_uid AND profile_index = p_profile_id;
END;
$$;

--------------------------------------------------------------------------------
-- verify_profile_pin(p_profile_id int, p_pin text)
-- Client: ProfileSyncService.verifyProfilePin()
-- Returns: decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
-- Columns: unlocked (bool), retry_after_seconds (int)
-- Rate limiting: 3 failures → 30 second lockout
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.verify_profile_pin(
    p_profile_id int,
    p_pin text
)
RETURNS TABLE (
    unlocked boolean,
    retry_after_seconds int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_pin_hash text;
    v_failed_attempts int;
    v_locked_until timestamptz;
    v_remaining_seconds int;
BEGIN
    SELECT pp.pin_hash, pp.failed_attempts, pp.locked_until
    INTO v_pin_hash, v_failed_attempts, v_locked_until
    FROM public.profile_pins pp
    WHERE pp.user_id = v_uid AND pp.profile_index = p_profile_id;

    -- No PIN set → unlocked
    IF v_pin_hash IS NULL THEN
        RETURN QUERY SELECT true, 0;
        RETURN;
    END IF;

    -- Check if locked out
    IF v_locked_until IS NOT NULL AND v_locked_until > now() THEN
        v_remaining_seconds := GREATEST(1, EXTRACT(EPOCH FROM (v_locked_until - now()))::int);
        RETURN QUERY SELECT false, v_remaining_seconds;
        RETURN;
    END IF;

    -- Verify PIN
    IF v_pin_hash = crypt(p_pin, v_pin_hash) THEN
        -- Correct: reset failures
        UPDATE public.profile_pins
        SET failed_attempts = 0, locked_until = NULL, updated_at = now()
        WHERE user_id = v_uid AND profile_index = p_profile_id;

        RETURN QUERY SELECT true, 0;
    ELSE
        -- Wrong: increment failures
        v_failed_attempts := COALESCE(v_failed_attempts, 0) + 1;

        IF v_failed_attempts >= 3 THEN
            UPDATE public.profile_pins
            SET failed_attempts = v_failed_attempts,
                locked_until = now() + interval '30 seconds',
                updated_at = now()
            WHERE user_id = v_uid AND profile_index = p_profile_id;

            RETURN QUERY SELECT false, 30;
        ELSE
            UPDATE public.profile_pins
            SET failed_attempts = v_failed_attempts, updated_at = now()
            WHERE user_id = v_uid AND profile_index = p_profile_id;

            RETURN QUERY SELECT false, 0;
        END IF;
    END IF;
END;
$$;

--------------------------------------------------------------------------------
-- sync_push_profile_settings_blob(p_profile_id int, p_settings_json jsonb)
-- Client: ProfileSettingsSyncService.pushCurrentProfileToRemote()
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_profile_settings_blob(
    p_profile_id int,
    p_settings_json jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    INSERT INTO public.profile_settings_blobs (user_id, profile_id, settings_json, updated_at)
    VALUES (v_uid, p_profile_id, p_settings_json, now())
    ON CONFLICT (user_id, profile_id)
    DO UPDATE SET
        settings_json = p_settings_json,
        updated_at = now();
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_profile_settings_blob(p_profile_id int)
-- Client: ProfileSettingsSyncService.pullCurrentProfileFromRemote()
-- Returns: decodeList<SupabaseProfileSettingsBlob>()
-- Columns: profile_id, settings_json, updated_at
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_pull_profile_settings_blob(p_profile_id int)
RETURNS TABLE (
    profile_id int,
    settings_json jsonb,
    updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT psb.profile_id, psb.settings_json, psb.updated_at
    FROM public.profile_settings_blobs psb
    WHERE psb.user_id = v_uid AND psb.profile_id = p_profile_id;
END;
$$;
