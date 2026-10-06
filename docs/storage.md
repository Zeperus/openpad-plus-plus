# Storage

Markdown files are the user's data. Metadata (ids, timestamps) is kept separately and is **never** injected into
the `.md` files. Note text is never stored in a database or in settings.

## Layout (internal notes)

Below the app-private directory `filesDir/openpad/`:

```
notes/<id>.md            active notes - plain UTF-8 Markdown, exactly what the user typed
trash/<id>.md            trashed notes (same file name; only the directory differs)
index.json               metadata only: id, title, createdAt, updatedAt, trashedAt, autoTitle, favorite, lastOpenedAt, uri/persistent/bom (external)
```

`<id>` is a random UUID. The code lives behind `domain/NoteRepository`; `data/FileNoteRepository` is the
implementation, so the editor and UI never see paths.

## Identity and titles

- **The file name is the note's identity.** Renaming a note, auto-retitling it, or moving it to/from Trash never
  gives it a new id, and a file can always be tied to its note even if `index.json` is lost.
- The **title** lives in the index only. It is restricted to what would be a legal file name
  (`NoteFileName`): illegal and control characters and reserved names are replaced, leading/trailing dots dropped,
  max. 120 UTF-8 bytes. When a note leaves the app (Share, later) it is exported as `<title>.md`
  (`NoteInfo.exportFileName`), e.g. `Shopping.md`.
- Ids are validated as UUIDs before they are ever used to build a path, so ids read from a damaged or tampered
  index cannot contain separators or `..`.
- Titles of active notes are unique (case-insensitive). Create/restore/auto-title add a suffix (`Todo 2`); an
  explicit rename to a taken title fails with `NoteNameConflictException`.
- **Auto-title:** until the user renames a note, its title follows the first non-blank line (block markers such
  as `#`, `-`, `[ ]` removed). Emptying a note keeps its title. A manual rename switches this off for good.

## Favorites and Recent

Both are **metadata in `index.json`**, keyed by the note id; the `.md` file is never touched (a test compares
the bytes and the modification time before and after). They therefore survive renames, auto-retitling, Trash and Restore.

- `favorite` (boolean, default false). The Favorites section lists active favorites alphabetically and is shown
  only when there is at least one.
- `lastOpenedAt` (millis, nullable). Set when a note is **created** and whenever it **becomes the open note**
  (`markOpened`). Editing does not change it, so typing never reorders Recent.
- `NoteLists.recent()` (a pure function) = active, non-favorite notes with a `lastOpenedAt`, newest first,
  ties broken by `updatedAt`, then title (case-insensitive), then id, limited to 3. Favorites are excluded, so a
  note is never in both Favorites and Recent; unfavoriting a note that was used recently puts it back in Recent.
  Notes without a timestamp (files adopted from disk) were never used and are not recent. Trashed notes are in
  neither list but keep their flags, so Restore brings them back as they were.
- FILES is always the complete list of active notes, so a favorite or recent note also appears there.

Index versions: v1 (title-named files), v2 (id-named files), v3 (adds `favorite`, `lastOpenedAt`), v4 (adds the external-document fields). Entries from an
older index load with `favorite = false` and `lastOpenedAt = updatedAt` (the last edit is the best available
"last use"); the index is then rewritten as v3 once. Unknown fields are ignored, so an index written by a newer
version still loads.

## External documents (Milestone 5)

