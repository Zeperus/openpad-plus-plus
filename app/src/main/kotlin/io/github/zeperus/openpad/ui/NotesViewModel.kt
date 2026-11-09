package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.zeperus.openpad.domain.AppLog
import io.github.zeperus.openpad.domain.Autosaver
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.EditorStateStore
import io.github.zeperus.openpad.domain.ExportResult
import io.github.zeperus.openpad.domain.ExportTarget
import io.github.zeperus.openpad.domain.FolderInfo
import io.github.zeperus.openpad.domain.FolderNameConflictException
import io.github.zeperus.openpad.domain.FolderNotEmptyException
import io.github.zeperus.openpad.domain.InvalidFolderNameException
import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteContent
import io.github.zeperus.openpad.domain.NoteEditor
import io.github.zeperus.openpad.domain.NoteExport
import io.github.zeperus.openpad.domain.NoteFileName
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteLists
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteNotInTrashException
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import io.github.zeperus.openpad.domain.OpenDocuments
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.SessionStore
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupPlanner
import io.github.zeperus.openpad.domain.TabItem
import io.github.zeperus.openpad.editor.Cursor
import io.github.zeperus.openpad.editor.DocumentSelection
import io.github.zeperus.openpad.editor.DocumentSelections
import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.EditorRow
import io.github.zeperus.openpad.editor.EditorSession
import io.github.zeperus.openpad.editor.EditorStateCodec
import io.github.zeperus.openpad.editor.FindInNote
import io.github.zeperus.openpad.editor.FindMatch
import io.github.zeperus.openpad.editor.ListImport
import io.github.zeperus.openpad.editor.RichText
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.Segment
import io.github.zeperus.openpad.editor.SegmentEditing
import io.github.zeperus.openpad.editor.SegmentOp
import io.github.zeperus.openpad.editor.Snapshot
import io.github.zeperus.openpad.editor.SpanKind
import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Find in note: the text searched for, where it occurs in the open note, and which occurrence is the current one. */
data class FindState(val query: String, val matches: List<FindMatch>, val current: Int) {
    val currentMatch: FindMatch? get() = matches.getOrNull(current)
}

enum class UserMessage { SaveFailed, NoteUnreadable, ActionFailed, SourceUnavailable, FolderNotEmpty, MovedToTrash, SmartChecklistOn, FavoriteAdded, FavoriteRemoved }

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
 * [actions] (including autosave), so draft materialization can never interleave with a tab operation. Typing does not
 * wait for [actions]: while an action waits for the disk or a provider, the old note is still on screen and editable, so
 * what was typed meanwhile is written right before its editor is replaced (see [replaceEditor]).
 *
 * The text the user edits is an [EditorSession] (rows, caret, undo history) on top of that Markdown: every edit goes
 * keyboard -> operation -> document model -> Markdown -> [NoteEditor] -> autosave. Each open tab keeps its session, so
 * caret and undo history survive switching tabs; a session is only reused if its Markdown is exactly what is on disk.
 * Preparing the session of a note that is opened (parsing it, applying remembered history) happens off the main thread
 * (see [prepareSession]).
 */
