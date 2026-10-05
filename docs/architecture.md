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
  `NoteTitles`, `NoteEditor` (draft rules, save/clear/rename/trash for one open note), `Autosaver` (debounce).
- `data/` - `FileNoteRepository` (files + `index.json`), `AtomicFiles` (atomic write/move, strict UTF-8 read).
- `ui/` - `NotesViewModel` (open note, Files/Trash lists, user actions), `NotesScreen` (drawer, editor, dialogs).
- `OpenPadApplication` - hand-wired dependencies and the application-lifetime `appScope`.

**Threading rule:** `appScope` runs on `Dispatchers.Main.immediate` because the view model publishes Compose
state. Blocking file I/O happens inside the repository on its own dispatcher (`Dispatchers.IO`). Writing Compose
state from a background thread can leave the UI showing stale data when it happens before Compose has started
observing (seen on a cold start), so do not move `appScope` off the main dispatcher.

Persistence rules and data-safety details: see [storage.md](storage.md).

Principles: constructor injection by hand (no DI framework), no layer without a purpose, Compose-independent
domain/markdown code, build entirely from the terminal with `./gradlew`.

Toolchain: AGP 9.4 (built-in Kotlin; the `kotlin-android` plugin must not be applied), Gradle 9.8, JDK 17,
compileSdk/targetSdk 37.
