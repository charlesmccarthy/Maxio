-- 005: Sync push/pull/delete functions
-- All SECURITY DEFINER, use get_sync_owner() internally for linked-device support.
-- Parameter names must match exactly what the Kotlin client sends.

--------------------------------------------------------------------------------
-- sync_push_watch_progress(p_entries jsonb, p_profile_id int)
-- Client: WatchProgressSyncService.pushToRemote() / pushSingleToRemote()
-- Pattern: upsert on (user_id, progress_key, profile_id)
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
            last_watched, progress_key, profile_id
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
            p_profile_id
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
            last_watched = EXCLUDED.last_watched;
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_watch_progress(p_profile_id int)
-- Client: WatchProgressSyncService.pullFromRemote()
-- Returns: decodeList<SupabaseWatchProgress>()
-- Column names must match @SerialName in SupabaseWatchProgress
--------------------------------------------------------------------------------
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
    profile_id int
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
           wp.last_watched, wp.progress_key, wp.profile_id
    FROM public.watch_progress wp
    WHERE wp.user_id = v_uid AND wp.profile_id = p_profile_id;
END;
$$;

--------------------------------------------------------------------------------
-- sync_delete_watch_progress(p_keys jsonb, p_profile_id int)
-- Client: WatchProgressSyncService.deleteFromRemote()
-- p_keys is a JSON array of progress_key strings
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_delete_watch_progress(
    p_keys jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_key text;
BEGIN
    FOR v_key IN SELECT jsonb_array_elements_text(p_keys)
    LOOP
        DELETE FROM public.watch_progress
        WHERE user_id = v_uid
          AND progress_key = v_key
          AND profile_id = p_profile_id;
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_push_library(p_items jsonb, p_profile_id int)
-- Client: LibrarySyncService.pushToRemote()
-- Pattern: delete all for user+profile, then insert fresh
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_library(
    p_items jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_item jsonb;
BEGIN
    DELETE FROM public.library_items
    WHERE user_id = v_uid AND profile_id = p_profile_id;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        INSERT INTO public.library_items (
            user_id, content_id, content_type, name, poster,
            poster_shape, background, description, release_info,
            imdb_rating, genres, addon_base_url, added_at, profile_id
        ) VALUES (
            v_uid,
            v_item->>'content_id',
            v_item->>'content_type',
            COALESCE(v_item->>'name', ''),
            v_item->>'poster',
            COALESCE(v_item->>'poster_shape', 'POSTER'),
            v_item->>'background',
            v_item->>'description',
            v_item->>'release_info',
            (v_item->>'imdb_rating')::real,
            COALESCE(v_item->'genres', '[]'::jsonb),
            v_item->>'addon_base_url',
            COALESCE((v_item->>'added_at')::bigint, 0),
            p_profile_id
        );
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_library(p_profile_id int, p_limit int, p_offset int)
-- Client: LibrarySyncService.pullFromRemote() with pagination
-- Returns: decodeList<SupabaseLibraryItem>()
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_pull_library(
    p_profile_id int,
    p_limit int DEFAULT 500,
    p_offset int DEFAULT 0
)
RETURNS TABLE (
    id uuid,
    user_id uuid,
    content_id text,
    content_type text,
    name text,
    poster text,
    poster_shape text,
    background text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb,
    addon_base_url text,
    added_at bigint,
    profile_id int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT li.id, li.user_id, li.content_id, li.content_type, li.name,
           li.poster, li.poster_shape, li.background, li.description,
           li.release_info, li.imdb_rating, li.genres, li.addon_base_url,
           li.added_at, li.profile_id
    FROM public.library_items li
    WHERE li.user_id = v_uid AND li.profile_id = p_profile_id
    ORDER BY li.added_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

--------------------------------------------------------------------------------
-- sync_push_addons(p_addons jsonb, p_profile_id int)
-- Client: AddonSyncService.pushToRemote()
-- Pattern: delete all for user+profile, then insert fresh
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_addons(
    p_addons jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_addon jsonb;
BEGIN
    DELETE FROM public.addons
    WHERE user_id = v_uid AND profile_id = p_profile_id;

    FOR v_addon IN SELECT * FROM jsonb_array_elements(p_addons)
    LOOP
        INSERT INTO public.addons (
            user_id, url, name, enabled, sort_order, profile_id
        ) VALUES (
            v_uid,
            v_addon->>'url',
            v_addon->>'name',
            COALESCE((v_addon->>'enabled')::boolean, true),
            COALESCE((v_addon->>'sort_order')::int, 0),
            p_profile_id
        );
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_push_plugins(p_plugins jsonb, p_profile_id int)
-- Client: PluginSyncService.pushToRemote()
-- Pattern: delete all for user+profile, then insert fresh
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_plugins(
    p_plugins jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_plugin jsonb;
BEGIN
    DELETE FROM public.plugins
    WHERE user_id = v_uid AND profile_id = p_profile_id;

    FOR v_plugin IN SELECT * FROM jsonb_array_elements(p_plugins)
    LOOP
        INSERT INTO public.plugins (
            user_id, url, name, enabled, sort_order, profile_id
        ) VALUES (
            v_uid,
            v_plugin->>'url',
            v_plugin->>'name',
            COALESCE((v_plugin->>'enabled')::boolean, true),
            COALESCE((v_plugin->>'sort_order')::int, 0),
            p_profile_id
        );
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_push_watched_items(p_items jsonb, p_profile_id int)
-- Client: WatchedItemsSyncService.pushToRemote()
-- Pattern: delete all for user+profile, then insert fresh
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_push_watched_items(
    p_items jsonb,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
    v_item jsonb;
BEGIN
    DELETE FROM public.watched_items
    WHERE user_id = v_uid AND profile_id = p_profile_id;

    FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        INSERT INTO public.watched_items (
            user_id, content_id, content_type, title,
            season, episode, watched_at, profile_id
        ) VALUES (
            v_uid,
            v_item->>'content_id',
            v_item->>'content_type',
            COALESCE(v_item->>'title', ''),
            (v_item->>'season')::int,
            (v_item->>'episode')::int,
            (v_item->>'watched_at')::bigint,
            p_profile_id
        );
    END LOOP;
END;
$$;

--------------------------------------------------------------------------------
-- sync_pull_watched_items(p_profile_id int)
-- Client: WatchedItemsSyncService.pullFromRemote()
-- Returns: decodeList<SupabaseWatchedItem>()
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sync_pull_watched_items(p_profile_id int)
RETURNS TABLE (
    id uuid,
    user_id uuid,
    content_id text,
    content_type text,
    title text,
    season int,
    episode int,
    watched_at bigint,
    profile_id int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_uid uuid := (public.get_sync_owner())::uuid;
BEGIN
    RETURN QUERY
    SELECT wi.id, wi.user_id, wi.content_id, wi.content_type, wi.title,
           wi.season, wi.episode, wi.watched_at, wi.profile_id
    FROM public.watched_items wi
    WHERE wi.user_id = v_uid AND wi.profile_id = p_profile_id;
END;
$$;
