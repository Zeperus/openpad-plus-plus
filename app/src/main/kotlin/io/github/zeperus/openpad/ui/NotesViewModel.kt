package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.zeperus.openpad.domain.Autosaver
import io.github.zeperus.openpad.domain.EditorStateStore
import io.github.zeperus.openpad.editor.EditorStateCodec
import io.github.zeperus.openpad.editor.PersistedEditorState
import kotlinx.coroutines.delay
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
import io.github.zeperus.openpad.editor.Cursor
import io.github.zeperus.openpad.editor.DocumentSelection
import io.github.zeperus.openpad.editor.DocumentSelections
import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.EditorSession
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.SpanKind
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
 *
 * The text the user edits is an [EditorSession] (rows, caret, undo history) on top of that Markdown: every edit goes
 * keyboard -> operation -> document model -> Markdown -> [NoteEditor] -> autosave. Each open tab keeps its session, so
 * caret and undo history survive switching tabs; a session is only reused if its Markdown is exactly what is on disk.
 */
class NotesViewModel(
    private val repository: NoteRepository,
    private val sessionStore: SessionStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val editorStates: EditorStateStore = EditorStateStore.None,
) : ViewModel() {
    private val actions = Mutex() // user actions and autosave run one at a time, in order
    private var editor = NoteEditor(repository)

    private var rich = EditorSession(EditorDocument.empty())
    private val sessions = HashMap<NoteId, EditorSession>() // the sessions of the open tabs
    private var uiVersion = 0L
    private var focusToken = 0L

    // Caret and recent undo history per open note, remembered across app restarts (see EditorStateCodec).
    private val persistedStates = HashMap<String, PersistedEditorState>()
    private val dirtyStates = HashSet<NoteId>()
    private val stateChanges = Channel<Unit>(Channel.CONFLATED)

    /** False until the previous session has been restored; the editor must not be used before that. */
    var ready by mutableStateOf(false)
        private set

    /** The Markdown of the open document, as last handed to the autosave. */
    var text by mutableStateOf("")
        private set

    /** What the editor shows: rows, caret, formatting state. */
    var ui by mutableStateOf(snapshot())
        private set

    /** A selection that spans rows (the native text selection only works inside one row); null when there is none. */
    var docSelection by mutableStateOf<DocumentSelection?>(null)
        private set

    /** Changes whenever a different document (or a cleared one) is loaded into the editor; row ids restart then. */
    var epoch by mutableIntStateOf(0)
        private set

    /** Set when an operation moved the caret to another row; the row takes the keyboard focus and calls [focusHandled]. */
    var focusRequest by mutableStateOf<FocusRequest?>(null)
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

    init {
        scope.launch {
            for (change in stateChanges) {
                delay(STATE_DELAY_MILLIS) // a burst of typing is saved once
                persistStates()
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

    /** Replaces the whole document with [newText] (Markdown). Used for programmatic changes; typing uses [onRowText]. */
    fun onTextChange(newText: String) {
        if (!ready || readOnly) return
        rich = EditorSession(EditorDocument.fromMarkdown(newText))
        rich.smartChecklist = current?.smartChecklist == true
        epoch++
        registerSession()
        publish()
        pushMarkdown(newText)
    }

    // ---- Editing (the rich editor reports what the user did; the session turns it into document changes) ----------

    /** The text field of row [rowId] now holds [newText] with the caret at [caret]. */
    fun onRowText(rowId: Long, newText: String, caret: Int) = edit { it.onText(rowId, newText, caret) }

    fun onBackspaceAtStart(rowId: Long) = edit { it.backspaceAtStart(rowId) }

    /** The caret or selection moved inside row [rowId]. */
    fun onSelection(rowId: Long, start: Int, end: Int) {
        if (!ready) return
        val next = Cursor(rowId, start, end)
        if (rich.cursor == next) return
        rich.moveCursor(next)
        publish()
        markStateDirty()
    }

    // ---- Selections that span rows -----------------------------------------------------------------------------

    /** The selection gesture / handles report the selection. A collapsed one is no selection. */
    fun setDocumentSelection(selection: DocumentSelection?) {
        if (!ready) return
        docSelection = selection?.takeUnless { it.isCollapsed }?.let { DocumentSelections.validated(rich.doc, it) }
    }

    fun selectAll() {
        if (!ready) return
        docSelection = DocumentSelections.selectAll(rich.doc)
    }

    fun clearSelection() {
        docSelection = null
    }

    /** What Copy puts on the clipboard: the cross-row selection, else the native selection in the row; null if nothing is selected. */
    fun selectedText(markdown: Boolean): String? {
        val selection = currentSelection() ?: return null
        val text = if (markdown) rich.selectedMarkdown(selection) else rich.selectedText(selection)
        return text.takeIf { it.isNotEmpty() }
    }

    /** Cut: removes the selection (one undo step) and returns what to put on the clipboard. */
    fun cutSelection(): String? {
        if (readOnly) return null
        val selection = currentSelection() ?: return null
        val text = rich.selectedText(selection).takeIf { it.isNotEmpty() } ?: return null
        edit { it.deleteSelection(selection) }
        docSelection = null
        return text
    }

    fun deleteSelection() {
        val selection = currentSelection() ?: return
        edit { it.deleteSelection(selection) }
        docSelection = null
    }

    /** Paste: replaces the selection (if any) with [text], as plain text. Without a selection it is inserted at the caret. */
    fun pasteText(text: String) {
        val selection = currentSelection()
        if (selection != null) {
            edit { it.replaceSelection(selection, text) }
            docSelection = null
            return
        }
        val cursor = rich.cursor ?: return
        val row = rich.doc.row(cursor.rowId) ?: return
        val old = row.text.text
        val lo = minOf(cursor.start, cursor.end).coerceIn(0, old.length)
        val hi = maxOf(cursor.start, cursor.end).coerceIn(0, old.length)
        onRowText(row.id, old.substring(0, lo) + text + old.substring(hi), lo + text.length)
    }

    /** "Paste as Markdown": an explicit action; the text is parsed and its blocks inserted (replacing the selection). */
    fun pasteMarkdown(text: String) {
        val selection = docSelection
        edit { it.pasteMarkdown(text, selection) }
        docSelection = null
    }

    private fun currentSelection(): DocumentSelection? {
        docSelection?.let { return it }
        val c = rich.cursor ?: return null
        if (c.isCollapsed || rich.doc.row(c.rowId) == null) return null
        return DocumentSelection(io.github.zeperus.openpad.editor.DocumentPosition(c.rowId, minOf(c.start, c.end)), io.github.zeperus.openpad.editor.DocumentPosition(c.rowId, maxOf(c.start, c.end)))
    }

    fun toggleStyle(kind: SpanKind) = edit { it.toggleStyle(kind) }

    fun setLink(href: String) = edit { it.setLink(href.trim()) }

    fun removeLink() = edit { it.removeLink() }

    fun setBlock(kind: RowKind) = edit { it.setKind(kind) }

    fun toggleList(ordered: Boolean) = edit { it.toggleList(ordered) }

    fun toggleTask() = edit { it.toggleTask() }

    fun setChecked(rowId: Long, checked: Boolean) = edit { it.setChecked(rowId, checked) }

    fun indent() = edit { it.indent() }

    fun outdent() = edit { it.outdent() }

    fun insertRule() = edit { it.insertRule() }

    fun undo() = edit { it.undo() }

    fun redo() = edit { it.redo() }

    /** A tap below the last row: the caret goes to the end of the last row that can hold text. */
    fun focusEnd() {
        if (!ready || readOnly) return
        val row = rich.doc.rows.lastOrNull { it.isTextual } ?: return
        rich.moveCursor(Cursor(row.id, row.text.length))
        publish()
        requestFocus(row.id)
    }

    /** Puts the caret at the end of row [rowId] and gives it the keyboard focus (e.g. "Edit source" on a table). */
    fun focusRow(rowId: Long) {
        if (!ready || readOnly) return
        val row = rich.doc.row(rowId) ?: return
        rich.moveCursor(Cursor(rowId, row.text.length))
        publish()
        requestFocus(rowId)
    }

    fun focusHandled(request: FocusRequest) {
        if (focusRequest == request) focusRequest = null
    }

    /**
     * Runs one editing operation. An exception in the editor never reaches the file: the last Markdown that was handed to
     * the autosave stays as it is, and the user is told.
     */
    private inline fun edit(operation: (EditorSession) -> Boolean) {
        if (!ready || readOnly) return
        val before = rich.cursor?.rowId
        val changed = try {
            operation(rich)
        } catch (e: Exception) {
            message = UserMessage.ActionFailed
            false
        }
        publish()
        if (changed) { docSelection = null; commitMarkdown() }
        markStateDirty()
        val row = rich.cursor?.rowId
        if (row != null && row != before) requestFocus(row)
    }

    private fun commitMarkdown() {
        val markdown = try {
            rich.markdown()
        } catch (e: Exception) {
            message = UserMessage.SaveFailed // the previous Markdown stays in the editor and on disk
            return
        }
        pushMarkdown(markdown)
    }

    private fun pushMarkdown(markdown: String) {
        text = markdown
        editor.onTextChanged(markdown)
        autosaver.notifyChanged()
    }

    /** The open note's caret or history changed: it is saved shortly (and when the app goes to the background). */
    private fun markStateDirty() {
        val id = editor.info?.id ?: return
        dirtyStates += id
        stateChanges.trySend(Unit)
    }

    private suspend fun persistStates() {
        val ids = dirtyStates.toList()
        dirtyStates.clear()
        val pruned = persistedStates.keys.retainAll(session.noteIds.mapTo(HashSet()) { it.value })
        var changed = pruned
        for (id in ids) {
            val live = sessions[id] ?: continue
            val captured = try { EditorStateCodec.capture(live) } catch (e: Exception) { continue }
            persistedStates[id.value] = captured
            changed = true
        }
        if (!changed) return
        try {
            editorStates.save(persistedStates.toMap())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // disposable: the next change writes it again
        }
    }

    private fun requestFocus(rowId: Long) {
        focusRequest = FocusRequest(rowId, ++focusToken)
    }

    private fun snapshot(): EditorUi {
        val session = rich
        val cursor = session.cursor
        val row = cursor?.let { session.doc.row(it.rowId) }
        return EditorUi(
            doc = session.doc,
            cursor = cursor,
            active = session.activeKinds(),
            rowKind = row?.kind,
            link = session.linkAtCaret(),
            canUndo = session.history.canUndo,
            canRedo = session.history.canRedo,
            version = ++uiVersion,
        )
    }

    private fun publish() {
        ui = snapshot()
        docSelection = docSelection?.let { DocumentSelections.validated(rich.doc, it) }
    }

    /** The open note's session belongs to its tab. A draft has none until it becomes a note. */
    private fun registerSession() {
        editor.info?.id?.let { sessions[it] = rich }
    }

    /** Called when the app goes to the background: make sure everything is on disk. */
    fun flush() {
        scope.launch {
            autosaver.flush()
            persistStates() // after the text: the saved state is only valid for the text that is on disk
        }
    }

    // ---- Documents -------------------------------------------------------------------------------------------

    /** "New note": activates the single blank page, creating it if there is none. Never a second draft. */
    fun newNote() = act {
        saveNow()
        commitSession(session.newDraft())
        syncEditor(markOpened = false)
        if (editor.isDraft && editor.smartOnCreate) switchTo(NoteEditor(repository)) // a blank checklist page becomes a plain one
    }

    /**
     * "New checklist": the blank page, but as a smart checklist - one empty task item to type into, and the note is created
     * with Smart Checklist switched on. Still an ordinary `.md` file; nothing is written until there is text.
     */
    fun newChecklist() = act {
        saveNow()
        commitSession(session.newDraft())
        syncEditor(markOpened = false)
        if (!editor.isDraft) return@act
        switchTo(NoteEditor(repository, smartOnCreate = true))
        rich.moveCursor(Cursor(rich.doc.rows.first().id, 0))
        publish()
        requestFocus(rich.doc.rows.first().id)
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

    /**
     * Smart checklist mode for the open note (stored as metadata, never in the Markdown). Switching it on sorts the note's
     * checklists once - unchecked first - as one undoable step; from then on ticking an item moves it.
     */
    fun toggleSmartChecklist() = act {
        if (readOnly) return@act
        val enable = !(editor.info?.smartChecklist ?: false)
        if (!editor.setSmartChecklist(enable)) return@act
        rich.smartChecklist = enable
        if (enable) edit { it.sortChecklists() }
        afterEditorChanged()
    }

    /** Empties the open note. The note and its file stay. */
    fun clear() = act {
        if (readOnly) return@act
        editor.clear()
        text = editor.text
        rich = EditorSession(EditorDocument.empty())
        rich.smartChecklist = current?.smartChecklist == true
        epoch++
        registerSession()
        publish()
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
            persistStates()
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
            try { persistedStates.putAll(editorStates.load()) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* nothing remembered */ }
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
        if (info != null) { sessions[info.id] = rich; markStateDirty() }
    }

    private fun commitSession(next: OpenDocuments) {
        session = next
        if (persistedStates.keys.any { key -> next.noteIds.none { it.value == key } }) stateChanges.trySend(Unit) // closed tabs forget their history
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
        markStateDirty() // the tab being left keeps its caret and history for later
        editor = next
        text = next.text
        current = next.info
        readOnly = next.readOnly
        docSelection = null
        rich = sessionFor(next)
        rich.smartChecklist = next.info?.smartChecklist ?: next.smartOnCreate
        if (rich.smartChecklist && !next.readOnly && rich.settleLoaded()) commitMarkdown() // loading puts a smart checklist in order
        epoch++
        focusRequest = null
        publish()
    }

    /** The open tab's own session if it still matches the file exactly (keeps caret and undo), otherwise a fresh one. */
    private fun sessionFor(next: NoteEditor): EditorSession {
        val id = next.info?.id
        val kept = id?.let { sessions[it] }?.takeIf { runCatching { it.markdown() == next.text }.getOrDefault(false) }
        val restored = if (kept == null && id != null) persistedStates[id.value]?.let { EditorStateCodec.restore(next.text, it) } else null
        val result = kept ?: restored ?: EditorSession(if (id == null && next.smartOnCreate) EditorDocument.emptyChecklist() else EditorDocument.fromMarkdown(next.text))
        if (id != null) sessions[id] = result
        sessions.keys.retainAll((session.noteIds + listOfNotNull(id)).toSet())
        return result
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

private const val STATE_DELAY_MILLIS = 800L

