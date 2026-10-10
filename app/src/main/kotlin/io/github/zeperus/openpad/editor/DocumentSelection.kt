package io.github.zeperus.openpad.editor

/** A place in the document: row [rowId] (stable, survives reordering) and a character [offset] in its text. A rule has offsets 0 and 1. */
data class DocumentPosition(val rowId: Long, val offset: Int)

/**
 * A selection that may span rows. [anchor] is where it started, [focus] is the end the user moves; either can come first.
 * Positions are logical (row id + offset), never row indexes, so the selection survives rows that move (a ticked task in a
 * smart checklist) and edits elsewhere; [DocumentSelections.validated] repairs or drops it when rows disappeared.
 */
data class DocumentSelection(val anchor: DocumentPosition, val focus: DocumentPosition) {
    val isCollapsed: Boolean get() = anchor == focus
}

/** One row's share of a selection: characters `[from, to)` of its text. */
data class SelectedSlice(val row: EditorRow, val index: Int, val from: Int, val to: Int) {
    val isWhole: Boolean get() = from == 0 && to == length(row)
}

private fun length(row: EditorRow) = if (row.kind == RowKind.Rule) 1 else row.text.length

/**
 * What can be done with a selection across rows: the text it covers, as plain text or Markdown, and removing/replacing it
 * so that the document stays valid. All pure functions on the immutable document.
 */
object DocumentSelections {
    /** The positions in document order, or null if a row is gone. */
    fun ordered(doc: EditorDocument, sel: DocumentSelection): Pair<DocumentPosition, DocumentPosition>? {
        val a = doc.indexOf(sel.anchor.rowId)
        val b = doc.indexOf(sel.focus.rowId)
        if (a < 0 || b < 0) return null
        val forward = a < b || (a == b && sel.anchor.offset <= sel.focus.offset)
        return if (forward) sel.anchor to sel.focus else sel.focus to sel.anchor
    }

    /** The selection with offsets clamped into their rows, or null if a row no longer exists. */
    fun validated(doc: EditorDocument, sel: DocumentSelection): DocumentSelection? {
        fun fix(p: DocumentPosition): DocumentPosition? {
            val row = doc.row(p.rowId) ?: return null
            return p.copy(offset = p.offset.coerceIn(0, length(row)))
        }
        return DocumentSelection(fix(sel.anchor) ?: return null, fix(sel.focus) ?: return null)
    }

    fun selectAll(doc: EditorDocument): DocumentSelection? {
        val first = doc.rows.firstOrNull() ?: return null
        val last = doc.rows.last()
        val sel = DocumentSelection(DocumentPosition(first.id, 0), DocumentPosition(last.id, length(last)))
        return sel.takeUnless { it.isCollapsed }
    }

    /** The rows the selection touches, each with the part that is selected. Rows with nothing selected are left out. */
    fun slices(doc: EditorDocument, sel: DocumentSelection): List<SelectedSlice> {
        val (start, end) = ordered(doc, sel) ?: return emptyList()
        val first = doc.indexOf(start.rowId)
        val last = doc.indexOf(end.rowId)
        val out = ArrayList<SelectedSlice>()
        for (i in first..last) {
            val row = doc.rows[i]
            val len = length(row)
            val from = if (i == first) start.offset.coerceIn(0, len) else 0
            val to = if (i == last) end.offset.coerceIn(0, len) else len
            if (to > from || (i in (first + 1) until last && len == 0)) out += SelectedSlice(row, i, from, maxOf(from, to))
        }
        return out
    }

    private fun isTextual(row: EditorRow) = row.kind == RowKind.Paragraph || row.kind is RowKind.Heading || row.kind == RowKind.Quote || row.kind is RowKind.ListItem

    // ---- Copy ------------------------------------------------------------------------------------------------

    /**
     * Readable plain text, one line per row - what is on screen is what is copied: list items get their bullet / number / box,
     * nothing is added between rows (no blank lines the editor does not show; an empty row is an empty line), and a line break
     * inside a row stays. A long paragraph that only *wraps* on the screen has no line break in it, so none is copied.
     */
    fun plainText(doc: EditorDocument, sel: DocumentSelection): String {
        val slices = slices(doc, sel).filter { it.to > it.from || it.row.kind != RowKind.Rule }
        if (slices.isEmpty()) return ""
        if (slices.size == 1 && !slices[0].isWhole) return slices[0].row.text.text.substring(slices[0].from, slices[0].to)
        val out = StringBuilder()
        var first = true
        for (s in slices) {
            val row = s.row
            if (!first) out.append('\n')
            first = false
            val body = if (row.kind == RowKind.Rule) "---" else row.text.text.substring(s.from, s.to)
            if (s.from == 0) out.append(prefix(doc, s.index, row))
            out.append(body)
        }
        return out.toString()
    }

    private fun prefix(doc: EditorDocument, index: Int, row: EditorRow): String {
        val kind = row.kind as? RowKind.ListItem ?: return ""
        val indent = "  ".repeat(row.depth.coerceAtLeast(0))
        val mark = when {
            kind.checked == true -> "☑"
            kind.checked == false -> "☐"
            kind.list.ordered -> "${doc.numberOf(index) ?: 1}."
            else -> "•"
        }
        return "$indent$mark "
    }

