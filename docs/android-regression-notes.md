# Android regression notes

This file records Android client input and keyboard behavior that must not be
accidentally reversed in later releases.

## Version history

| Version | Commit | Input / keyboard note |
| --- | --- | --- |
| v1.2.4 | 8892343 | Added fallback composer dialog when inline input was unreliable. |
| v1.2.5 | 54e1179 | Restored inline composer. |
| v1.2.6 | d36cf4d | Fixed keyboard covering the composer by translating the composer above the detected keyboard height and adding matching scroll padding. |
| v1.2.8 | 42f5014 | Tried to fix first tap focus with explicit touch/focus listeners. |
| v1.2.9 | 8d7b92e | Stabilized first tap by removing explicit touch/focus listeners and relying on native EditText behavior. |
| v1.3.0 | 4637ec7 | Reintroduced explicit focus helpers while adding update checks. This risked reversing the v1.2.9 input fix. |
| v1.3.1 | 6f3b031 | Streaming rendering was throttled, but touch/focus handling changed again. Input regressions reappeared. |
| v1.3.2 | a334cc9 | Attempted to force focus recovery, but still kept explicit touch/focus behavior and did not restore keyboard avoidance. |
| v1.3.3 | current | Restores the stable approach: native EditText input behavior from v1.2.9 plus keyboard avoidance from v1.2.6. |
| v1.3.4 | current | Adds a narrowly scoped first-touch focus path, computes keyboard avoidance from screen visible bounds, slows stream rendering to reading speed, and routes chat through `/api/search-chat` for desktop-equivalent web search. |

## Guardrails

- Do not add broad `setOnFocusChangeListener` loops to `promptInput`.
- If first-tap handling is needed, keep it limited to `ACTION_DOWN` when the
  input does not already have focus. Do not keep forcing focus while the user is
  editing.
- Do not repeatedly force `openKeyboard()` from focus changes. Native `EditText`
  should own normal tap-to-type behavior after the first focus.
- Keep keyboard avoidance tied to measured keyboard height:
  `composer.setTranslationY(-keyboardHeight)` and bottom scroll padding of
  `keyboardHeight + 12dp`.
- If streaming needs to prevent a second send, use the send button state. Avoid
  changing input focus behavior as part of streaming rendering fixes.
