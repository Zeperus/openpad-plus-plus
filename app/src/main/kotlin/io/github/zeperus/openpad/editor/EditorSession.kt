package io.github.zeperus.openpad.editor

/** A point in the edit history: the document and where the caret was. */
data class Snapshot(val doc: EditorDocument, val cursor: Cursor?)

/**
 * Bounded undo/redo. Snapshots share their unchanged rows (the document is persistent), so the memory per step is one
 * list of references. A burst of typing in one row counts as a single step.
 */
class EditHistory(
    private val limit: Int = DEFAULT_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    private var typingRow: Long? = null
    private var typingAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val size: Int get() = undoStack.size

    /** Call with the state *before* an edit. [typedInRow] is set for plain typing, which is merged into one step. */
    fun record(before: Snapshot, typedInRow: Long? = null) {
        val now = clock()
        val merge = typedInRow != null && typedInRow == typingRow && now - typingAt < COALESCE_MILLIS && undoStack.isNotEmpty()
        typingRow = typedInRow
        typingAt = now
        if (!merge) {
            undoStack += before
            if (undoStack.size > limit) undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(current: Snapshot): Snapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack += current
        typingRow = null
        return previous
    }

    fun redo(current: Snapshot): Snapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack += current
        typingRow = null
        return next
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val COALESCE_MILLIS = 1_000L
    }
}

/**
 * The state of one open document in the rich editor: the [EditorDocument], the caret, the formatting for the next typed
 * characters and the undo history. Plain Kotlin (no Compose), so everything here is unit-tested directly; the UI only
 * forwards what the user does and shows [doc].
 */
class EditorSession(
    initial: EditorDocument,
    clock: () -> Long = System::currentTimeMillis,
    historyLimit: Int = EditHistory.DEFAULT_LIMIT,
) {
    var doc: EditorDocument = initial
        private set

    var cursor: Cursor? = null
        private set

    /** Formatting for text typed next, when the user switched it on/off with an empty selection. */
    var typingStyle: Set<SpanKind>? = null
        private set

    val history = EditHistory(historyLimit, clock)

    /** The Markdown of the document right now. */
    fun markdown(): String = doc.toMarkdown()

    // ---- Input -----------------------------------------------------------------------------------------------

    /** The user's caret/selection moved (not caused by an edit). */
    fun moveCursor(newCursor: Cursor) {
        if (cursor != newCursor && (cursor?.rowId != newCursor.rowId || cursor?.start != newCursor.start)) typingStyle = null
        cursor = newCursor
    }

    /** A text field reported new text for a row. Returns true if the document changed. */
    fun onText(rowId: Long, newText: String, caret: Int): Boolean {
        val before = doc
        val edit = EditorOps.edit(doc, rowId, newText, caret, typingStyle)
        if (edit.doc === before) { cursor = edit.cursor; return false }
        val typedOnly = edit.doc.rows.size == before.rows.size && edit.doc.rows.map { it.id } == before.rows.map { it.id }
        history.record(Snapshot(before, cursor), if (typedOnly) rowId else null)
        doc = edit.doc
        cursor = edit.cursor
        typingStyle = null
        return true
    }

    fun backspaceAtStart(rowId: Long): Boolean = commit(EditorOps.backspaceAtStart(doc, rowId))

    fun toggleStyle(kind: SpanKind): Boolean {
        val c = cursor ?: return false
        if (c.isCollapsed) { // nothing selected: applies to what is typed next
            val row = doc.row(c.rowId) ?: return false
            val base = typingStyle ?: row.text.kindsAt(c.start - 1).filterTo(HashSet()) { it != SpanKind.Link && it != SpanKind.HardBreak && it != SpanKind.Raw }
            typingStyle = if (kind in base) base - kind else base + kind
            return false
        }
        return EditorOps.toggleStyle(doc, c, kind)?.let { commit(it) } ?: false
    }

    fun setLink(href: String, title: String? = null): Boolean = cursor?.let { c -> EditorOps.setLink(doc, c, href, title)?.let { commit(it) } } ?: false

    fun removeLink(): Boolean = cursor?.let { c -> EditorOps.removeLink(doc, c)?.let { commit(it) } } ?: false

    fun setKind(kind: RowKind): Boolean = cursor?.let { commit(EditorOps.setKind(doc, it.rowId, kind)) } ?: false

    fun toggleList(ordered: Boolean): Boolean = cursor?.let { commit(EditorOps.toggleList(doc, it.rowId, ordered)) } ?: false

    fun toggleTask(): Boolean = cursor?.let { commit(EditorOps.toggleTask(doc, it.rowId)) } ?: false

    fun setChecked(rowId: Long, checked: Boolean): Boolean = commit(EditorOps.setChecked(doc, rowId, checked), keepCursor = true)

    fun indent(): Boolean = cursor?.let { commit(EditorOps.indent(doc, it.rowId)) } ?: false

    fun outdent(): Boolean = cursor?.let { commit(EditorOps.outdent(doc, it.rowId)) } ?: false

    fun insertRule(): Boolean = cursor?.let { commit(EditorOps.insertRule(doc, it.rowId)) } ?: false

    // ---- Undo / redo -----------------------------------------------------------------------------------------

    fun undo(): Boolean = history.undo(Snapshot(doc, cursor))?.let { restore(it); true } ?: false

    fun redo(): Boolean = history.redo(Snapshot(doc, cursor))?.let { restore(it); true } ?: false

    private fun restore(s: Snapshot) {
        doc = s.doc
        cursor = s.cursor?.takeIf { doc.indexOf(it.rowId) >= 0 } ?: doc.rows.firstOrNull { it.isTextual }?.let { Cursor(it.id, 0) }
        typingStyle = null
    }

    // ---- Queries for the toolbar -----------------------------------------------------------------------------

    /** The inline kinds that are on at the caret/selection (what the toolbar highlights). */
    fun activeKinds(): Set<SpanKind> {
        val c = cursor ?: return emptySet()
        val row = doc.row(c.rowId) ?: return emptySet()
        if (c.isCollapsed) return typingStyle ?: row.text.kindsAt(c.start - 1).filterTo(HashSet()) { it != SpanKind.Link || row.text.linkAtCursor(c.start) != null }
        val lo = minOf(c.start, c.end)
        val hi = maxOf(c.start, c.end)
        return SpanKind.entries.filterTo(HashSet()) { row.text.hasKind(it, lo, hi) }
    }

    fun linkAtCaret(): Span? {
        val c = cursor ?: return null
        val row = doc.row(c.rowId) ?: return null
        return row.text.linkAtCursor(c.start)
    }

    private fun commit(edit: Edit, keepCursor: Boolean = false): Boolean {
        if (edit.doc === doc) { if (!keepCursor) cursor = edit.cursor; return false }
        history.record(Snapshot(doc, cursor))
        doc = edit.doc
        if (!keepCursor) cursor = edit.cursor
        typingStyle = null
        return true
    }
}
