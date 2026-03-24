-- 004: Helper functions

-- get_sync_owner(): Resolves the effective user ID for data operations.
-- If the caller is a linked device, returns the sync owner's user_id.
-- Otherwise returns the caller's own user_id.
-- Called by AuthManager.getEffectiveUserId() via postgrest.rpc("get_sync_owner")
-- Client decodes with: result.decodeAs<String>()
CREATE OR REPLACE FUNCTION public.get_sync_owner()
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_owner_id uuid;
BEGIN
    SELECT owner_id INTO v_owner_id
    FROM public.linked_devices
    WHERE device_user_id = v_uid
    LIMIT 1;

    IF v_owner_id IS NOT NULL THEN
        RETURN v_owner_id::text;
    END IF;

    RETURN v_uid::text;
END;
$$;
