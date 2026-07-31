# Android release signing

The public debug keystore is only for development and upgrade-compatible debug builds. Production releases must use the private release key.

## Local files

The following files are intentionally ignored by Git:

- `app/signing/15code-release.jks`
- `app/signing/15code-release.secret`

Both files are required to reproduce the release signature. Store encrypted backups in at least two controlled locations. Losing either file prevents future upgrades signed with the same identity.

Release certificate SHA-256 fingerprint:

```text
5E:BC:67:38:6D:80:08:DE:AF:C7:88:83:06:23:5E:C5:30:B7:54:6F:89:C3:DD:1F:24:C5:F3:5E:0D:69:A2:BF
```

Build locally with:

```bash
scripts/build_signed_release.sh
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Verify it with Android SDK `apksigner` before publishing.

## GitHub Actions secrets

Configure these repository secrets without adding line breaks:

- `ANDROID_SIGNING_KEY_BASE64`: base64 of `15code-release.jks`
- `ANDROID_SIGNING_SECRET_BASE64`: base64 of `15code-release.secret`

The workflow derives the keystore passwords in memory, builds the signed APK/AAB, and verifies the APK certificate. Never upload the private files as workflow artifacts or attach them to a release.

## Migration note

The existing public APK uses the debug certificate. Android will not install a release-signed APK over that package as a normal update. Before public rollout, choose and document one migration path:

1. Ask current test users to export or preserve data, uninstall the debug build, and install the release build; or
2. Publish the release build under a new application ID and migrate users deliberately.

Do not rotate the production release key after rollout unless the distribution channel provides an approved key-upgrade mechanism.
