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

grep -q 'private static final String APP_VERSION = "1.4.4";' "$MAIN" \
  || fail "APP_VERSION must be 1.4.4"
grep -q 'versionName "1.4.4"' "$GRADLE" \
  || fail "Gradle versionName must be 1.4.4"
grep -q 'versionCode 32' "$GRADLE" \
  || fail "Gradle versionCode must be 32"
grep -q 'signingConfigs' "$GRADLE" \
  || fail "stable debug signing config is required"
grep -q '15code-debug.keystore' "$GRADLE" \
  || fail "debug build must use the stable 15code debug keystore"
[ -f "$ROOT/app/signing/15code-debug.keystore" ] \
  || fail "stable debug keystore file is missing"

grep -q 'SEARCH_CHAT = PLATFORM + "/api/search-chat"' "$MAIN" \
  || fail "Android chat must use platform /api/search-chat"
grep -q 'IMAGE_GENERATIONS = "https://cli.15code.com/v1/images/generations"' "$MAIN" \
  || fail "Android image generation must use cli.15code.com Images API"
grep -q 'IMAGE_EDITS = "https://cli.15code.com/v1/images/edits"' "$MAIN" \
  || fail "Android image editing must use cli.15code.com Images API"
grep -q 'body.put("model", "gpt-image-2")' "$MAIN" \
  || fail "Android image generation must use the public gpt-image-2 model"
grep -q '当前账号尚未开通图片权限' "$MAIN" \
  || fail "Android image UI must preserve the server permission boundary"
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
grep -q 'CATALOG = PLATFORM + "/api/catalog"' "$MAIN" \
  || fail "Android models must load from the public Catalog"
grep -q 'SUPPORTED_CATALOG_SCHEMA_VERSION = 1' "$MAIN" \
  || fail "Android must reject unknown Catalog schema versions safely"
grep -q 'capabilities.optBoolean("vision", false)' "$MAIN" \
  || fail "image support must come from Catalog capabilities"
grep -q 'catalogWarning = "离线目录 · 暂时无法刷新"' "$MAIN" \
  || fail "Catalog failures must preserve the last successful offline directory"
if grep -q 'PLATFORM + "/api/pricing"' "$MAIN"; then
  fail "Android model metadata must not fall back to the authenticated pricing endpoint"
fi
if grep -q '"qwen3.6".equals(selectedModel)' "$MAIN"; then
  fail "Android image routing must not hard-code qwen3.6"
fi
grep -q '正在后台同步账户' "$MAIN" \
  || fail "saved sessions must open chat before background account refresh"
if grep -q 'prefs.edit().putString("sessionToken"' "$MAIN" || grep -q 'prefs.edit().putString("goKey"' "$MAIN"; then
  fail "credentials must not remain in plaintext SharedPreferences"
fi
grep -q 'SecurePreferences' "$MAIN" \
  || fail "credentials must use Android Keystore encryption"
grep -q 'ChatDatabase.get' "$MAIN" \
  || fail "chat history must use Room"
grep -q 'room-runtime' "$GRADLE" \
  || fail "Room runtime dependency is required"
grep -q 'forceUpgradeBelow' "$MAIN" \
  || fail "Catalog minimum-version policy must be enforced"
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
