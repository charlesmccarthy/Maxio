-- 003: Row-level security policies

-- Enable RLS on all tables
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_pins ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_settings_blobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.addons ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.plugins ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watch_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.library_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watched_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.linked_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tv_login_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.avatar_catalog ENABLE ROW LEVEL SECURITY;

-- Profiles: own rows
CREATE POLICY profiles_own ON public.profiles
    FOR ALL USING (auth.uid() = user_id);

-- Profile PINs: own rows
CREATE POLICY profile_pins_own ON public.profile_pins
    FOR ALL USING (auth.uid() = user_id);

-- Profile settings blobs: own rows
CREATE POLICY settings_own ON public.profile_settings_blobs
    FOR ALL USING (auth.uid() = user_id);

-- Addons: own rows + linked device owner's (read-only)
CREATE POLICY addons_own ON public.addons
    FOR ALL USING (auth.uid() = user_id);
CREATE POLICY addons_linked_read ON public.addons
    FOR SELECT USING (
        user_id IN (
            SELECT owner_id FROM public.linked_devices
            WHERE device_user_id = auth.uid()
        )
    );

-- Plugins: own rows + linked device owner's (read-only)
CREATE POLICY plugins_own ON public.plugins
    FOR ALL USING (auth.uid() = user_id);
CREATE POLICY plugins_linked_read ON public.plugins
    FOR SELECT USING (
        user_id IN (
            SELECT owner_id FROM public.linked_devices
            WHERE device_user_id = auth.uid()
        )
    );

-- Watch progress: own rows (SECURITY DEFINER RPCs handle linked access)
CREATE POLICY wp_own ON public.watch_progress
    FOR ALL USING (auth.uid() = user_id);

-- Library items: own rows
CREATE POLICY lib_own ON public.library_items
    FOR ALL USING (auth.uid() = user_id);

-- Watched items: own rows
CREATE POLICY watched_own ON public.watched_items
    FOR ALL USING (auth.uid() = user_id);

-- Linked devices: owner can manage, device can read
CREATE POLICY linked_owner ON public.linked_devices
    FOR ALL USING (auth.uid() = owner_id);
CREATE POLICY linked_device_read ON public.linked_devices
    FOR SELECT USING (auth.uid() = device_user_id);

-- Sync codes: own codes
CREATE POLICY sync_codes_own ON public.sync_codes
    FOR ALL USING (auth.uid() = user_id);

-- TV login sessions: accessible by authenticated users (for QR flow)
CREATE POLICY tv_sessions_authenticated ON public.tv_login_sessions
    FOR ALL USING (auth.uid() IS NOT NULL);

-- Avatar catalog: readable by everyone
CREATE POLICY avatar_catalog_read ON public.avatar_catalog
    FOR SELECT USING (true);
