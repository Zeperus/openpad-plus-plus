# Current task

**Alpha `0.1.0-alpha.1`: Milestones 5 (external Markdown), 6 (Markdown engine) and 7 (rich editor) are implemented.**
Next (do not start without being asked): Milestone 8, checklist mode (completed items sink to the bottom).

## Status
- Unit tests: see `./gradlew test` (editor, engine, view model, storage); lint clean; `assembleDebug` and
  `assembleDebugAndroidTest` build.
- Instrumented tests run only on GitHub (`Instrumented tests` workflow, API 36 emulator, Test Orchestrator): see the
  latest run; the final result is recorded in the release notes / final report.
- Version `0.1.0-alpha.1` (versionName), shown at the bottom of Settings.

## What exists
- Storage, Favorites/Recent/Trash, tabs, session, startup modes (Milestones 2-4).
- External Markdown: picker, "Open with"/"Edit with", in-place edit with recovery copy, read-only detection, Share (M5).
- Markdown engine: commonmark-java 0.30 + own model + verified serializer, byte-exact write-back of untouched blocks (M6).
- Rich editor: rows, operations, undo, Compose editor, formatting bar, per-tab sessions (M7). Details: docs/editor.md.
- Manual test list for the owner: docs/alpha-test-checklist.md.

## Architecture decisions (details in docs/)
- File name = note id. Session = its own `session.json`. Settings = DataStore.
- The Markdown is always written from the document model, never read back from the screen; the serializer is verified
  (re-parse) and falls back to other spellings; unsupported Markdown is a raw row.
- Text fields hold plain text (+ an invisible first character to detect Backspace at the start); formatting is a visual
  transformation. Pasted text is plain text.
- A tab's `EditorSession` is reused only if its Markdown equals the file just loaded.
- `appScope` MUST stay on `Dispatchers.Main.immediate`; `ColdStartTest` guards it.
- No DI framework, no Room.

## Known issues / limitations
- **Never run the Android emulator on the dev laptop** (hard-froze twice). Instrumented tests only via
  `gh workflow run instrumented.yml`. Local: `./gradlew test lint assembleDebug assembleDebugAndroidTest`
  (`source ~/.openpad-env.sh` first).
- Selection works inside one row; no cross-paragraph selection; copy gives plain text.
- Tables, images, HTML are raw rows (shown as source), not rendered.
- Caret/undo of a tab live only while the app runs.
- Android's glob `.md` filter for generic MIME types matches paths with up to six dots.
- Alpha builds are signed with the debug key: a build from another machine needs the old Alpha uninstalled first.
- The instrumented workflow skips docs-only changes and cancels superseded runs; if jobs fail with "not acquired by
  Runner", check githubstatus.com before suspecting the code.
