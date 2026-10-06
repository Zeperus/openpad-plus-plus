package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha 5 (density + Enter): what the document model says after Enter - adjacent rows, no rows that only exist for spacing - and
 * where the caret is (offset 0 of the new item's own text). The Compose side shows exactly these rows, one line each.
 */
class EnterAndLayoutTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.enterAtEnd(i: Int) {
        val t = doc.rows[i].text.text
        onText(doc.rows[i].id, t + "\n", t.length + 1)
    }
    private fun EditorSession.texts() = doc.rows.map { it.text.text }

    // ---- layout / semantic model ---------------------------------------------------------------------------

    @Test fun `Enter in a paragraph creates one adjacent paragraph`() {
        val s = session("Line one")
        s.enterAtEnd(0)
        assertEquals(listOf("Line one", ""), s.texts())
        assertEquals(listOf(RowKind.Paragraph, RowKind.Paragraph), s.doc.rows.map { it.kind })
    }

    @Test fun `no fake blank paragraph is added for spacing`() {
        val s = session("Line one")
        s.enterAtEnd(0)
        s.onText(s.doc.rows[1].id, "Line two", 8)
        s.enterAtEnd(1)
        s.onText(s.doc.rows[2].id, "Line three", 10)
        // three rows, one per line: the rows ARE the lines. (The blank lines in the file are separators, not rows.)
        assertEquals(listOf("Line one", "Line two", "Line three"), s.texts())
        assertEquals("Line one\n\nLine two\n\nLine three\n", s.markdown())
    }

    @Test fun `intentional blank lines in the Markdown are still not extra rows and still survive a no-op edit`() {
        val original = "Line one\n\n\n\nLine two\n"
        val s = session(original)
        assertEquals(listOf("Line one", "Line two"), s.texts())
        assertEquals(original, s.markdown()) // untouched: written back byte for byte
    }

    @Test fun `Enter in a bullet list creates exactly one next item`() {
        val s = session("- Item")
        s.enterAtEnd(0)
        assertEquals(2, s.doc.rows.size)
        assertTrue(s.doc.rows.all { it.kind is RowKind.ListItem })
    }

    @Test fun `Enter in a checklist creates exactly one next unchecked task`() {
        val s = session("- [x] Schwein")
        s.enterAtEnd(0)
        assertEquals(2, s.doc.rows.size)
        assertEquals(listOf(true, false), s.doc.rows.map { (it.kind as RowKind.ListItem).checked })
    }

    // ---- caret ------------------------------------------------------------------------------------------------

    private fun assertCaretInNewItem(s: EditorSession, expectedRows: Int) {
        assertEquals(expectedRows, s.doc.rows.size)
        val cursor = s.cursor!!
        val last = s.doc.rows.last()
        assertEquals("the caret is in the new (last) row", last.id, cursor.rowId)
        assertEquals("at the start of its text", 0, cursor.start)
        assertEquals(0, cursor.end)
        assertEquals("", last.text.text)
        // no focus remains on the old row
        assertTrue(s.doc.rows.dropLast(1).none { it.id == cursor.rowId })
    }

    @Test fun `Enter in a checklist puts the caret at offset 0 of the new item`() {
        val s = session("- [ ] Schwein")
        s.moveCursor(Cursor(s.doc.rows[0].id, 7))
        s.enterAtEnd(0)
        assertCaretInNewItem(s, 2)
    }

    @Test fun `Enter in a bullet list puts the caret at offset 0 of the new item`() {
        val s = session("- Item")
        s.moveCursor(Cursor(s.doc.rows[0].id, 4))
        s.enterAtEnd(0)
        assertCaretInNewItem(s, 2)
    }

    @Test fun `Enter in a numbered list puts the caret at offset 0 of the new item`() {
        val s = session("1. Item")
        s.moveCursor(Cursor(s.doc.rows[0].id, 4))
        s.enterAtEnd(0)
        assertCaretInNewItem(s, 2)
    }

    @Test fun `Enter in the middle of a checklist item keeps the rest in the new item with the caret in front of it`() {
        val s = session("- [ ] MilkBread")
        s.moveCursor(Cursor(s.doc.rows[0].id, 4))
        s.onText(s.doc.rows[0].id, "Milk\nBread", 5)
        assertEquals(listOf("Milk", "Bread"), s.texts())
        assertEquals(s.doc.rows[1].id, s.cursor!!.rowId)
        assertEquals(0, s.cursor!!.start)
        assertNotEquals(s.doc.rows[0].id, s.cursor!!.rowId)
    }

    @Test fun `typing after Enter goes into the new item`() {
        val s = session("- [ ] Schwein")
        s.enterAtEnd(0)
        s.onText(s.cursor!!.rowId, "Salz", 4)
        assertEquals("- [ ] Schwein\n- [ ] Salz\n", s.markdown())
        assertFalse(s.markdown().contains("\n\n"))
    }

    // ---- what the field shows ---------------------------------------------------------------------------------

    @Test fun `the field text is the rows joined by single line breaks`() {
        val s = session("Line one\n\nLine two\n\nLine three\n")
        val seg = Segment(s.doc.rows)
        assertEquals("Line one\nLine two\nLine three", seg.text)
        assertEquals(seg.text.length + 1, seg.displayLength)
    }

    @Test fun `an empty last row needs one more drawn character`() {
        val s = session("- [ ] a")
        s.enterAtEnd(0)
        val seg = Segment(s.doc.rows)
        assertEquals("a\n", seg.text)
        assertEquals(seg.text.length + 2, seg.displayLength)
    }
}
