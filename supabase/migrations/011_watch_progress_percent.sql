-- 011: Persist progress_percent in the watch_progress round-trip.
-- Trakt only exposes a completion percentage (0..100), not absolute position/duration,
-- so items imported from Trakt carry their progress in progress_percent with position=0
-- and duration=0. The sync previously stored only position/duration, so those items came
-- back as 0% on other devices and dropped out of Continue Watching. Store the percent too.

ALTER TABLE public.watch_progress
    ADD COLUMN IF NOT EXISTS progress_percent real;

--------------------------------------------------------------------------------
-- sync_push_watch_progress: now also writes progress_percent
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_watch_progress(
    p_entries jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_entry jsonb;
BEGIN
    FOR v_entry IN SELECT * FROM jsonb_array_elements(p_entries)
    LOOP
        INSERT INTO public.watch_progress (
            user_id, content_id, content_type, video_id,
            season, episode, position, duration,
            last_watched, progress_key, profile_id, progress_percent
        ) VALUES (
            v_uid,
            v_entry->>'content_id',
            v_entry->>'content_type',
            v_entry->>'video_id',
            (v_entry->>'season')::int,
            (v_entry->>'episode')::int,
            (v_entry->>'position')::bigint,
            (v_entry->>'duration')::bigint,
            (v_entry->>'last_watched')::bigint,
            v_entry->>'progress_key',
            p_profile_id,
            (v_entry->>'progress_percent')::real
        )
        ON CONFLICT (user_id, progress_key, profile_id)
        DO UPDATE SET
            content_id = EXCLUDED.content_id,
            content_type = EXCLUDED.content_type,
            video_id = EXCLUDED.video_id,
            season = EXCLUDED.season,
            episode = EXCLUDED.episode,
            position = EXCLUDED.position,
            duration = EXCLUDED.duration,
            last_watched = EXCLUDED.last_watched,
            progress_percent = EXCLUDED.progress_percent;
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_watch_progress: now also returns progress_percent
-- (dropped first because adding a column changes the function's return type)
--------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.sync_pull_watch_progress(int);

CREATE OR REPLACE FUNCTION public.sync_pull_watch_progress(p_profile_id int)
RETURNS TABLE (
    id uuid,
    user_id uuid,
    content_id text,
    content_type text,
    video_id text,
    season int,
    episode int,
    "position" bigint,
    duration bigint,
    last_watched bigint,
    progress_key text,
    profile_id int,
    progress_percent real
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT wp.id, wp.user_id, wp.content_id, wp.content_type, wp.video_id,
           wp.season, wp.episode, wp.position, wp.duration,
           wp.last_watched, wp.progress_key, wp.profile_id, wp.progress_percent
    FROM public.watch_progress wp
    WHERE wp.user_id = v_uid AND wp.profile_id = p_profile_id;
END;
$$;
