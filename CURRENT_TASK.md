# Current task

**Milestone 4 (open documents, sessions, startup modes): COMPLETE and CONFIRMED.**
**Milestone 3 (Favorites + Recent): COMPLETE and CONFIRMED** (9/9 on 2026-10-05).
Next: Milestone 5 (rich Markdown editor). **Do not start it without being asked.**

## Verification
- Unit tests: 276, 0 failing (`./gradlew test`); lint clean; `assembleDebug` and `assembleDebugAndroidTest` build.
- Instrumented tests on GitHub (`Instrumented tests` workflow, API 36 emulator, Test Orchestrator = fresh process per
  test): **25/25 green** (1 ColdStartTest, 8 NotesFlowTest, 16 SessionFlowTest), run 37380496887 on commit `870ea35`.
- Regular CI (`ci.yml`: build, unit tests, lint) green on the same commit.
- Mutation checks: unit level (neighbour rule, draft materialization, startup persistence) and instrumented level
  (default startup mode, close-neighbour rule, run on a throwaway branch: 4 tests failed as they should; branch deleted).

## What Milestone 4 delivered
- `OpenDocuments` (pure): ordered saved-note ids, at most one transient blank draft (always last), active tab; open
  without duplicates, activate, close (next, else previous, else blank), close others, close all, remove, retainOnly,
  materializeDraft. `StartupPlanner` + `StartupMode` (ResumeSession, ResumeAndBlank = default, BlankNote).
- `FileSessionStore` (own atomic `session.json`, lenient reads), `DataStoreSettingsStore` (startup mode).
- `NotesViewModel`: one live editor, one mutex for actions and autosave, startup restore + immediate persist, `ready`
  gate, draft -> note transition, Delete closes the tab, Restore does not reopen, unreadable/stale notes dropped.
- UI: compact tab strip (no per-tab close button; long press = Close / Close others / Close all; overflow has Close),
  Settings screen (Startup options), blank tab labelled "New note".
- Details: docs/architecture.md ("Open documents and sessions"), docs/storage.md ("Session and settings").

## Architecture decisions (details in docs/)
- File name = note id; the id is recoverable from the file even if the index is lost.
- "Used" for Recent = created or opened/activated during a run, not edited, not restored at startup.
- Session = its own disposable `session.json`; settings = DataStore. Neither can affect note files.
- Tab order and Recent order are independent. Closing never deletes anything.
- `appScope` MUST stay on `Dispatchers.Main.immediate`; `ColdStartTest` guards it.
- No DI framework, no Room.

## Known issues / gaps
- **Never run the Android emulator on the dev laptop** (hard-froze twice with emulator + Gradle). Instrumented tests
  run only via the `Instrumented tests` GitHub workflow (`gh workflow run instrumented.yml`). Local checks:
  `./gradlew test lint assembleDebug`. `source ~/.openpad-env.sh` sets JAVA_HOME/ANDROID_HOME.
- Switching tabs reloads the note from disk: cursor position and undo history are not kept per tab.
- A true "kill the process and relaunch" cannot be done inside one instrumented test; persistence is covered from both
  sides (what the UI writes, and what a relaunch does with such a file) - see SessionFlowTest's header comment.
- Opening a note while only an untouched blank tab exists leaves that blank tab open ("Ideas | New note"); it is
  never duplicated and never becomes a file, but it is not auto-replaced either.
- If the process dies in the instant between a draft becoming a note and `session.json` being rewritten, the new note
  is not in the stored session (it is still in FILES and Recent; no text is lost).
- `index.json` is rewritten on every save/open (fine for small note counts). UI strings are English only.
- The instrumented workflow is separate from `ci.yml`, not a required gate, skips docs-only changes and cancels
  superseded runs on a ref. GitHub had an Actions outage on 2026-10-05 (runners not acquired); if jobs fail with
  "not acquired by Runner", check githubstatus.com before suspecting the code.

## Suggested next milestone
Milestone 5: the rich Markdown editor (parser -> document model -> Compose editor -> serializer), starting with
paragraph, heading, bold, italic and lists, with aggressive round-trip tests. Evaluate maintained Markdown libraries
first (CommonMark/GFM). Keep the model Compose-independent; unknown Markdown must be preserved.
