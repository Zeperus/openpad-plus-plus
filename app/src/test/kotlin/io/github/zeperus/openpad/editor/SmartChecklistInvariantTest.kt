package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartChecklistInvariantTest {
    private var now = 0L
    private fun smart(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now }).also { it.smartChecklist = true }
    private fun EditorSession.tasks() = doc.rows.map { r -> (if ((r.kind as? RowKind.ListItem)?.checked == true) "x " else "o ") + r.text.text }

    @Test fun `converting a paragraph below completed items to a task puts it above them`() {
        val s = smart("- [ ] A\n- [x] B\n\nnew")
        // turn the paragraph into a task item: it joins the list above it and the list stays in order
        s.moveCursor(Cursor(s.doc.rows[2].id, 0))
        s.toggleTask()
        assertEquals(listOf("o A", "o new", "x B"), s.tasks())
    }

    @Test fun `a task created by Enter on a completed item lands above the completed ones`() {
        val s = smart("- [ ] A\n- [x] B\n")
        val b = s.doc.rows[1]
        s.onText(b.id, "B\n", 2)
        assertEquals(listOf("o A", "o ", "x B"), s.tasks())
    }

    @Test fun `an already sorted document is not touched`() {
        val s = smart("- [ ] A\n- [x] B\n")
        val before = s.doc.rows
        assertFalse(s.settleLoaded())
        assertTrue(before === s.doc.rows)
        assertEquals("- [ ] A\n- [x] B\n", s.markdown())
    }

    @Test fun `loading an out-of-order smart checklist sorts it without an undo step`() {
        val s = smart("- [x] A\n- [ ] B\n")
        assertTrue(s.settleLoaded())
        assertEquals(listOf("o B", "x A"), s.tasks())
        assertFalse(s.history.canUndo)
    }

    @Test fun `pasting several lines into a checklist keeps it in order`() {
        val s = smart("- [ ] A\n- [x] B\n")
        s.onText(s.doc.rows[0].id, "A\nmore", 6) // since Alpha 8 every pasted line is an item of its own
        assertEquals(listOf("o A", "o more", "x B"), s.tasks())
    }

    @Test fun `undo and redo return to consistent documents`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        s.setChecked(s.doc.rows[0].id, true)
        s.setChecked(s.doc.rows[0].id, true) // B
        assertEquals(listOf("o C", "x A", "x B"), s.tasks())
        s.undo()
        assertEquals(listOf("o B", "o C", "x A"), s.tasks())
        s.undo()
        assertEquals(listOf("o A", "o B", "o C"), s.tasks())
        s.redo(); s.redo()
        assertEquals(listOf("o C", "x A", "x B"), s.tasks())
    }

    @Test fun `a new checklist document has one empty unchecked task and writes nothing`() {
        val s = EditorSession(EditorDocument.emptyChecklist(), clock = { now })
        s.smartChecklist = true
        assertEquals(1, s.doc.rows.size)
        assertEquals(false, (s.doc.rows[0].kind as RowKind.ListItem).checked)
        s.onText(s.doc.rows[0].id, "Milk", 4)
        assertEquals("- [ ] Milk\n", s.markdown())
    }

    @Test fun `nested parent keeps its children when sorting after a conversion`() {
        val s = smart("- [x] done\n  - [ ] child\n- [ ] open\n")
        assertTrue(s.settleLoaded())
        assertEquals(listOf("o open", "x done", "o child"), s.tasks())
        assertEquals("- [ ] open\n- [x] done\n  - [ ] child\n", s.markdown())
    }
}
