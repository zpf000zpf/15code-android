# 15code Android

Android client for 15code. This first version is a lightweight native shell around `https://15code.com`, so website updates, login, pricing, docs, recharge, and model changes stay synchronized with the main platform.

## Features

- Native Android WebView container
- 15code-branded launcher icon and splash theme
- JavaScript, DOM storage, file chooser, downloads, and external link handling
- Pull-to-refresh style reload from the toolbar
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
2. Push a tag like `v1.0.0`.
3. GitHub Actions builds Android artifacts and attaches them to the release.

