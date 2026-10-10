package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selections that span several rows: what Copy, Copy as Markdown, Cut and Paste do with them. */
class DocumentSelectionTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.pos(row: Int, offset: Int) = DocumentPosition(doc.rows[row].id, offset)
    private fun EditorSession.sel(a: Int, ao: Int, b: Int, bo: Int) = DocumentSelection(pos(a, ao), pos(b, bo))

    // ---- Selecting ---------------------------------------------------------------------------------------------

    @Test fun `a selection covers the tail of the first row, whole middle rows and the head of the last row`() {
        val s = session("Alpha one\n\nBeta two\n\nGamma three")
        val slices = DocumentSelections.slices(s.doc, s.sel(0, 6, 2, 5))
        assertEquals(listOf(6 to 9, 0 to 8, 0 to 5), slices.map { it.from to it.to })
    }

    @Test fun `backward selections are the same as forward ones`() {
        val s = session("Alpha one\n\nBeta two")
        val forward = DocumentSelections.plainText(s.doc, s.sel(0, 6, 1, 4))
        val backward = DocumentSelections.plainText(s.doc, s.sel(1, 4, 0, 6))
        assertEquals("one\nBeta", forward)
        assertEquals(forward, backward)
    }

    @Test fun `selection select-all covers everything`() {
        val s = session("a\n\nb")
        assertEquals("a\nb", DocumentSelections.plainText(s.doc, DocumentSelections.selectAll(s.doc)!!))
    }

    @Test fun `a collapsed selection copies nothing and deletes nothing`() {
        val s = session("abc")
        val sel = s.sel(0, 1, 0, 1)
        assertEquals("", DocumentSelections.plainText(s.doc, sel))
        assertNull(DocumentSelections.delete(s.doc, sel))
    }

    @Test fun `positions survive rows that move and are repaired when a row is gone`() {
        val s = EditorSession(EditorDocument.fromMarkdown("- [ ] A\n- [ ] B\n- [ ] C\n"), clock = { now })
        s.smartChecklist = true
        val sel = DocumentSelection(DocumentPosition(s.doc.rows[0].id, 0), DocumentPosition(s.doc.rows[1].id, 1))
        s.setChecked(s.doc.rows[0].id, true) // A moves to the end
        // still A..B in logical terms: the selection now runs backwards through the document order
        val (start, end) = DocumentSelections.ordered(s.doc, sel)!!
        assertEquals(s.doc.rows[0].id, start.rowId) // B
        assertEquals(s.doc.rows[2].id, end.rowId) // A
        assertNull(DocumentSelections.validated(s.doc, DocumentSelection(DocumentPosition(999, 0), start)))
        assertEquals(1, DocumentSelections.validated(s.doc, DocumentSelection(DocumentPosition(s.doc.rows[0].id, 99), start))!!.anchor.offset)
    }

    // ---- Plain-text copy ---------------------------------------------------------------------------------------

    @Test fun `copy of a heading and a list is readable text`() {
        val s = session("# Shopping\n\n- One\n- Two\n")
        val text = DocumentSelections.plainText(s.doc, DocumentSelections.selectAll(s.doc)!!)
        assertEquals("Shopping\n• One\n• Two", text)
    }

    @Test fun `copy shows task boxes and numbers`() {
        val s = session("1. one\n2. two\n\n- [ ] Milk\n- [x] Bread\n")
        val text = DocumentSelections.plainText(s.doc, DocumentSelections.selectAll(s.doc)!!)
        assertEquals("1. one\n2. two\n☐ Milk\n☑ Bread", text)
    }

    @Test fun `copy from the middle of a list item to a paragraph`() {
        val s = session("- first item\n\nafter")
        assertEquals("item\nafter", DocumentSelections.plainText(s.doc, s.sel(0, 6, 1, 5)))
    }

    @Test fun `copy within one row is just the text`() {
        val s = session("Hello **bold** world")
        assertEquals("Hello bold", DocumentSelections.plainText(s.doc, s.sel(0, 0, 0, 10)))
    }

    @Test fun `copy across a heading into a paragraph`() {
        val s = session("# Title\n\nbody text")
        assertEquals("tle\nbody", DocumentSelections.plainText(s.doc, s.sel(0, 2, 1, 4)))
    }

    // ---- Markdown copy -----------------------------------------------------------------------------------------

    @Test fun `copy as Markdown keeps headings, lists and tasks`() {
        val s = session("# Shopping\n\n- [ ] Milk\n- [x] Bread\n")
        assertEquals("# Shopping\n\n- [ ] Milk\n- [x] Bread", DocumentSelections.markdown(s.doc, DocumentSelections.selectAll(s.doc)!!))
    }

    @Test fun `copy as Markdown keeps inline formatting`() {
        val s = session("Some **bold** and *italic* text")
        assertEquals("**bold** and *italic*", DocumentSelections.markdown(s.doc, s.sel(0, 5, 0, 20)))
    }

    @Test fun `a piece from the middle of a list item is text not an item`() {
        val s = session("- first item\n- second")
        assertEquals("item\n\n- sec", DocumentSelections.markdown(s.doc, s.sel(0, 6, 1, 3)))
    }

    @Test fun `copy as Markdown of numbered lists and quotes`() {
        val s = session("> quoted\n\n1. a\n2. b\n")
        assertEquals("> quoted\n\n1. a\n2. b", DocumentSelections.markdown(s.doc, DocumentSelections.selectAll(s.doc)!!))
    }

    // ---- Cut ---------------------------------------------------------------------------------------------------

    @Test fun `cut across paragraphs joins the rest and is one undo step`() {
        val s = session("Alpha one\n\nBeta two\n\nGamma three\n")
        val original = s.markdown()
        assertTrue(s.deleteSelection(s.sel(0, 6, 2, 6)))
        assertEquals("Alpha three\n", s.markdown())
        assertEquals(s.doc.rows[0].id, s.cursor!!.rowId)
        assertEquals(6, s.cursor!!.start)
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
        assertFalse(s.undo())
        assertTrue(s.redo())
        assertEquals("Alpha three\n", s.markdown())
    }

    @Test fun `cut from a paragraph into a list leaves a valid list`() {
        val s = session("intro text\n\n- a\n- b\n- c\n")
        s.deleteSelection(s.sel(0, 5, 2, 1)) // from "text" to the end of the second item
        assertEquals("intro\n\n- c\n", s.markdown().replace("intro \n", "intro\n"))
        assertTrue(s.doc.rows.drop(1).all { it.kind is RowKind.ListItem })
    }

    @Test fun `cut from a list item to a paragraph`() {
        val s = session("- one\n- two\n\nafter\n")
        s.deleteSelection(s.sel(1, 1, 2, 2))
        assertEquals("- one\n- tter\n", s.markdown())
    }

    @Test fun `cut a whole heading and paragraph leaves the rest`() {
        val s = session("# Title\n\nbody\n\nrest\n")
        s.deleteSelection(s.sel(0, 0, 1, 4))
        assertEquals("rest\n", s.markdown())
        assertEquals(1, s.doc.rows.size)
    }

    @Test fun `cut a whole checklist keeps one paragraph to type into`() {
        val s = session("- [ ] a\n- [x] b\n")
        assertTrue(s.deleteSelection(DocumentSelections.selectAll(s.doc)!!))
        assertEquals(1, s.doc.rows.size)
        assertEquals("", s.markdown().trim())
        s.undo()
        assertEquals("- [ ] a\n- [x] b\n", s.markdown())
    }

    @Test fun `a rule between the ends is removed with the rest`() {
        val s = session("one\n\n---\n\ntwo\n")
        s.deleteSelection(s.sel(0, 1, 2, 1))
        assertEquals(listOf("owo"), s.doc.rows.map { it.text.text }.filter { it.isNotEmpty() })
        assertTrue(s.doc.rows.none { it.kind == RowKind.Rule })
    }

    @Test fun `code and raw rows at the edge are not merged with text`() {
        val s = session("text\n\n```\ncode line\n```\n")
        s.deleteSelection(s.sel(0, 2, 1, 4))
        val texts = s.doc.rows.map { it.text.text }
        assertEquals(listOf("te", " line"), texts.take(2))
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
        assertTrue(s.doc.rows[1].kind is RowKind.Code)
        assertTrue(s.markdown().contains("```"))
    }

    @Test fun `cut keeps untouched content byte for byte`() {
        val s = session("* odd\n*   list\n\nmiddle one\n\nmiddle two\n\n```kotlin\nkeep\n```\n")
        s.deleteSelection(s.sel(2, 7, 3, 7))
        assertTrue(s.markdown().startsWith("* odd\n*   list\n\n"))
        assertTrue(s.markdown().endsWith("```kotlin\nkeep\n```\n"))
    }

    // ---- Paste over a selection --------------------------------------------------------------------------------

    @Test fun `paste over a selection replaces it with plain text in one step`() {
        val s = session("Alpha one\n\nBeta two\n")
        val original = s.markdown()
        assertTrue(s.replaceSelection(s.sel(0, 6, 1, 4), "# not a heading"))
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind) // pasted Markdown stays text
        assertEquals("Alpha # not a heading two", s.doc.rows[0].text.text)
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
    }

    @Test fun `paste with blank lines over a selection makes paragraphs`() {
        val s = session("abc\n\ndef")
        s.replaceSelection(s.sel(0, 1, 1, 1), "X\n\nY")
        assertEquals(listOf("aX", "Yef"), s.doc.rows.map { it.text.text })
    }

    @Test fun `random selections never break the document`() {
        val md = "# T\n\nOne **two** three\n\n- a\n  - b\n- [ ] c\n- [x] d\n\n> q\n\n---\n\n```\ncode\n```\n\nlast\n"
        val r = kotlin.random.Random(7)
        repeat(300) {
            val s = session(md)
            val rows = s.doc.rows
            val a = rows[r.nextInt(rows.size)]
            val b = rows[r.nextInt(rows.size)]
            fun off(row: EditorRow) = r.nextInt((if (row.kind == RowKind.Rule) 1 else row.text.length) + 1)
            val sel = DocumentSelection(DocumentPosition(a.id, off(a)), DocumentPosition(b.id, off(b)))
            val text = DocumentSelections.plainText(s.doc, sel)
            val markdown = DocumentSelections.markdown(s.doc, sel)
            assertNotNull(text); assertNotNull(markdown)
            s.deleteSelection(sel)
            val ids = s.doc.rows.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(s.doc.rows.isNotEmpty())
            val reloaded = EditorDocument.fromMarkdown(s.markdown()) // always writable and readable
            assertNotNull(reloaded)
            if (s.history.canUndo) { s.undo(); assertEquals(md, s.markdown()) }
        }
    }

    // ---- Paste as Markdown -------------------------------------------------------------------------------------

    @Test fun `paste as Markdown inserts blocks below the caret and is one undo step`() {
        val s = session("first\n\nlast\n")
        val original = s.markdown()
        s.moveCursor(Cursor(s.doc.rows[0].id, 5))
        assertTrue(s.pasteMarkdown("## Pasted\n\n- [ ] a\n- [x] b\n"))
        assertEquals("first\n\n## Pasted\n\n- [ ] a\n- [x] b\n\nlast\n", s.markdown())
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
    }

    @Test fun `paste as Markdown into an empty paragraph replaces it`() {
        val s = session("")
        s.moveCursor(Cursor(s.doc.rows[0].id, 0))
        s.pasteMarkdown("# Title\n\ntext")
        assertEquals("# Title\n\ntext\n", s.markdown())
    }

    @Test fun `paste as Markdown keeps separate lists apart from existing ones`() {
        val s = session("- one\n")
        s.moveCursor(Cursor(s.doc.rows[0].id, 3))
        s.pasteMarkdown("1. x\n2. y\n")
        assertEquals(listOf("one", "x", "y"), s.doc.rows.map { it.text.text })
        assertEquals("- one\n\n1. x\n2. y\n", s.markdown())
    }

    @Test fun `paste as Markdown over a selection replaces it`() {
        val s = session("Alpha one\n\nBeta two\n")
        assertTrue(s.pasteMarkdown("**new**", s.sel(0, 6, 1, 4)))
        assertTrue(s.markdown().contains("**new**"))
        assertTrue(s.undo())
        assertEquals("Alpha one\n\nBeta two\n", s.markdown())
    }

    @Test fun `plain paste never interprets Markdown but paste as Markdown does`() {
        val s = session("x")
        s.onText(s.doc.rows[0].id, "x# h", 4)
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
        s.moveCursor(Cursor(s.doc.rows[0].id, 4))
        s.pasteMarkdown("# h")
        assertTrue(s.doc.rows.any { it.kind == RowKind.Heading(1) })
    }
}
