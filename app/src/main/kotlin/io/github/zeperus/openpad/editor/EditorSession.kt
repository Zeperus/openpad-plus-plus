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

    /** The recorded steps, oldest first (for saving them between runs). */
    fun undoSnapshots(): List<Snapshot> = undoStack.toList()

    fun redoSnapshots(): List<Snapshot> = redoStack.toList()

    /** Replaces the history with steps that were saved earlier (bounded by the limit). */
    fun restore(undo: List<Snapshot>, redo: List<Snapshot>) {
        undoStack.clear(); redoStack.clear()
        undoStack += undo.takeLast(limit)
        redoStack += redo.takeLast(limit)
        typingRow = null
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

    /** Smart checklist mode of this document (a property of the note, not of the Markdown): see [Checklist]. */
    var smartChecklist: Boolean = false

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
        val edit = EditorOps.edit(doc, rowId, newText, caret, typingStyle, smartChecklist)
        if (edit.doc === before) { cursor = edit.cursor; return false }
        val typedOnly = edit.doc.rows.size == before.rows.size && edit.doc.rows.map { it.id } == before.rows.map { it.id }
        history.record(Snapshot(before, cursor), if (typedOnly) rowId else null)
        doc = settled(edit.doc)
        cursor = edit.cursor
        typingStyle = null
        return true
    }

    fun backspaceAtStart(rowId: Long): Boolean = commit(EditorOps.backspaceAtStart(doc, rowId))

    /** Delete at the end of a row (the row below is joined onto it). */
    fun joinWithNext(rowId: Long): Boolean = commit(EditorOps.joinWithNext(doc, rowId))

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

    /**
     * Bullet / numbered list button. Over a selection that reaches across rows, or on a row that holds several lines, *every logical
     * line* becomes its own item (see [ListConversion]); on a single line it is the plain toggle. One undo step.
     */
    fun toggleList(ordered: Boolean, sel: DocumentSelection? = null): Boolean =
        convertRows(sel, if (ordered) ListTarget.Numbered else ListTarget.Bullet) { EditorOps.toggleList(doc, it, ordered) }

    /** Checklist button: like [toggleList], with task items. */
    fun toggleTask(sel: DocumentSelection? = null): Boolean = convertRows(sel, ListTarget.Task) { EditorOps.toggleTask(doc, it) }

    private fun convertRows(sel: DocumentSelection?, target: ListTarget, single: (Long) -> Edit): Boolean {
        val range = rowRange(sel) ?: return false
        if (range.first == range.last && ListConversion.isSimple(doc.rows[range.first])) return commit(single(doc.rows[range.first].id))
        if (sel != null) DocumentSelections.ordered(doc, sel)?.first?.let { cursor = Cursor(it.rowId, it.offset) } // what Undo brings back
        return ListConversion.convert(doc, range.first, range.last, target)?.let { commit(it) } ?: false
    }

    /** The rows a block operation applies to: those the selection touches (a last row of which nothing is selected is not one of them), else the caret's row. */
    private fun rowRange(sel: DocumentSelection?): IntRange? {
        if (sel != null) {
            val (a, b) = DocumentSelections.ordered(doc, sel) ?: return null
            val first = doc.indexOf(a.rowId)
            var last = doc.indexOf(b.rowId)
            if (last > first && b.offset == 0) last--
            return first..last
        }
        val c = cursor ?: return null
        val i = doc.indexOf(c.rowId)
        return if (i < 0) null else i..i
    }

    fun setChecked(rowId: Long, checked: Boolean): Boolean = commit(EditorOps.setChecked(doc, rowId, checked, smartChecklist), keepCursor = true)

    /**
     * In smart checklist mode the document always has unchecked items above completed ones. Every edit ends here: if it left a
     * sortable checklist out of order (a new task among completed ones, a converted row, a paste) it is put right, as part of
     * the same history step. A document that is already in order is returned as it is, so nothing is rewritten for nothing.
     */
    private fun settled(d: EditorDocument): EditorDocument {
        if (!smartChecklist) return d
        val sorted = Checklist.normalize(d.rows)
        return if (sorted === d.rows) d else d.withRows(sorted)
    }

    /** A loaded document of a smart checklist note that is out of order is sorted (no history entry). True if it changed. */
    fun settleLoaded(): Boolean {
        val d = settled(doc)
        if (d === doc) return false
        doc = d
        return true
    }

    /** Switching smart checklist mode on sorts the existing checklists once, as one undoable step. */
    fun sortChecklists(): Boolean = EditorOps.sortChecklists(doc)?.let { commit(it, keepCursor = true) } ?: false

    fun indent(): Boolean = cursor?.let { commit(EditorOps.indent(doc, it.rowId)) } ?: false

    fun outdent(): Boolean = cursor?.let { commit(EditorOps.outdent(doc, it.rowId)) } ?: false

    fun insertRule(): Boolean = cursor?.let { commit(EditorOps.insertRule(doc, it.rowId)) } ?: false

    // ---- Selections that span rows ---------------------------------------------------------------------------

    fun selectedText(sel: DocumentSelection): String = DocumentSelections.plainText(doc, sel)

    fun selectedMarkdown(sel: DocumentSelection): String = DocumentSelections.markdown(doc, sel)

    /** Removes the selected content (Cut / Delete) as one undo step; the caret ends up where the selection started. */
    fun deleteSelection(sel: DocumentSelection): Boolean = withSelectionStart(sel) { DocumentSelections.delete(doc, sel) }

    /** Replaces the selection by pasted [text] (plain text) as one undo step. */
    fun replaceSelection(sel: DocumentSelection, text: String): Boolean =
        withSelectionStart(sel) { DocumentSelections.replace(doc, sel, text, smartChecklist) }

    /** Inline formatting over a selection across rows (the caret stays where it was). */
    fun toggleStyleOver(selection: DocumentSelection, kind: SpanKind): Boolean =
        EditorOps.toggleStyleOver(doc, selection, kind)?.let { commit(it, keepCursor = true) } ?: false

    /** Paste as Markdown: parses [markdown] and inserts its blocks at the caret row (or over [sel]). One undo step. */
    fun pasteMarkdown(markdown: String, sel: DocumentSelection? = null): Boolean {
        var base = doc
        if (sel != null) {
            val removed = DocumentSelections.delete(doc, sel)
            if (removed != null) base = removed.doc
            val row = removed?.cursor?.rowId ?: cursor?.rowId ?: return false
            val edit = EditorOps.pasteMarkdown(base, row, markdown) ?: return false
            return commit(edit)
        }
        val row = cursor?.rowId ?: return false
        return EditorOps.pasteMarkdown(doc, row, markdown)?.let { commit(it) } ?: false
    }

    /**
     * Paste as Checklist: every meaningful line of [text] becomes an item (markers, messenger headers and blank lines are cleaned, see
     * [ListImport]) - inserted below the caret row, in place of a blank row, or in place of [sel]. One undo step.
     */
    fun pasteAsChecklist(text: String, sel: DocumentSelection? = null): Boolean {
        val items = ListImport.checklistItems(text)
        if (items.isEmpty()) return false
        var base = doc
        var row = cursor?.rowId
        if (sel != null) {
            val start = DocumentSelections.ordered(doc, sel)?.first
            DocumentSelections.delete(doc, sel)?.let { base = it.doc; row = it.cursor.rowId }
            if (start != null) cursor = Cursor(start.rowId, start.offset) // what Undo brings back
        }
        val target = row?.takeIf { base.indexOf(it) >= 0 } ?: base.rows.lastOrNull { it.isTextual }?.id ?: return false
        return EditorOps.pasteChecklist(base, target, items)?.let { commit(it) } ?: false
    }

    private inline fun withSelectionStart(sel: DocumentSelection, edit: () -> Edit?): Boolean {
        val (start, _) = DocumentSelections.ordered(doc, sel) ?: return false
        cursor = Cursor(start.rowId, start.offset) // what Undo brings back
        val result = edit() ?: return false
        return commit(result)
    }

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
        doc = settled(edit.doc)
        if (!keepCursor) cursor = edit.cursor
        typingStyle = null
        return true
    }
}
