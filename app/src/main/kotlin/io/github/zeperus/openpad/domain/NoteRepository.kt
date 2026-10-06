package io.github.zeperus.openpad.domain

import java.io.IOException

/**
 * Storage of notes. Implementations keep the text in real `.md` files and only metadata elsewhere.
 * All operations are safe to call from any coroutine.
 */
interface NoteRepository {
    /** Creates a note with [text] (may be empty) and returns it. The title is derived from the first line. */
    suspend fun createNote(text: String): NoteInfo

    /** Active (non-trashed) notes, sorted by title. */
    suspend fun listNotes(): List<NoteInfo>

    /** Trashed notes, most recently trashed first. */
    suspend fun listTrash(): List<NoteInfo>

    /** Reads a note (active or trashed). */
    suspend fun openNote(id: NoteId): NoteContent

    /** Atomically replaces the text of an active note. */
    suspend fun saveNote(id: NoteId, text: String): NoteInfo

    /** Renames an active note. Throws [InvalidNoteNameException] or [NoteNameConflictException]. */
    suspend fun renameNote(id: NoteId, newTitle: String): NoteInfo

    /** Marks/unmarks an active note as favorite. Pure metadata: the `.md` file is not touched. */
    suspend fun setFavorite(id: NoteId, favorite: Boolean): NoteInfo

    /** Switches smart checklist mode on/off for an active note. Pure metadata: the `.md` file is not touched. */
    suspend fun setSmartChecklist(id: NoteId, enabled: Boolean): NoteInfo

    /** Folders, sorted by name (case-insensitive). */
    suspend fun listFolders(): List<FolderInfo>

    /** Creates a folder. Throws [InvalidFolderNameException] or [FolderNameConflictException]. */
    suspend fun createFolder(name: String): FolderInfo

    suspend fun renameFolder(id: String, name: String): FolderInfo

    /** Deletes an *empty* folder (no active notes in it); otherwise [FolderNotEmptyException]. Notes are never deleted with it. */
    suspend fun deleteFolder(id: String)

    /** Files an active note in [folderId], or takes it out of its folder with null. Pure metadata. */
    suspend fun moveNote(id: NoteId, folderId: String?): NoteInfo

    /** Records that an active note just became the open note (feeds Recent). */
    suspend fun markOpened(id: NoteId): NoteInfo

    /**
     * Registers an external document (or finds the existing entry for the same [uri]) so that it can be opened like a
     * note. [persistent] says whether access survives a restart; entries that do not are forgotten on the next start.
     */
    suspend fun openExternal(uri: String, persistent: Boolean): NoteInfo

    /** Removes an external document from openPad++. The file itself is never touched or deleted. */
    suspend fun forgetExternal(id: NoteId)

    suspend fun moveToTrash(id: NoteId): NoteInfo

    suspend fun restoreFromTrash(id: NoteId): NoteInfo

    /** Only notes that are already in the Trash can be deleted permanently. */
    suspend fun deletePermanently(id: NoteId)
}

open class NoteStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

class NoteNotFoundException(id: NoteId) : NoteStorageException("No such note: ${id.value}")

class NoteNotInTrashException(id: NoteId) : NoteStorageException("Note is not in the Trash: ${id.value}")

class InvalidNoteNameException(name: String) : NoteStorageException("Invalid note name: '$name'")

class NoteNameConflictException(name: String) : NoteStorageException("A note named '$name' already exists")

class InvalidFolderNameException(name: String) : NoteStorageException("Invalid folder name: '$name'")

class FolderNameConflictException(name: String) : NoteStorageException("A folder named '$name' already exists")

class FolderNotFoundException(id: String) : NoteStorageException("No such folder: $id")

class FolderNotEmptyException(name: String) : NoteStorageException("The folder '$name' still contains notes")

/** The note's file is not valid UTF-8; it is left untouched so nothing gets corrupted by an overwrite. */
class NoteUnreadableException(title: String, cause: Throwable? = null) :
    NoteStorageException("Cannot read '$title' as UTF-8 text", cause)

/** The external document cannot be read or written right now (missing, permission revoked, provider gone, too slow). */
class NoteSourceUnavailableException(message: String, cause: Throwable? = null) : NoteStorageException(message, cause)

/** The operation does not make sense for an external document (Trash, rename). */
class ExternalNotSupportedException(operation: String) : NoteStorageException("Not supported for external documents: $operation")
