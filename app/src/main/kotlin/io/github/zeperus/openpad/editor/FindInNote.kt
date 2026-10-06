package io.github.zeperus.openpad.editor

/** One occurrence of the search text in row [rowId]: characters `[start, end)` of its text. */
data class FindMatch(val rowId: Long, val start: Int, val end: Int)

/** Find in note: looks through the *visible* text of the rows (no Markdown punctuation), case-insensitive. */
object FindInNote {
    fun matches(doc: EditorDocument, query: String): List<FindMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<FindMatch>()
        for (row in doc.rows) {
            if (row.kind == RowKind.Rule) continue
            // a table, image or HTML block that is drawn as formatted content has no text to point at
            if (row.kind == RowKind.Raw && RawBlocks.classify(row.text.text).let { it is RawBlock.Table || it is RawBlock.Image || (it is RawBlock.Html && it.simple != null) }) continue
            val text = row.text.text
            var at = text.indexOf(query, 0, ignoreCase = true)
            while (at >= 0) {
                out += FindMatch(row.id, at, at + query.length)
                at = text.indexOf(query, at + maxOf(1, query.length), ignoreCase = true)
            }
        }
        return out
    }
}
