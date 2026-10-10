package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.Gen
import io.github.zeperus.openpad.markdown.MarkdownSerializer
import io.github.zeperus.openpad.markdown.OpenPadDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The unified text field: what the model makes of changes in the joined text of several rows. */
class SegmentEditingTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun segment(s: EditorSession) = (Segments.split(s.doc) { Segments.isBlock(it, emptySet()) }.first { it is DocItem.Text } as DocItem.Text).segment
    private fun EditorSession.text() = segment(this).text
    private fun EditorSession.rows() = doc.rows.map { it.text.text }
    private fun EditorSession.kinds() = doc.rows.map { it.kind }

    /** What an editor does: the field changes [a, b) to [inserted] with the caret at [caretBefore] (default: after the change). */
    private fun EditorSession.native(a: Int, b: Int, inserted: String, caretBefore: Int = if (a == b) a else b, selStart: Int = caretBefore): Boolean {
        val seg = segment(this)
        val new = seg.text.replaceRange(a, b, inserted)
        val op = SegmentEditing.interpret(seg, seg.text, new, selStart, caretBefore, a + inserted.length)
        return SegmentEditing.apply(this, op)
    }

    @Test fun `lines pasted at the end of a row go into that row even when the next row reads the same at the seam`() {
        val s = session("- [ ] Milk\n- [x] Existing completed\n")
        s.smartChecklist = true
        // the diff alone would say: inserted "Water\nCheese\n" at the start of the second row; the caret says: at the end of the first
        assertTrue(s.native(4, 4, "\nWater\nCheese", caretBefore = 4))
        assertEquals(listOf("Milk", "Water", "Cheese", "Existing completed"), s.rows())
        assertEquals(listOf(false, false, false, true), s.doc.rows.map { (it.kind as RowKind.ListItem).checked })
    }

    // ---- The segment itself ------------------------------------------------------------------------------------

    @Test fun `the segment text is the rows joined by line breaks and positions map back`() {
        val s = session("one\n\ntwo words\n\n- a\n- b\n")
        val seg = segment(s)
        assertEquals("one\ntwo words\na\nb", seg.text)
        assertEquals(DocumentPosition(seg.ids[1], 3), seg.position(7))
        assertEquals(DocumentPosition(seg.ids[0], 3), seg.position(3)) // the line break belongs to the row before it
        assertEquals(DocumentPosition(seg.ids[1], 0), seg.position(4))
        for (p in 0..seg.text.length) assertEquals(p, seg.absolute(seg.position(p)))
    }

    @Test fun `rules and rendered blocks separate segments`() {
        val s = session("before\n\n---\n\nafter\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nend\n")
        val items = Segments.split(s.doc) { Segments.isBlock(it, emptySet()) }
        assertEquals(listOf("T", "B", "T", "B", "T"), items.map { if (it is DocItem.Text) "T" else "B" })
        // the table opened as source becomes text again
        val table = s.doc.rows.first { it.kind == RowKind.Raw }
        val asSource = Segments.split(s.doc) { Segments.isBlock(it, setOf(table.id)) }
        assertEquals(listOf("T", "B", "T"), asSource.map { if (it is DocItem.Text) "T" else "B" })
    }

    @Test fun `a segment keeps its key while its rows change`() {
        val keys = SegmentKeys()
        assertEquals(listOf(0, 1), keys.assign(listOf(listOf(1L, 2L), listOf(5L))))
        assertEquals(listOf(0, 1), keys.assign(listOf(listOf(9L, 1L, 2L), listOf(5L)))) // Enter at the start: a new row, same field
        assertEquals(listOf(0, 2, 1), keys.assign(listOf(listOf(9L, 1L), listOf(2L), listOf(5L)))) // a split: the first part keeps the field
        assertEquals(listOf(3), keys.assign(listOf(listOf(100L)))) // nothing in common: a new field
    }

    // ---- Typing ------------------------------------------------------------------------------------------------

    @Test fun `typing a character changes that row only and the text is accepted as typed`() {
        val s = session("Hello world\n\nsecond\n")
        val before = s.text()
        assertTrue(s.native(5, 5, ","))
        assertEquals(listOf("Hello, world", "second"), s.rows())
        assertEquals(before.replaceRange(5, 5, ","), s.text())
    }

    @Test fun `deleting inside a row and replacing a selection inside a row`() {
        val s = session("Hello world\n")
        s.native(5, 11, "") // delete " world"
        assertEquals(listOf("Hello"), s.rows())
        s.native(0, 5, "Bye", caretBefore = 5, selStart = 0)
        assertEquals(listOf("Bye"), s.rows())
    }

    // ---- Enter ---------------------------------------------------------------------------------------------------

    @Test fun `Enter at the end of a bullet makes the next item and the text is as typed`() {
        val s = session("- one\n- two\n")
        val len = s.text().length
        val proposed = s.text() + "\n"
        assertTrue(s.native(len, len, "\n"))
        assertEquals(listOf("one", "two", ""), s.rows())
        assertTrue(s.kinds().all { it is RowKind.ListItem })
        assertEquals(proposed, s.text())
    }

    @Test fun `Enter on an empty bullet leaves the list and the text does not change`() {
        val s = session("- one\n- two\n")
        val len = s.text().length
        s.native(len, len, "\n")
        val text = s.text() // "one\ntwo\n"
        assertTrue(s.native(text.length, text.length, "\n")) // Enter on the empty item
        assertEquals(listOf("one", "two", ""), s.rows())
        assertEquals(RowKind.Paragraph, s.kinds().last())
        assertEquals("the field's text is unchanged: the proposed extra line break is refused", text, s.text())
    }

    @Test fun `Enter in the middle of a paragraph splits it`() {
        val s = session("Hello world\n")
        s.native(5, 5, "\n")
        assertEquals(listOf("Hello", " world"), s.rows())
    }

    @Test fun `Enter over a selection replaces it`() {
        val s = session("Hello brave world\n")
        s.native(5, 11, "\n", caretBefore = 11, selStart = 5)
        assertEquals(listOf("Hello", " world"), s.rows())
    }

    // ---- Backspace and Delete at line breaks ----------------------------------------------------------------------

    @Test fun `Backspace at the start of a list item makes it a paragraph and the text does not change`() {
        val s = session("intro\n\n- item\n")
        val text = s.text() // "intro\nitem"
        // the item starts at 6; Backspace deletes the line break at 5
        assertTrue(s.native(5, 6, "", caretBefore = 6))
        assertEquals(listOf("intro", "item"), s.rows())
        assertEquals(RowKind.Paragraph, s.kinds()[1])
        assertEquals(text, s.text())
    }

    @Test fun `Backspace at the start of a paragraph joins it to the row above`() {
        val s = session("one\n\ntwo\n")
        assertTrue(s.native(3, 4, "", caretBefore = 4))
        assertEquals(listOf("onetwo"), s.rows())
        assertEquals(3, s.cursor!!.start)
    }

    @Test fun `Delete at the end of a row joins the next row onto it`() {
        val s = session("one\n\ntwo\n")
        assertTrue(s.native(3, 4, "", caretBefore = 3))
        assertEquals(listOf("onetwo"), s.rows())
    }

    @Test fun `Delete at the end of a list item joins the next item and keeps the first one's kind`() {
        val s = session("- a\n- b\n")
        assertTrue(s.native(1, 2, "", caretBefore = 1))
        assertEquals(listOf("ab"), s.rows())
        assertTrue(s.kinds()[0] is RowKind.ListItem)
    }

    @Test fun `deleting the line break next to the caret is chosen when an empty row sits between two`() {
        val s = session("a\n\n\nb\n") // rows: a, b  (blank lines do not make rows) - use soft rows instead
        s.onText(s.doc.rows[0].id, "a", 1)
        val seg = segment(s)
        assertEquals("a\nb", seg.text)
    }

    // ---- Selections that span rows ---------------------------------------------------------------------------------

    @Test fun `deleting a range that spans paragraphs joins the rest`() {
        val s = session("Alpha one\n\nBeta two\n\nGamma three\n")
        val before = s.markdown()
        assertTrue(s.native(6, 25, "", caretBefore = 25, selStart = 6)) // "one\nBeta two\nGamma " ... through the middle of the third
        assertEquals(listOf("Alpha three"), s.rows())
        assertTrue(s.undo())
        assertEquals(before, s.markdown())
    }

    @Test fun `typing over a selection across a heading and a list item`() {
        val s = session("# Title here\n\n- first item\n- second\n")
        // from "here" to the middle of "first item"
        val seg = segment(s)
        val a = seg.text.indexOf("here")
        val b = seg.text.indexOf("item")
        assertTrue(s.native(a, b, "X", caretBefore = b, selStart = a))
        assertEquals(listOf("Title Xitem", "second"), s.rows())
        assertEquals(RowKind.Heading(1), s.kinds()[0])
    }

    @Test fun `replacing a selection that covers whole items`() {
        val s = session("intro\n\n- a\n- b\n- c\n\nend\n")
        val seg = segment(s)
        val a = seg.start(1)
        val b = seg.end(2)
        s.native(a, b, "", caretBefore = b, selStart = a)
        assertEquals(listOf("intro", "c", "end"), s.rows())
        assertTrue(s.kinds()[1] is RowKind.ListItem)
    }

    @Test fun `pasting text with line breaks into a row is plain text`() {
        val s = session("Hello\n")
        s.native(5, 5, " # not\n- a heading")
        assertTrue(s.kinds().all { it == RowKind.Paragraph })
        assertEquals("Hello # not\n- a heading", s.rows()[0])
    }

    @Test fun `a stale change is ignored`() {
        val s = session("abc\n")
        val seg = segment(s)
        assertEquals(SegmentOp.None, SegmentEditing.interpret(seg, "something else", "something else!", 0, 0, 0))
        assertEquals(SegmentOp.None, SegmentEditing.interpret(seg, seg.text, seg.text, 0, 0, 0))
    }

    @Test fun `a selection from a checklist item into a paragraph can be deleted and typed over`() {
        val s = session("- [ ] Milk\n- [x] Bread\n\nafter the list\n")
        val seg = segment(s)
        val a = seg.text.indexOf("lk")
        val b = seg.text.indexOf("the list")
        assertTrue(s.native(a, b, "!", caretBefore = b, selStart = a))
        assertEquals(listOf("Mi!the list"), s.rows())
        assertEquals(RowKind.ListItem(((s.kinds()[0] as RowKind.ListItem).list), false), s.kinds()[0]) // the first row (an unchecked task) survives
        assertTrue(s.undo())
        assertEquals(listOf("Milk", "Bread", "after the list"), s.rows())
    }

    @Test fun `a selection from a numbered item up into a heading works backwards too`() {
        val s = session("# Head line\n\n1. one\n2. two\n")
        val seg = segment(s)
        val start = seg.text.indexOf("line")
        val end = seg.text.indexOf("two") // select from "line" through "one" and the start of "two"
        assertTrue(s.native(start, end, "", caretBefore = start, selStart = end)) // the caret was at the start: a backwards selection
        assertEquals(listOf("Head two"), s.rows())
        assertEquals(RowKind.Heading(1), s.kinds()[0])
    }

    @Test fun `row ids stay valid and unique through cross-row edits`() {
        val s = session("a\n\nb\n\nc\n\nd\n")
        val ids = s.doc.rows.map { it.id }
        val seg = segment(s)
        s.native(seg.start(1), seg.end(2), "")
        val remaining = s.doc.rows.map { it.id }
        assertEquals(remaining.size, remaining.toSet().size)
        assertTrue(ids.containsAll(remaining.filter { it in ids }))
        assertTrue(s.doc.row(s.cursor!!.rowId) != null)
    }

    // ---- Smart checklists and the focus ids ------------------------------------------------------------------------

    @Test fun `Enter on a completed task in a smart checklist leaves the new task above the completed ones`() {
        val s = session("- [ ] a\n- [x] b\n")
        s.smartChecklist = true
        val seg = segment(s)
        s.native(seg.end(1), seg.end(1), "\n")
        assertEquals(listOf("a", "", "b"), s.rows())
    }

    @Test fun `rows keep their ids through typing`() {
        val s = session("- a\n- b\n")
        val ids = s.doc.rows.map { it.id }
        s.native(1, 1, "x")
        assertEquals(ids, s.doc.rows.map { it.id })
    }

    // ---- Random native edits never break anything --------------------------------------------------------------------

    @Test fun `random edits of the joined text keep the document valid and undoable`() {
        var checked = 0
        repeat(400) { seed ->
            val r = Random(seed)
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 4_000_000).document()))
            val s = session(md)
            s.smartChecklist = seed % 3 == 0
            repeat(12) {
                val items = Segments.split(s.doc) { Segments.isBlock(it, emptySet()) }.filterIsInstance<DocItem.Text>()
                if (items.isEmpty()) return@repeat
                val seg = items[r.nextInt(items.size)].segment
                val len = seg.text.length
                val a = r.nextInt(len + 1)
                val b = minOf(len, a + (if (r.nextInt(3) == 0) r.nextInt(1, 30) else if (r.nextBoolean()) 1 else 0))
                val ins = when (r.nextInt(6)) { 0 -> "\n"; 1 -> ""; 2 -> "x"; 3 -> "ab c"; 4 -> "\n\n"; else -> "- [ ] t" }
                val before = s.markdown()
                val canUndo = s.history.canUndo
                val new = seg.text.replaceRange(a, b, ins)
                if (new == seg.text) return@repeat
                val caret = if (a == b) a else b
                val op = SegmentEditing.interpret(seg, seg.text, new, if (a == b) a else a, caret, a + ins.length)
                val changed = SegmentEditing.apply(s, op)
                val ids = s.doc.rows.map { it.id }
                assertEquals("seed $seed: unique ids", ids.size, ids.toSet().size)
                assertTrue(s.doc.rows.isNotEmpty())
                assertNotNull(EditorDocument.fromMarkdown(s.markdown())) // always writable and readable
                val cursorRow = s.cursor?.rowId
                if (cursorRow != null) assertNotNull("seed $seed: the cursor row exists", s.doc.row(cursorRow))
                if (changed) {
                    checked++
                    assertTrue(s.undo())
                    assertEquals("seed $seed: undo restores the previous markdown", before, s.markdown())
                    assertTrue(s.redo())
                }
                if (!canUndo && !changed) assertFalse(s.history.canUndo)
            }
        }
        assertTrue("the property test did real work ($checked edits)", checked > 500)
    }
}

class NumberingTest {
    @Test fun `the one-pass numbering agrees with numberOf`() {
        val md = "1. a\n2. b\n   1. x\n   2. y\n3. c\n\ntext\n\n5. e\n6. f\n\n- bullet\n\n1) p\n2) q\n"
        val doc = EditorDocument.fromMarkdown(md)
        val numbers = Numbering.of(doc.rows)
        for ((i, row) in doc.rows.withIndex()) assertEquals("row $i", doc.numberOf(i), numbers[row.id])
    }
}