    /** The selection as Markdown: the structure (headings, lists, tasks, quotes) is kept, partial first/last rows are cut. */
    fun markdown(doc: EditorDocument, sel: DocumentSelection): String {
        val slices = slices(doc, sel)
        if (slices.isEmpty()) return ""
        val single = slices.size == 1 && !slices[0].isWhole
        val rows = slices.mapIndexed { n, s ->
            val text = if (s.row.kind == RowKind.Rule) s.row.text else s.row.text.substring(s.from, s.to)
            // a piece taken from the middle of a row is text, not a list item or heading of its own
            val kind = if (single || (n == 0 && s.from > 0)) RowKind.Paragraph else s.row.kind
            s.row.copy(kind = kind, text = text, depth = if (kind is RowKind.ListItem) s.row.depth else 0, touched = true)
        }
        return EditorDocument.fromRows(rows).toMarkdown().trimEnd('\n')
    }

    // ---- Cut / replace -----------------------------------------------------------------------------------------

    /**
     * Removes the selected content. The first and last row are joined when both are ordinary text rows (the result keeps the
     * first row's kind and id); code/raw rows at the edges are not merged with text. Rows in between are removed completely.
     * Null if nothing is selected.
     */
    fun delete(doc: EditorDocument, sel: DocumentSelection): Edit? {
        val (start, end) = ordered(doc, sel) ?: return null
        if (start == end) return null
        val ia = doc.indexOf(start.rowId)
        val ib = doc.indexOf(end.rowId)
        val rows = doc.rows.toMutableList()
        val first = rows[ia]
        val last = rows[ib]

        if (ia == ib) {
            if (first.kind == RowKind.Rule) { // the rule itself
                rows.removeAt(ia)
                val target = rows.getOrNull(ia) ?: rows.getOrNull(ia - 1)
                return Edit(doc.withRows(rows), Cursor(target?.id ?: first.id, 0))
            }
            rows[ia] = first.copy(text = first.text.replace(start.offset, end.offset, ""), touched = true)
            return Edit(doc.withRows(rows), Cursor(first.id, start.offset))
        }

        val keepFirst = first.kind != RowKind.Rule || start.offset >= 1
        val keepLast = last.kind != RowKind.Rule || end.offset < 1
        val head: RichText? = if (first.kind == RowKind.Rule) null else first.text.substring(0, start.offset)
        val tail: RichText? = if (last.kind == RowKind.Rule) null else last.text.substring(end.offset, last.text.length)
        // everything from the first to the last row goes; what survives is rebuilt below
        for (i in ib downTo ia) rows.removeAt(i)
        var at = ia
        var cursor: Cursor
        val merge = isTextual(first) && isTextual(last) && head != null && tail != null
        when {
            merge && start.offset == 0 && head!!.isEmpty && tail!!.isEmpty -> {
                // whole rows were selected (first row from its start to the end of the last): they are simply gone
                val next = rows.getOrNull(at) ?: rows.getOrNull(at - 1)
                cursor = Cursor(next?.id ?: first.id, if (rows.getOrNull(at) != null) 0 else next?.text?.length ?: 0)
            }
            merge -> {
                val joined = EditorOps.collapseBlankLines(head!!.plus(tail!!))
                // if nothing of the first row is left (selected from its very start) the row that ends the selection gives the kind
                val owner = if (start.offset == 0 && head.isEmpty) last else first
                val text = if (owner.kind is RowKind.Heading) EditorOps.singleLine(joined) else joined
                rows.add(at, first.copy(kind = owner.kind, depth = owner.depth, text = text, touched = true))
                cursor = Cursor(first.id, head.length)
            }
            else -> {
                var focus: Long? = null
                if (keepFirst) {
                    rows.add(at++, if (head != null) first.copy(text = head, touched = true) else first)
                    focus = rows[at - 1].id
                }
                if (keepLast) {
                    rows.add(at, if (tail != null) last.copy(text = tail, touched = true) else last)
                    focus = rows[at].id
                    cursor = Cursor(focus, 0)
                } else {
                    cursor = Cursor(focus ?: rows.getOrNull(at)?.id ?: rows.getOrNull(at - 1)?.id ?: first.id, if (focus != null) (head?.length ?: 0) else 0)
                }
            }
        }
        val result = doc.withRows(rows)
        if (result.row(cursor.rowId) == null) cursor = Cursor(result.rows.first().id, 0)
        return Edit(result, cursor)
    }

    /** Replaces the selection by [text] typed/pasted at its start (as plain text, like any paste). One edit, one undo step. */
    fun replace(doc: EditorDocument, sel: DocumentSelection, text: String, smart: Boolean = false): Edit? {
        val removed = delete(doc, sel) ?: return null
        if (text.isEmpty()) return removed
        val row = removed.doc.row(removed.cursor.rowId) ?: return removed
        if (!row.isTextual || row.kind == RowKind.Rule) return removed
        val t = row.text.text
        val at = removed.cursor.start.coerceIn(0, t.length)
        return EditorOps.edit(removed.doc, row.id, t.substring(0, at) + text + t.substring(at), at + text.length, null, smart)
    }
}
