# Storage

Markdown files are the user's data. Metadata (ids, timestamps) is kept separately and is **never** injected into
the `.md` files. Note text is never stored in a database or in settings.

## Layout (internal notes)

Below the app-private directory `filesDir/openpad/`:

```
notes/Shopping.md        active notes - plain UTF-8 Markdown, exactly what the user typed
trash/Old idea.md        trashed notes
index.json               metadata only: id, fileName, createdAt, updatedAt, trashedAt, autoTitle
```

The code lives behind `domain/NoteRepository`; `data/FileNoteRepository` is the implementation.
The editor and UI only know the interface, so storage can change without touching them.

## Identity and names

- A note is identified by a random **`NoteId`** (UUID) kept in `index.json`. The file name is *not* the identity,
  so renaming never invalidates references to a note.
- The file name is the title plus `.md`. Titles are sanitized by `NoteFileName`: illegal characters, control
  characters and reserved names are replaced, leading/trailing dots are dropped, and names are limited to
  120 UTF-8 bytes so they fit file-system limits. Callers never pass paths: every file access goes through
  `fileIn()`, which rejects separators, `.`/`..` and anything resolving outside its directory.
- Collisions are compared case-insensitively. Create/restore/auto-title add a suffix (`Todo 2.md`); an explicit
  rename to a taken name fails with `NoteNameConflictException` instead of silently renaming something else.
- **Auto-title:** until the user renames a note, its title follows the first non-blank line (block markers such as
  `#`, `-`, `[ ]` removed). Emptying a note keeps its name. A manual rename switches this off for good.

## Drafts (no `Untitled` clutter)

A new blank page is an **in-memory draft** (`NoteEditor.isDraft`). No file exists until the text contains
something other than whitespace; the first autosave then creates the note. Consequences:

- Opening / abandoning blank pages, pressing New Note repeatedly, or typing and erasing text before the first
  save leaves nothing on disk. *New Note* on an already blank page is a no-op.
- Once a file exists it is never removed implicitly - not even if the text is later emptied. That is what
  **Delete** (to Trash) is for. **Clear** empties the text and keeps the file.
- Delete on a draft that has unsaved text first saves it, so the text ends up in Trash rather than vanishing.

## Autosave and write safety

- `Autosaver` debounces: save 800 ms after the last keystroke, but at the latest 5 s into a typing burst.
  Saves also happen when the app is stopped (`ON_STOP`), before switching notes, creating a new note, renaming,
  deleting, and when the view model is cleared. The work runs in an application-lifetime scope so it is not
  cancelled with the screen. Failures keep the text in memory, show a message and are retried on the next change.
- Every file write is **atomic**: write `<name>.tmp` beside the target, `fsync`, then atomically rename over the
  target. A crash leaves either the complete old or the complete new content. Stale `.tmp` files are removed on
  the next start. (The containing directory is not fsynced; a power loss right after a rename may lose that
  one rename, never produce a half-written file.)
- Text is read with a **strict UTF-8 decoder**. A file that is not valid UTF-8 is reported
  (`NoteUnreadableException`) and never opened for editing, because saving would corrupt it.
- All repository operations are serialized by one mutex.

## Crash and metadata recovery

On first use the repository reconciles `index.json` with the directories. The directories are the source of truth:

| Situation | Result |
|-----------|--------|
| `.md` file without index entry (index lost/corrupt, file dropped in by hand) | adopted as a new note |
| index entry whose file is gone | entry dropped |
| file in `trash/` but index says active (crash between move and index write) or vice versa | follows the file |
| unreadable index | kept as `index.json.corrupt`, notes adopted from the directories |
| index entry with an unsafe file name | ignored |

Known limitation: a crash exactly between a *rename* and the index write makes the renamed file look like a new
note (new id). Text is not lost.

## Trash

*Delete* moves the file to `trash/` (name made unique there) and sets `trashedAt`. *Restore* moves it back,
adding a numeric suffix if the name has since been taken - it never overwrites. *Delete permanently* works only
on notes that are already in Trash. Trashed notes can be read but not saved or renamed.

## Not implemented yet

External `.md` files via the Storage Access Framework, favorites, recents and the open-document session
(Milestones 3, 4, 7). Those will add metadata (URIs, favorite flags, session) next to, not inside, the notes.
