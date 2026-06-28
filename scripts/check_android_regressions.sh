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

grep -q 'private static final String APP_VERSION = "1.3.6";' "$MAIN" \
  || fail "APP_VERSION must be 1.3.6"
grep -q 'versionName "1.3.6"' "$GRADLE" \
  || fail "Gradle versionName must be 1.3.6"
grep -q 'versionCode 19' "$GRADLE" \
  || fail "Gradle versionCode must be 19"

grep -q 'SEARCH_CHAT = PLATFORM + "/api/search-chat"' "$MAIN" \
  || fail "Android chat must use platform /api/search-chat"
grep -q 'body.put("searchMode", "auto")' "$MAIN" \
  || fail "Android chat must enable automatic search mode"
grep -q 'Authorization", "Bearer " + sessionToken' "$MAIN" \
  || fail "search-chat must authenticate with the platform session token"

grep -q 'STREAM_RENDER_INTERVAL_MS = 180' "$MAIN" \
  || fail "stream rendering interval must stay paced for readability"

grep -q 'promptInput.setOnTouchListener' "$MAIN" \
  || fail "first-touch focus path is missing"
if awk '/promptInput\.setOnTouchListener/,/^\s*\}\);/' "$MAIN" | grep -q 'return true'; then
  fail "promptInput touch listener must not consume touch events"
fi
grep -q 'requestFocusFromTouch' "$MAIN" \
  || fail "first touch should request touch focus"

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
