package io.github.zeperus.openpad.editor

/**
 * Rows that are edited in **one** text field: their texts joined by a single line break each. One field is what makes the text
 * selection native across paragraphs, headings and list items - an Android text field can only select inside itself. Rows that
 * cannot be text (a rule, a table, an image) separate segments.
 *
 * The field's text and the rows always correspond exactly: row `i` occupies `[start(i), end(i)]` and the line break at `end(i)`
 * separates it from row `i+1`. A line break *inside* a row (a soft break) is part of that row.
 */
class Segment(val rows: List<EditorRow>) {
    val ids: List<Long> = rows.map { it.id }
    private val starts = IntArray(rows.size)
    val text: String

    init {
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            if (i > 0) sb.append('\n')
            starts[i] = sb.length
            sb.append(row.text.text)
        }
        text = sb.toString()
    }

    /**
     * The length of the text *as drawn* by the field: its text plus the invisible marker at the start (see `FIELD_PREFIX`), plus one
     * invisible character if the last row is empty (a paragraph needs a character to be laid out with its own style).
     */
    val displayLength: Int get() = text.length + 1 + (if (rows.last().text.isEmpty) 1 else 0)

    /**
     * A caret that a tap put at [displayOffset] (the field's offsets: one more than the segment's, because of the invisible first
     * character) on the visual line that starts at [lineStart], limited to the row that owns that line. The layout puts a tap to the
     * right of a row's last line behind the invisible character that ends the row, which is the start of the next row.
     */
    fun clampTapToRowOfLine(displayOffset: Int, lineStart: Int): Int =
        minOf(displayOffset, end(rowIndexAt(maxOf(0, lineStart - 1))) + 1)

    fun start(index: Int): Int = starts[index]

    fun end(index: Int): Int = starts[index] + rows[index].text.length

    /** The row that contains [position]; the line break after a row belongs to that row. */
    fun rowIndexAt(position: Int): Int {
        var lo = 0
        var hi = rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= position) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun indexOf(rowId: Long): Int = rows.indexOfFirst { it.id == rowId }

    fun position(absolute: Int): DocumentPosition {
        val i = rowIndexAt(absolute.coerceIn(0, text.length))
        return DocumentPosition(rows[i].id, (absolute - starts[i]).coerceIn(0, rows[i].text.length))
    }

    /** The place in the field's text for a logical position, or null if the row is not in this segment. */
    fun absolute(p: DocumentPosition): Int? {
        val i = indexOf(p.rowId)
        return if (i < 0) null else starts[i] + p.offset.coerceIn(0, rows[i].text.length)
    }
}

sealed interface DocItem {
    /** Rows in one text field. */
    class Text(val segment: Segment) : DocItem

    /** A row that is drawn as something other than text (a rule, a table, an image, simple HTML). */
    class Block(val row: EditorRow) : DocItem
}

object Segments {
    /** The document as text segments and blocks, in order. [isBlock] says which rows are drawn as non-text blocks. */
    fun split(doc: EditorDocument, isBlock: (EditorRow) -> Boolean): List<DocItem> {
        val items = ArrayList<DocItem>()
        var run = ArrayList<EditorRow>()
        fun flush() {
            if (run.isNotEmpty()) { items += DocItem.Text(Segment(run)); run = ArrayList() }
        }
        for (row in doc.rows) {
            if (isBlock(row)) { flush(); items += DocItem.Block(row) } else run += row
        }
        flush()
        return items
    }

    /** Rows that are never text: rules, and raw rows that have a formatted drawing (unless they are opened as source). */
    fun isBlock(row: EditorRow, sourceMode: Set<Long>): Boolean = when (row.kind) {
        RowKind.Rule -> true
        RowKind.Raw -> row.id !in sourceMode && RawBlocks.classify(row.text.text).let {
            it is RawBlock.Table || it is RawBlock.Image || (it is RawBlock.Html && it.simple != null)
        }
        else -> false
    }

    /** The segment that holds row [rowId], or null (it is a block, or gone). */
    fun containing(doc: EditorDocument, rowId: Long, sourceMode: Set<Long>): Segment? =
        split(doc) { isBlock(it, sourceMode) }.firstNotNullOfOrNull { (it as? DocItem.Text)?.segment?.takeIf { s -> rowId in s.ids } }
}

/**
 * Stable identities for segments, so that a text field is not replaced when its content changes. Rows come and go (Enter creates
 * one, a join removes one) but a segment that shares rows with an earlier one keeps that one's key; a segment with no row in
 * common gets a new key (its field is new).
 */
class SegmentKeys {
    private var previous: List<Pair<Int, Set<Long>>> = emptyList()
    private var counter = 0

    fun assign(segments: List<List<Long>>): List<Int> {
        val result = arrayOfNulls<Int>(segments.size)
        val taken = HashSet<Int>()
        // the best overlaps first
        val pairs = ArrayList<Triple<Int, Int, Int>>() // overlap, segment index, key
        for ((s, ids) in segments.withIndex()) {
            val set = ids.toHashSet()
            for ((key, old) in previous) {
                val overlap = old.count { it in set }
                if (overlap > 0) pairs += Triple(overlap, s, key)
            }
        }
        for ((_, s, key) in pairs.sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.first }.thenBy { it.second })) {
            if (result[s] == null && key !in taken) { result[s] = key; taken += key }
        }
        val keys = segments.indices.map { s -> result[s] ?: (counter++).also { taken += it } }
        previous = keys.zip(segments.map { it.toHashSet() as Set<Long> })
        return keys
    }
}
