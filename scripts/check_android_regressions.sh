#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/com/fifteencode/android/MainActivity.java"
GRADLE="$ROOT/app/build.gradle"
NOTES="$ROOT/docs/android-regression-notes.md"

fail() {
  echo "REGRESSION: $*" >&2
  exit 1
}

grep -q 'private static final String APP_VERSION = "1.4.0";' "$MAIN" \
  || fail "APP_VERSION must be 1.4.0"
grep -q 'versionName "1.4.0"' "$GRADLE" \
  || fail "Gradle versionName must be 1.4.0"
grep -q 'versionCode 28' "$GRADLE" \
  || fail "Gradle versionCode must be 28"
grep -q 'signingConfigs' "$GRADLE" \
  || fail "stable debug signing config is required"
grep -q '15code-debug.keystore' "$GRADLE" \
  || fail "debug build must use the stable 15code debug keystore"
[ -f "$ROOT/app/signing/15code-debug.keystore" ] \
  || fail "stable debug keystore file is missing"

grep -q 'SEARCH_CHAT = PLATFORM + "/api/search-chat"' "$MAIN" \
  || fail "Android chat must use platform /api/search-chat"
grep -q 'body.put("searchMode", "auto")' "$MAIN" \
  || fail "Android chat must enable automatic search mode"
grep -q 'Authorization", "Bearer " + sessionToken' "$MAIN" \
  || fail "search-chat must authenticate with the platform session token"

grep -q 'STREAM_RENDER_INTERVAL_MS = 100' "$MAIN" \
  || fail "stream rendering interval must stay paced for readability"
grep -q 'messageList.getHeight() - scroll.getHeight()' "$MAIN" \
  || fail "streaming chat must scroll using measured content height"
grep -q 'selectVisionModelForImage()' "$MAIN" \
  || fail "image attachments must switch away from the text-only default model"
grep -q '正在后台同步账户' "$MAIN" \
  || fail "saved sessions must open chat before background account refresh"
grep -q 'remove("sessionToken").remove("goKey")' "$MAIN" \
  || fail "session expiry must not clear local chat history"
grep -q 'PopupMenu menu = new PopupMenu' "$MAIN" \
  || fail "header actions must use the compact overflow menu"
grep -q 'attachmentPreview.setVisibility(View.VISIBLE)' "$MAIN" \
  || fail "image attachments must show a visible preview"

grep -q 'promptInput.setOnClickListener(v -> openComposerDialog())' "$MAIN" \
  || fail "bottom composer input must open the dialog composer"
grep -q 'composer.setOnClickListener(v -> openComposerDialog())' "$MAIN" \
  || fail "composer tap must open the dialog composer"
grep -q 'PopupWindow popup = new PopupWindow' "$MAIN" \
  || fail "composer must use a bottom input sheet"
grep -q 'popup.showAtLocation(root, Gravity.BOTTOM' "$MAIN" \
  || fail "composer sheet must appear from the bottom"
grep -q 'chat-composer-sheet-input' "$MAIN" \
  || fail "bottom sheet composer must expose a stable content description"

grep -q 'ApplicationInfo.FLAG_DEBUGGABLE' "$MAIN" \
  || fail "smoke test gate must use ApplicationInfo.FLAG_DEBUGGABLE"
grep -q 'chat-composer-input' "$MAIN" \
  || fail "composer must expose a stable content description for UI tests"

grep -q 'composer.setTranslationY(-keyboardHeight)' "$MAIN" \
  || fail "keyboard avoidance must move composer above keyboard"
grep -q 'getWindow().getDecorView().getWindowVisibleDisplayFrame' "$MAIN" \
  || fail "keyboard height must be based on visible window bounds"

grep -q 'First-tap handling must return `false`' "$NOTES" \
  || fail "regression notes must document the non-consuming touch requirement"

echo "Android regression checks passed"
