# Current task

**Milestone 2 (storage vertical slice): complete.** Next: Milestone 3 (application shell: Favorites, Recent).

## Completed
- M0/M1: environment, repo, CI (assemble + unit tests + lint), docs, license.
- M2: file-backed `NoteRepository`, drafts, autosave, rename, Clear/Delete/Trash/Restore/Delete permanently,
  drawer UI with `+ New Note` / FILES / TRASH, temporary raw-Markdown editor. 83 JVM unit tests, 5 instrumented
  UI tests (run once on an API 36 emulator: all passed). Verified manually on the emulator, including process kill.

## Architecture decisions (details in docs/)
- Notes = real `.md` files in `filesDir/openpad/{notes,trash}`; `index.json` holds ids/timestamps only; the
  directories are the source of truth and the index self-heals (docs/storage.md).
- Stable `NoteId`, file name = title; auto-title follows first line until the first manual rename.
- Blank drafts exist only in memory; no file until meaningful text.
- `appScope` MUST stay on `Dispatchers.Main.immediate` (Compose state; see docs/architecture.md).
- No DI framework, no Room yet (index.json is enough; revisit when favorites/recents/session need queries).

## Known issues / gaps
- Instrumented tests are not run in CI (no emulator job). They also do not catch the cold-start state-threading
  regression (all tests share one process; fix idea: AndroidX Test Orchestrator with `clearPackageData`; an
  attempt was started but not verified, so it was reverted).
- Index.json is rewritten on every save (fine for small note counts).
- (fixed) Rename crash window: notes are now named by id, see docs/storage.md.
- The dev laptop is memory-tight (~2-6 GB available) and froze once while Gradle + the emulator were running.
  Run the emulator and Gradle one at a time; `~/.openpad-env.sh` sets JAVA_HOME/ANDROID_HOME.
- Everything in the UI is English only; strings are in resources.

## Exact next action
Milestone 3: add `Favorites` and `Recent` (last 3 used, excluding favorites) to the drawer. Needs metadata for
favorite flag and last-opened time: extend `index.json` entries (`favorite`, `lastOpenedAt`) and
`NoteRepository` (`setFavorite`, `markOpened`), with a pure `recentNotes()` function + tests.
