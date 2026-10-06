package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The part of caret hit testing that does not need a screen: a tap belongs to the row whose line it is on. Offsets are the field's
 * (one more than the segment's: the field starts with an invisible character).
 */
class TapMappingTest {
    private fun segment(md: String) = Segment(EditorDocument.fromMarkdown(md).rows)

    @Test fun `a tap right of a single row that is not the last lands at its end, not at the start of the next row`() {
        val s = segment("Hello\n\nWorld\n") // field text: marker + "Hello" + break + "World"
        // the layout answers "after the break" = 7 for a tap right of "Hello"; the first line starts at 0
        assertEquals(6, s.clampTapToRowOfLine(7, 0))
        assertEquals(12, s.clampTapToRowOfLine(12, 7)) // right of the last row: its end
    }

    @Test fun `taps inside the text and left of it are not changed`() {
        val s = segment("Hello\n\nWorld\n")
        for (offset in 1..6) assertEquals(offset, s.clampTapToRowOfLine(offset, 0))
        assertEquals(7, s.clampTapToRowOfLine(7, 7)) // left of "World": its start
        assertEquals(9, s.clampTapToRowOfLine(9, 7))
    }

    @Test fun `a wrapped row is limited by its own end, whichever of its lines was tapped`() {
        val s = segment("aaaa bbbb cccc\n\nnext\n")
        // suppose the row wraps so that its second visual line starts at display offset 6; a tap right of that last line gives 16 (after the break)
        assertEquals(15, s.clampTapToRowOfLine(16, 6))
        // a tap right of the first visual line is answered inside the row and is kept
        assertEquals(5, s.clampTapToRowOfLine(5, 0))
    }

    @Test fun `an empty row ends where it starts`() {
        val s = Segment(EditorDocument.fromMarkdown("- a").rows + EditorDocument.fromMarkdown("- b").rows.map { it })
        // rows "a" and "b": a tap right of "a" (display 2 is its end, 3 the start of "b")
        assertEquals(2, s.clampTapToRowOfLine(3, 0))
    }

    @Test fun `list items and headings behave like paragraphs`() {
        val s = segment("# Title\n\n- [ ] Milk\n- Bread\n1. One\n")
        val texts = s.rows.map { it.text.text }
        assertEquals(listOf("Title", "Milk", "Bread", "One"), texts)
        var lineStart = 0
        for (i in s.rows.indices) {
            val end = s.end(i) + 1
            assertEquals("right of row $i", end, s.clampTapToRowOfLine(end + 1, lineStart))
            lineStart = end + 1
        }
    }
}
