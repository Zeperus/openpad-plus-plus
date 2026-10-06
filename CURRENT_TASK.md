# Current task

**Alpha 2 (`0.1.0-alpha.2`): editor focus/keyboard stability, list editing and Smart Checklist are done and published.**
Next (do not start without being asked): Milestone 8, checklist polish.

## Status
- Unit tests: 605, 0 failing (`./gradlew test`); lint clean; `assembleDebug` and `assembleDebugAndroidTest` build.
- Instrumented tests on GitHub (API 36 emulator, Test Orchestrator): **62/62 green** (ColdStart 1, NotesFlow 8, SessionFlow 16,
  ExternalFlow 9, RichEditor 13, StructuralEditing 15), run 37437654926; regular CI green.
- Version `0.1.0-alpha.2` (versionCode 2), shown at the bottom of Settings.

## What Alpha 2 changed
- **Root cause of the keyboard flicker:** each row kind was drawn by its own composable branch, so a kind change (paragraph <->
  list item) disposed the focused text field; joining rows deleted the focused row. Now all kinds share one structure and the
  operations keep the focused row's id (Enter: focused row = second half; Backspace-join: focused row survives). Details and
  the test strategy: docs/editor.md "Identity and focus".
- **Smart Checklist** (per-note metadata flag `smartChecklist` in `index.json`, overflow menu): check -> bottom, uncheck -> end of
  the unchecked group, one undo step, struck-through display, sibling groups of task items only, nested rows move with their item.
  Ordinary task lists are never sorted. Rules in `editor/Checklist.kt`.

## Architecture decisions (details in docs/)
- File name = note id. Session = `session.json`. Settings = DataStore. Note metadata (favorite, smart checklist, ...) in `index.json`.
- Markdown is always written from the document model; unsupported Markdown is a raw row.
- Row id = Compose key = focus target = caret owner. Never use `requestFocus()` to hide identity loss.
- Text fields hold plain text (+ invisible first character to detect Backspace at the start); formatting is a visual transformation.
- `appScope` MUST stay on `Dispatchers.Main.immediate`; `ColdStartTest` guards it.

## Known issues / limitations
- **Never run the Android emulator on the dev laptop.** Instrumented tests only via `gh workflow run instrumented.yml` / push.
  Local: `./gradlew test lint assembleDebug assembleDebugAndroidTest` (`source ~/.openpad-env.sh`).
- The emulator has no soft keyboard, so "keyboard did not flicker" is verified through its cause: no field is disposed by a
  structural edit and the focused field keeps its focus (`EditorDiagnostics`). Real Gboard/Samsung behaviour needs the phone check.
- Smart checklist: enforced on check/uncheck/Enter-on-completed/switching on; items added or converted otherwise stay where put.
  Groups mixing plain and task items are not reordered. Mode is per note (no global default).
- Selection works inside one row; copy gives plain text; tables/images/HTML are raw rows; caret/undo live only while the app runs.
- Alpha builds use the debug key: a build from another machine needs the old Alpha uninstalled first.
