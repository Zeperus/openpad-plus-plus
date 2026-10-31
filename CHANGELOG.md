# Changelog

All notable changes are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- **Export all notes** (Settings -> Backup): copies every note as a `.md` file into a folder you choose; external files are skipped,
  nothing is overwritten, the result lists what was exported and what failed.
- **"Changed in another app"**: an external document that another app changed after openPad++ read it is no longer overwritten.
  You are asked: *Reload* (your text is kept as a separate note "<title> (my version)") or *Keep mine*; until you decide, a bar says
  that the file is not saved. Coming back to the app shows the new version of an unchanged external document.
- **Lasting "Not saved" bar** with *Try again* after a failed save, until the next save succeeds (before: a 4-second message).

### Changed
- **Faster editing in long lists:** list markers and checkboxes find their row in constant time (was a linear search per row, i.e.
  quadratic per layout); *Find in note* is only recomputed when the text or the search changes, not on every caret move; the editor's
  segment lookup no longer counts items again for every visible row.
- **Typing in long notes is much faster:** unchanged paragraphs are no longer converted again on every keystroke (300 keystrokes in an
  8000-word note: 586 ms -> 46 ms on the test machine), and the editor's segment layout is computed once per change.
- **Search** clears old results as soon as you type, shows *Searching…*, works in the background, has a clear button and does not
  autocorrect the search words.
- **FILES can be sorted by last change** (toggle in the FILES header, remembered) and every note there shows when it was last changed
  ("5 min. ago", "Yesterday").
- **Messages appear on every screen** (also in Settings and Search) and Favorites says *Added to / Removed from Favorites*.
- **TalkBack:** list markers are no longer read as separate items, checklist labels are cut at a word with "…", the area below the last
  line is announced as *Continue writing at the end*, drawer sections are headings, the Not saved / read-only bars are announced.
- **Consistent words:** *Move to Trash…* (was *Delete note…*), *New note* / *New checklist* without a "+", *Read-only*; German
  *In den Papierkorb…*, *Notiz leeren…*, no space before "…".
- Remembered caret/undo history is written 0.8 s after the last change (at the latest after 5 s) instead of about every second while
  typing, and its encoding runs in the background.
- **Share** prepares the file in the background and keeps copies that another app may still be reading (older than an hour are removed).
- `index.json` is written compactly (older, pretty-printed files are read as before); the title check on every save needs no regex.
- **Easier to find and to undo:** *New folder* is always in the drawer (also before the first folder exists); the formatting bar shows
  lists and checklists right after bold/italic, fades at the edge when it can scroll, has 48 dp buttons and shows a button's name on long
  press; tabs are 48 dp high and *Close others / Close all* are also in the menu; *Clear note* can be undone; *Move to Trash* offers *Undo*;
  destructive confirmations are red; turning on the Smart Checklist says what it does.
- **Dialogs survive rotating the phone** (with the typed name). The name field is focused and selected, *Done* on the keyboard confirms, a name that will be saved differently says so, the
  confirm button cannot be pressed twice; the folder list scrolls with *New folder* always visible.
- The menu's sub-page is called *Markdown clipboard…* and always opens on the main page again; a named note's title no longer reacts
  to a tap (long press renames).
- Debug/test builds show measured editing and opening times at the bottom of Settings (for measuring on a phone).
- The view model ends its background loop when it is discarded.

### Fixed
- Ticking the top item of a long Smart Checklist keeps the scroll position (after Alpha 8).
- *Delete permanently* checks that the file is really gone; if not, the note stays in Trash and an error is shown (before, it could
  reappear in Trash after a restart).
- A document provider that hangs (e.g. a cloud folder without network) no longer blocks saving all other notes: its call is
  abandoned after 15 s, the document reports "not available" until the provider answers, and an abandoned write never runs late.
- A save that fails while switching notes now shows "Could not save" and the Not saved bar (before: "That did not work. Your notes
  were not changed.").

### Changed (development)
- `docs/roadmap.md` restored (it was emptied by accident in the Alpha 8 status commit) and updated.
- Random/property unit tests run a fifth of their cases locally and the full count on CI (`-Popenpad.fullProperties=true`); the local
  unit test run is about half as long, CI keeps the same protection.
- Four test classes that were hidden in files of other classes have their own files (`NumberingTest`, `WhitespacePreservationTest`,
  `SmartChecklistInvariantTest`, `RawRowsInDocumentTest`).
- Instrumented tests: a run for one test class no longer cancels the full run of a push (separate CI concurrency groups).
- CONTRIBUTING: targeted test loops per package and per UI test class.
- UI tests are grouped by feature (`ChecklistUiTest`, `ClipboardUiTest`, `ExternalFileUiTest`, `SearchUiTest`, ...) instead of by release.
- `NotesScreen` is split into drawer, dialogs, tab strip and settings files; the app preferences live in their own `SettingsModel`
  (a failed preference write no longer crashes the app).
- Lint: unused strings and the unused `ui-tooling-preview` dependency removed, `mutableIntStateOf` for the text size.

## [0.1.0-alpha.8] - 2026-10-10 (pre-release / test build)

### Fixed
- **Copy / Cut keep the logical lines.** Rows are copied one per line (no blank line between paragraphs any more, an empty row is an empty
  line, a paragraph that only wraps on the screen gets no line break). CRLF / CR from other apps are line breaks.
- **Several lines are several list items.** Converting a row of several lines (or a selection over several rows) to a checklist, bullet or
  numbered list makes one item per line - never one item with continuation lines; leading markers (`- * + • – —`, `[ ]`, `[x]`, `☐`, `☑`)
  are cleaned and a done marker keeps the task done. Pasting several lines into a list or checklist item makes sibling items (the text behind
  the caret stays behind the last line); one undo step. A blank line between items separates two lists instead of making an empty item.

### Added
- **Paste as Checklist** (text menu and overflow menu, "Als Checkliste einfügen"): the clipboard becomes one checklist item per meaningful
  line; list/task markers and WhatsApp/messenger headers (`[10.10., 12:42] Sybille: - Bier`, `[12:42] Sybille: Bier`, the WhatsApp export form)
  are removed, ordinary text such as `Note: buy milk` is never touched. In a blank note the note becomes a Smart Checklist. Normal Paste stays
  lossless.

## [0.1.0-alpha.7] - 2026-10-07 (pre-release / test build)

### Added
- **Editor text size** (Settings -> Editor -> Text size, `[-] 16 sp [+]`, 12-28 sp, with a live preview). It sets the body size; headings (1.8 to 1.0 x),
  code, tables and captions follow, line heights scale with it, the marker column and checkboxes adapt. Android's system font scale still applies on
  top. Applies at once and survives a restart; it is an app preference and never touches a note.

## [0.1.0-alpha.6] - 2026-10-07 (pre-release / test build)

### Fixed
- **Tapping to the right of a row's text put the caret at the start of the next row** (it looked like the start of the same row) instead of at
  the end of the tapped row. A tap now belongs to the row - and, for wrapped paragraphs, the visual line - it is on: right of the text is the
  end of that line/row, left of it the start, between letters between them; a second tap in an already focused row moves the caret too. The
  page margin around a row takes part. Checkboxes still toggle.

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
