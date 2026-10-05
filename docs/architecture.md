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

## Open documents and sessions (Milestone 4, core only - not yet wired into the UI)

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

Still to do for this milestone: integrate the model into `NotesViewModel`, the tab strip and the settings screen.

Principles: constructor injection by hand (no DI framework), no layer without a purpose, Compose-independent
domain/markdown code, build entirely from the terminal with `./gradlew`.

Toolchain: AGP 9.4 (built-in Kotlin; the `kotlin-android` plugin must not be applied), Gradle 9.8, JDK 17,
compileSdk/targetSdk 37.