class NotesViewModel(
    private val repository: NoteRepository,
    private val sessionStore: SessionStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val editorStates: EditorStateStore = EditorStateStore.None,
    private val languageManager: io.github.zeperus.openpad.domain.LanguageManager? = null,
    private val exportTarget: ExportTarget? = null,
    /** Where the global search computes its hits (tests pass a test dispatcher). */
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Where the remembered editor state is encoded for the file, and where the session of a note that is opened is prepared
     * (parsed, history applied) - never the main thread in the app (tests pass a test dispatcher).
     */
    private val editorStateDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val actions = Mutex() // user actions and autosave run one at a time, in order
    private var editor = NoteEditor(repository)

    private var rich = EditorSession(EditorDocument.empty())
    private val sessions = HashMap<NoteId, EditorSession>() // the sessions of the open tabs
    private var uiVersion = 0L
    private var focusToken = 0L

    // Caret and recent undo history per open note, remembered across app restarts (see EditorStatePersistence).
    private val editorStatePersistence = EditorStatePersistence(editorStates, scope, editorStateDispatcher)
        .also { it.liveSessions = { id -> sessions[id] } }

    /** False until the previous session has been restored; the editor must not be used before that. */
    var ready by mutableStateOf(false)
        private set

    /** The Markdown of the open document, as last handed to the autosave. */
    var text by mutableStateOf("")
        private set

    /** What the editor shows: rows, caret, formatting state. */
    var ui by mutableStateOf(snapshot())
        private set

    /**
     * The text selected in the editor, as positions in the document (set by [onFieldSelection]). A text field holds several rows,
     * so the native selection can reach across rows: paragraphs, headings and list items. Copy, Cut, Paste and the formatting and
     * list buttons work on it. Null when nothing is selected.
     */
    var docSelection by mutableStateOf<DocumentSelection?>(null)
        private set

    /** Find in note (null = closed). */
    var find by mutableStateOf<FindState?>(null)
        private set

    /** The document [find]'s matches were computed for: they are only searched again when the document or the query changes. */
    private var findDoc: Any? = null

    /** Global note search over the titles and texts of all notes (the search screen reads its state). */
    val noteSearch = NoteSearchController(
        repository = repository,
        scope = scope,
        notes = { notes },
        openText = { current?.id?.let { it to editor.text } }, // the open note is searched with what is on screen
        computeDispatcher = searchDispatcher,
    )

    /**
     * Counts the times the *model* moved the caret (an operation, Undo, a focus request, a new document). A text field that sees a
     * new number puts its caret where the model says; carets the user moves are only reported to the model, never the other way.
     */
    var cursorEpoch by mutableIntStateOf(0)
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

    /** Why [readOnly] is true, for an external document (null otherwise). */
    var readOnlyReason by mutableStateOf<io.github.zeperus.openpad.domain.ReadOnlyReason?>(null)
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

    /** The folders (one level), sorted by name. Empty for people who never use them: then nothing about folders is shown. */
    var folders by mutableStateOf<List<FolderInfo>>(emptyList())
        private set

    /** Folders opened in the drawer (the one holding the open note is always shown open). */
    var expandedFolders by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The app preferences shown in Settings (startup mode, language, editor text size). Independent of the notes. */
    val preferences = SettingsModel(settings, languageManager, scope)

    var message by mutableStateOf<UserMessage?>(null)
        private set

    /** The note the last "Move to Trash" trashed; [undoTrash] restores it. */
    private var lastTrashed: NoteId? = null

    /** True after an autosave/flush failed; false again after the next successful save. The UI shows a lasting "not saved" bar. */
    var saveFailed by mutableStateOf(false)
        private set

    /** Set by [saveNow] when its write failed; [act] reads it so an action that failed because of the save says "not saved". Reset at the start of each act. */
    private var saveFailedNow = false

    /** Title of the active external document that another app changed since openPad++ read it; the UI asks reload / keep mine. */
    var externalConflict by mutableStateOf<String?>(null)
        private set

    /** The note the conflict belongs to: an answer given while another note is open does not apply to it. */
    private var conflictNoteId: NoteId? = null

    /** Whether the conflict question is shown as a dialog. Dismissing it keeps the conflict (the UI shows a bar until it is answered). */
    var conflictDialogVisible by mutableStateOf(false)
        private set

    /** True while "Export all notes" runs. */
    var exporting by mutableStateOf(false)
        private set

    /** The result of the last export, shown once by the UI. */
    var exportResult by mutableStateOf<ExportResult?>(null)
        private set

    /**
     * True while an action has been waiting for its turn or running for longer than [BUSY_AFTER_MILLIS] (a slow document provider,
     * a long note): the UI shows that the app is working, so a tap that seems to do nothing is explained. Quick actions never set
     * it, so nothing flickers. False again as soon as no slow action is left, however the action ended.
     */
    var busy by mutableStateOf(false)
        private set

    /** Actions that are slow in the sense of [busy] and have not ended yet. Main thread only. */
    private var slowActions = 0

    /** The tab strip. The draft's title is empty here; the UI shows its own "New note" label for it. */
    val tabs: List<TabItem> get() = session.items(notes, draftTitle = "")

    /** Rename/Delete make sense once there is a note or at least text that would become one. */
    val hasNote: Boolean get() = current != null || text.isNotBlank()

    /** Any open note can be given a name - also a blank page: naming it makes it a (still empty) note. Not external documents. */
    val canRename: Boolean get() = current?.isExternal != true

    /** The user named the open note: tapping its title does not rename it (a long press does); an automatic title is renamed by a tap. */
    val hasExplicitTitle: Boolean get() = current?.hasExplicitTitle == true

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
                } catch (e: Exception) {
                    AppLog.e(LOG_TAG, "The open tabs could not be saved", e) // the next change writes them again
                }
            }
        }
    }

    private val autosaver = Autosaver(
        scope,
        onError = { e ->
            if (e is NoteChangedExternallyException) {
                raiseConflict(e.title)
            } else {
                AppLog.e(LOG_TAG, "Autosave failed", e)
                message = UserMessage.SaveFailed
                saveFailed = true
            }
        },
    ) { actions.withLock { saveNow() } }

    init {
        act { startup() }
    }

    /**
     * Replaces the whole document with [newText] (Markdown) and starts a fresh editor session for it. **Test entry point only:** the app
     * never calls it - typing goes through [applyFieldOp] / [onRowText], which keep row identity, focus and undo. Do not use it for
     * programmatic edits in the app.
     */
    @androidx.annotation.VisibleForTesting
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

    /**
     * A change in a text field that holds [Segment]s of rows was interpreted as [op]: it is applied to the document. The field is
     * the one the user is typing in, so no focus request is made (it has the focus already).
     */
    fun applyFieldOp(op: SegmentOp): Boolean {
        var changed = false
        edit(focus = false) { changed = SegmentEditing.apply(it, op); changed }
        return changed
    }

    /** The selection of a segment's text field moved: [start]..[end] in the field's text. A range may reach across rows. */
    fun onFieldSelection(segment: Segment, start: Int, end: Int) {
        if (!ready) return
        val a = segment.position(minOf(start, end))
        val b = segment.position(maxOf(start, end))
        if (a == b) {
            docSelection = null
            onSelection(a.rowId, a.offset, a.offset)
            return
        }
        val selection = DocumentSelection(a, b)
        val cursor = Cursor(a.rowId, a.offset, if (a.rowId == b.rowId) b.offset else a.offset)
        if (docSelection == selection && rich.cursor == cursor) return
        docSelection = selection
        rich.moveCursor(cursor)
        publish()
        markStateDirty()
    }

    // ---- Find in note and global search ----------------------------------------------------------------------

    fun startFind(query: String = find?.query.orEmpty()) {
        find = computeFind(query, 0)
    }

    fun setFindQuery(query: String) {
        find = computeFind(query, 0)
    }

    fun findNext() = stepFind(1)

    fun findPrevious() = stepFind(-1)

    fun closeFind() {
        find = null
    }

    private fun stepFind(by: Int) {
        val f = find ?: return
        if (f.matches.isEmpty()) return
        find = f.copy(current = (f.current + by).mod(f.matches.size))
    }

    private fun computeFind(query: String, current: Int): FindState {
        val doc = rich.doc
        val matches = FindInNote.matches(doc, query)
        findDoc = doc
        return FindState(query, matches, current.coerceIn(0, maxOf(0, matches.lastIndex)))
    }

    /** Opens a search result from [noteSearch] and shows where the text is (Find in note, prefilled with the query). */
    fun openFromSearch(id: NoteId, query: String) = act {
        openNoteInternal(id)
        if (ready) find = computeFind(query, 0)
    }

    // ---- Selections that span rows -----------------------------------------------------------------------------

    /** What "Copy as Markdown" from the menu copies: the selection, or - without one - the whole note. */
    fun markdownToCopy(): String? = selectedText(markdown = true) ?: text.trimEnd('\n').takeIf { it.isNotEmpty() }

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

    /**
     * "Paste as Checklist": an explicit action. Every meaningful line of the clipboard text becomes a task item - list markers, task
     * markers and chat headers (`[10.10., 12:42] Sybille: - Bier`) are cleaned off, blank lines dropped - below the caret, in place of a
     * blank row or of the selection. In a blank note the note becomes a Smart Checklist (unchecked above completed). One undo step.
     */
    fun pasteAsChecklist(text: String) = act {
        if (!ready || readOnly || ListImport.checklistItems(text).isEmpty()) return@act
        val blank = rich.doc.rows.all { it.kind == RowKind.Paragraph && it.text.isEmpty }
        if (blank && !rich.smartChecklist) {
            // a blank page: the note will be created as a smart checklist (a draft that holds text is never replaced, it becomes the note)
            if (editor.isDraft && !editor.hasUnsavedChanges) switchTo(NoteEditor(repository, smartOnCreate = true))
            else if (editor.setSmartChecklist(true)) { rich.smartChecklist = true; afterEditorChanged() }
        }
        val selection = currentSelection()
        edit { it.pasteAsChecklist(text, selection) }
        docSelection = null
    }

    private fun currentSelection(): DocumentSelection? {
        docSelection?.let { return it }
        val c = rich.cursor ?: return null
        if (c.isCollapsed || rich.doc.row(c.rowId) == null) return null
        return DocumentSelection(io.github.zeperus.openpad.editor.DocumentPosition(c.rowId, minOf(c.start, c.end)), io.github.zeperus.openpad.editor.DocumentPosition(c.rowId, maxOf(c.start, c.end)))
    }

    fun toggleStyle(kind: SpanKind) {
        val selection = docSelection
        if (selection != null && selection.anchor.rowId != selection.focus.rowId) edit { it.toggleStyleOver(selection, kind) } else edit { it.toggleStyle(kind) }
    }

    fun setLink(href: String) = edit { it.setLink(href.trim()) }

    fun removeLink() = edit { it.removeLink() }

    fun setBlock(kind: RowKind) = edit { it.setKind(kind) }

    fun toggleList(ordered: Boolean) { val selection = docSelection; edit { it.toggleList(ordered, selection) } }

    fun toggleTask() { val selection = docSelection; edit { it.toggleTask(selection) } }

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
        cursorEpoch++
        requestFocus(row.id)
    }

    /** Puts the caret at the end of row [rowId] and gives it the keyboard focus (e.g. "Edit source" on a table). */
    fun focusRow(rowId: Long) {
        if (!ready || readOnly) return
        val row = rich.doc.row(rowId) ?: return
        rich.moveCursor(Cursor(rowId, row.text.length))
        publish()
        cursorEpoch++
        requestFocus(rowId)
    }

    fun focusHandled(request: FocusRequest) {
        if (focusRequest == request) focusRequest = null
    }

    /**
     * Runs one editing operation. An exception in the editor never reaches the file: the last Markdown that was handed to
     * the autosave stays as it is, and the user is told.
     */
    private inline fun edit(focus: Boolean = true, operation: (EditorSession) -> Boolean) {
        if (!ready || readOnly) return
        val started = System.nanoTime()
        val before = rich.cursor?.rowId
        val changed = try {
            operation(rich)
        } catch (e: Exception) {
            AppLog.e(LOG_TAG, "An editing operation failed", e)
            message = UserMessage.ActionFailed
            false
        }
        publish()
        if (changed) { docSelection = null; commitMarkdown() }
        cursorEpoch++
        markStateDirty()
        val row = rich.cursor?.rowId
        if (focus && row != null && row != before) requestFocus(row)
        EditorDiagnostics.recordEdit(System.nanoTime() - started)
    }

    private fun commitMarkdown() {
        val markdown = try {
            rich.markdown()
        } catch (e: Exception) {
            AppLog.e(LOG_TAG, "The document could not be turned into Markdown", e)
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
        editorStatePersistence.markDirty(id)
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
        val f = find
        if (f != null && rich.doc !== findDoc) find = computeFind(f.query, f.current) // a caret move alone keeps the matches
    }

    /** The open note's session belongs to its tab. A draft has none until it becomes a note. */
    private fun registerSession() {
        editor.info?.id?.let { sessions[it] = rich }
    }

    /** Called when the app goes to the background: make sure everything is on disk. */
    fun flush() {
        scope.launch {
            autosaver.flush()
            editorStatePersistence.persist() // after the text: the saved state is only valid for the text that is on disk
        }
    }

    /** Tries the failed save again (the "not saved" bar's retry). */
    fun retrySave() = flush()

    /**
     * Conflict answer "use the other app's version": the open external document shows what the file holds now. The text the user
     * had is not lost: if it differs from the file, it is kept as a new internal note titled [copyTitle] (it shows in FILES). Text
     * typed while the document is being read goes into that copy too.
     */
    fun reloadExternal(copyTitle: String) = act {
        if (externalConflict == null) return@act // a second tap: the first answer already resolved the conflict
        val id = editor.info?.id ?: return@act
        if (conflictNoteId != null && conflictNoteId != id) { clearConflict(); return@act }
        // The copy comes first: if it cannot be made, the conflict and the baseline stay as they are.
        var copy: NoteId? = null
        var copied = ""
        val mine = editor.text
        if (mine.isNotBlank()) {
            copy = createCopy(mine, copyTitle)
            copied = mine
        }
        // Like [onAppResumed]: one read that does not move the baseline. Until the editor really shows the other version, a save
        // of the text still on screen (the autosave is queued behind this action) must keep seeing the conflict - also when a
        // step below fails. Null means the file is again what openPad++ last saw (or nothing was ever compared): then an ordinary
        // read moves nothing.
        val before = editor
        val info = before.info ?: return@act
        val version = repository.checkExternal(id)
        val next = if (version != null) {
            NoteEditor(repository, NoteContent(info, version.text, before.readOnly, before.readOnlyReason))
        } else {
            val content = repository.openNote(id)
            NoteEditor(repository, NoteContent(content.info, content.text, content.readOnly, content.readOnlyReason))
        }
        val prepared = prepareSession(next, reuseKept = false)
        // The document stayed editable while it was read: what was typed meanwhile is the user's version too, so the copy gets it.
        // The last comparison and the replacement below have no suspension point between them.
        while (editor.text != copied && (copy != null || editor.text.isNotBlank())) {
            val latest = editor.text
            val existing = copy
            if (existing == null) {
                copy = createCopy(latest, copyTitle)
            } else {
                repository.saveNote(existing, latest)
            }
            copied = latest
        }
        sessions.remove(id)
        switchTo(next, prepared)
        // Only now, with the editor showing the other version, the baseline moves (no save can run before this: it waits for [actions]).
        if (version != null) repository.adoptExternal(id, version)
        clearConflict()
        saveFailed = false
        refreshLists()
    }

    /** Keeps [text] as a new internal note, titled [copyTitle] if that is a valid name (made unique), else automatically. */
    private suspend fun createCopy(text: String, copyTitle: String): NoteId {
        val copy = repository.createNote(text)
        val wanted = NoteFileName.sanitizeOrNull(copyTitle)
        if (wanted != null) {
            try {
                repository.renameNote(copy.id, NoteFileName.uniqueTitle(wanted, notes.map { it.title }))
            } catch (e: NoteStorageException) {
                // the copy keeps its automatic title
            }
        }
        return copy.id
    }

    /** Conflict answer "keep mine": the editor's text overwrites the document, which is allowed once for the current on-disk version. */
    fun keepMineExternal() = act {
        if (externalConflict == null) return@act // a second tap: nothing is pending any more
        val id = editor.info?.id ?: return@act
        if (conflictNoteId != null && conflictNoteId != id) { clearConflict(); return@act }
        repository.overrideExternalChange(id)
        saveNow()
        clearConflict()
        saveFailed = false
    }

    /** Closes the conflict dialog without choosing. The conflict stays (the UI offers it again) until it is answered. */
    fun dismissExternalConflict() {
        conflictDialogVisible = false
    }

    /** Shows the conflict question again (after it was dismissed). */
    fun showConflictDialog() {
        if (externalConflict != null) conflictDialogVisible = true
    }

    /**
     * The app is shown again (ON_START). An external document that another app changed meanwhile is reloaded, but only if
     * the editor holds nothing unsaved. If text is typed while the document is being read or prepared, that text is kept and
     * the conflict is asked instead.
     */
    fun onAppResumed() = act {
        if (!ready) return@act
        val before = editor
        val info = before.info ?: return@act
        if (!info.isExternal || before.hasUnsavedChanges) return@act
        val textBefore = before.text
        // One read. It does not move the baseline, so if the user types meanwhile the saves still see the other app's version.
        val version = try {
            repository.checkExternal(info.id)
        } catch (e: NoteSourceUnavailableException) {
            null
        } catch (e: NoteUnreadableException) {
            null
        } ?: return@act // not changed outside, nothing to compare, or the document cannot be read right now
        val next = NoteEditor(repository, NoteContent(info, version.text, before.readOnly, before.readOnlyReason))
        val prepared = prepareSession(next, reuseKept = false)
        // No suspension point between this check and the replacement: nothing typed until now can be replaced unseen.
        if (editor !== before || before.hasUnsavedChanges || before.text != textBefore) { raiseConflict(info.title); return@act }
        sessions.remove(info.id)
        switchTo(next, prepared)
        // Only now, with the editor showing the other version, the baseline moves. The autosave waits for [actions], so no save runs before this.
        repository.adoptExternal(info.id, version)
    }

    /**
     * "Export all notes": writes every active internal note as a `.md` file into the folder [folderUri] (picked by the user).
     * External documents are not copied - they already are files the user owns. The outcome goes to [exportResult].
     */
    fun exportAll(folderUri: String) {
        val target = exportTarget ?: return
        if (exporting) return // a second tap does nothing
        exporting = true
        scope.launch {
            try {
                // under the lock only the quick part: save the open note (if that fails, the version on disk is exported) and read the texts
                val (files, skipped) = actions.withLock {
                    try { saveNow() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    val all = repository.listNotes()
                    val titles = all.associate { it.id to it.title }
                    val planned = NoteExport.plan(all).map { item ->
                        val text = try { repository.openNote(item.id).text } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        Triple(item.fileName, titles[item.id] ?: item.fileName, text)
                    }
                    planned to all.count { it.isExternal }
                }
                // writing into the chosen folder happens outside the lock: typing and autosave go on meanwhile
                var written = 0
                val failed = ArrayList<String>()
                for ((fileName, title, text) in files) {
                    if (text == null) { failed += title; continue }
                    try { target.write(folderUri, fileName, text); written++ } catch (e: CancellationException) { throw e } catch (_: Exception) { failed += title }
                }
                exportResult = ExportResult(written, failed, skipped)
            } catch (e: NoteStorageException) {
                AppLog.e(LOG_TAG, "Export failed", e)
                message = UserMessage.ActionFailed // the notes could not be listed: nothing was exported
            } finally {
                exporting = false
            }
        }
    }

    fun exportResultShown() {
        exportResult = null
    }

    // ---- Documents -------------------------------------------------------------------------------------------

    /** "New note": activates the single blank page, creating it if there is none. Never a second draft. */
    fun newNote() = act {
        saveNow()
        commitSession(session.newDraft())
        syncEditor(markOpened = false)
        // a blank checklist page becomes a plain one (one that holds text is never replaced)
        if (editor.isDraft && editor.smartOnCreate && !editor.hasUnsavedChanges) switchTo(NoteEditor(repository))
    }

    /**
     * "New checklist": the blank page, but as a smart checklist - one empty task item to type into, and the note is created
     * with Smart Checklist switched on. Still an ordinary `.md` file; nothing is written until there is text.
     */
    fun newChecklist() = act {
        saveNow()
        commitSession(session.newDraft())
        syncEditor(markOpened = false)
        if (!editor.isDraft || editor.hasUnsavedChanges) return@act // a page that holds text is never replaced
        switchTo(NoteEditor(repository, smartOnCreate = true))
        rich.moveCursor(Cursor(rich.doc.rows.first().id, 0))
        publish()
        requestFocus(rich.doc.rows.first().id)
    }

    /** Opens a note from the drawer: already open -> just activate it, otherwise append a tab. */
    fun openNote(id: NoteId) = act { openNoteInternal(id) }

    private suspend fun openNoteInternal(id: NoteId) {
        if (current?.id == id) return
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

    /**
     * The user picked the document again through the picker to get write access to a file that arrived read-only ("Open with write
     * access"). If it now opens writable it replaces the read-only entry (the file is the same, only the access differs); if it is
     * still read-only both stay and the reason is shown again. Nothing is copied.
     */
    fun upgradeExternal(uri: String, persistent: Boolean) = act {
        val old = current?.takeIf { it.isExternal && readOnly }
        saveNow()
        val info = repository.openExternal(uri, persistent)
        commitSession(session.open(info.id))
        syncEditor(markOpened = true)
        if (old != null && old.id != info.id && current?.id == info.id && !readOnly) {
            commitSession(session.remove(old.id))
            runCatching { repository.forgetExternal(old.id) }
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

    /** Runs [block] in the application scope and waits for it: closing the dialog that asked cannot cancel it half-way. */
    private suspend fun <T> inAppScope(block: suspend () -> T): T = scope.async { block() }.await()

    suspend fun rename(title: String): RenameResult = inAppScope { renameLocked(title) }

    private suspend fun renameLocked(title: String): RenameResult = actions.withLock {
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

    // ---- Folders ---------------------------------------------------------------------------------------------

    fun toggleFolder(id: String) {
        expandedFolders = if (id in expandedFolders) expandedFolders - id else expandedFolders + id
    }

    suspend fun createFolder(name: String): RenameResult = inAppScope { actions.withLock { folderResult { repository.createFolder(name); refreshLists() } } }

    suspend fun renameFolder(id: String, name: String): RenameResult = inAppScope { actions.withLock { folderResult { repository.renameFolder(id, name); refreshLists() } } }

    /** Only an empty folder is removed; notes are never deleted with a folder. */
    fun deleteFolder(id: String) = act {
        try {
            repository.deleteFolder(id)
            expandedFolders = expandedFolders - id
        } catch (e: FolderNotEmptyException) {
            message = UserMessage.FolderNotEmpty
        }
        refreshLists()
    }

    /** Files the open note in [folderId] (null = no folder). Pure metadata: the note file does not move or change. */
    fun moveCurrentToFolder(folderId: String?) = act {
        if (editor.setFolder(folderId)) afterEditorChanged()
        folderId?.let { expandedFolders = expandedFolders + it }
    }

    /** Creates a folder and files the open note in it. */
    suspend fun createFolderAndMove(name: String): RenameResult = inAppScope {
        actions.withLock {
            folderResult {
                val folder = repository.createFolder(name)
                if (editor.setFolder(folder.id)) afterEditorChanged()
                expandedFolders = expandedFolders + folder.id
                refreshLists()
            }
        }
    }

    private suspend fun folderResult(block: suspend () -> Unit): RenameResult = try {
        block()
        RenameResult.Ok
    } catch (e: InvalidFolderNameException) {
        RenameResult.InvalidName
    } catch (e: FolderNameConflictException) {
        RenameResult.NameTaken
    } catch (e: NoteStorageException) {
        RenameResult.Failed
    }

    /** Favorites or unfavorites the open note (creating it first if it is a draft with text). */
    fun toggleFavorite() = act {
        val favorite = !(editor.info?.favorite ?: false)
        if (editor.setFavorite(favorite)) {
            afterEditorChanged()
            message = if (favorite) UserMessage.FavoriteAdded else UserMessage.FavoriteRemoved
        }
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
        if (enable) {
            message = UserMessage.SmartChecklistOn // set before the sort, so a failure of the sort is what the user sees
            edit { it.sortChecklists() }
        }
        afterEditorChanged()
    }

    /**
     * Empties the open note. The note and its file stay. Clearing is one editing step: Undo brings the whole text back. The
     * emptied note is written at once, as it always was.
     */
    fun clear() = act {
        if (!ready || readOnly) return@act
        if (rich.doc.rows.all { it.kind == RowKind.Paragraph && it.text.isEmpty }) return@act // nothing to clear
        edit(focus = false) { s ->
            rich = clearedSession(s)
            registerSession()
            epoch++ // the rows are new: the editor is laid out again
            true
        }
        saveNow()
        afterEditorChanged()
    }

    /**
     * The session of [old] emptied to one blank paragraph. The undo history of [old] is kept and the document before the clear
     * is its last step, so Undo returns to it with its caret. Built here because the session's own deletion keeps the edge rows
     * of raw blocks (a table, a code block, an image) as empty rows, which would stay visible as empty blocks.
     */
    private fun clearedSession(old: EditorSession): EditorSession {
        val doc = old.doc
        val blank = doc.withRows(listOf(EditorRow(doc.nextId, RowKind.Paragraph, RichText(""), touched = true)), doc.nextId + 1)
        val session = EditorSession(blank)
        session.smartChecklist = old.smartChecklist
        session.history.restore(old.history.undoSnapshots() + Snapshot(doc, old.cursor), emptyList())
        session.moveCursor(Cursor(blank.rows.first().id, 0))
        return session
    }

    /**
     * Moves the open note to the Trash. Its tab closes and a neighbouring tab (or a fresh blank page) becomes
     * active. A draft with text is saved first, so that text ends up in the Trash rather than vanishing.
     */
    fun deleteCurrent() = act {
        val external = current?.takeIf { it.isExternal }
        // In both cases the note being left is gone from openPad++ before the next one is shown, so there is nothing that text typed
        // into it after this tap could be written to: syncEditor does not try (it would only fail and keep the removed note open).
        if (external != null) { // an external file is only removed from openPad++, never deleted
            saveNow()
            repository.forgetExternal(external.id)
            commitSession(session.remove(external.id))
            syncEditor(markOpened = true, keepTyped = false)
            refreshLists()
            return@act
        }
        val trashed = editor.moveToTrash()
        if (trashed != null) {
            lastTrashed = trashed.id
            message = UserMessage.MovedToTrash
            val s = session
            commitSession(if (s.activeNoteId == null) s.close(DocumentTab.Draft) else s.remove(trashed.id))
            syncEditor(markOpened = true, keepTyped = false)
        }
        refreshLists()
    }

    /** Undoes the last "Move to Trash": the note comes back from the Trash and is opened again. */
    fun undoTrash() = act {
        val id = lastTrashed ?: return@act
        try {
            repository.restoreFromTrash(id)
        } catch (e: NoteNotInTrashException) {
            lastTrashed = null
            return@act // restored by hand (or deleted for good) meanwhile: nothing to undo
        }
        lastTrashed = null // a failed restore keeps the undo for another try
        refreshLists()
        openNoteInternal(id)
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

    fun messageShown() {
        message = null
    }

    override fun onCleared() {
        noteSearch.cancel()
        scope.launch {
            autosaver.flush()
            editorStatePersistence.persist()
            autosaver.close()
            sessionChanges.close() // the last queued session state is still written
            editorStatePersistence.close() // after the final write above
        }
    }

    // ---- Internals (all of these run under `actions` unless noted) -------------------------------------------

    /**
     * Restores the previous session according to the startup setting. The result is persisted immediately, so a
     * mode that does not restore (Blank note) replaces the old session instead of leaving it to resurface later.
     */
    private suspend fun startup() {
        try {
            val mode = preferences.load()
            editorStatePersistence.load()
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

    /**
     * Writes the active editor if it has pending changes; a draft that just became a note joins the session. Returns true if
     * something was written.
     */
    private suspend fun saveNow(): Boolean {
        val saved = editor
        val wasDraft = saved.isDraft
        val wrote = try {
            saved.save()
        } catch (e: NoteChangedExternallyException) {
            throw e // asked, not failed: the conflict question covers it
        } catch (e: NoteStorageException) {
            saveFailed = true
            saveFailedNow = true
            message = UserMessage.SaveFailed
            throw e
        }
        saveFailed = false // whatever was pending is on disk now
        if (wrote) {
            if (saved === editor) {
                current = saved.info
                if (wasDraft) materializeDraft(saved.info)
            }
            refreshLists()
        }
        return wrote
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
        editorStatePersistence.retainOnly(next.noteIds.toSet()) // closed tabs forget their history
        sessionChanges.trySend(next.toPersisted())
    }

    /**
     * Makes the editor show the active tab. A note that cannot be loaded (deleted meanwhile, not valid UTF-8) is
     * dropped from the session and the next tab is tried; this ends at the latest with a blank page.
     *
     * [keepTyped]: text typed into the editor being left while this runs is saved before it is replaced (see [replaceEditor]).
     * Only false when that editor's note was just removed from openPad++, so there is nothing left to save it to.
     */
    private suspend fun syncEditor(markOpened: Boolean, keepTyped: Boolean = true) {
        while (true) {
            val id = session.activeNoteId
            if (id == null) {
                if (!editor.isDraft) replaceEditor(NoteEditor(repository), null, keepTyped)
                return
            }
            if (editor.info?.id == id) return
            val next = try {
                val content = repository.openNote(id)
                val info = if (markOpened) repository.markOpened(id) else content.info
                NoteEditor(repository, NoteContent(info, content.text, content.readOnly, content.readOnlyReason))
            } catch (e: NoteSourceUnavailableException) {
                message = UserMessage.SourceUnavailable
                commitSession(session.remove(id))
                continue
            } catch (e: NoteUnreadableException) {
                message = UserMessage.NoteUnreadable
                commitSession(session.remove(id))
                continue
            } catch (e: NoteNotFoundException) {
                commitSession(session.remove(id))
                continue
            }
            // outside the try: a failure to save the editor being left says nothing about the note being opened
            replaceEditor(next, prepareSession(next), keepTyped)
            return
        }
    }

    /**
     * Replaces the editor by [next] (with its [prepared] session, if any). The editor being left was editable all the time the
     * action waited - for a save, a file, a document provider, the preparation of [next] - so text typed into it meanwhile is
     * written first ([keepTyped]). The last check that nothing is pending and the replacement have no suspension point between
     * them: no typed text can stay behind in an editor that goes away.
     *
     * If that save fails, nothing is replaced: the text stays on screen, the failure shows (the "not saved" bar, or the conflict
     * question for an external document another app changed), and the tab strip shows the editor's own tab as the active one
     * again, so it agrees with what is on screen (a tab that was being closed comes back, at the end).
     */
    private suspend fun replaceEditor(next: NoteEditor, prepared: EditorSession?, keepTyped: Boolean) {
        if (keepTyped) {
            try {
                while (editor.hasUnsavedChanges) {
                    val leaving = editor
                    val wasDraft = leaving.isDraft
                    // A save that writes nothing while changes are pending cannot happen ([NoteEditor.save]). If it ever did, the
                    // editor must stay (its text is not on disk) and the loop must not spin: the action fails instead.
                    if (!saveNow()) throw NoteStorageException("Pending text could not be saved")
                    // A blank page typed into while another tab was being opened is a note now: the blank page's tab becomes its
                    // tab (when the blank page was the active tab, [saveNow] did that already and no blank page is left).
                    val created = leaving.info
                    if (wasDraft && created != null && session.draftOpen) commitSession(session.materializeDraft(created.id))
                }
            } catch (e: NoteStorageException) {
                commitSession(editor.info?.id?.let { session.open(it) } ?: session.newDraft())
                throw e
            }
        }
        switchTo(next, prepared)
    }

    /**
     * Shows [next]. Not suspending, so nothing can be typed into the editor being left while this runs. [prepared] is the session
     * from [prepareSession]; without one (a blank page, or a kept session that changed meanwhile) it is made here.
     */
    private fun switchTo(next: NoteEditor, prepared: EditorSession? = null) {
        val started = System.nanoTime()
        markStateDirty() // the tab being left keeps its caret and history for later
        editor = next
        text = next.text
        current = next.info
        readOnly = next.readOnly
        readOnlyReason = next.readOnlyReason
        docSelection = null
        find = null
        rich = sessionFor(next, prepared)
        rich.smartChecklist = next.info?.smartChecklist ?: next.smartOnCreate
        cursorEpoch++
        if (rich.smartChecklist && !next.readOnly && rich.settleLoaded()) commitMarkdown() // loading puts a smart checklist in order
        epoch++
        focusRequest = null
        publish()
        EditorDiagnostics.recordOpen(System.nanoTime() - started)
    }

    /**
     * [prepared], or else the open tab's own session if it still matches the file exactly (keeps caret and undo), the remembered
     * one, or a fresh one. Registers it as the tab's session.
     */
    private fun sessionFor(next: NoteEditor, prepared: EditorSession?): EditorSession {
        val id = next.info?.id
        val result = prepared ?: run {
            val kept = id?.let { sessions[it] }?.takeIf { runCatching { it.markdown() == next.text }.getOrDefault(false) }
            val restored = if (kept == null && id != null) editorStatePersistence.restore(id, next.text) else null
            kept ?: restored ?: EditorSession(if (id == null && next.smartOnCreate) EditorDocument.emptyChecklist() else EditorDocument.fromMarkdown(next.text))
        }
        if (id != null) sessions[id] = result
        sessions.keys.retainAll((session.noteIds + listOfNotNull(id)).toSet())
        return result
    }

    /**
     * The session for [next], a saved note about to be shown, prepared on [editorStateDispatcher]: comparing the tab's kept
     * session with the file, applying the remembered caret and undo history (each step a whole document) and parsing the note
     * can take hundreds of milliseconds for a long note, which must not stall the main thread. Same choice as [sessionFor]:
     * the kept session if its Markdown is exactly the file ([reuseKept]), else the remembered state for exactly this text, else
     * a fresh session.
     *
     * Only immutable values cross threads: the text, the remembered state, and the kept session's [EditorDocument] (immutable),
     * read here on the main thread. The kept session itself is never touched off the main thread. Nothing changes it meanwhile
     * either: it belongs to a tab that is not the active one (the active tab's session is [rich]; [next] is not the active note,
     * or its kept session was dropped), and only [rich] is ever edited. It is reused only if it is still the tab's session with
     * that same document when the work is done; otherwise null is returned and [sessionFor] decides on the main thread.
     * The returned session is new or the kept one; the caller installs it on the main thread.
     */
    private suspend fun prepareSession(next: NoteEditor, reuseKept: Boolean = true): EditorSession? {
        val id = next.info?.id ?: return null // a blank page costs nothing: made in [switchTo]
        val text = next.text
        val kept = if (reuseKept) sessions[id] else null
        val keptDoc = kept?.doc
        val state = editorStatePersistence.stateOf(id) // an immutable value, read here on the main thread
        val made = withContext(editorStateDispatcher) {
            if (keptDoc != null && runCatching { keptDoc.toMarkdown() == text }.getOrDefault(false)) {
                null // the kept session matches the file
            } else {
                state?.let { EditorStateCodec.restore(text, it) } ?: EditorSession(EditorDocument.fromMarkdown(text))
            }
        }
        return made ?: kept?.takeIf { sessions[id] === it && it.doc === keptDoc }
    }

    private suspend fun refreshLists() {
        val lists = repository.lists()
        notes = lists.notes
        favorites = NoteLists.favorites(lists.notes)
        recent = NoteLists.recent(lists.notes)
        trash = lists.trash
        folders = lists.folders
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            // [busy] is set if this action is still waiting or running after BUSY_AFTER_MILLIS, and reset when it ends however it ends
            var slow = false
            val watch = launch {
                delay(BUSY_AFTER_MILLIS)
                slow = true
                slowActions++
                busy = true
            }
            try {
                actions.withLock {
                    saveFailedNow = false
                    try {
                        block()
                    } catch (e: NoteChangedExternallyException) {
                        raiseConflict(e.title) // asked, not failed: the user decides what happens to the document
                    } catch (e: NoteStorageException) {
                        AppLog.e(LOG_TAG, "An action failed", e)
                        // a failed save already says so; anything else is a failed action
                        message = if (saveFailedNow) UserMessage.SaveFailed else UserMessage.ActionFailed
                        saveFailedNow = false
                    }
                }
            } finally {
                watch.cancel() // no timer outlives the action
                if (slow) {
                    slowActions--
                    busy = slowActions > 0
                }
            }
        }
    }

    /** The UI could not hand a note to the share sheet: says so like any other failed action. */
    fun reportShareFailed() {
        message = UserMessage.ActionFailed
    }

    /** Asks the user about a document that another app changed. The question belongs to the open note. */
    private fun raiseConflict(title: String) {
        externalConflict = title
        conflictNoteId = editor.info?.id
        conflictDialogVisible = true
    }

    private fun clearConflict() {
        externalConflict = null
        conflictNoteId = null
        conflictDialogVisible = false
    }

    companion object {
        /** How long an action may wait or run before [busy] says so: long enough that quick actions never make it flicker. */
        internal const val BUSY_AFTER_MILLIS = 400L

        private const val LOG_TAG = "NotesViewModel"
    }
}
