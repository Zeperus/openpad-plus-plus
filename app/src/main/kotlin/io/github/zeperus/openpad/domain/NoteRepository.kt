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

    /** Records that an active note just became the open note (feeds Recent). */
    suspend fun markOpened(id: NoteId): NoteInfo

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

/** The note's file is not valid UTF-8; it is left untouched so nothing gets corrupted by an overwrite. */
class NoteUnreadableException(title: String, cause: Throwable? = null) :
    NoteStorageException("Cannot read '$title' as UTF-8 text", cause)
