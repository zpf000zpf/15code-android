# 15code Android

Android client for 15code. Version 1.4.7 is a native chat and image app that signs in with a 15code account, loads the public Catalog, and uses the same 15code API platform as the desktop app.

## Features

- Native Android login and chat interface
- 15code account session restore
- Model picker and release policy loaded from `/api/catalog`
- Session and API credentials encrypted with Android Keystore
- Room-backed multi-session history, search, pin, rename, delete, restore, and drafts
- Chat completions through `cli.15code.com`
- Streaming replies with a stop button
- Automatic non-stream fallback when Android aborts a streaming socket
- Local recent chat history
- Image attachment payloads for vision-capable models
- In-chat `gpt-image-2` generation and editing with results kept in the active conversation, landscape/square/portrait sizes, standard/high quality, PNG/JPEG/WebP output, and Gallery save
- 15code-branded launcher icon and splash theme
- New-chat action, saved model choice, and mobile-optimized message bubbles
- Balance/account status display after login
- GitHub Actions build for debug APK, release APK, and release AAB

## Build

This project is intended to build on GitHub Actions or a machine with Android SDK installed.

```bash
gradle assembleDebug
scripts/build_signed_release.sh
```

Release builds are mandatory-signed. The private release keystore and its secret seed are ignored by Git and must be backed up securely. See `docs/release-signing.md`.

## Release Flow

1. Commit changes.
2. Push a tag like `v1.1.0`.
3. GitHub Actions restores the signing material from repository secrets, builds signed APK/AAB artifacts, verifies the APK certificate, and attaches the artifacts to the release.
