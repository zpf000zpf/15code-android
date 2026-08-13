#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb}"
PACKAGE="com.fifteencode.android"
ACTIVITY="$PACKAGE/.MainActivity"
INPUT_DESC="chat-composer-input"
SMOKE_ID="composer-$(date +%s)"
ASCII_TEXT="NativeIme123"
STREAM_TEXT="DraftWhileStreaming456"
STOPPING_TEXT="停止中"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "COMPOSER SMOKE FAILED: $*" >&2
  exit 1
}

if [ ! -x "$ADB" ]; then
  fail "adb not found; set ANDROID_HOME, ANDROID_SDK_ROOT or ADB"
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
  fail "no Android device or emulator is connected"
fi
"$ADB" shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null
if [ ! -f "$APK" ]; then
  if [ -x "$ROOT/gradlew" ]; then
    GRADLE_BIN="$ROOT/gradlew"
  else
    GRADLE_BIN="$(command -v gradle || true)"
  fi
  [ -n "$GRADLE_BIN" ] || fail "debug APK is missing and Gradle is unavailable"
  "$GRADLE_BIN" --no-daemon assembleDebug
fi

start_smoke() {
  local reset="$1"
  local streaming="$2"
  "$ADB" shell am force-stop "$PACKAGE"
  "$ADB" shell am start -W -n "$ACTIVITY" \
    --ez smokeComposer true \
    --es smokeConversationId "$SMOKE_ID" \
    --ez smokeResetDraft "$reset" \
    --ez smokeStreaming "$streaming" >/dev/null
  sleep 1
}

dump_ui() {
  "$ADB" shell uiautomator dump /sdcard/15code-composer.xml >/dev/null
  "$ADB" pull /sdcard/15code-composer.xml "$1" >/dev/null
}

wait_for_composer() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    dump_ui "$TMP_DIR/ui.xml"
    if grep -Fq "content-desc=\"$INPUT_DESC\"" "$TMP_DIR/ui.xml"; then
      return
    fi
    sleep 0.5
  done
  fail "native bottom composer was not found"
}

input_bounds() {
  sed -n "s/.*content-desc=\"$INPUT_DESC\"[^>]*bounds=\"\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]\".*/\1 \2 \3 \4/p" "$1" | head -1
}

tap_composer() {
  wait_for_composer
  read -r left top right bottom <<<"$(input_bounds "$TMP_DIR/ui.xml")"
  [ -n "${left:-}" ] || fail "native bottom composer was not found"
  echo "$bottom" >"$TMP_DIR/composer-bottom-before-ime"
  "$ADB" shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
  sleep 1
}

assert_focused_and_keyboard_visible() {
  dump_ui "$TMP_DIR/focused.xml"
  grep -Eq "content-desc=\"$INPUT_DESC\"[^>]*focused=\"true\"" "$TMP_DIR/focused.xml" \
    || fail "first tap did not focus the native EditText"
  read -r _ _ _ bottom_after <<<"$(input_bounds "$TMP_DIR/focused.xml")"
  bottom_before="$(cat "$TMP_DIR/composer-bottom-before-ime")"
  [ -n "${bottom_after:-}" ] || fail "composer disappeared after the keyboard opened"
  if [ "$bottom_after" -ge "$((bottom_before - 80))" ]; then
    fail "adjustResize did not move the composer above the software keyboard"
  fi
  "$ADB" shell dumpsys input_method >"$TMP_DIR/ime.txt"
  "$ADB" shell dumpsys window insets >"$TMP_DIR/window-insets.txt" 2>/dev/null || true
  grep -Eq 'mInputShown=true|mIsInputViewShown=true|inputShown=true' "$TMP_DIR/ime.txt" \
    || grep -Eq 'ime.*visible=true|visible.*ime' "$TMP_DIR/window-insets.txt" \
    || fail "software keyboard did not become visible"
}

assert_text() {
  local expected="$1"
  dump_ui "$TMP_DIR/text.xml"
  grep -Eq "content-desc=\"$INPUT_DESC\"[^>]*text=\"$expected\"|text=\"$expected\"[^>]*content-desc=\"$INPUT_DESC\"" "$TMP_DIR/text.xml" \
    || fail "composer text is not '$expected'"
}

tap_button_with_text() {
  local text="$1"
  dump_ui "$TMP_DIR/button.xml"
  read -r left top right bottom <<<"$(sed -n "s/.*text=\"$text\"[^>]*bounds=\"\\[\\([0-9]*\\),\\([0-9]*\\)\\]\\[\\([0-9]*\\),\\([0-9]*\\)\\]\".*/\\1 \\2 \\3 \\4/p" "$TMP_DIR/button.xml" | head -1)"
  [ -n "${left:-}" ] || fail "button '$text' was not found"
  "$ADB" shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
}

assert_stop_is_locked() {
  dump_ui "$TMP_DIR/stopping.xml"
  grep -Eq "text=\"$STOPPING_TEXT\"[^>]*enabled=\"false\"|enabled=\"false\"[^>]*text=\"$STOPPING_TEXT\"" "$TMP_DIR/stopping.xml" \
    || fail "Stop did not remain locked while the active stream exits"
}

"$ADB" install -r "$APK" >/dev/null
start_smoke true false
tap_composer
assert_focused_and_keyboard_visible
"$ADB" shell input text "$ASCII_TEXT"
assert_text "$ASCII_TEXT"

"$ADB" shell input keyevent KEYCODE_BACK
sleep 1
tap_composer
assert_focused_and_keyboard_visible

"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$ACTIVITY" \
  --ez smokeComposer true \
  --es smokeConversationId "$SMOKE_ID" \
  --ez smokeResetDraft false \
  --ez smokeStreaming false >/dev/null
sleep 1
assert_text "$ASCII_TEXT"

start_smoke true true
tap_composer
assert_focused_and_keyboard_visible
"$ADB" shell input text "$STREAM_TEXT"
assert_text "$STREAM_TEXT"
dump_ui "$TMP_DIR/stream.xml"
grep -Eq "content-desc=\"$INPUT_DESC\"[^>]*focused=\"true\"" "$TMP_DIR/stream.xml" \
  || fail "streaming updates stole focus from the composer"
grep -Eq 'text="停止"' "$TMP_DIR/stream.xml" \
  || fail "streaming smoke mode did not activate the stop button"
tap_button_with_text "停止"
sleep 0.3
assert_stop_is_locked

echo "Android native composer smoke passed"
