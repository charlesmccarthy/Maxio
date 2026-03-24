-- 002: Create all tables
-- Column names match @SerialName annotations in SupabaseModels.kt exactly.

-- User profiles (up to 4 per account)
CREATE TABLE IF NOT EXISTS public.profiles (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_index int NOT NULL,
    name text NOT NULL DEFAULT '',
    avatar_color_hex text NOT NULL DEFAULT '#1E88E5',
    uses_primary_addons boolean NOT NULL DEFAULT false,
    uses_primary_plugins boolean NOT NULL DEFAULT false,
    avatar_id text,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, profile_index)
);

-- Profile PIN locks
CREATE TABLE IF NOT EXISTS public.profile_pins (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_index int NOT NULL,
    pin_hash text NOT NULL,
    failed_attempts int NOT NULL DEFAULT 0,
    locked_until timestamptz,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, profile_index)
);

-- Profile settings blobs (theme, layout, player, etc.)
CREATE TABLE IF NOT EXISTS public.profile_settings_blobs (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_id int NOT NULL,
    settings_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz DEFAULT now(),
    UNIQUE(user_id, profile_id)
);

-- Stremio addon URLs
CREATE TABLE IF NOT EXISTS public.addons (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    url text NOT NULL,
    name text,
    enabled boolean NOT NULL DEFAULT true,
    sort_order int NOT NULL DEFAULT 0,
    profile_id int NOT NULL DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_addons_user_profile ON public.addons(user_id, profile_id);

-- Plugin repository URLs
CREATE TABLE IF NOT EXISTS public.plugins (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    url text NOT NULL,
    name text,
    enabled boolean NOT NULL DEFAULT true,
    sort_order int NOT NULL DEFAULT 0,
    profile_id int NOT NULL DEFAULT 1,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plugins_user_profile ON public.plugins(user_id, profile_id);

-- Watch progress (playback position tracking)
CREATE TABLE IF NOT EXISTS public.watch_progress (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id text NOT NULL,
    content_type text NOT NULL,
    video_id text NOT NULL,
    season int,
    episode int,
    position bigint NOT NULL DEFAULT 0,
    duration bigint NOT NULL DEFAULT 0,
    last_watched bigint NOT NULL DEFAULT 0,
    progress_key text NOT NULL,
    profile_id int NOT NULL DEFAULT 1,
    UNIQUE(user_id, progress_key, profile_id)
);
CREATE INDEX IF NOT EXISTS idx_wp_user_profile ON public.watch_progress(user_id, profile_id);

-- Library (saved movies/shows)
CREATE TABLE IF NOT EXISTS public.library_items (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id text NOT NULL,
    content_type text NOT NULL,
    name text NOT NULL DEFAULT '',
    poster text,
    poster_shape text NOT NULL DEFAULT 'POSTER',
    background text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb NOT NULL DEFAULT '[]'::jsonb,
    addon_base_url text,
    added_at bigint NOT NULL DEFAULT 0,
    profile_id int NOT NULL DEFAULT 1,
    UNIQUE(user_id, content_id, profile_id)
);
CREATE INDEX IF NOT EXISTS idx_lib_user_profile ON public.library_items(user_id, profile_id);

-- Watched items history
CREATE TABLE IF NOT EXISTS public.watched_items (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id text NOT NULL,
    content_type text NOT NULL,
    title text NOT NULL DEFAULT '',
    season int,
    episode int,
    watched_at bigint NOT NULL,
    profile_id int NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_watched_unique
    ON public.watched_items(user_id, content_id, content_type, COALESCE(season, -1), COALESCE(episode, -1), profile_id);
CREATE INDEX IF NOT EXISTS idx_watched_user_profile ON public.watched_items(user_id, profile_id);

-- Linked devices for multi-device sync
CREATE TABLE IF NOT EXISTS public.linked_devices (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name text,
    linked_at timestamptz DEFAULT now(),
    UNIQUE(owner_id, device_user_id)
);
CREATE INDEX IF NOT EXISTS idx_linked_owner ON public.linked_devices(owner_id);
CREATE INDEX IF NOT EXISTS idx_linked_device ON public.linked_devices(device_user_id);

-- Sync codes for device pairing
CREATE TABLE IF NOT EXISTS public.sync_codes (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code text NOT NULL UNIQUE,
    pin_hash text NOT NULL,
    created_at timestamptz DEFAULT now(),
    expires_at timestamptz DEFAULT (now() + interval '15 minutes'),
    claimed boolean NOT NULL DEFAULT false
);

-- TV login sessions (for QR-based auth flow)
CREATE TABLE IF NOT EXISTS public.tv_login_sessions (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    code text NOT NULL UNIQUE,
    device_nonce text NOT NULL,
    device_name text,
    redirect_base_url text,
    status text NOT NULL DEFAULT 'pending',
    authorized_user_id uuid REFERENCES auth.users(id),
    access_token text,
    refresh_token text,
    created_at timestamptz DEFAULT now(),
    expires_at timestamptz DEFAULT (now() + interval '10 minutes')
);

-- Avatar catalog (public, read-only)
CREATE TABLE IF NOT EXISTS public.avatar_catalog (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    storage_path text NOT NULL,
    category text NOT NULL DEFAULT 'default',
    sort_order int NOT NULL DEFAULT 0,
    bg_color text
);
