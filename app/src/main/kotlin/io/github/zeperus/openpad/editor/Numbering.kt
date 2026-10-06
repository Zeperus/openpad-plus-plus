package io.github.zeperus.openpad.editor

/** The numbers shown for numbered list items, in one pass over the rows. */
object Numbering {
    /** Row id -> number, for the ordered list items among [rows] (same rule as [EditorDocument.numberOf]). */
    fun of(rows: List<EditorRow>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        val counters = HashMap<Int, Pair<Long, Int>>() // depth -> (list id, last number)
        for (row in rows) {
            val kind = row.kind as? RowKind.ListItem
            if (kind == null) { counters.clear(); continue }
            val depth = row.depth
            counters.keys.filter { it > depth }.forEach { counters.remove(it) }
            val previous = counters[depth]
            val number = if (previous != null && previous.first == kind.list.id) previous.second + 1 else kind.list.start
            counters[depth] = kind.list.id to number
            if (kind.list.ordered) out[row.id] = number
        }
        return out
    }
}
