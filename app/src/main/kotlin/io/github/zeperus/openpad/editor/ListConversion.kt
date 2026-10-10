package io.github.zeperus.openpad.editor

/** What the list buttons of the formatting bar turn text into. */
enum class ListTarget { Bullet, Numbered, Task }

/**
 * "Convert to list" for more than one logical line: a selection over several rows, or one row that holds several lines (a soft
 * line break). **Every logical line becomes its own item**, never one item with continuation lines. A logical line is a row, or
 * the part of a row between two line breaks - never a line of the *screen*, so a long paragraph that merely wraps stays one item.
 *
 * Rules (docs/editor.md, "Lists from several lines"):
 *  - a leading list marker or task marker of a line is removed (`- Milk`, `• Milk`, `- [x] Milk`, `☐ Milk`); a done marker keeps a
 *    task done when the target is a checklist; the marker is never left in the text.
 *  - a blank line between two items is a **separator**: an empty paragraph between two lists (a blank line is no item). Blank lines
 *    at the ends of the converted text are dropped.
 *  - rows that already are list items keep their nesting; the lists of the selected items are converted as a whole.
 *  - rules, and code / source rows inside a multi-row selection, are left as they are and end the list.
 */
internal object ListConversion {
    /** True if [row] alone is handled by the one-row operations (toggle, switch bullet <-> numbered): one line, no marker to clean. */
    fun isSimple(row: EditorRow): Boolean {
        if ('\n' in row.text.text) return false
        if (row.kind is RowKind.ListItem || row.kind == RowKind.Rule) return true
        return !ListImport.hasMarker(row.text.text)
    }

    private fun matches(kind: RowKind.ListItem, target: ListTarget) = when (target) {
        ListTarget.Task -> kind.checked != null
        ListTarget.Bullet -> !kind.list.ordered && kind.checked == null
        ListTarget.Numbered -> kind.list.ordered && kind.checked == null
    }

    private fun lines(t: RichText): List<RichText> {
        val out = ArrayList<RichText>()
        var from = 0
        while (true) {
            val i = t.text.indexOf('\n', from)
            if (i < 0) { out += t.substring(from, t.length); return out }
            out += t.substring(from, i)
            from = i + 1
        }
    }

    /** Converts rows [first]..[last] of [doc]. Null if nothing changes. */
    fun convert(doc: EditorDocument, first: Int, last: Int, target: ListTarget): Edit? {
        val rows = doc.rows
        val range = rows.subList(first, last + 1)
        val multi = first != last

        // everything already is what is asked for: back to plain (a checklist becomes plain bullets)
        if (range.all { r -> (r.kind as? RowKind.ListItem)?.let { matches(it, target) } == true && '\n' !in r.text.text }) {
            val changed = range.map { r ->
                val k = r.kind as RowKind.ListItem
                if (target == ListTarget.Task) r.copy(kind = k.copy(checked = null), touched = true)
                else r.copy(kind = RowKind.Paragraph, depth = 0, touched = true)
            }
            val end = changed.last()
            return Edit(doc.withRows(rows.subList(0, first) + changed + rows.subList(last + 1, rows.size)), Cursor(end.id, end.text.length))
        }

        var next = doc.nextId
        val ordered = target == ListTarget.Numbered
        fun newInfo() = ListInfo(next++, ordered, 1, if (ordered) '.' else '-')
        val above = rows.getOrNull(first - 1)
        val aboveList = (above?.kind as? RowKind.ListItem)?.takeIf { above.depth == 0 && it.list.ordered == ordered }?.list

        val out = ArrayList<EditorRow>()
        val mapped = HashMap<Pair<Long, Int>, ListInfo>()
        var group: ListInfo? = null // the list that the next plain row joins
        var gap = false // a blank line was seen since the last item
        var items = 0

        /** A blank line between two items ends the list: an empty paragraph stands between them. */
        fun breakAtGap() {
            if (gap && items > 0 && out.last().kind is RowKind.ListItem) {
                out += EditorRow(next++, RowKind.Paragraph, RichText(""), 0, touched = true)
                group = null
            }
            gap = false
        }

        for (r in range) {
            val k = r.kind
            val isList = k is RowKind.ListItem
            val keep = k == RowKind.Rule || (multi && (k is RowKind.Code || k == RowKind.Raw))
            if (keep || (multi && !isList && r.text.isEmpty)) { // not converted: it stays and ends the list
                out += r
                group = null
                gap = false
                continue
            }
            var lastOfRow = -1 // where the row's last item is in [out]
            for (line in lines(r.text)) {
                val row: EditorRow
                if (k is RowKind.ListItem) {
                    if (line.isEmpty) { gap = true; continue }
                    breakAtGap()
                    val info = if (target == ListTarget.Task) k.list else mapped.getOrPut(k.list.id to r.depth) { newInfo() }
                    val checked = if (target == ListTarget.Task) (k.checked ?: false) else null
                    row = EditorRow(next++, RowKind.ListItem(info, checked), line, r.depth, touched = true)
                } else {
                    val c = ListImport.clean(line.text)
                    if (c == null) { gap = true; continue }
                    breakAtGap()
                    val info = group ?: (if (out.isEmpty()) aboveList else null) ?: newInfo()
                    group = info
                    val checked = if (target == ListTarget.Task) (c.checked ?: false) else null
                    row = EditorRow(next++, RowKind.ListItem(info, checked), line.substring(c.start, c.end), 0, touched = true)
                }
                items++
                out += row
                lastOfRow = out.lastIndex
            }
            // the last item of a row keeps the row's id (the caret stays in the same row); the others are new
            if (lastOfRow >= 0) out[lastOfRow] = out[lastOfRow].copy(id = r.id)
        }
        if (items == 0) {
            if (multi) return null
            // one row without any text after its marker ("- "): an empty item to type into
            val r = range.single()
            val info = aboveList ?: newInfo()
            val row = EditorRow(r.id, RowKind.ListItem(info, if (target == ListTarget.Task) false else null), RichText(""), 0, touched = true)
            return Edit(doc.withRows(rows.subList(0, first) + row + rows.subList(last + 1, rows.size), next), Cursor(row.id, 0))
        }
        val end = out.last { it.kind is RowKind.ListItem }
        return Edit(doc.withRows(rows.subList(0, first) + out + rows.subList(last + 1, rows.size), next), Cursor(end.id, end.text.length))
    }
}
