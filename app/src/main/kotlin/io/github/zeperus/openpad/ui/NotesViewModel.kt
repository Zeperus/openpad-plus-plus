package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.zeperus.openpad.domain.Autosaver
import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteEditor
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UserMessage { SaveFailed, NoteUnreadable, ActionFailed }

enum class RenameResult { Ok, InvalidName, NameTaken, Failed }

/**
 * Holds the open note and the Files/Trash lists.
 *
 * Everything runs in [scope] (an application-lifetime scope, not viewModelScope) so a save or delete that has
 * started is never cancelled just because the screen went away. [scope] must dispatch on the main thread:
 * the Compose state below is only reliably observed when written there. Blocking file I/O happens inside the
 * repository on its own dispatcher.
 */
class NotesViewModel(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
) : ViewModel() {
    private val actions = Mutex() // user actions run one at a time, in order
    private var editor = NoteEditor(repository)

    /** Text shown in the editor. */
    var text by mutableStateOf("")
        private set

    /** The open note, or null while it is still an unsaved draft. */
    var current by mutableStateOf<NoteInfo?>(null)
        private set

    var notes by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    var trash by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    var message by mutableStateOf<UserMessage?>(null)
        private set

    private val autosaver = Autosaver(scope, onError = { message = UserMessage.SaveFailed }) { saveNow() }

    /** Rename/Delete make sense once there is a note or at least text that would become one. */
    val hasNote: Boolean get() = current != null || text.isNotBlank()

    init {
        act { refreshLists() }
    }

    fun onTextChange(newText: String) {
        text = newText
        editor.onTextChanged(newText)
        autosaver.notifyChanged()
    }

    /** Called when the app goes to the background: make sure everything is on disk. */
    fun flush() {
        scope.launch { autosaver.flush() }
    }

    fun newNote() = act {
        saveNow()
        if (editor.isDraft) return@act // already a blank page: do not stack up empty drafts
        switchTo(NoteEditor(repository))
    }

    fun openNote(id: NoteId) = act {
        if (current?.id == id) return@act
        saveNow()
        try {
            switchTo(NoteEditor(repository, repository.openNote(id)))
        } catch (e: NoteUnreadableException) {
            message = UserMessage.NoteUnreadable
        }
    }

    suspend fun rename(title: String): RenameResult = actions.withLock {
        try {
            val renamed = editor.rename(title)
            current = editor.info
            refreshLists()
            if (renamed) RenameResult.Ok else RenameResult.Failed
        } catch (e: InvalidNoteNameException) {
            RenameResult.InvalidName
        } catch (e: NoteNameConflictException) {
            RenameResult.NameTaken
        } catch (e: NoteStorageException) {
            RenameResult.Failed
        }
    }

    /** Empties the open note. The note and its file stay. */
    fun clear() = act {
        editor.clear()
        text = editor.text
        current = editor.info
        refreshLists()
    }

    /** Moves the open note to the Trash and shows a fresh blank page. */
    fun deleteCurrent() = act {
        editor.moveToTrash()
        switchTo(NoteEditor(repository))
        refreshLists()
    }

    fun restore(id: NoteId) = act {
        repository.restoreFromTrash(id)
        refreshLists()
    }

    fun deletePermanently(id: NoteId) = act {
        repository.deletePermanently(id)
        refreshLists()
    }

    fun messageShown() {
        message = null
    }

    override fun onCleared() {
        scope.launch {
            autosaver.flush()
            autosaver.close()
        }
    }

    private suspend fun saveNow() {
        val saved = editor
        if (saved.save()) {
            if (saved === editor) current = saved.info
            refreshLists()
        }
    }

    private fun switchTo(next: NoteEditor) {
        editor = next
        text = next.text
        current = next.info
    }

    private suspend fun refreshLists() {
        notes = repository.listNotes()
        trash = repository.listTrash()
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            actions.withLock {
                try {
                    block()
                } catch (e: NoteStorageException) {
                    message = UserMessage.ActionFailed
                }
            }
        }
    }
}
