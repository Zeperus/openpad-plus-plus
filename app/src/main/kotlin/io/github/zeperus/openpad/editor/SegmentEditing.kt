package io.github.zeperus.openpad.editor

/** What a change made in a segment's text field means for the document. */
sealed interface SegmentOp {
    /** Characters typed, pasted or deleted *inside one row* (this includes Enter at the end or in the middle of it). */
    data class TextEdit(val rowId: Long, val newText: String, val caret: Int) : SegmentOp

    /** Backspace with the caret at the very start of a row: the line break in front of it was deleted. */
    data class Backspace(val rowId: Long) : SegmentOp

    /** Delete at the very end of a row: the line break behind it was deleted. */
    data class ForwardJoin(val rowId: Long) : SegmentOp

    /** Anything that reaches over a line break: a range was deleted or replaced. */
    data class Replace(val selection: DocumentSelection, val inserted: String) : SegmentOp

    data object None : SegmentOp
}

/**
 * The bridge between a text field that holds several rows and the document model. The field reports *text* changes (it knows
 * nothing about rows, kinds or lists); this turns each change into the operation the user meant - typing in a row, Enter,
 * Backspace at the start of a list item, deleting across paragraphs - so the model stays the one truth and the field is made to
 * show what the model decided (see the UI layer). Pure and tested without Android.
 */
object SegmentEditing {
    /**
     * [old] is the text the field had (it must be the segment's text, else the change is stale and ignored); [new] the text
     * after the edit. The selection before/after tells Backspace from Delete when both look the same in the text.
     */
    fun interpret(segment: Segment, old: String, new: String, beforeStart: Int, beforeEnd: Int, afterEnd: Int): SegmentOp {
        if (old != segment.text || old == new) return SegmentOp.None
        // the smallest change that turns old into new
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        var a = p
        var b = old.length - s
        var inserted = new.substring(p, new.length - s)
        // a deletion of identical characters (an empty line between two line breaks) is the one next to the caret
        if (inserted.isEmpty() && b > a && beforeStart == beforeEnd) {
            val k = b - a
            val c = beforeStart
            when {
                c - k >= 0 && old.regionMatches(c - k, old, a, k) -> { a = c - k; b = c }
                c + k <= old.length && old.regionMatches(c, old, a, k) -> { a = c; b = c + k }
            }
        }
        // A text pasted at the end of a row can look like text inserted at the start of the next row (when both read the same around the
        // seam): the caret says where it was really inserted.
        if (b == a && inserted.length >= 2 && beforeStart == beforeEnd && beforeStart != a) {
            val c = beforeStart
            val k = new.length - old.length
            if (c in 0..old.length && c + k <= new.length && old.substring(0, c) + new.substring(c, c + k) + old.substring(c) == new) {
                a = c; b = c
                inserted = new.substring(c, c + k)
            }
        }
        val i = segment.rowIndexAt(a)
        val j = segment.rowIndexAt(b)
        val row = segment.rows[i]
        val start = segment.start(i)
        if (i == j) { // inside one row
            if (inserted == "\n" && a != b) return replace(segment, a, b, inserted) // Enter over a selection
            val local = row.text.text
            val newText = local.substring(0, a - start) + inserted + local.substring(b - start)
            return SegmentOp.TextEdit(row.id, newText, (afterEnd - start).coerceIn(0, newText.length))
        }
        // the single line break between two rows
        if (inserted.isEmpty() && j == i + 1 && a == segment.end(i) && b == segment.start(j)) {
            if (beforeStart == beforeEnd && beforeStart == segment.start(j)) return SegmentOp.Backspace(segment.rows[j].id)
            if (beforeStart == beforeEnd && beforeStart == segment.end(i)) return SegmentOp.ForwardJoin(row.id)
        }
        return replace(segment, a, b, inserted)
    }

    private fun replace(segment: Segment, a: Int, b: Int, inserted: String) =
        SegmentOp.Replace(DocumentSelection(segment.position(a), segment.position(b)), inserted)

    /** Runs [op] on the session (one undo step). True if the document changed. */
    fun apply(session: EditorSession, op: SegmentOp): Boolean = when (op) {
        is SegmentOp.TextEdit -> session.onText(op.rowId, op.newText, op.caret)
        is SegmentOp.Backspace -> session.backspaceAtStart(op.rowId)
        is SegmentOp.ForwardJoin -> session.joinWithNext(op.rowId)
        is SegmentOp.Replace -> session.replaceSelection(op.selection, op.inserted)
        SegmentOp.None -> false
    }
}
