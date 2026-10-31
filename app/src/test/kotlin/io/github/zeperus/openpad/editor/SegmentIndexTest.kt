package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** [Segment.indexOf]: the index of a row by its id, -1 for an id the segment does not have. */
class SegmentIndexTest {
    private fun segment(md: String) = Segment(EditorDocument.fromMarkdown(md).rows)

    @Test fun `indexOf finds the first, a middle and the last row`() {
        val seg = segment("- a\n- b\n- c\n")
        assertEquals(3, seg.rows.size)
        assertEquals(0, seg.indexOf(seg.ids[0]))
        assertEquals(1, seg.indexOf(seg.ids[1]))
        assertEquals(2, seg.indexOf(seg.ids[2]))
    }

    @Test fun `indexOf is -1 for an id the segment does not have`() {
        val seg = segment("- a\n- b\n")
        assertEquals(-1, seg.indexOf(Long.MAX_VALUE))
        assertEquals(-1, seg.indexOf(-1L))
        assertEquals(-1, Segment(emptyList()).indexOf(0L))
    }

    @Test fun `with a repeated id the first row wins`() {
        val rows = EditorDocument.fromMarkdown("- a\n- b\n- c\n").rows
        val seg = Segment(listOf(rows[0], rows[1], rows[2].copy(id = rows[0].id)))
        assertEquals(0, seg.indexOf(rows[0].id))
        assertEquals(1, seg.indexOf(rows[1].id))
    }
}
