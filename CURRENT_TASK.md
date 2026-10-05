# Current task

**Milestone 3 (Favorites + Recent) and the pre-M3 hardening are complete.** Next: Milestone 4 (open documents /
session / startup modes). Do not start it without being asked.

## Completed
- M0/M1: environment, repo, CI (assemble + unit tests + lint), docs, license.
- M2: file-backed `NoteRepository`, drafts, autosave, rename, Clear/Delete/Trash/Restore/Delete permanently, drawer UI.
- Hardening: note files are named by id (`notes/<uuid>.md`), titles live in `index.json`; rename/auto-title are a
  single atomic index write and Trash/Restore keep the file name, so no crash can change a note's id
  (docs/storage.md, "Crash consistency"). Migration from the v1 title-named layout.
- Hardening: Android Test Orchestrator (fresh process + cleared data per instrumented test) and `ColdStartTest`
  (UiAutomator, no Compose rule). Verified to fail when `appScope` leaves `Dispatchers.Main.immediate`.
- M3: `favorite` + `lastOpenedAt` metadata (index v3, old indexes load with defaults), pure `NoteLists.favorites()` /
  `recent()` (last 3 opened non-favorites), drawer order FAVORITES, RECENT, FILES, TRASH (first two only when
  non-empty), Favorite/Unfavorite in the overflow menu.
- Tests: 146 JVM unit tests; 9 instrumented tests (orchestrator) run in the `Instrumented tests` workflow.

## Architecture decisions (details in docs/)
- File name = note id; the id is recoverable from the file even if the index is lost (docs/storage.md).
- "Used" for Recent = created or opened, not edited. Notes never opened (adopted files) are not recent.
- Favorites/recency are index metadata only; the `.md` files are never touched.
- `appScope` MUST stay on `Dispatchers.Main.immediate` (Compose state; docs/architecture.md); `ColdStartTest` guards it.
- No DI framework, no Room (index.json still suffices; reconsider when the session/open-documents state grows).

## Known issues / gaps
- **Never run the Android emulator on the dev laptop**: it hard-froze twice while emulator + Gradle ran together
  (RAM is ~2-6 GB free). Verify instrumented tests through the `Instrumented tests` GitHub workflow
  (`gh run list --workflow instrumented.yml`). Local checks: `./gradlew test lint assembleDebug`.
  `source ~/.openpad-env.sh` sets JAVA_HOME/ANDROID_HOME.
- The instrumented workflow is separate from `ci.yml` (it is slower and emulators are occasionally flaky on shared
  runners); it is not a required gate. It cancels superseded runs on the same ref.
- Favorites/Recent could not be re-inspected on a device manually after the last UI change for the reason above;
  they are covered by the instrumented tests instead.
- `index.json` is rewritten on every save/open (fine for small note counts).
- UI strings are English only (all in resources).
- The overflow menu is the only Favorite control (deliberately uncluttered); no long-press/swipe actions yet.

## Exact next action
Milestone 4: open-documents model (ordered open notes, active note, close vs delete), session persistence, startup
modes (Resume / Resume + blank note [default] / Blank note) in DataStore, mobile-friendly document strip with
long-press Close. Start with a pure `Session` model + restore logic + tests, then UI.