A `.md` file chosen with the system file picker ("Open file…") or handed over by another app ("Open with" / "Edit
with") is **edited where it is**, through the Storage Access Framework (`content://` URI). It is never copied into the
app's storage.

- **Identity:** the document gets an entry in `index.json` (`uri`, `persistent`, `bom`) and a UUID like any note, so tabs,
  session, Favorites and Recent need no special cases. There is no file under `notes/`. Opening the same URI again finds
  the same entry.
- **Reading:** strict UTF-8 (anything else is reported, never opened for editing), size limit 8 MB, a UTF-8 byte order
  mark is stripped for editing and written back if the file had one, line endings are untouched. Every provider call has a
  15-second timeout, so a hanging provider cannot freeze the repository.
- **Writing:** providers cannot replace a file atomically, so (1) the new text is first written atomically to a private
  recovery copy `external-backups/<id>.md`, (2) the document is overwritten, (3) its content is **read back and compared**.
  If the provider fails or writes only part, the save fails visibly (the editor keeps the text and retries) and the original
  or the recovery copy still has the content.
- **Read-only:** a document without a write grant (or whose provider says it cannot be written) is detected when it is opened;
  it is shown with a "Read-only" banner and is never written (editor, Clear and autosave are blocked).
- **No Trash:** moving an external file to the Trash would delete it from the user's storage. Instead *Remove from
  openPad++* only forgets the entry; **the file is never deleted** (and a pending edit is written first). Rename is not
  offered for external documents (the title follows the provider's file name).
- **Persistent access:** picker results are persisted (`takePersistableUriPermission`). Grants from "Open with" often are
  not; such an entry is kept for the running session only and forgotten at the next start (the file is untouched).
  A persistent document that later becomes unavailable (moved, deleted, access withdrawn) stays listed; opening it shows a
  message, and a restored session simply drops its tab.
- **Share** (any note): a copy is written to a private cache folder as `<Title>.md` and handed out through a FileProvider
  (`<applicationId>.share`, not exported, read grant per intent, only the `share/` cache folder is reachable). The recipient
  gets a real, readable `Shopping.md`.
- **Intent filters:** `text/markdown`, `text/x-markdown`, and files whose name ends in `.md`/`.markdown` with a generic type.
  Other files are not claimed. `MainActivity` is `singleTask`, so a second "Open with" arrives in the running screen instead
  of creating another session.

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

## Crash consistency

Every repository operation does **at most one file-system step that matters, then one atomic index write**:

| Operation | File step | Index write | Crash between the two |
|-----------|-----------|-------------|-----------------------|
| create | write `notes/<id>.md` | add entry | file is adopted under the **same id**, title re-derived from its first line |
| save | atomic rewrite of the file | `updatedAt`, auto-title | new text is kept; only the timestamp/title update is lost |
| **rename** | *none* | new title | old *or* new title - never a different id |
| Trash / Restore | move `<id>.md` between directories | `trashedAt` | the file's location wins; same id |
| delete permanently | delete the file | remove entry | entry is dropped |

Because rename (and auto-retitling) needs no file step at all, the old "file renamed but index not updated"
window no longer exists: there is nothing to get out of sync. Trash moves keep the file name, so there is no name
collision to resolve either.

If a step fails inside a running app, the in-memory state is discarded and re-read (and re-reconciled) from disk
on the next operation, so the app never continues on state that is ahead of the disk.

On first use the repository reconciles `index.json` with the directories. The directories are the source of truth
for which notes exist and whether they are trashed; the index supplies titles and timestamps:

| Situation | Result |
|-----------|--------|
| `<uuid>.md` without index entry (index lost/corrupt, crash after create) | adopted with that **same id**; title from the first line |
| `.md` file with any other name (placed by hand) | renamed to `<newid>.md` and adopted; title from its name |
| index entry whose file is gone | entry dropped |
| file in the other directory than the index says (crash during Trash/Restore) | follows the file |
| the same id present in both directories | the indexed location wins; the stray copy is kept as a separate note, never deleted |
| unreadable index | kept as `index.json.corrupt`, notes adopted from the directories |
| index entry with an invalid id | ignored |
| leftover `*.tmp` files (interrupted atomic write) | deleted; the previous content is intact |

What is lost if `index.json` is lost entirely: custom titles (re-derived from the first line), timestamps,
favorites and recency. Note text and ids are not lost.

## Migration from the version-1 layout

Version 1 (Milestone 2) named files after their title (`notes/Shopping.md`) and stored `fileName` in the index.
On load, each such entry's file is renamed to `<id>.md`, the title is taken from the old name, and the index is
rewritten as version 2 without `fileName`. The step is idempotent, so a crash in the middle just resumes on the next
start (including when the file was already renamed but the index not yet rewritten).

## Trash

*Delete* moves the file to `trash/` and sets `trashedAt`. *Restore* moves it back; if another note has taken the
title in the meantime the restored note gets a numeric suffix (`Plan 2`) - it never overwrites or renames another note. *Delete permanently* works only
on notes that are already in Trash. Trashed notes can be read but not saved or renamed.

## Session and settings (Milestone 4)

- **`session.json`** (next to `index.json`, but a separate file on purpose): `version`, the ordered `noteIds` of the
  open saved notes and the `activeNoteId` (absent while the blank page is active). The transient blank page is never
  stored. Written atomically like every other file here. It is disposable by design: reading never throws, and a
  missing, empty, truncated or garbage file simply means "no open notes". Entries of the wrong type are skipped
  individually, unknown fields are ignored, and an interrupted write leaves the previous session intact. A broken
  session can never affect note files or `index.json`.
- Stale ids (deleted, trashed or never existing) are dropped when the session is restored
  (`StartupPlanner`); Restore from Trash does not reopen a note by itself.
- **Startup setting:** `startup_mode` in a Jetpack DataStore (preferences) file. Default (new installation, missing,
  corrupted or unknown stored value): `ResumeAndBlank` ("Resume + blank note"). The other values are
  `ResumeSession` and `BlankNote`.
- The view model rewrites `session.json` after every change to the open documents (open, close, switch, draft
  becoming a note, delete) and once at startup, right after the plan for the chosen mode was made.

## Folders (Alpha 3)

Folders are metadata in `index.json` (`folders: [{id, name}]` and an optional `folderId` on a note); the `.md` files do not move or change and
keep their stable ids. One level only. Names are trimmed, 1-60 characters, no control characters, unique ignoring case. A folder can only be
deleted while no *active* note is in it (a trashed note filed there comes back unfiled); a note filed in a folder that no longer exists is
unfiled on load. Favorites and Recent ignore folders. External documents are not offered for folders. The index version is 5; older
indexes load unchanged (every new field has a default).

## Editor state (Alpha 3)

`editor-state.json` holds the remembered caret and a bounded undo history per open note, tied to a fingerprint of the exact text; see
[editor.md](editor.md#remembered-caret-and-undo-alpha-3). It is disposable (like `session.json`): damaged or stale means "nothing
remembered".

## Signing and updates (Alpha line)

Android only installs an update over an app signed with the **same certificate**. The Alpha line is signed with one stable key - the
"Android Debug" certificate that signed `v0.1.0-alpha.1`, `v0.1.0-alpha.2` and every later Alpha (SHA-256 of the certificate
`7C:2A:FA:95:46:C9:C0:1D:2C:E4:BE:D9:28:83:6E:BA:08:36:15:BF:62:70:4E:3E:A8:61:80:8A:C1:3B:F9:F0`, valid until 2056). The keystore is **not in the
repository**; it is kept outside it (`~/.openpad-signing/`, mode 700/600) and `app/build.gradle.kts` uses it when `OPENPAD_KEYSTORE` is set
(otherwise Gradle's default debug key is used - fine for development and CI, but such an APK cannot update an installed Alpha). Releases
are built locally with that keystore and the certificate fingerprint is compared with the previous release before publishing
(`apksigner verify --print-certs`). Never replace this key during the Alpha line; keep an off-machine copy of the keystore. A production
signing key (and Play App Signing) is a decision for the first real release and will need a fresh install the first time. No keystore,
password or key is ever committed; GitHub Actions builds only debug APKs signed with the runner's throw-away key.

## Not implemented yet

Nothing further.
