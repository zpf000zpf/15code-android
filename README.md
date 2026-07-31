# 15code Android

Android client for 15code. Version 1.4.1 is a native chat app that signs in with a 15code account, loads available models, and streams replies from the same 15code API platform used by the desktop app.

## Features

- Native Android login and chat interface
- 15code account session restore
- Model picker loaded from `/api/pricing`, optimized for phone selection
- Chat completions through `cli.15code.com`
- Streaming replies with a stop button
- Automatic non-stream fallback when Android aborts a streaming socket
- Local recent chat history
- Image attachment payloads for vision-capable models
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
