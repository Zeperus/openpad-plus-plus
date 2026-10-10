# Architecture

Single-module Android app (`:app`), Kotlin, Jetpack Compose, Material 3. Minimum API 28.

```
io.github.zeperus.openpad
├── ui/        Compose screens, theme (no business logic)
├── domain/    Pure-Kotlin note concepts and rules (unit-testable on the JVM)
├── data/      File storage, metadata, settings, session
└── markdown/  (later) parser, document model, serializer
```

## Current structure (Milestone 2)

- `domain/` - `NoteId`/`NoteInfo`/`NoteContent`, `NoteRepository` (interface + exceptions), `NoteFileName`,
  `NoteTitles`, `NoteLists` (pure Favorites/Recent calculation), `NoteEditor` (draft rules, save/clear/rename/trash for one open note), `Autosaver` (debounce).
- `data/` - `FileNoteRepository` (`<id>.md` files + `index.json`), `AtomicFiles` (atomic write/move, strict UTF-8 read).
- `ui/` - `NotesViewModel` (open note, Files/Trash lists, user actions), `NotesScreen` (drawer, editor, dialogs).
- `OpenPadApplication` - hand-wired dependencies and the application-lifetime `appScope`.

**Threading rule:** `appScope` runs on `Dispatchers.Main.immediate` because the view model publishes Compose
state. Blocking file I/O happens inside the repository on its own dispatcher (`Dispatchers.IO`). Writing Compose
state from a background thread can leave the UI showing stale data when it happens before Compose has started
observing (seen on a cold start), so do not move `appScope` off the main dispatcher.

Persistence rules and data-safety details: see [storage.md](storage.md).

## Open documents and sessions (Milestone 4)

The "mobile tabs" are a pure, immutable model in `domain/OpenDocuments.kt`; nothing in it knows about Compose,
files or Android.

- **Identity:** saved tabs are `DocumentTab.Saved(NoteId)` - the stable note UUID, never a title or path. A
  rename therefore cannot change, duplicate or reorder a tab; tab titles are looked up from the note's current
  metadata (`OpenDocuments.items`).
- **Shape:** `noteIds` (ordered) + `draftOpen` + `activeNoteId` (null = the draft is active). Invariants, checked on
  construction: ids are distinct, the active note is open, and there is always at least one tab.
- **One draft at most.** The transient blank page is a tab without id, always last. `newDraft()` reuses an existing
  one, so repeated "new note" requests or restarts cannot accumulate blank tabs. When the draft gets its first
  meaningful content and the repository creates the note, `materializeDraft(id)` turns the draft tab into that
  note's tab in place; the next "new note" opens a fresh draft.
- **Operations:** `open` (append and activate, or just activate if already open - never a duplicate),
  `activate` (never reorders), `close` (next tab, else previous, else a fresh blank page), `closeOthers`,
  `closeAll`, `remove` (note moved to Trash), `retainOnly` (drop stale ids). Closing only changes this model; it
  never deletes, trashes or modifies a note.
- **Startup:** `StartupPlanner.initial(mode, persisted, existingIds)` builds the launch state. `ResumeSession`
  restores open notes and the active one (blank page if nothing valid remains); `ResumeAndBlank` (default) restores
  the notes and adds one active blank page; `BlankNote` starts with one blank page. Stale and duplicate ids are
  ignored. Only saved notes are persisted (`toPersisted()`), so a blank page never survives a restart and cannot pile up.
- **Persistence:** `SessionStore` (`data/FileSessionStore`, own `session.json`) and `SettingsStore`
  (`data/DataStoreSettingsStore`, Jetpack DataStore). See [storage.md](storage.md).
- **Recent vs tab order are separate concepts:** activating a tab never reorders tabs; "used" for Recent stays
  "created or opened" (docs/storage.md).

### Integration in `NotesViewModel` and the UI

- **One live editor** - the active tab's. Switching tabs saves it and loads the other note from the repository, so
  unsaved text exists only in the active editor (cursor/undo state is therefore not kept per tab).
- **One mutex for everything:** user actions *and* autosave run under the same `actions` lock, so a draft becoming a
  note can never interleave with a tab operation. Session writes go through a conflated channel: the latest state
  wins and writes are strictly ordered; a failed write is ignored (the session is disposable).
- **Draft -> note:** whenever a save creates the note while the blank tab is active (autosave, switching, rename,
  favorite), the VM calls `materializeDraft(id)`; the tab keeps its place and focus. Delete on a blank page with text
  saves it first, so that text ends up in Trash.
- **Startup** runs once per view model (process start, or relaunch after the activity was finished): read the
  setting and `session.json`, list existing notes, `StartupPlanner.initial`, **persist the result immediately**
  (so "Blank note" replaces the old session instead of letting it resurface), then load the active note. The editor
  and tab strip stay hidden behind a `ready` flag so nothing can be typed into a blank page that is about to be replaced.
- **"Used" and Recent:** activating a saved note during a run (drawer, tab tap, neighbour after a close) records
  `lastOpenedAt`; restoring the session at startup does not. Tab order and Recent order are independent.
- **Closing** never deletes: it only changes the open-document model (the `.md` file, Favorites, Recent and FILES are
  untouched). Closing the active tab activates the next tab, else the previous one, else a fresh blank page.
  Delete (to Trash) additionally removes the tab; Restore from Trash does not reopen the note.
