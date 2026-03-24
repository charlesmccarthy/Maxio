-- 009: Avatar catalog function

-- get_avatar_catalog()
-- Client: AvatarRepository via postgrest.rpc("get_avatar_catalog")
-- Returns: decodeList<SupabaseAvatarCatalogItem>()
-- Columns: id, display_name, storage_path, category, sort_order, bg_color
CREATE OR REPLACE FUNCTION public.get_avatar_catalog()
RETURNS SETOF public.avatar_catalog
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
    SELECT * FROM public.avatar_catalog ORDER BY category, sort_order;
$$;
