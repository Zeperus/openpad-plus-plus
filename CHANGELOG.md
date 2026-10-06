# Changelog

All notable changes are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.1.0-alpha.1] - 2026-10-06 (pre-release / test build)

### Added
- **Rich Markdown editor**: the formatted text is the editor (no visible `#`, `**`, `- [ ]`). Paragraphs, headings 1-6, bold,
  italic, strikethrough, inline code, links, bullet / numbered / task lists (real checkboxes, nesting), quotes, code blocks,
  horizontal rules. Unsupported Markdown (tables, HTML, ...) is kept as raw rows. Compact formatting bar above the keyboard.
- Undo / redo (bounded, 100 steps per tab); every tab keeps its caret and undo history while it is open.
- **Markdown engine**: commonmark-java 0.30 + own document model and verified serializer; untouched parts of a file are written back
  byte for byte; property/fuzz/performance tests.
- Version shown in Settings; launcher icon from the supplied artwork.

### Changed
- The temporary raw-Markdown text field is gone.

### Changed
- Note files are now named by id (`notes/<uuid>.md`) and titles live in the index, so rename/trash cannot change a
  note's id even if the app crashes mid-operation. Older layouts are migrated automatically.

### Added
- External Markdown documents: "Open file…" (system picker) and "Open with"/"Edit with" for `.md` files, edited in
  place via the Storage Access Framework with a recovery copy and read-back verification, read-only detection,
  and "Remove from openPad++" (never deletes the file). Share any note as a real `.md` file.
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