- **Notes that cannot be loaded** when a tab is activated (deleted meanwhile, not valid UTF-8) are dropped from the
  session, a message is shown, and the next tab is tried - ending at the latest with a blank page.
- **UI:** `TabStrip` (compact `LazyRow`, truncated titles, active tab scrolled into view, **no close button on tabs**;
  long press opens Close / Close others / Close all; the overflow menu has Close too) and a minimal `SettingsScreen`
  (Startup: Resume session / Resume + blank note / Blank note). The blank tab is labelled "New note".

## The editor (Milestones 6 and 7)

`markdown/` (parser, document model, verified serializer) and `editor/` (rows, operations, undo) are plain Kotlin without
Compose or Android types; `ui/editor/` is the Compose layer (see [editor.md](editor.md)). `NotesViewModel` owns one
`EditorSession` per open tab and turns every edit into Markdown for `NoteEditor` and the autosave:

```
RichEditor -> NotesViewModel -> EditorSession -> EditorOps -> EditorDocument -> Markdown -> NoteEditor -> Autosaver -> file
```

A tab's session is reused when its Markdown equals the freshly loaded file, so caret and undo survive tab switches but can
never overwrite a changed file. `appScope` still runs on `Dispatchers.Main.immediate` (Compose state).

**Row identity.** A row id is the identity of an editable block: it is the Compose key, the focus target and the caret owner.
Operations change rows in place (kind, text, depth) and the focused row survives Enter and Backspace-joins (see
[editor.md](editor.md#identity-and-focus-why-the-keyboard-used-to-flicker)). The smart checklist flag is note metadata
(`NoteInfo.smartChecklist`, `index.json`), handed to the `EditorSession`; the ordering rules live in `editor/Checklist.kt`.

## Alpha 8 additions

- **Lines and lists** (pure Kotlin, `editor/`): `ListImport` (line endings, list/task markers, messenger headers, the items of a Paste as
  Checklist), `ListConversion` (bullet / numbered / checklist for several rows or lines), `EditorOps.pasteLines` (several lines pasted into
  a list item or heading) and `EditorOps.pasteChecklist`; `DocumentSelections.plainText` copies one line per row. `EditorSession` routes the
  list buttons (`toggleList` / `toggleTask` take the selection) and `pasteAsChecklist`; `NotesViewModel.pasteAsChecklist` also makes a blank
  note a Smart Checklist. The UI only adds the menu items (`SegmentField` text menu, overflow menu in `NotesScreen`). Everything is one
  `commit` -> one undo step, and the smart checklist order is re-established by `settled` as for every edit. Details: [editor.md](editor.md#clipboard-and-lines-alpha-8).

## Alpha 4 additions

- **Unified text field per run of rows** (`editor/Segments.kt`, `SegmentEditing.kt`, `Numbering.kt`, `ui/editor/SegmentField.kt`): the pure
  segment logic maps rows <-> field text/offsets and interprets keyboard edits as document operations; the Compose side is one
  `BasicTextField(TextFieldState)` per segment with input/output transformations. This is what makes the system's own selection cross
  rows. `NotesViewModel.applyFieldOp` is the only way field edits reach the model. Details: [editor.md](editor.md).
- **External write access** (`domain/WriteAccess.kt`, `data/ContentResolverDocuments.kt`): `WriteAccessPolicy` combines the URI permission,
  the provider's flag and a probe; the picker contract `OpenWritableDocument` asks for read+write+persistable. See [storage.md](storage.md).
- **Language** (`domain/AppLanguage.kt`, `data/AppCompatLocaleApplier.kt`): `LanguageManager` persists the choice (DataStore key `language`)
  and applies it with `AppCompatDelegate.setApplicationLocales`; `MainActivity` is an `AppCompatActivity`.

## Alpha 3 additions

- **Selection and clipboard**: `editor/DocumentSelection.kt` (pure: positions by row id, slices, plain text, Markdown, delete/replace); since
  Alpha 4 the selection itself is the native one of the segment field and `ui/editor/SelectionUi.kt` only holds the clipboard helper.
- **Rendered raw blocks**: `editor/RawBlocks.kt` classifies a raw row (table / image / simple HTML / other); `ui/editor/RawViews.kt` draws it.
  The model is unchanged: they are still raw rows written back verbatim.
- **Remembered state**: `editor/PersistedState.kt` (fingerprint, bounded steps) with `FileEditorStateStore` (`editor-state.json`); the view
  model loads it at startup, restores a session only for the exact text, saves dirty sessions ~1 s after a change and on `flush()`.
- **Search / Find**: `domain/NoteSearch.kt` and `editor/FindInNote.kt` are pure; the view model runs the search off the actions lock.
- **Folders**: metadata in the index (`NoteInfo.folderId`, `FolderInfo`), drawer grouping and dialogs in `NotesScreen`.
- **Adaptive layout**: `BoxWithConstraints` + `WindowSizeClass`: Expanded width -> `PermanentNavigationDrawer` ("sidebar"), otherwise the modal drawer.
- **Smart checklist** is enforced in `EditorSession.settled` after every edit; **New checklist** creates the draft with `smartOnCreate`.

Principles: constructor injection by hand (no DI framework), no layer without a purpose, Compose-independent
domain/markdown code, build entirely from the terminal with `./gradlew`.

Toolchain: AGP 9.4 (built-in Kotlin; the `kotlin-android` plugin must not be applied), Gradle 9.8, JDK 17,
compileSdk/targetSdk 37.
