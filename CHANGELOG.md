# Changelog

All notable changes are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.1.0-alpha.5] - 2026-10-07 (pre-release / test build)

### Fixed
- **Rows were spaced far too wide apart.** Every row was followed by a phantom empty line (Compose adds one after a paragraph style that ends in
  a line break). The breaks between rows are now drawn as invisible characters, lines are 1.5 x the text size (headings a little more), so
  normal lines, bullets, numbers and checklists sit under each other like in a notepad. Blank lines in the Markdown are still kept in the file.
- **Enter in a list left the caret behind the checkbox** until something was typed: the empty last row had no paragraph style (no indent).
  The new item now has the caret at the start of its text immediately - bullets, numbers and checklists alike - without any focus change.
- Checkboxes keep a comfortable touch target (48 dp wide, one line tall) although the rows are closer together.

### Added
- **Title from the top bar:** tap an automatic title (first line, or "Untitled") to rename the note; once you chose a name, a long press renames
  it (a tap does nothing) and editing the first line never changes it. Giving a blank note a title makes it a real (empty) note that stays.
  The title is metadata only; the Markdown file is not touched.

### Notes
- "Untitled" / "Unbenannt" is shown for a blank page (the tab still says "New note"). Same signing certificate as Alpha 1-4.

## [0.1.0-alpha.4] - 2026-10-06 (pre-release / test build)

### Changed
- **Native text selection across paragraphs, headings, lists, checklists and quotes.** Consecutive text rows now share one text field,
  so the system's own long press, selection handles and toolbar work across them, forward and backward. (Alpha 3's custom gesture,
  handles and selection bar - which did not behave like Android's - are gone.) Copy puts readable text on the clipboard, **Copy as
  Markdown** is in the same toolbar, Cut is one undo step, typing or Delete replaces/removes a selection, formatting applies to every
  selected row. The Alpha 2 keyboard/focus behaviour is kept (no field is created or disposed by an edit; checkboxes keep the keyboard).

### Fixed
- **Files opened with "Open file..." were read-only.** The picker was asked for read access only; it now asks for read + write +
  persistable access and for openable documents. A document handed over by another app ("Open with") with only a read grant stays
  read-only, now says so (`README.md · Read only`, with the reason) and offers **Open with write access...** (the file picker again,
  no copy). Write capability is decided from the granted/persisted permission, the provider's capability flag and, when that is
  missing, a harmless open-for-append probe.

### Added
- **Language selector** in Settings: System default / Deutsch / English (Android per-app locales; system default stays the default).
  Settings are now Startup / Language / Version.

### Notes
- Same signing certificate as Alpha 1-3 (installs over them); index format 5 unchanged.

## [0.1.0-alpha.3] - 2026-10-06 (pre-release / test build)

### Added
- **Selection across paragraphs, lists and checklists** (long press and drag into another row, drag handles, Select all); Copy (readable text),
  **Copy as Markdown**, Cut (one undo step), Paste over a selection, **Paste as Markdown**.
- **New checklist** (drawer): a blank page with Smart Checklist on; Smart Checklist now keeps unchecked items above completed ones after every
  edit (new tasks, conversions, pastes, loading).
- **Tables** drawn as tables (alignment, horizontal scroll), **images** (`content://` shown; others a placeholder with alt text and host, never
  downloaded), **simple HTML** shown formatted (allowlist, no scripts or network, the rest as source). "Edit source" opens any of them as text.
- **Search notes** (titles and content) and **Find in note** (count, previous/next).
- **Simple folders** (one level, metadata only): create, rename, move, delete empty ones.
- Caret/selection **and a bounded undo history are remembered across app restarts** (only for the exact same text).
- **German UI** (English remains the fallback); wide screens show a permanent sidebar.

### Improved
- Blank lines around an edited block are kept as they were; untouched blocks are still byte-exact.
- Accessibility: 48 dp touch targets, task and folder state announcements; performance checked on 500-item lists, 8000-word notes,
  table-heavy notes and 300-note search.

### Notes
- Same signing certificate as Alpha 1 and 2 (installs over them); index format 5 (older ones load unchanged).

## [0.1.0-alpha.2] - 2026-10-06 (pre-release / test build)

### Fixed
- The keyboard no longer closes and reopens (and the caret no longer jumps) when a list is converted, left, joined or
  split. Cause: each row kind was drawn by a different composable branch, so a kind change disposed the focused text field.
  All kinds now share one structure and the operations keep the focused row's identity. Held Backspace/Delete works across
  list boundaries.
- Enter on an empty list item leaves the list without leaving an extra empty row.

### Added
- Smart Checklist (per note, overflow menu): checked items move below the unchecked ones and are struck through; unchecking returns
  an item to the end of the unchecked group; check + move is one undo step. Ordinary Markdown task lists keep their order. The mode
  is stored as metadata, not in the Markdown.

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
