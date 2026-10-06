# Current task

**Alpha 3 (`0.1.0-alpha.3`): selection across rows, clipboard, smart checklist polish, tables/images/HTML, search, folders, German, wide screens, remembered caret and undo - done and published.**
Next (do not start without being asked): Milestone 8 (global checklist default, drag reorder, table cell editing).

## Status
- Unit tests: 723, 0 failing (`./gradlew test`); lint clean; `assembleDebug` and `assembleDebugAndroidTest` build.
- Instrumented tests on GitHub (API 36 emulator, Test Orchestrator): **81/81 green** (ColdStart 1, NotesFlow 8, SessionFlow 16, ExternalFlow 9,
  RichEditor 13, StructuralEditing 15, Selection 5, Alpha3 14), run 37453651186; regular CI green.
- Signing: Alpha 1, 2 and 3 share one certificate (SHA-256 7c2afa95...13bf9f0); keystore in `~/.openpad-signing/`, not in the repository.

## Regression boundary
The Alpha 2 keyboard/focus behaviour (held Backspace across lists, no keyboard flicker, ticking a checkbox keeps the keyboard) was confirmed
on a real phone. Row identity rules (one composable structure for every row kind, the focused row survives Enter and joins, no
`requestFocus()` to hide identity loss) are in docs/editor.md; `StructuralEditingTest` (15) guards them on every run.

## What Alpha 3 added (details in docs/editor.md, docs/storage.md)
- Selection across rows (logical positions by row id; long press + drag, handles, Select all), Copy (readable text), Copy as Markdown,
  Cut (one undo step), Paste over a selection, Paste as Markdown.
- New checklist; the smart checklist invariant (unchecked above completed) is kept after every edit and on load.
- Tables, images (content:// only; placeholders otherwise), allowlisted simple HTML drawn; "Edit source" for each. Raw Markdown is untouched.
- Search notes, Find in note, one-level folders (index metadata), German UI (+ locales_config), wide-screen sidebar, 48 dp targets.
- Remembered caret and bounded undo history per open note (`editor-state.json`, fingerprint-checked), whitespace preservation around edits.

## Architecture decisions
- File name = note id; metadata (favorite, smart checklist, folder) in `index.json` v5; session in `session.json`; editor state in `editor-state.json`.
- Markdown is written from the document model; raw rows are written back verbatim; image-only paragraphs are raw rows for display only.
- Dialog actions run in the application scope (`inAppScope`): closing the dialog cannot cancel a half-done operation.
- `appScope` MUST stay on `Dispatchers.Main.immediate`; `ColdStartTest` guards it.

## Known issues / limitations
- **Never run the Android emulator on the dev laptop.** Instrumented tests only via CI. Local: `./gradlew test lint assembleDebug assembleDebugAndroidTest`
  (`source ~/.openpad-env.sh`, which also exports the signing keystore variables).
- No soft keyboard on the CI emulator: keyboard behaviour is verified through its cause (no field disposed, focus kept) plus the owner's phone.
- A cross-row selection cannot be extended with shift+arrows; typing while one exists ends it. Android drops a range selection when a field loses focus,
  so a remembered range comes back as a caret.
- Inline images, tables with inline HTML, nested block content in list items, relative image paths: shown as text/placeholder.
- Search reads files directly (no index). The `.md` "Open with" glob matches paths with up to six dots.
- Alpha APKs use the debug certificate of this machine's keystore; keep an off-machine copy of `~/.openpad-signing/`.
