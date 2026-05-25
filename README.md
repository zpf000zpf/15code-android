# 15code Android

Android client for 15code. Version 1.2.2 is a native chat app that signs in with a 15code account, loads available models, and streams replies from the same 15code API platform used by the desktop app.

## Features

- Native Android login and chat interface
- 15code account session restore
- Model picker loaded from `/api/pricing`
- Chat completions through `cli.15code.com`
- Streaming replies with a stop button
- Automatic non-stream fallback when Android aborts a streaming socket
- 15code-branded launcher icon and splash theme
- New-chat action, saved model choice, and mobile-optimized message bubbles
- Balance/account status display after login
- GitHub Actions build for debug APK, release APK, and release AAB

## Build

This project is intended to build on GitHub Actions or a machine with Android SDK installed.

```bash
./gradlew assembleDebug
./gradlew bundleRelease
```

The release build uses the default unsigned Android artifact unless signing secrets are added to the workflow later.

## Release Flow

1. Commit changes.
2. Push a tag like `v1.1.0`.
3. GitHub Actions builds Android artifacts and attaches them to the release.
