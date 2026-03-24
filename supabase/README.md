# Maxio Supabase Backend Setup

This directory contains SQL migrations to set up your own Supabase backend for Maxio.

## Prerequisites

- A free [Supabase](https://supabase.com) account

## Setup Steps

### 1. Create a Supabase Project

1. Go to [supabase.com](https://supabase.com) and sign in
2. Click **New Project**
3. Choose a name (e.g., `maxio`), set a database password, pick the closest region
4. Wait for provisioning (~2 minutes)

### 2. Configure Auth

1. Go to **Authentication → Providers**
2. Ensure **Email** is enabled
3. Go to **Authentication → Settings** (under Email)
4. **Disable "Confirm email"** — for personal use, skip email verification
5. Optionally enable **"Allow anonymous sign-ins"** if you want QR login support

### 3. Run SQL Migrations

1. Go to **SQL Editor** in your Supabase dashboard
2. Run each migration file **in order** (001 through 010):
   - Copy the contents of each `.sql` file
   - Paste into the SQL Editor
   - Click **Run**
3. Each file should execute without errors

Migration order:
```
001_extensions.sql        → Enable pgcrypto
002_tables.sql            → Create 12 tables
003_rls_policies.sql      → Row-level security
004_functions_helpers.sql  → get_sync_owner() helper
005_functions_sync.sql     → 9 sync push/pull functions
006_functions_profiles.sql → 9 profile/PIN functions
007_functions_device_linking.sql → 5 device linking functions
008_functions_tv_login.sql → 2 TV login functions (optional)
009_functions_avatar.sql   → Avatar catalog function
010_seed_avatars.sql       → Placeholder avatar data
```

### 4. Set Up Storage (Optional)

For profile avatars:
1. Go to **Storage → New Bucket**
2. Name: `avatars`
3. Check **Public bucket**
4. Upload avatar PNG images (256x256 recommended)

### 5. Get Your Credentials

1. Go to **Project Settings → API**
2. Copy:
   - **Project URL** (e.g., `https://abcdefg.supabase.co`)
   - **anon public** key (starts with `eyJ...`)

### 6. Update App Config

Edit `local.properties` (and/or `local.dev.properties`) in the project root:

```properties
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
AVATAR_PUBLIC_BASE_URL=https://YOUR-PROJECT-REF.supabase.co/storage/v1/object/public
```

### 7. Build and Test

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

Install the APK on your Android TV device, create an account with email/password, and verify sync works.

## What Each Migration Does

| File | Description |
|------|-------------|
| `001` | Enables `pgcrypto` extension (for bcrypt PIN hashing, UUID generation) |
| `002` | Creates all 12 tables: profiles, profile_pins, profile_settings_blobs, addons, plugins, watch_progress, library_items, watched_items, linked_devices, sync_codes, tv_login_sessions, avatar_catalog |
| `003` | Row-level security policies — ensures users can only access their own data |
| `004` | `get_sync_owner()` — resolves effective user ID for linked-device scenarios |
| `005` | Sync push/pull/delete functions for watch progress, library, addons, plugins, watched items |
| `006` | Profile management: push/pull profiles, PIN set/clear/verify with rate limiting, settings blob sync |
| `007` | Device linking: generate/claim sync codes, unlink devices, sync overview |
| `008` | TV login session management (optional — only needed for QR-based login) |
| `009` | Avatar catalog query function |
| `010` | Seed data for default avatar options |

## Notes

- No Kotlin code changes needed — the app reads Supabase config from `BuildConfig` fields populated by `local.properties`
- The TV login Edge Function (`tv-logins-exchange`) is not included — use email/password login instead
- All sync functions use `SECURITY DEFINER` with `get_sync_owner()` to support multi-device linking
