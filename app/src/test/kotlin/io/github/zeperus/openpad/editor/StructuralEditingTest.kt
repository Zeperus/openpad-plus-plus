package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focused row is the text field the user is typing in, and the keyboard is connected to it. A structural edit must
 * *change* that row - never replace it - so these tests pin down which row ids survive, where the caret ends up, and what the
 * list keys do. (The Compose side keeps the field alive as long as the row id is the same.)
 */
class StructuralEditingTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.id(i: Int) = doc.rows[i].id
    private fun EditorSession.text(i: Int) = doc.rows[i].text.text
    private fun EditorSession.kinds() = doc.rows.map { it.kind }
    private fun EditorSession.texts() = doc.rows.map { it.text.text }
    private fun EditorSession.at(i: Int, offset: Int = text(i).length) = moveCursor(Cursor(id(i), offset))
    private fun EditorSession.enterAt(i: Int, offset: Int = text(i).length) {
        val t = text(i)
        onText(id(i), t.substring(0, offset) + "\n" + t.substring(offset), offset + 1)
    }
    private fun EditorSession.type(i: Int, text: String) = onText(id(i), text, text.length)
    private val EditorSession.focusedId get() = cursor!!.rowId

    // ---- Kind changes keep the row ---------------------------------------------------------------------------

    @Test fun `paragraph to bullet and back keeps the row, the caret and the selection`() {
        val s = session("hello world")
        val id = s.id(0)
        s.moveCursor(Cursor(id, 2, 7))
        s.toggleList(false)
        assertTrue(s.doc.rows[0].kind is RowKind.ListItem)
        assertEquals(id, s.id(0))
        assertEquals(1, s.doc.rows.size)
        s.toggleList(false)
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
        assertEquals(id, s.id(0))
    }

    @Test fun `numbered and checklist items become paragraphs in the same row`() {
        for (build in listOf<(EditorSession) -> Unit>({ it.toggleList(true) }, { it.toggleTask() })) {
            val s = session("item")
            s.at(0)
            build(s)
            val id = s.id(0)
            s.onBackspaceAtStartForTest(id)
            assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
            assertEquals(id, s.id(0))
            assertEquals(id, s.focusedId)
            assertEquals("item", s.text(0))
        }
    }

    private fun EditorSession.onBackspaceAtStartForTest(id: Long) = backspaceAtStart(id)

    @Test fun `ticking and unticking keeps the row`() {
        val s = session("- [ ] a\n- [ ] b\n")
        val ids = s.doc.rows.map { it.id }
        s.setChecked(ids[0], true)
        s.setChecked(ids[0], false)
        assertEquals(ids, s.doc.rows.map { it.id })
    }

    @Test fun `converting a heading or quote keeps the row`() {
        val s = session("title")
        val id = s.id(0)
        s.at(0)
        s.setKind(RowKind.Heading(2))
        s.setKind(RowKind.Quote)
        s.setKind(RowKind.Paragraph)
        assertEquals(id, s.id(0))
    }

    // ---- Enter ------------------------------------------------------------------------------------------------

    @Test fun `Enter keeps the focused row as the second half and puts the caret at its start`() {
        val s = session("hello world")
        val id = s.id(0)
        s.at(0, 5)
        s.enterAt(0, 5)
        assertEquals(listOf("hello", " world"), s.texts())
        assertEquals(id, s.id(1)) // the focused field is the one that continues
        assertEquals(id, s.focusedId)
        assertEquals(0, s.cursor!!.start)
    }

    @Test fun `Enter at the end of bullet items continues the list`() {
        val s = session("- one\n- two")
        val two = s.id(1)
        s.at(1)
        s.enterAt(1)
        assertEquals(listOf("one", "two", ""), s.texts())
        assertTrue(s.doc.rows.all { it.kind is RowKind.ListItem })
        assertEquals(two, s.id(2)) // the row being typed in continues as the new, empty item ...
        assertEquals(two, s.focusedId) // ... and keeps the caret: the field is never replaced
    }

    @Test fun `Enter on an empty bullet item leaves the list without a ghost row`() {
        val s = session("- one\n- two")
        s.at(1)
        s.enterAt(1)
        val empty = s.id(2)
        s.enterAt(2, 0)
        assertEquals(3, s.doc.rows.size)
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
        assertEquals(empty, s.id(2))
        assertEquals(empty, s.focusedId)
        assertEquals("- one\n- two", s.markdown().trimEnd().let { it })
    }

    @Test fun `Enter on an empty numbered item leaves the list`() {
        val s = session("1. One\n2. Two")
        s.at(1); s.enterAt(1)
        assertEquals(listOf("One", "Two", ""), s.texts())
        assertEquals(3, s.doc.rows.count { it.kind is RowKind.ListItem })
        s.enterAt(2, 0)
        assertEquals(3, s.doc.rows.size)
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
    }

    @Test fun `Enter on a checklist item makes another unchecked task and an empty one leaves`() {
        val s = session("- [ ] Milk\n- [ ] Bread")
        s.at(1); s.enterAt(1)
        val third = s.doc.rows[2].kind as RowKind.ListItem
        assertEquals(false, third.checked)
        s.enterAt(2, 0)
        assertEquals(3, s.doc.rows.size)
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
    }

    @Test fun `Enter in the middle of a checked task creates an unchecked one`() {
        val s = session("- [x] done thing")
        s.at(0, 4)
        s.enterAt(0, 4)
        assertEquals(true, (s.doc.rows[0].kind as RowKind.ListItem).checked)
        assertEquals(false, (s.doc.rows[1].kind as RowKind.ListItem).checked)
    }

    @Test fun `new item then leaving the list leaves no ghost row and keeps the same field`() {
        val s = session("- a")
        s.at(0)
        val focused = s.focusedId
        repeat(2) { s.enterAt(s.doc.indexOf(s.focusedId), s.text(s.doc.indexOf(s.focusedId)).length) } // new item, then leave the list
        assertEquals(listOf("a", ""), s.texts())
        assertEquals(RowKind.Paragraph, s.doc.rows.last().kind)
        assertEquals(focused, s.focusedId) // the same field the whole time
    }

    // ---- Backspace ----------------------------------------------------------------------------------------------

    @Test fun `Backspace at the start of an empty bullet leaves the list`() {
        val s = session("- a\n- b")
        s.at(1); s.enterAt(1)
        val empty = s.id(2)
        s.backspaceAtStart(empty)
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
        assertEquals(empty, s.focusedId)
    }

    @Test fun `Backspace at the start of a paragraph below a list joins it and the focused row survives`() {
        val s = session("- one\n\ntext")
        val text = s.id(1)
        s.at(1, 0)
        s.backspaceAtStart(text)
        assertEquals(1, s.doc.rows.size)
        assertEquals(text, s.id(0)) // the focused row took the place of the item above it
        assertEquals(text, s.focusedId)
        assertEquals("onetext", s.text(0))
        assertEquals(3, s.cursor!!.start)
        assertTrue(s.doc.rows[0].kind is RowKind.ListItem)
    }

    @Test fun `holding Backspace across a list boundary keeps one focused row all the way`() {
        val s = session("- a\n- b\n\npara")
        val focused = s.id(s.doc.rows.size - 1)
        s.at(s.doc.rows.size - 1, 0)
        val seen = HashSet<Long>()
        // each press: delete a character if there is one before the caret, else Backspace-at-start
        repeat(30) {
            val i = s.doc.indexOf(focused)
            val row = s.doc.rows[i]
            val caret = s.cursor!!.start
            if (caret > 0) s.onText(focused, row.text.text.removeRange(caret - 1, caret), caret - 1) else s.backspaceAtStart(focused)
            seen += s.focusedId
        }
        assertEquals(setOf(focused), seen)
    }

    // ---- Smart checklist ----------------------------------------------------------------------------------------

    private fun smart(md: String) = session(md).also { it.smartChecklist = true }
    private fun EditorSession.tasks() = doc.rows.map { r -> (r.kind as? RowKind.ListItem)?.checked.let { c -> (if (c == true) "x " else "o ") + r.text.text } }

    @Test fun `checking the first item moves it to the bottom`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        s.setChecked(s.id(0), true)
        assertEquals(listOf("o B", "o C", "x A"), s.tasks())
        assertEquals("- [ ] B\n- [ ] C\n- [x] A\n", s.markdown())
    }

    @Test fun `checking a middle item`() {
        val s = smart("- [ ] Milk\n- [ ] Bread\n- [ ] Cheese\n")
        s.setChecked(s.id(1), true)
        assertEquals("- [ ] Milk\n- [ ] Cheese\n- [x] Bread\n", s.markdown())
    }

    @Test fun `checking the last item changes nothing but its state`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        s.setChecked(s.id(2), true)
        assertEquals("- [ ] A\n- [ ] B\n- [x] C\n", s.markdown())
    }

    @Test fun `completion order is kept and unchecking returns to the end of the unchecked group`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        val (a, b) = s.doc.rows.map { it.id }
        s.setChecked(b, true)
        assertEquals(listOf("o A", "o C", "x B"), s.tasks())
        s.setChecked(a, true)
        assertEquals(listOf("o C", "x B", "x A"), s.tasks()) // B then A: the order they were completed in
        s.setChecked(b, false)
        assertEquals(listOf("o C", "o B", "x A"), s.tasks())
        assertEquals("- [ ] C\n- [ ] B\n- [x] A\n", s.markdown())
    }

    @Test fun `unchecking puts the item after the unchecked ones and before the completed ones`() {
        val s = smart("- [ ] Milk\n- [ ] Cheese\n- [x] Bread\n- [x] Eggs\n")
        s.setChecked(s.id(2), false)
        assertEquals("- [ ] Milk\n- [ ] Cheese\n- [ ] Bread\n- [x] Eggs\n", s.markdown())
    }

    @Test fun `rows keep their ids when they move`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        val ids = s.doc.rows.map { it.id }
        s.setChecked(ids[0], true)
        assertEquals(setOf(ids[0], ids[1], ids[2]), s.doc.rows.map { it.id }.toSet())
        assertEquals(listOf(ids[1], ids[2], ids[0]), s.doc.rows.map { it.id })
    }

    @Test fun `a normal task list is never sorted`() {
        val s = session("- [ ] A\n- [ ] B\n- [ ] C\n")
        s.setChecked(s.id(0), true)
        assertEquals("- [x] A\n- [ ] B\n- [ ] C\n", s.markdown())
    }

    @Test fun `the order survives saving and reloading`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        s.setChecked(s.id(0), true)
        val reloaded = EditorDocument.fromMarkdown(s.markdown())
        assertEquals(listOf("B", "C", "A"), reloaded.rows.map { it.text.text })
    }

    @Test fun `ticking and the move are one undo step`() {
        val s = smart("- [ ] A\n- [ ] B\n- [ ] C\n")
        val original = s.markdown()
        s.setChecked(s.id(1), true)
        assertEquals("- [ ] A\n- [ ] C\n- [x] B\n", s.markdown())
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
        assertFalse(s.undo())
        assertTrue(s.redo())
        assertEquals("- [ ] A\n- [ ] C\n- [x] B\n", s.markdown())
    }

    @Test fun `an item takes its nested rows with it`() {
        val s = smart("- [ ] A\n  - [ ] a1\n  - [x] a2\n- [ ] B\n")
        s.setChecked(s.id(0), true)
        assertEquals(listOf("B", "A", "a1", "a2"), s.texts())
        assertEquals(listOf(0, 0, 1, 1), s.doc.rows.map { it.depth })
        assertEquals("- [ ] B\n- [x] A\n  - [ ] a1\n  - [x] a2\n", s.markdown())
    }

    @Test fun `nested checklists sort inside their parent only`() {
        val s = smart("- [ ] Parent\n  - [ ] a\n  - [ ] b\n- [ ] Other\n")
        s.setChecked(s.id(1), true)
        assertEquals(listOf("Parent", "b", "a", "Other"), s.texts())
        assertEquals("- [ ] Parent\n  - [ ] b\n  - [x] a\n- [ ] Other\n", s.markdown())
    }

    @Test fun `a group that mixes plain items and tasks is left alone`() {
        val s = smart("- plain\n- [ ] task\n- [ ] other\n")
        s.setChecked(s.id(1), true)
        assertEquals("- plain\n- [x] task\n- [ ] other\n", s.markdown())
    }

    @Test fun `Enter on a completed item adds the new task above the completed ones`() {
        val s = smart("- [ ] A\n- [x] B\n- [x] C\n")
        s.at(1); s.enterAt(1)
        val rows = s.tasks()
        assertEquals(listOf("o A", "o ", "x B", "x C"), rows)
    }

    @Test fun `switching smart mode on sorts once as one undo step`() {
        val s = session("- [x] A\n- [ ] B\n- [x] C\n- [ ] D\n")
        val original = s.markdown()
        s.smartChecklist = true
        assertTrue(s.sortChecklists())
        assertEquals("- [ ] B\n- [ ] D\n- [x] A\n- [x] C\n", s.markdown())
        assertFalse(s.sortChecklists()) // already sorted
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
    }

    @Test fun `smart sorting leaves other blocks in place`() {
        val s = smart("Intro\n\n- [ ] A\n- [ ] B\n\nOutro\n")
        s.setChecked(s.id(1), true)
        assertEquals("Intro\n\n- [ ] B\n- [x] A\n\nOutro\n", s.markdown())
    }
}

class WhitespacePreservationTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.type(i: Int, text: String) = onText(doc.rows[i].id, text, text.length)

    @Test fun `blank lines around an edited block stay as they were`() {
        val s = session("Intro\n\n\n\nMiddle\n\n\nOutro\n")
        s.type(1, "Middle changed")
        assertEquals("Intro\n\n\n\nMiddle changed\n\n\nOutro\n", s.markdown())
    }

    @Test fun `two edited blocks in a row keep the gap between them`() {
        val s = session("One\n\n\nTwo\n\n\n\nThree\n")
        s.type(0, "One!")
        s.type(1, "Two!")
        assertEquals("One!\n\n\nTwo!\n\n\n\nThree\n", s.markdown())
    }

    @Test fun `an edited list keeps the blank lines before and after it`() {
        val s = session("Intro\n\n\n- a\n- b\n\n\nOutro\n")
        s.type(2, "b!")
        assertEquals("Intro\n\n\n- a\n- b!\n\n\nOutro\n", s.markdown())
    }

    @Test fun `CRLF documents stay CRLF around the edit`() {
        val s = session("One\r\n\r\n\r\nTwo\r\n\r\nThree\r\n")
        s.type(1, "Two!")
        assertEquals("One\r\n\r\n\r\nTwo!\r\n\r\nThree\r\n", s.markdown())
    }

    @Test fun `a single line break between blocks is not carried over to rewritten text`() {
        val s = session("# Title\nbody text\n")
        s.type(1, "body changed")
        // the heading is untouched; the rewritten paragraph is separated safely
        assertEquals("# Title\n\nbody changed\n", s.markdown().replace("\n\n\n", "\n\n"))
    }

    @Test fun `untouched blocks keep their own spelling`() {
        val s = session("* odd\n*   list\n\nText   here\n\n```kotlin\ncode\n```\n")
        s.type(2, "Text   here!")
        assertEquals("* odd\n*   list\n\nText   here!\n\n```kotlin\ncode\n```\n", s.markdown())
    }
}

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
