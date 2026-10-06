package io.github.zeperus.openpad.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The state of one open note in the editor, including the *draft* rule:
 *
 * A fresh note is a draft that exists only in memory. No file is created until the text contains something
 * other than whitespace, so opening and abandoning blank pages leaves nothing behind. Once a file exists it is
 * never removed implicitly - not even if the text is later emptied (that is what Delete is for).
 */
class NoteEditor(
    private val repository: NoteRepository,
    initial: NoteContent? = null,
    /** A read-only document is shown but never written: [save], [clear] and text changes are ignored. */
    val readOnly: Boolean = initial?.readOnly ?: false,
) {
    private val mutex = Mutex()

    @Volatile var info: NoteInfo? = initial?.info
        private set

    @Volatile var text: String = initial?.text ?: ""
        private set

    // Text as last written to disk. null while the note is still an in-memory draft.
    @Volatile private var savedText: String? = initial?.text

    val isDraft: Boolean get() = info == null

    /** True if there is text that is not on disk yet (and would be written by [save]). */
    val hasUnsavedChanges: Boolean
        get() = if (info == null) text.isNotBlank() else text != savedText

    fun onTextChanged(newText: String) {
        if (readOnly) return
        text = newText
    }

    /** Writes pending changes. Returns true if something was written. Safe to call repeatedly. */
    suspend fun save(): Boolean = mutex.withLock {
        if (readOnly) return@withLock false
        val snapshot = text
        val current = info
        when {
            current == null -> {
                if (snapshot.isBlank()) return@withLock false
                info = repository.createNote(snapshot)
                savedText = snapshot
                true
            }
            snapshot == savedText -> false
            else -> {
                info = repository.saveNote(current.id, snapshot)
                savedText = snapshot
                true
            }
        }
    }

    /** Empties the text. The note (file) stays. A draft simply becomes an empty draft. */
    suspend fun clear() {
        if (readOnly) return
        text = ""
        save()
    }

    /** Renames the note, creating it first if it is a draft with real content. Returns false for blank drafts. */
    suspend fun rename(newTitle: String): Boolean {
        save()
        return mutex.withLock {
            val current = info ?: return@withLock false
            info = repository.renameNote(current.id, newTitle)
            true
        }
    }

    /** Sets the favorite flag, creating the note first if it is a draft with real content. False for blank drafts. */
    suspend fun setFavorite(favorite: Boolean): Boolean {
        save()
        return mutex.withLock {
            val current = info ?: return@withLock false
            info = repository.setFavorite(current.id, favorite)
            true
        }
    }

    /** Moves the note to the Trash after writing pending changes. Returns the trashed note, or null for a draft. */
    suspend fun moveToTrash(): NoteInfo? {
        save()
        return mutex.withLock {
            val current = info ?: return@withLock null
            repository.moveToTrash(current.id)
        }
    }
}
