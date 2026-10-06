package io.github.zeperus.openpad.editor


/** The caret or selection: [start]..[end] within the text of row [rowId]. */
data class Cursor(val rowId: Long, val start: Int, val end: Int = start) {
    val isCollapsed: Boolean get() = start == end
}

/** The result of an operation: the new document and where the caret goes. */
data class Edit(val doc: EditorDocument, val cursor: Cursor)

/**
 * Editing operations on an [EditorDocument]. Pure functions: nothing is mutated, every operation returns a new
 * document. They implement what the keyboard and the formatting bar do; the Compose layer only translates input events.
 */
object EditorOps {
    // ---- Typing ----------------------------------------------------------------------------------------------

    /**
     * The text of row [rowId] became [newText] with the caret at [caret] (this is what a text field reports). A single
     * typed line break splits the row (Enter); everything else - including pasted text with line breaks - is plain text.
     * [typingStyle] is the formatting for new text when the user toggled it with an empty selection.
     */
    fun edit(doc: EditorDocument, rowId: Long, newText: String, caret: Int, typingStyle: Set<SpanKind>? = null, smart: Boolean = false): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val old = row.text.text
        if (newText == old) return Edit(doc, Cursor(rowId, caret.coerceIn(0, old.length)))

        var prefix = 0
        val max = minOf(old.length, newText.length)
        while (prefix < max && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        val removedEnd = old.length - suffix
        val inserted = newText.substring(prefix, newText.length - suffix)

        // a single typed line break is the Enter key, in every kind of row (code inserts a line break, others split)
        if (removedEnd == prefix && inserted == "\n" && row.kind != RowKind.Rule) return enter(doc, rowId, prefix, smart)

        val isPlain = row.kind is RowKind.Code || row.kind == RowKind.Raw
        val replaced = if (isPlain) RichText(newText) else row.text.replace(prefix, removedEnd, inserted, typingStyle)
        val text = if (row.kind is RowKind.Heading) singleLine(replaced) else replaced
        val caretInText = caret.coerceIn(0, text.length)

        // blank lines in a paragraph (pasted, or left behind by a deletion) are paragraph breaks
        if (!isPlain && PARAGRAPH_BREAK.containsMatchIn(text.text)) return splitIntoParagraphs(doc, index, row, text, caretInText)

        val updated = doc.rows.toMutableList().also { it[index] = row.copy(text = text, touched = true) }
        return Edit(doc.withRows(updated), Cursor(rowId, caretInText))
    }

    /** A heading is one line: line breaks (from a paste) become spaces. */
    private fun singleLine(t: RichText): RichText =
        if ('\n' !in t.text) t else RichText.of(t.text.replace('\n', ' '), t.spans.filter { it.kind != SpanKind.HardBreak })

    /** Joining two paragraphs must not leave a blank line (which a paragraph cannot contain): runs of line breaks become one. */
    private fun collapseBlankLines(t: RichText): RichText {
        var text = t
        while (true) {
            val m = PARAGRAPH_BREAK.find(text.text) ?: return text
            // keep the first line break, drop the rest (and the whitespace between them)
            text = text.replace(m.range.first + 1, m.range.last + 1, "")
        }
    }

    private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n[\\n \t]*")

    /** The row's text contains blank lines (from a paste): the first part stays in the row, the others become paragraphs. */
    private fun splitIntoParagraphs(doc: EditorDocument, index: Int, row: EditorRow, text: RichText, caret: Int): Edit {
        val parts = ArrayList<IntRange>() // start..endExclusive-1 of each paragraph in [text]
        var start = 0
        for (m in PARAGRAPH_BREAK.findAll(text.text)) {
            parts += start until m.range.first
            start = m.range.last + 1
        }
        parts += start until text.length
        val rows = doc.rows.toMutableList()
        var next = doc.nextId
        val created = ArrayList<EditorRow>()
        var cursorRow = row.id
        var cursorOffset = 0
        for ((n, range) in parts.withIndex()) {
            val slice = text.substring(range.first, range.last + 1)
            val id = if (n == 0) row.id else next++
            val contains = caret >= range.first && caret <= range.last + 1
            val inGapBefore = n > 0 && caret > parts[n - 1].last + 1 && caret < range.first
            if (contains || inGapBefore) { cursorRow = id; cursorOffset = if (inGapBefore) 0 else caret - range.first }
            if (n == 0) rows[index] = row.copy(text = slice, touched = true)
            else created += EditorRow(id, if (row.kind == RowKind.Quote) RowKind.Quote else RowKind.Paragraph, slice, 0, touched = true)
        }
        rows.addAll(index + 1, created)
        return Edit(doc.withRows(rows, next), Cursor(cursorRow, cursorOffset))
    }

