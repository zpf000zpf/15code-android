#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/com/fifteencode/android/MainActivity.java"
GRADLE="$ROOT/app/build.gradle"
NOTES="$ROOT/docs/android-regression-notes.md"
SMOKE="$ROOT/scripts/smoke_android_composer.sh"
REGRESSION_WORKFLOW="$ROOT/.github/workflows/android-regression.yml"

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
grep -Fq 'postJson(IMAGE_GENERATIONS, body, goKey, false, requestId)' "$MAIN" \
  || fail "Android image generation must send a stable idempotency request ID"
grep -q '"X-Client-Request-Id", requestId' "$MAIN" \
  || fail "Android image editing must send a stable idempotency request ID"
grep -q 'class ImageVersionEntity' "$ROOT/app/src/main/java/com/fifteencode/android/ImageVersionEntity.java" \
  || fail "Android image conversations must persist image versions"
grep -q 'addMigrations(MIGRATION_1_2, MIGRATION_2_3)' "$ROOT/app/src/main/java/com/fifteencode/android/ChatDatabase.java" \
  || fail "Android must preserve existing chat data while evolving image versions"
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
grep -q 'listRecentImageVersions(conversationId, MAX_HISTORY_IMAGE_VERSIONS)' "$MAIN" \
  || fail "reopened conversations must restore recent generated-image cards"
grep -q 'TimelineEntry.forImage(version' "$MAIN" \
  || fail "generated images must merge into the persisted conversation timeline"
grep -q 'msg.put("createdAt", row.createdAt)' "$MAIN" \
  || fail "restored messages must retain stable timeline timestamps"
grep -q 'item.optLong("createdAt", 0)' "$MAIN" \
  || fail "chat saves must preserve message timestamps instead of rewriting order"
grep -q 'parentVersionId = edit ? selectedImageVersionId : null' "$MAIN" \
  || fail "iterative image edits must persist their direct parent version"
grep -q 'selectedImageVersionId = null' "$MAIN" \
  || fail "album images and cleared attachments must not retain a generated-image parent"
grep -q 'selectedImageVersionId = version.id' "$MAIN" \
  || fail "continuing an image edit must select the generated parent version"
grep -q 'decodeSampledBitmap' "$MAIN" \
  || fail "large generated images must use sampled previews"
grep -q 'imagePreviewExecutor.execute' "$MAIN" \
  || fail "image preview and file preparation must stay off the main thread"
grep -q 'conn.setChunkedStreamingMode(8192)' "$MAIN" \
  || fail "image edits must stream multipart files instead of buffering the whole request"
grep -q 'attachmentPreview.setVisibility(View.VISIBLE)' "$MAIN" \
  || fail "image attachments must show a visible preview"
grep -q 'persistCurrentImageSelection();' "$MAIN" \
  || fail "pending image attachments must survive process recreation"
grep -q 'private String selectedImageConversationId;' "$MAIN" \
  || fail "pending image attachments must remain scoped to their conversation"
grep -q 'File attachment = persistAlbumAttachment(conversationId, bytes, mime);' "$MAIN" \
  || fail "album attachments must be copied into private app storage"
grep -A3 -q 'private void logout() {' "$MAIN" \
  || fail "logout must remain explicit"
if ! sed -n '/private void logout() {/,/securePrefs.remove("sessionToken")/p' "$MAIN" \
    | grep -q 'clearSelectedImageState();'; then
  fail "logout must clear pending attachments and owned private files"
fi

grep -q 'ApplicationInfo.FLAG_DEBUGGABLE' "$MAIN" \
  || fail "smoke test gate must use ApplicationInfo.FLAG_DEBUGGABLE"
grep -q 'chat-composer-input' "$MAIN" \
  || fail "composer must expose a stable content description for UI tests"
grep -q 'promptInput.setFocusable(true)' "$MAIN" \
  || fail "bottom composer must remain a native focusable EditText"
grep -q 'promptInput.setFocusableInTouchMode(true)' "$MAIN" \
  || fail "bottom composer must accept touch focus natively"
grep -q 'promptInput.setCursorVisible(true)' "$MAIN" \
  || fail "bottom composer must expose its native text cursor"
grep -q 'promptInput.addTextChangedListener(new TextWatcher()' "$MAIN" \
  || fail "bottom composer drafts must be observed continuously"
grep -q 'DRAFT_SAVE_DELAY_MS = 350' "$MAIN" \
  || fail "draft writes must stay debounced"
grep -q 'flushCurrentDraft();' "$MAIN" \
  || fail "drafts must flush on lifecycle and conversation boundaries"
grep -q 'restorePromptDraft(storedDraft)' "$MAIN" \
  || fail "saved Room drafts must restore into the inline composer"
