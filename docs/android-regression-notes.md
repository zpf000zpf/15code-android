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
| v1.3.5 | current | Keeps the first-touch focus path but does not consume the touch event, allowing native EditText click/input handling to continue. |
| v1.3.6 | current | Keeps the v1.3.5 input fix and makes the smoke-test debug gate independent of generated `BuildConfig`. |
| v1.3.7 | current | Uses the system dialog composer as the primary Android input path. The bottom composer opens the dialog on one tap instead of relying on inline `EditText` focus. |
| v1.3.8 | current | Replaces the dialog composer with a bottom input sheet inspired by chat apps: tap the bottom bar, edit in a bottom panel, then send. |
| v1.3.9 | current | Adds a stable debug signing key so future GitHub debug APKs can be installed over the previous app without uninstalling, preserving SharedPreferences chat history. |
| v1.3.10 | current | Adds the visible web-search toggle while retaining automatic search mode. |
| v1.3.11 | current | Fixes streaming chat scrolling by using the measured message content height instead of focus-based `fullScroll`, so long answers remain viewable before generation finishes. |
| v1.3.12 | current | Reduces the streaming render interval from 180 ms to 100 ms so visible output follows the model more closely without returning to per-token screen churn. |
| v1.3.13 | current | Preserves multimodal image content through the platform and automatically switches image requests from text-only Qwen3.6 to GPT-5.6 Terra. |
| v1.3.14 | current | Opens cached chat immediately while account data refreshes in the background, and preserves local history on temporary network failures. |
| v1.4.1 | current | Introduces the mandatory production Release signature and retains the v1.4.0 interface behavior. Existing debug-signed installs require uninstall/reinstall migration. |
| v1.4.0 | previous | Simplifies the header, moves secondary actions into an overflow menu, combines account/model controls, and adds a removable image preview. |
| v1.4.4 | current | Uses the public `gpt-image-2` model for generation and editing, matching the server permission and pricing boundary. |
| v1.4.5 | pending release | Keeps generation and editing inside the active chat, renders results in the conversation, and adds presentation-friendly image size, quality and output-format choices while retaining the same `gpt-image-2` permission and pricing path. |
| v1.4.6 | pending release | Removes client-side image price prompts while preserving server-side permission checks, reservations, accounting and settlement. |
| v1.4.7 | pending release | Adopts the unified 15code brand icon across adaptive and legacy Android launchers. |
| v1.4.3 | previous | Adds permission-aware image generation and editing through `cli.15code.com/v1`, with preview, iterative editing, and gallery save. |
| v1.5.0 | release candidate | Restores the native inline bottom `EditText`, removes the popup composer and all manual IME/focus workarounds, keeps the input connection alive during streaming, and persists per-conversation drafts with Room plus a process-death fallback. |
| v1.5.0 | release candidate | Restores image cards with their original text timeline after reopening a conversation, preserves direct parent IDs across repeated edits, clears generated-version identity when switching to an album image, and samples previews off the UI thread. |

## Guardrails

- Keep `promptInput` as the one real, focusable native `EditText` in the bottom
  composer. Do not replace it with an `AlertDialog`, `PopupWindow`, fake input,
  or separate bottom sheet.
- Do not add click, touch, or focus listeners whose purpose is to request focus
  or call `showSoftInput()`. A normal tap must be handled entirely by
  `EditText` and Android's input-method manager.
- Only `adjustResize` owns IME layout. Do not measure keyboard height, translate
  the composer, or add keyboard-sized scroll padding; those create double
  avoidance on devices where the system already resizes the activity.
- While the current UI does not consume system-bar insets itself, keep Android
  15's edge-to-edge enforcement opted out so `adjustResize` has one predictable
  content area. If the app later adopts edge-to-edge, replace this with a single
  root-window insets implementation and test IME/system bars together first.
- Never disable `promptInput` during streaming. The send button may become a
  stop button, while the user keeps editing the next draft with the same input
  connection.
- After Stop is tapped, keep the send button in a disabled `Stopping` state
  until the old worker exits. The composer remains editable, but a second
  request must not share the first request's message array or connection state.
- A user-cancelled stream must not enter the ordinary non-streaming retry path,
  including when cancellation happens before the first response token arrives.
- Persist drafts per conversation after a short debounce, flush them when the
  activity pauses or the conversation changes, and restore without overwriting
  text typed after an asynchronous history load began.
- While Room history is loading, keep the native composer editable and persist
  its draft, but temporarily lock Send/New/Menu/Model. Otherwise a fast send or
  new-chat tap can race the older history snapshot and replace live state or
  persist an empty snapshot over an existing conversation.
- Freeze the selected model and search mode when a request starts. Later UI or
  account refreshes must not relabel or reroute a stream that is already active.
- Keep debug APK signing stable. Changing the signing key forces uninstall and
  deletes local app data, including chat history and session state.
- If streaming needs to prevent a second send, use the send button state. Avoid
  changing input focus behavior as part of streaming rendering fixes.
- Keep message `createdAt` values stable when replacing the bounded request
  history. Generated-image cards are merged by their completion timestamp, so
  rewriting text timestamps on every save changes the visible conversation.
- A generated image selected for another edit must carry its own version ID as
  `parentVersionId`. Album images have no generated parent; removing, sending,
  switching conversations, and starting a new chat must clear that identity.
- Decode image cards and attachment thumbnails with bounded sampled previews on
  an image worker. Never read and decode a full generated image on the UI
  thread; copy original files in bounded chunks for edits and gallery saves.
- During streaming, scroll to the measured message-list height after layout.
  Do not restore focus-based `fullScroll`, which can stop at the first screen
  while a selectable response bubble is still growing.
