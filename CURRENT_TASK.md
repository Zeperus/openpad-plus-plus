# Current task

**Milestone 3 (Favorites + Recent): COMPLETE and CONFIRMED.** Instrumented tests 9/9 green on GitHub (run
37376144550 on push and run 37379121023 via `workflow_dispatch`, both on commit `bcd7c97`, 1 ColdStartTest + 8
NotesFlowTest, 0 failures). Regular CI (build/unit/lint) green.
**Milestone 4 (open documents / session / startup): IN PROGRESS.** Core done; UI integration is being built next.
Do not mark Milestone 4 complete until its own instrumented tests are green on GitHub.

## Status of Milestone 3
- Earlier failures were test bugs (a new note is listed under RECENT *and* FILES; a drawer-close helper), fixed in
  `ce9eaf8`. The GitHub Actions outage of 2026-10-05 (runners not acquired) delayed the confirmation; it ended
  around 21:55 UTC.

## Milestone 4 - done so far (core only, no UI / no ViewModel / no Compose / no instrumented changes)
- `domain/OpenDocuments` (pure): ordered saved-note ids, at most one transient blank draft (always last tab),
  active tab; `open` (no duplicates), `activate`, `newDraft`, `materializeDraft`, `close` (next, else previous, else
  blank), `closeOthers`, `closeAll`, `remove`, `retainOnly`, `items` (titles for display), `toPersisted`.
- `domain/Startup`: `StartupMode` (ResumeSession, ResumeAndBlank = default, BlankNote), `StartupPlanner`,
  `SessionStore` / `SettingsStore` interfaces.
- `data/FileSessionStore`: separate atomic `session.json`, lenient reading that never throws.
- `data/DataStoreSettingsStore`: startup mode in DataStore (default ResumeAndBlank; corrupt/unknown -> default).
- Tests: 227 JVM unit tests total, 0 failing (43 OpenDocuments incl. a randomized 300x60-operation invariant test,
  15 StartupPlanner, 18 FileSessionStore, 5 DataStore). Mutation-checked (neighbour rule).

## Next: Milestone 4 UI integration (Milestone 3 is confirmed; this is the current work)
Design decisions already taken, so they do not need to be re-derived:
- `NotesViewModel` keeps ONE active `NoteEditor`; switching tabs = flush + load the other note from the repository
  (cursor/undo state is not kept per tab for now). Unsaved text exists only in the active editor.
- All VM state changes (session, editor, lists) and `autosave` must run under the existing `actions` mutex
  (split `saveNow()` into a locked variant used by actions and a locking wrapper for the Autosaver) so draft
  materialization cannot interleave with tab operations. Session writes go through a conflated channel so the
  latest state wins and ordering is safe.
- Draft -> note: after any save that creates the note while the draft tab is active, call
  `session.materializeDraft(id)`. Delete on a draft with text saves it first (it ends up in Trash).
- `markOpened` on every activation of a saved note during a run (drawer open, tab tap, neighbour after close), but
  NOT when restoring the session at startup. Recent order and tab order stay independent.
- Startup runs once per ViewModel (process start or after the activity was finished): load setting + session +
  existing note ids -> `StartupPlanner.initial` -> persist immediately (so "Blank note" overwrites the old session)
  -> load the active note. Gate the editor behind a `ready` flag so nothing can be typed into a draft that is about
  to be replaced.
- Tab strip: horizontally scrollable `LazyRow`, compact, truncated titles, no permanent close "X"; long-press menu
  Close / Close others / Close all; overflow menu gets "Close"; draft tab title "New note". Settings: a minimal
  screen with "Startup": Resume session / Resume + blank note / Blank note.
- Instrumented tests for M4 (restoration, default startup mode, close vs delete, no duplicate tab) are to be added
  and verified through GitHub CI only.

## Architecture decisions (details in docs/)
- File name = note id; the id is recoverable from the file even if the index is lost (docs/storage.md).
- "Used" for Recent = created or opened, not edited. Favorites/recency are index metadata only.
- Session = its own disposable `session.json`; settings = DataStore. Neither can affect note files.
- `appScope` MUST stay on `Dispatchers.Main.immediate` (Compose state; docs/architecture.md); `ColdStartTest` guards it.
- No DI framework, no Room.

## Known issues / gaps
- **Never run the Android emulator on the dev laptop** (hard-froze twice with emulator + Gradle). Instrumented tests
  run only via the `Instrumented tests` GitHub workflow. Local checks: `./gradlew test lint assembleDebug`.
  `source ~/.openpad-env.sh` sets JAVA_HOME/ANDROID_HOME.
- The instrumented workflow is separate from `ci.yml` and not a required gate; it cancels superseded runs on a ref.
- `index.json` is rewritten on every save/open (fine for small note counts).
- UI strings are English only. The overflow menu is the only Favorite control.