grep -q 'android:windowSoftInputMode="adjustResize"' "$ROOT/app/src/main/AndroidManifest.xml" \
  || fail "the Android window must own keyboard resize behavior"
grep -q 'android:windowOptOutEdgeToEdgeEnforcement">true' "$ROOT/app/src/main/res/values-v35/styles.xml" \
  || fail "Android 15 must preserve system-managed non-edge-to-edge IME resizing"
grep -q 'replaceConversationMessages' "$ROOT/app/src/main/java/com/fifteencode/android/ChatDao.java" \
  || fail "chat history replacement must be transactional"
grep -q 'conversation.draft = existing.draft' "$ROOT/app/src/main/java/com/fifteencode/android/ChatDao.java" \
  || fail "chat-history writes must not overwrite a newer composer draft"
grep -Fq 'prefs.edit().putString(draftFallbackKey(currentConversationId), currentDraft).apply()' "$MAIN" \
  || fail "each text change must leave a process-death draft fallback"
grep -q '!currentConversationId.equals(promptDraftConversationId)' "$MAIN" \
  || fail "an unloaded blank composer must never overwrite a stored conversation draft"
grep -q 'boolean allowConversationChanges = !streaming && !historyLoadStarted' "$MAIN" \
  || fail "conversation switching must stay disabled while loading or streaming owns message state"
grep -q '&& !imageAttachmentLoading && !imageRequestRunning;' "$MAIN" \
  || fail "conversation switching must stay disabled while image state belongs to the active conversation"
grep -q 'if (historyLoadStarted) {' "$MAIN" \
  || fail "sending must wait for asynchronous conversation history to finish loading"
grep -q 'final String requestModel = selectedModel;' "$MAIN" \
  || fail "each stream must keep the model selected when the request started"
grep -q 'if (!streaming || stopRequested) return;' "$MAIN" \
  || fail "repeated stop taps must not reopen the streaming state"
grep -q 'sendButton.setText(stopRequested ? "停止中" : "停止")' "$MAIN" \
  || fail "the send button must stay locked until the stopped request exits"
grep -q 'sendButton.setEnabled(!stopRequested);' "$MAIN" \
  || fail "the stop control must be disabled until the stopped request exits"
grep -q 'if (stopRequested) {' "$MAIN" \
  || fail "a user-cancelled stream must never fall through to non-streaming retry"
grep -q 'imageExecutor.execute' "$MAIN" \
  || fail "long image requests must not block Room draft persistence"
grep -q 'smokeStreaming' "$MAIN" \
  || fail "debug composer smoke mode must cover streaming input"
grep -q 'runComposerStreamingSmokeFrame' "$MAIN" \
  || fail "streaming smoke mode must exercise repeated message-list updates"
grep -q 'assert_focused_and_keyboard_visible' "$SMOKE" \
  || fail "device smoke test must verify first-tap focus and IME visibility"
grep -q 'adjustResize did not move the composer' "$SMOKE" \
  || fail "device smoke test must verify the composer remains above the IME"
grep -q 'assert_text "\$STREAM_TEXT"' "$SMOKE" \
  || fail "device smoke test must verify typing during streaming"
grep -q 'assert_stop_is_locked' "$SMOKE" \
  || fail "device smoke test must verify stop stays locked while streaming exits"
grep -q 'parentVersionId = edit ? selectedImageVersionId : null' "$MAIN" \
  || fail "continuous image edits must retain the immediately selected generated parent"
grep -q 'listRecentImageVersions(conversationId, MAX_HISTORY_IMAGE_VERSIONS)' "$MAIN" \
  || fail "history restoration must reload persisted image versions"
grep -q 'Collections.sort(timeline, Comparator' "$MAIN" \
  || fail "history restoration must merge image cards into the stable message timeline"
grep -q 'android-emulator-runner@v2' "$REGRESSION_WORKFLOW" \
  || fail "CI must run the native composer smoke test on Android"
grep -Fq 'api-level: [28, 35]' "$REGRESSION_WORKFLOW" \
  || fail "CI composer smoke must cover both legacy and Android 15 IME behavior"

for forbidden in \
  'openComposerDialog' \
  'PopupWindow' \
  'chat-composer-sheet-input' \
  'promptInput.setOnClickListener' \
  'promptInput.setOnTouchListener' \
  'promptInput.setOnFocusChangeListener' \
  'composer.setOnClickListener' \
  'promptInput.setEnabled' \
  'showSoftInput' \
  'composer.setTranslationY' \
  'getWindowVisibleDisplayFrame'; do
  if grep -Fq "$forbidden" "$MAIN"; then
    fail "inline composer reintroduced forbidden input workaround: $forbidden"
  fi
done

grep -q 'Only `adjustResize` owns IME layout' "$NOTES" \
  || fail "regression notes must document the single keyboard-layout owner"

echo "Android regression checks passed"