    // ---- Enter / Backspace -----------------------------------------------------------------------------------

    /**
     * Enter. The row that has the keyboard focus **keeps its identity**: it becomes the second half and a new row for the first
     * half appears above it, so the text field the user is typing in is never replaced and the caret just stays at the start
     * of it. ([smart]: in a smart checklist a new item next to completed ones goes above the first completed item.)
     */
    fun enter(doc: EditorDocument, rowId: Long, offset: Int, smart: Boolean = false): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val o = offset.coerceIn(0, row.text.length)
        val rows = doc.rows.toMutableList()
        var next = doc.nextId

        fun newRow(kind: RowKind, text: RichText, depth: Int = row.depth) = EditorRow(next++, kind, text, depth, touched = true)

        when (val kind = row.kind) {
            RowKind.Paragraph, is RowKind.Heading, RowKind.Quote, is RowKind.ListItem -> {
                if (row.text.isEmpty) {
                    if (kind is RowKind.ListItem) return leaveList(doc, rowId)
                    if (kind == RowKind.Quote) { // Enter on an empty quote line leaves the quote
                        rows[index] = row.copy(kind = RowKind.Paragraph, touched = true)
                        return Edit(doc.withRows(rows), Cursor(row.id, 0))
                    }
                }
                val fresh = if (kind is RowKind.ListItem) kind.copy(checked = if (kind.checked != null) false else null) else kind
                if (o == 0 && !row.text.isEmpty) { // pushes the row down, an empty one appears above it
                    val aboveKind = if (kind is RowKind.Heading) RowKind.Paragraph else fresh
                    rows.add(index, newRow(aboveKind, RichText("")))
                    return Edit(doc.withRows(rows, next), Cursor(row.id, 0))
                }
                val (left, right) = row.text.split(o)
                val secondKind = if (kind is RowKind.Heading) RowKind.Paragraph else fresh
                rows[index] = row.copy(id = next++, text = left, touched = true) // the first half is the new row (keeps where it came from) ...
                var at = index + 1
                // ... following nested items stay with the first half, so the second half goes after them
                if (kind is RowKind.ListItem) while (at < rows.size && rows[at].isListItem && rows[at].depth > row.depth) at++
                rows.add(at, row.copy(kind = secondKind, text = right, touched = true)) // ... and this row keeps its id
                var result: List<EditorRow> = rows
                if (smart && kind is RowKind.ListItem && kind.checked == true) result = Checklist.placeNewUnchecked(rows, row.id)
                return Edit(doc.withRows(result, next), Cursor(row.id, 0))
            }
            is RowKind.Code -> {
                val text = row.text.text
                if (o == text.length && text.endsWith("\n")) { // a second Enter at the end leaves the code block
                    rows[index] = row.copy(id = next++, text = RichText(text.dropLast(1)), touched = true) // the code stays, the focused row continues as a paragraph
                    rows.add(index + 1, row.copy(kind = RowKind.Paragraph, text = RichText(""), depth = 0, touched = true))
                    return Edit(doc.withRows(rows, next), Cursor(row.id, 0))
                }
                rows[index] = row.copy(text = RichText(text.substring(0, o) + "\n" + text.substring(o)), touched = true)
                return Edit(doc.withRows(rows), Cursor(row.id, o + 1))
            }
            RowKind.Raw -> {
                val text = row.text.text
                rows[index] = row.copy(text = RichText(text.substring(0, o) + "\n" + text.substring(o)), touched = true)
                return Edit(doc.withRows(rows), Cursor(row.id, o + 1))
            }
            RowKind.Rule -> return Edit(doc, Cursor(rowId, 0))
        }
    }

    /** Backspace with the caret at the very start of the row. */
    fun backspaceAtStart(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val rows = doc.rows.toMutableList()
        when (row.kind) {
            is RowKind.ListItem -> return if (row.depth > 0) outdent(doc, rowId) else toParagraph(doc, rowId)
            is RowKind.Heading, RowKind.Quote, is RowKind.Code -> return toParagraph(doc, rowId)
            // unsupported Markdown is kept as it is: it cannot be turned into a paragraph (that would change its meaning),
            // but once its text is gone the empty block is removed
            RowKind.Raw -> {
                if (!row.text.isEmpty) return Edit(doc, Cursor(rowId, 0))
                rows.removeAt(index)
                val target = rows.getOrNull(index - 1) ?: rows.getOrNull(index)
                return Edit(doc.withRows(rows), Cursor(target?.id ?: rowId, 0))
            }
            RowKind.Rule -> return Edit(doc, Cursor(rowId, 0))
            RowKind.Paragraph -> Unit
        }
        val previous = doc.rows.getOrNull(index - 1) ?: return Edit(doc, Cursor(rowId, 0))
        return when (previous.kind) {
            RowKind.Rule -> { // deletes the rule
                rows.removeAt(index - 1)
                Edit(doc.withRows(rows), Cursor(rowId, 0))
            }
            RowKind.Paragraph, is RowKind.Heading, RowKind.Quote, is RowKind.ListItem -> {
                // the focused row survives (it takes the place and kind of the previous one), so the keyboard stays on the same field
                val at = previous.text.length
                val joined = collapseBlankLines(previous.text.plus(row.text))
                rows[index - 1] = previous.copy(id = row.id, text = joined, touched = true)
                rows.removeAt(index)
                Edit(doc.withRows(rows), Cursor(row.id, minOf(at, joined.length)))
            }
            is RowKind.Code, RowKind.Raw -> {
                if (row.text.isEmpty) { // only an empty line is removed; never merge text into code
                    rows.removeAt(index)
                    Edit(doc.withRows(rows), Cursor(previous.id, previous.text.length))
                } else {
                    Edit(doc, Cursor(rowId, 0))
                }
            }
        }
    }

    private fun toParagraph(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val rows = doc.rows.toMutableList()
        val text = if (row.kind is RowKind.Code || row.kind == RowKind.Raw) RichText(row.text.text) else row.text
        rows[index] = row.copy(kind = RowKind.Paragraph, text = text, depth = 0, touched = true)
        return Edit(doc.withRows(rows), Cursor(rowId, 0))
    }

    /** Enter on an empty list item: one level up, or out of the list. */
    private fun leaveList(doc: EditorDocument, rowId: Long): Edit {
        val row = doc.row(rowId)!!
        return if (row.depth > 0) outdent(doc, rowId) else toParagraph(doc, rowId)
    }

    // ---- Inline formatting -----------------------------------------------------------------------------------

    /** Toggles bold/italic/strike/code on the selection. Returns null for a collapsed selection (typing style, see session). */
    fun toggleStyle(doc: EditorDocument, cursor: Cursor, kind: SpanKind): Edit? {
        if (cursor.isCollapsed) return null
        val index = doc.indexOf(cursor.rowId)
        val row = doc.rows[index]
        if (row.kind is RowKind.Code || row.kind == RowKind.Raw || row.kind == RowKind.Rule) return null
        val lo = minOf(cursor.start, cursor.end)
        val hi = maxOf(cursor.start, cursor.end)
        val rows = doc.rows.toMutableList()
        rows[index] = row.copy(text = row.text.toggle(kind, lo, hi), touched = true)
        return Edit(doc.withRows(rows), cursor)
    }

    /** Makes the selection a link to [href] (an existing link on it is replaced). */
    fun setLink(doc: EditorDocument, cursor: Cursor, href: String, title: String? = null): Edit? {
        val index = doc.indexOf(cursor.rowId)
        val row = doc.rows[index]
        if (row.kind is RowKind.Code || row.kind == RowKind.Raw || row.kind == RowKind.Rule) return null
        var lo = minOf(cursor.start, cursor.end)
        var hi = maxOf(cursor.start, cursor.end)
        if (lo == hi) { // caret inside an existing link: change that link
            val existing = row.text.linkAtCursor(lo) ?: return null
            lo = existing.start; hi = existing.end
        }
        val rows = doc.rows.toMutableList()
        rows[index] = row.copy(text = row.text.add(SpanKind.Link, lo, hi, href, title), touched = true)
        return Edit(doc.withRows(rows), cursor)
    }

    fun removeLink(doc: EditorDocument, cursor: Cursor): Edit? {
        val index = doc.indexOf(cursor.rowId)
        val row = doc.rows[index]
        val link = (if (cursor.isCollapsed) row.text.linkAtCursor(cursor.start) else null)
        val lo = link?.start ?: minOf(cursor.start, cursor.end)
        val hi = link?.end ?: maxOf(cursor.start, cursor.end)
        if (hi <= lo) return null
        val rows = doc.rows.toMutableList()
        rows[index] = row.copy(text = row.text.remove(SpanKind.Link, lo, hi), touched = true)
        return Edit(doc.withRows(rows), cursor)
    }

    // ---- Block formatting ------------------------------------------------------------------------------------

    /** Normal text, heading 1-6, quote or code. */
    fun setKind(doc: EditorDocument, rowId: Long, kind: RowKind): Edit {
        require(kind is RowKind.Paragraph || kind is RowKind.Heading || kind == RowKind.Quote || kind is RowKind.Code) { "use the list operations for lists" }
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        if (row.kind == kind) return Edit(doc, Cursor(rowId, 0))
        val rows = doc.rows.toMutableList()
        val toCode = kind is RowKind.Code
        val text = when {
            toCode -> RichText(row.text.text)
            kind is RowKind.Heading -> singleLine(row.text)
            else -> row.text
        }
        // a row inside a list leaves it; its nested items stay (their depth is repaired by the document)
        rows[index] = row.copy(kind = kind, text = text, depth = 0, touched = true)
        return Edit(doc.withRows(rows), Cursor(rowId, text.length))
    }

    /**
     * Bullet / numbered list: turns the row into an item of that kind, or back into a paragraph if it already is one.
     * A new item joins the list directly above it if that has the same kind and depth.
     */
    fun toggleList(doc: EditorDocument, rowId: Long, ordered: Boolean): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val rows = doc.rows.toMutableList()
        val current = row.kind as? RowKind.ListItem
        if (current != null && current.list.ordered == ordered) {
            return toParagraph(doc, rowId)
        }
        if (current != null) { // switch between bullet and numbered: the whole run of items at this depth changes
            val info = current.list.copy(ordered = ordered, marker = if (ordered) '.' else '-', start = 1)
            for (i in rows.indices) {
                val k = rows[i].kind as? RowKind.ListItem ?: continue
                if (k.list.id == current.list.id && rows[i].depth == row.depth) rows[i] = rows[i].copy(kind = k.copy(list = info), touched = true)
            }
            return Edit(doc.withRows(rows), Cursor(rowId, row.text.length))
        }
        val above = rows.getOrNull(index - 1)
        val aboveKind = above?.kind as? RowKind.ListItem
        val joins = aboveKind != null && aboveKind.list.ordered == ordered && above.depth == 0
        val info = if (joins) aboveKind!!.list else ListInfo(doc.nextId, ordered, 1, if (ordered) '.' else '-')
        val text = if (row.kind is RowKind.Code || row.kind == RowKind.Raw) RichText(row.text.text) else row.text
        rows[index] = row.copy(kind = RowKind.ListItem(info, null), text = text, depth = 0, touched = true)
        return Edit(doc.withRows(rows, if (joins) doc.nextId else doc.nextId + 1), Cursor(rowId, text.length))
    }

    /** Task list: makes the row an unchecked task item; on a task item it becomes a plain bullet item again. */
    fun toggleTask(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val rows = doc.rows.toMutableList()
        val current = row.kind as? RowKind.ListItem
        if (current != null) {
            rows[index] = row.copy(kind = current.copy(checked = if (current.checked == null) false else null), touched = true)
            return Edit(doc.withRows(rows), Cursor(rowId, row.text.length))
        }
        val bullet = toggleList(doc, rowId, ordered = false)
        val bulletRows = bullet.doc.rows.toMutableList()
        val i = bullet.doc.indexOf(rowId)
        val k = bulletRows[i].kind as RowKind.ListItem
        bulletRows[i] = bulletRows[i].copy(kind = k.copy(checked = false), touched = true)
        return Edit(bullet.doc.withRows(bulletRows), bullet.cursor)
    }

    /** Tapping a checkbox. In a smart checklist ([smart]) the item then moves (see [Checklist]); that is part of the same edit. */
    fun setChecked(doc: EditorDocument, rowId: Long, checked: Boolean, smart: Boolean = false): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val kind = row.kind as? RowKind.ListItem ?: return Edit(doc, Cursor(rowId, 0))
        if (kind.checked == null || kind.checked == checked) return Edit(doc, Cursor(rowId, 0))
        val rows = doc.rows.toMutableList()
        rows[index] = row.copy(kind = kind.copy(checked = checked), touched = true)
        val placed = if (smart) Checklist.place(rows, rowId, checked) else rows
        return Edit(doc.withRows(placed), Cursor(rowId, 0))
    }

    /** Switching smart checklist mode on: sorts the task checklists once (unchecked first). Null if nothing changes. */
    fun sortChecklists(doc: EditorDocument): Edit? {
        val sorted = Checklist.normalize(doc.rows)
        if (sorted === doc.rows || sorted.map { it.id } == doc.rows.map { it.id }) return null
        return Edit(doc.withRows(sorted), Cursor(doc.rows.first().id, 0))
    }

    /** Makes the item a child of the item above it (with everything nested below it). */
    fun indent(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val kind = row.kind as? RowKind.ListItem ?: return Edit(doc, Cursor(rowId, 0))
        val previous = doc.rows.getOrNull(index - 1)
        if (previous == null || !previous.isListItem || previous.depth < row.depth) return Edit(doc, Cursor(rowId, 0)) // needs a sibling above
        val newDepth = row.depth + 1
        val rows = doc.rows.toMutableList()
        // join the list that already exists at the new depth directly above (inside the same parent), else start one
        val sibling = findSiblingList(rows, index, newDepth)
        val info = sibling ?: ListInfo(doc.nextId, kind.list.ordered, 1, kind.list.marker)
        rows[index] = row.copy(kind = kind.copy(list = info), depth = newDepth, touched = true)
        var j = index + 1
        while (j < rows.size && rows[j].isListItem && rows[j].depth > row.depth) {
            rows[j] = rows[j].copy(depth = rows[j].depth + 1, touched = true)
            j++
        }
        return Edit(doc.withRows(rows, if (sibling == null) doc.nextId + 1 else doc.nextId), Cursor(rowId, row.text.length))
    }

    /** One level out; at the top level the item becomes a paragraph. */
    fun outdent(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val kind = row.kind as? RowKind.ListItem ?: return Edit(doc, Cursor(rowId, 0))
        if (row.depth == 0) return toParagraph(doc, rowId)
        val newDepth = row.depth - 1
        val rows = doc.rows.toMutableList()
        // becomes the next sibling of its parent: same list as the nearest item above at the new depth
        var info = kind.list
        var j = index - 1
        while (j >= 0 && rows[j].isListItem) {
            if (rows[j].depth == newDepth) { info = (rows[j].kind as RowKind.ListItem).list; break }
            if (rows[j].depth < newDepth) break
            j--
        }
        rows[index] = row.copy(kind = kind.copy(list = info), depth = newDepth, touched = true)
        var k = index + 1
        while (k < rows.size && rows[k].isListItem && rows[k].depth > row.depth) {
            rows[k] = rows[k].copy(depth = rows[k].depth - 1, touched = true)
            k++
        }
        return Edit(doc.withRows(rows), Cursor(rowId, row.text.length))
    }

    private fun findSiblingList(rows: List<EditorRow>, index: Int, depth: Int): ListInfo? {
        var j = index - 1
        while (j >= 0 && rows[j].isListItem) {
            if (rows[j].depth == depth) return (rows[j].kind as RowKind.ListItem).list
            if (rows[j].depth < depth - 1) return null
            j--
        }
        return null
    }

    /** Inserts a horizontal rule below the row (replacing it if it is an empty paragraph). */
    fun insertRule(doc: EditorDocument, rowId: Long): Edit {
        val index = doc.indexOf(rowId)
        val row = doc.rows[index]
        val rows = doc.rows.toMutableList()
        val rule = EditorRow(doc.nextId, RowKind.Rule, RichText(""), 0, touched = true)
        val paragraph = EditorRow(doc.nextId + 1, RowKind.Paragraph, RichText(""), 0, touched = true)
        return if (row.kind == RowKind.Paragraph && row.text.isEmpty) {
            rows[index] = rule
            rows.add(index + 1, paragraph)
            Edit(doc.withRows(rows, doc.nextId + 2), Cursor(paragraph.id, 0))
        } else {
            rows.add(index + 1, rule)
            rows.add(index + 2, paragraph)
            Edit(doc.withRows(rows, doc.nextId + 2), Cursor(paragraph.id, 0))
        }
    }
}

