package io.github.zeperus.openpad.editor

/**
 * Smart checklist rules on the flat row list: checked task items sit below the unchecked ones. Only whole *sibling groups*
 * of task items are reordered; an item always takes its nested rows with it, nothing is ever pulled out of its parent.
 * A group that mixes task items with plain items is left alone (reordering it could not follow a simple rule).
 *
 * Rule for a single change: checking moves the item to the very bottom of its siblings (so items stay in the order they
 * were completed); unchecking moves it to the end of the unchecked group, just above the first completed item. The order
 * inside each group is otherwise untouched. No history of earlier positions is kept.
 */
internal object Checklist {
    /** Rows `[lo, hi)` that make up the siblings of the item at [index] with all their nested rows, or null if there is no such group. */
    private fun siblingRange(rows: List<EditorRow>, index: Int): IntRange? {
        val head = rows[index]
        val kind = head.kind as? RowKind.ListItem ?: return null
        var lo = index
        while (lo - 1 >= 0 && belongs(rows[lo - 1], head, kind)) lo--
        var hi = index + 1
        while (hi < rows.size && belongs(rows[hi], head, kind)) hi++
        return lo until hi
    }

    private fun belongs(row: EditorRow, head: EditorRow, kind: RowKind.ListItem): Boolean {
        val k = row.kind as? RowKind.ListItem ?: return false
        return row.depth > head.depth || (row.depth == head.depth && k.list.id == kind.list.id)
    }

    /** Splits `[range]` into units: a sibling row plus its nested rows. Null if a sibling is not a task item. */
    private fun units(rows: List<EditorRow>, range: IntRange, depth: Int): List<List<EditorRow>>? {
        val out = ArrayList<MutableList<EditorRow>>()
        for (i in range) {
            val row = rows[i]
            if (row.depth == depth) {
                if ((row.kind as RowKind.ListItem).checked == null) return null
                out += mutableListOf(row)
            } else out.lastOrNull()?.add(row) ?: return null
        }
        return out
    }

    private fun isChecked(unit: List<EditorRow>) = (unit[0].kind as RowKind.ListItem).checked == true

    private fun rebuild(rows: List<EditorRow>, range: IntRange, units: List<List<EditorRow>>): List<EditorRow> =
        rows.subList(0, range.first) + units.flatten().map { it.copy(touched = true) } + rows.subList(range.last + 1, rows.size)

    /** After the item [rowId] was set to [checked]: puts it where the rule says. Returns the rows unchanged if there is nothing to do. */
    fun place(rows: List<EditorRow>, rowId: Long, checked: Boolean): List<EditorRow> {
        val index = rows.indexOfFirst { it.id == rowId }
        if (index < 0) return rows
        val range = siblingRange(rows, index) ?: return rows
        val units = units(rows, range, rows[index].depth) ?: return rows
        val unit = units.first { it[0].id == rowId }
        val rest = units - setOf(unit)
        val target = if (checked) rest + listOf(unit) else {
            val firstDone = rest.indexOfFirst { isChecked(it) }
            if (firstDone < 0) rest + listOf(unit) else rest.subList(0, firstDone) + listOf(unit) + rest.subList(firstDone, rest.size)
        }
        if (target.map { it[0].id } == units.map { it[0].id }) return rows
        return rebuild(rows, range, target)
    }

    /** A new unchecked item that was created next to checked ones: moves it above the first completed sibling. */
    fun placeNewUnchecked(rows: List<EditorRow>, rowId: Long): List<EditorRow> = place(rows, rowId, checked = false)

    /** Sorts every task checklist in the document once (switching the mode on): stable, unchecked first. */
    fun normalize(rows: List<EditorRow>): List<EditorRow> {
        var result = rows
        var i = 0
        while (i < result.size) {
            val row = result[i]
            if (row.kind is RowKind.ListItem && row.depth == 0) {
                val range = siblingRange(result, i)!!
                result = sortGroup(result, range, 0)
                i = range.last + 1
            } else i++
        }
        return result
    }

    private fun sortGroup(rows: List<EditorRow>, range: IntRange, depth: Int): List<EditorRow> {
        // nested groups first, so moving a unit later carries its already sorted children
        var current = rows
        var i = range.first
        while (i <= range.last) {
            val row = current[i]
            if (row.depth == depth + 1) {
                val nested = siblingRange(current, i)!!.let { r -> maxOf(r.first, range.first)..minOf(r.last, range.last) }
                current = sortGroup(current, nested, depth + 1)
                i = nested.last + 1
            } else i++
        }
        val units = units(current, range, depth) ?: return current
        val sorted = units.filterNot { isChecked(it) } + units.filter { isChecked(it) }
        if (sorted.map { it[0].id } == units.map { it[0].id }) return current
        return rebuild(current, range, sorted)
    }
}
