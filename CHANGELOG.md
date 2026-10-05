# Changelog

All notable changes are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed
- Note files are now named by id (`notes/<uuid>.md`) and titles live in the index, so rename/trash cannot change a
  note's id even if the app crashes mid-operation. Older layouts are migrated automatically.

### Added
- Open documents ("mobile tabs"): compact scrollable tab strip, no duplicate tabs, order kept, safe Close / Close others /
  Close all (long press or overflow menu - closing never deletes), at most one transient blank note ("New note")
  that becomes a real note on its first meaningful text. Opening an already open note just activates it.
- Session restore across restarts (own `session.json`) and a Settings screen with the startup options
  Resume session / Resume + blank note (default) / Blank note.
- Favorites and Recent in the drawer (order: FAVORITES, RECENT, FILES, TRASH). Favorite/Unfavorite via the overflow menu.
  Recent = last 3 opened non-favorite notes. Pure metadata; Markdown files are never modified.
- Process-isolated cold-start regression test (Android Test Orchestrator + UiAutomator) and an emulator CI workflow.
- Local notes stored as real `.md` files (atomic writes, strict UTF-8, metadata in `index.json`, self-healing index).
- Draft rule: blank pages never create files; first meaningful text creates the note.
- Autosave (idle + max-wait debounce, flush on stop/switch/close).
- Rename, Clear (keeps the note), Delete (to Trash), Restore, Delete permanently - each with confirmation where data could be lost.
- Navigation drawer with `+ New Note`, FILES and TRASH; temporary raw-Markdown editor.
- Project bootstrap: Kotlin + Jetpack Compose app, Gradle wrapper, CI (build, unit tests, lint), GPL-3.0-or-later license.
