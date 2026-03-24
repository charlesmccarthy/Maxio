-- 010: Seed avatar catalog with placeholder entries
-- Replace storage_path values with actual uploaded images in Supabase Storage.
-- Create a public bucket named "avatars" and upload PNG files there.

INSERT INTO public.avatar_catalog (id, display_name, storage_path, category, sort_order, bg_color)
VALUES
    ('default-blue', 'Blue', 'avatars/default-blue.png', 'default', 1, '#1E88E5'),
    ('default-red', 'Red', 'avatars/default-red.png', 'default', 2, '#E53935'),
    ('default-green', 'Green', 'avatars/default-green.png', 'default', 3, '#43A047'),
    ('default-purple', 'Purple', 'avatars/default-purple.png', 'default', 4, '#8E24AA'),
    ('default-orange', 'Orange', 'avatars/default-orange.png', 'default', 5, '#FB8C00'),
    ('default-teal', 'Teal', 'avatars/default-teal.png', 'default', 6, '#00897B')
ON CONFLICT (id) DO NOTHING;
