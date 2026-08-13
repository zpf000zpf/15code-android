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

grep -q 'private static final String APP_VERSION = "1.4.7";' "$MAIN" \
  || fail "APP_VERSION must be 1.4.7"
grep -q 'versionName "1.4.7"' "$GRADLE" \
  || fail "Gradle versionName must be 1.4.7"
grep -q 'versionCode 35' "$GRADLE" \
  || fail "Gradle versionCode must be 35"
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
if grep -q 'IMAGE_PRICING\|图片价格说明\|价格来源：OpenRouter\|单次最大预约' "$MAIN"; then
  fail "Android must not display image pricing prompts"
fi
grep -q 'body.put("model", "gpt-image-2")' "$MAIN" \
  || fail "Android image generation must use the public gpt-image-2 model"
grep -Fq 'new String[]{"横版 1536x1024", "方图 1024x1024", "竖版 1024x1536"}' "$MAIN" \
  || fail "Android image generation must expose presentation-friendly image sizes"
grep -q 'return quality.getSelectedItemPosition() == 1 ? "high" : "medium";' "$MAIN" \
  || fail "Android image generation must expose standard and high quality"
grep -q 'return index == 1 ? "jpeg" : index == 2 ? "webp" : "png";' "$MAIN" \
  || fail "Android image generation must preserve output format selection"
grep -q '当前账号尚未开通图片权限' "$MAIN" \
  || fail "Android image UI must preserve the server permission boundary"
grep -Fq 'postJson(IMAGE_GENERATIONS, body, goKey, false, "img-" + UUID.randomUUID())' "$MAIN" \
  || fail "Android image generation must send an idempotency request ID"
grep -q '"X-Client-Request-Id", "img-edit-" + UUID.randomUUID()' "$MAIN" \
  || fail "Android image editing must send an idempotency request ID"
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
grep -q 'attachButton.setContentDescription("添加图片或生成图片")' "$MAIN" \
  || fail "image generation must be accessible from the chat composer"
grep -q '在当前对话中生成/修改图片' "$MAIN" \
  || fail "image generation must stay in the current conversation"
grep -q 'messageList.addView(card, lp)' "$MAIN" \
  || fail "generated images must render in the active conversation"
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
