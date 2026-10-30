# Security policy

## Reporting a vulnerability

Please report security issues privately through GitHub's **Report a vulnerability** button on this repository's Security tab, rather than in a public issue. You can expect an acknowledgement within three working days.

## Scope

- The Cloudflare Worker in `backend/worker`
- The Supabase schema and Row Level Security policies in `backend/supabase`
- The iOS and Android apps

## How secrets are handled

- Nothing secret is committed. CI reads the Android upload key from the `ANDROID_SIGNING` Actions secret; the Worker reads optional feed credentials from Cloudflare secrets.
- Values committed or set as plain variables are public by design: the Supabase project URL and publishable key (protected by Row Level Security) and the Worker URL.
- Local configuration lives in git-ignored files (`android/secrets.properties`, `ios/Config/Secrets.xcconfig`) created from the committed `.example` templates.
