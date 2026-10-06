package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.zeperus.openpad.domain.Autosaver
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteContent
import io.github.zeperus.openpad.domain.NoteEditor
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteLists
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import io.github.zeperus.openpad.domain.OpenDocuments
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.SessionStore
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.domain.StartupPlanner
import io.github.zeperus.openpad.domain.TabItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

enum class UserMessage { SaveFailed, NoteUnreadable, ActionFailed, SourceUnavailable }

enum class RenameResult { Ok, InvalidName, NameTaken, Failed }

/**
 * Holds the open documents (the tab strip), the active note's editor and the Files/Trash lists.
 *
 * Everything runs in [scope] (an application-lifetime scope, not viewModelScope) so a save or delete that has
 * started is never cancelled just because the screen went away. [scope] must dispatch on the main thread:
 * the Compose state below is only reliably observed when written there. Blocking file I/O happens inside the
 * repositories on their own dispatchers.
 *
 * There is exactly one live [NoteEditor] - the active tab's. Switching tabs saves it and loads the other note
 * from the repository, so unsaved text only ever exists in the active editor. Every state change runs under
 * [actions] (including autosave), so draft materialization can never interleave with a tab operation.
 */
class NotesViewModel(
    private val repository: NoteRepository,
    private val sessionStore: SessionStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : ViewModel() {
    private val actions = Mutex() // user actions and autosave run one at a time, in order
    private var editor = NoteEditor(repository)

    /** False until the previous session has been restored; the editor must not be used before that. */
    var ready by mutableStateOf(false)
        private set

    /** Text shown in the editor. */
    var text by mutableStateOf("")
        private set

    /** The open note, or null while it is still an unsaved draft. */
    var current by mutableStateOf<NoteInfo?>(null)
        private set

    /** True while the open document cannot be changed (a read-only external file): shown, never written. */
    var readOnly by mutableStateOf(false)
        private set

    /** The open documents. Only saved notes are persisted; the single blank draft is transient. */
    var session by mutableStateOf(OpenDocuments.blank())
        private set

    /** All active notes (the FILES section). */
    var notes by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    /** FAVORITES section; the UI shows it only when this is not empty. */
    var favorites by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    /** RECENT section: the last 3 opened notes that are not favorites. */
    var recent by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    var trash by mutableStateOf<List<NoteInfo>>(emptyList())
        private set

    var startupMode by mutableStateOf(StartupMode.Default)
        private set

    var message by mutableStateOf<UserMessage?>(null)
        private set

    /** The tab strip. The draft's title is empty here; the UI shows its own "New note" label for it. */
    val tabs: List<TabItem> get() = session.items(notes, draftTitle = "")

    /** Rename/Delete make sense once there is a note or at least text that would become one. */
    val hasNote: Boolean get() = current != null || text.isNotBlank()

    // The latest session state wins; writes are strictly ordered. A failed write is not fatal: the session is
    // disposable and the next change writes it again.
    private val sessionChanges = Channel<PersistedSession>(Channel.CONFLATED)

    init {
        scope.launch {
            for (persisted in sessionChanges) {
                try {
                    sessionStore.save(persisted)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
    }

    private val autosaver = Autosaver(
        scope,
        onError = { message = UserMessage.SaveFailed },
    ) { actions.withLock { saveNow() } }

    init {
        act { startup() }
    }

    fun onTextChange(newText: String) {
        if (!ready || readOnly) return
        text = newText
        editor.onTextChanged(newText)
        autosaver.notifyChanged()
    }

    /** Called when the app goes to the background: make sure everything is on disk. */
    fun flush() {
        scope.launch { autosaver.flush() }
    }

    // ---- Documents -------------------------------------------------------------------------------------------

    /** "New note": activates the single blank page, creating it if there is none. Never a second draft. */
    fun newNote() = act {
        saveNow()
        commitSession(session.newDraft())
        syncEditor(markOpened = false)
    }

    /** Opens a note from the drawer: already open -> just activate it, otherwise append a tab. */
    fun openNote(id: NoteId) = act {
        if (current?.id == id) return@act
        saveNow()
        commitSession(session.open(id))
        syncEditor(markOpened = true)
        refreshLists()
    }

    /**
     * Opens a document that lives outside the app (file picker or "Open with"). It is edited in place and gets a tab like
     * any note. If it cannot be read, the entry that was just created for it is not kept.
     */
    fun openExternal(uri: String, persistent: Boolean) = act {
        saveNow()
        val alreadyKnown = notes.any { it.externalUri == uri }
        val info = repository.openExternal(uri, persistent)
        commitSession(session.open(info.id))
        syncEditor(markOpened = true)
        if (current?.id != info.id && !alreadyKnown) {
            runCatching { repository.forgetExternal(info.id) }
        }
        refreshLists()
    }

    /** Saves pending text and hands the current note to [onReady] (title and Markdown) for sharing as a `.md` file. */
    fun share(onReady: (title: String, markdown: String) -> Unit) = act {
        saveNow()
        val note = current ?: return@act
        onReady(note.title, editor.text)
    }

    fun selectTab(tab: DocumentTab) = act {
        if (tab == session.active) return@act
        saveNow()
        commitSession(session.activate(tab))
        syncEditor(markOpened = true)
        refreshLists()
    }

    /** Closes a tab. Never deletes or trashes the note, never touches Favorites or Recent. */
    fun closeTab(tab: DocumentTab) = act {
        val target = flushAndResolve(tab)
        commitSession(session.close(target))
        syncEditor(markOpened = true)
        refreshLists()
    }

    fun closeCurrent() = closeTab(session.active)

    fun closeOthers(keep: DocumentTab) = act {
        val keepWasActive = keep == session.active
        saveNow() // the active tab is about to be closed unless it is the one to keep
        val target = if (keepWasActive) session.active else keep // a draft that became a note changed identity
        commitSession(session.closeOthers(target))
        syncEditor(markOpened = true)
        refreshLists()
    }

    fun closeAll() = act {
        saveNow() // text typed into the draft is kept as a note; it is merely closed
        commitSession(session.closeAll())
        syncEditor(markOpened = false)
        refreshLists()
    }

    // ---- The open note ---------------------------------------------------------------------------------------

    suspend fun rename(title: String): RenameResult = actions.withLock {
        try {
            val renamed = editor.rename(title)
            afterEditorChanged()
            if (renamed) RenameResult.Ok else RenameResult.Failed
        } catch (e: InvalidNoteNameException) {
            RenameResult.InvalidName
        } catch (e: NoteNameConflictException) {
            RenameResult.NameTaken
        } catch (e: NoteStorageException) {
            RenameResult.Failed
        }
    }

    /** Favorites or unfavorites the open note (creating it first if it is a draft with text). */
    fun toggleFavorite() = act {
        val favorite = !(editor.info?.favorite ?: false)
        if (editor.setFavorite(favorite)) afterEditorChanged()
    }

    /** Empties the open note. The note and its file stay. */
    fun clear() = act {
        editor.clear()
        text = editor.text
        afterEditorChanged()
    }

    /**
     * Moves the open note to the Trash. Its tab closes and a neighbouring tab (or a fresh blank page) becomes
     * active. A draft with text is saved first, so that text ends up in the Trash rather than vanishing.
     */
    fun deleteCurrent() = act {
        val external = current?.takeIf { it.isExternal }
        if (external != null) { // an external file is only removed from openPad++, never deleted
            saveNow()
            repository.forgetExternal(external.id)
            commitSession(session.remove(external.id))
            syncEditor(markOpened = true)
            refreshLists()
            return@act
        }
        val trashed = editor.moveToTrash()
        if (trashed != null) {
            val s = session
            commitSession(if (s.activeNoteId == null) s.close(DocumentTab.Draft) else s.remove(trashed.id))
            syncEditor(markOpened = true)
        }
        refreshLists()
    }

    /** Restoring from the Trash does not reopen the note by itself. */
    fun restore(id: NoteId) = act {
        repository.restoreFromTrash(id)
        refreshLists()
    }

    fun deletePermanently(id: NoteId) = act {
        repository.deletePermanently(id)
        refreshLists()
    }

    // ---- Settings --------------------------------------------------------------------------------------------

    fun chooseStartupMode(mode: StartupMode) = act {
        settings.setStartupMode(mode)
        startupMode = mode
    }

    fun messageShown() {
        message = null
    }

    override fun onCleared() {
        scope.launch {
            autosaver.flush()
            autosaver.close()
            sessionChanges.close() // the last queued session state is still written
        }
    }

    // ---- Internals (all of these run under `actions` unless noted) -------------------------------------------

    /**
     * Restores the previous session according to the startup setting. The result is persisted immediately, so a
     * mode that does not restore (Blank note) replaces the old session instead of leaving it to resurface later.
     */
    private suspend fun startup() {
        try {
            val mode = try {
                settings.startupMode()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StartupMode.Default
            }
            startupMode = mode
            refreshLists()
            val initial = StartupPlanner.initial(mode, sessionStore.load(), notes.mapTo(HashSet()) { it.id })
            commitSession(initial)
            syncEditor(markOpened = false) // restoring a session is not "using" the note again
        } finally {
            ready = true
        }
    }

    /**
     * Saves pending text of the active tab and returns the tab that [tab] now refers to: if [tab] was the active
     * blank page and the save turned it into a note, the tab became that note's tab.
     */
    private suspend fun flushAndResolve(tab: DocumentTab): DocumentTab {
        val wasActive = tab == session.active
        if (wasActive) saveNow()
        return if (wasActive) session.active else tab
    }

    /** Writes the active editor if it has pending changes; a draft that just became a note joins the session. */
    private suspend fun saveNow() {
        val saved = editor
        val wasDraft = saved.isDraft
        if (saved.save()) {
            if (saved === editor) {
                current = saved.info
                if (wasDraft) materializeDraft(saved.info)
            }
            refreshLists()
        }
    }

    /** After an editor operation that may also have created the note (rename, favorite): update state. */
    private suspend fun afterEditorChanged() {
        current = editor.info
        materializeDraft(editor.info)
        refreshLists()
    }

    private fun materializeDraft(info: NoteInfo?) {
        if (info != null && session.activeNoteId == null && session.draftOpen) {
            commitSession(session.materializeDraft(info.id))
        }
    }

    private fun commitSession(next: OpenDocuments) {
        session = next
        sessionChanges.trySend(next.toPersisted())
    }

    /**
     * Makes the editor show the active tab. A note that cannot be loaded (deleted meanwhile, not valid UTF-8) is
     * dropped from the session and the next tab is tried; this ends at the latest with a blank page.
     */
    private suspend fun syncEditor(markOpened: Boolean) {
        while (true) {
            val id = session.activeNoteId
            if (id == null) {
                if (!editor.isDraft) switchTo(NoteEditor(repository))
                return
            }
            if (editor.info?.id == id) return
            try {
                val content = repository.openNote(id)
                val info = if (markOpened) repository.markOpened(id) else content.info
                switchTo(NoteEditor(repository, NoteContent(info, content.text, content.readOnly)))
                return
            } catch (e: NoteSourceUnavailableException) {
                message = UserMessage.SourceUnavailable
                commitSession(session.remove(id))
            } catch (e: NoteUnreadableException) {
                message = UserMessage.NoteUnreadable
                commitSession(session.remove(id))
            } catch (e: NoteNotFoundException) {
                commitSession(session.remove(id))
            }
        }
    }

    private fun switchTo(next: NoteEditor) {
        editor = next
        text = next.text
        current = next.info
        readOnly = next.readOnly
    }

    private suspend fun refreshLists() {
        val all = repository.listNotes()
        notes = all
        favorites = NoteLists.favorites(all)
        recent = NoteLists.recent(all)
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
