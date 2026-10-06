package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.CodeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every keyboard and formatting-bar action, checked by the Markdown it produces. */
class EditorOpsTest {
    private var now = 0L

    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })

    private fun EditorSession.id(i: Int) = doc.rows[i].id
    private fun EditorSession.text(i: Int) = doc.rows[i].text.text
    private fun EditorSession.type(i: Int, text: String, caret: Int = text.length) = onText(id(i), text, caret)
    private fun EditorSession.select(i: Int, start: Int, end: Int = start) = moveCursor(Cursor(id(i), start, end))
    private fun EditorSession.enterAt(i: Int, offset: Int) {
        val t = text(i)
        onText(id(i), t.substring(0, offset) + "\n" + t.substring(offset), offset + 1)
    }

    // ---- Typing ----------------------------------------------------------------------------------------------

    @Test fun `typing into a paragraph`() {
        val s = session("Hello")
        s.type(0, "Hello world")
        assertEquals("Hello world\n", s.markdown())
    }

    @Test fun `typing in the middle and deleting`() {
        val s = session("Hello world")
        s.type(0, "Hello big world")
        assertEquals("Hello big world\n", s.markdown())
        s.type(0, "Hello world")
        assertEquals("Hello world", s.markdown()) // equal to the original again: written back as it was
    }

    @Test fun `unicode german umlauts and emoji`() {
        val s = session("")
        s.type(0, "Größe 😀 日本語 äöüß")
        assertEquals("Größe 😀 日本語 äöüß\n", s.markdown())
    }

    @Test fun `text that looks like markdown stays literal text`() {
        val s = session("")
        s.type(0, "# not a heading")
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
        assertEquals("\\# not a heading\n", s.markdown())
        s.type(0, "**not bold** and - not a list")
        assertEquals("\\*\\*not bold\\*\\* and - not a list\n", s.markdown())
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
    }

    @Test fun `typing a hash after the words of a heading is kept as text`() {
        val s = session("# Title")
        s.type(0, "Title #1")
        assertEquals("# Title #1\n", s.markdown())
    }

    @Test fun `pasted text with line breaks stays in one paragraph`() {
        val s = session("")
        s.type(0, "line one\nline two\nline three")
        assertEquals(1, s.doc.rows.size)
        assertEquals("line one\nline two\nline three\n", s.markdown())
    }

    @Test fun `pasted markdown is ordinary text and is not interpreted`() {
        val s = session("")
        s.type(0, "# Heading\n\n- item\n- [ ] task\n\n**bold**")
        // blank lines separate paragraphs, nothing is turned into a heading, list or formatting
        assertEquals(listOf(RowKind.Paragraph, RowKind.Paragraph, RowKind.Paragraph), s.doc.rows.take(3).map { it.kind })
        assertEquals(listOf("# Heading", "- item\n- [ ] task", "**bold**"), s.doc.rows.take(3).map { it.text.text })
        // every markdown character is escaped, so reading the file back gives the same text
        val back = EditorDocument.fromMarkdown(s.markdown()).rows.take(3)
        assertEquals(listOf("# Heading", "- item\n- [ ] task", "**bold**"), back.map { it.text.text })
        assertEquals(listOf(RowKind.Paragraph, RowKind.Paragraph, RowKind.Paragraph), back.map { it.kind })
    }

    @Test fun `pasting several paragraphs into the middle of a paragraph`() {
        val s = session("startend")
        s.type(0, "startone\n\ntwo\n\nthreeend", caret = 20)
        assertEquals(listOf("startone", "two", "threeend"), s.doc.rows.take(3).map { it.text.text })
        assertEquals(s.id(2), s.cursor!!.rowId)
        assertEquals(5, s.cursor!!.start)
        assertEquals("startone\n\ntwo\n\nthreeend\n", s.markdown())
    }

    // ---- Enter -----------------------------------------------------------------------------------------------

    @Test fun `enter in a paragraph splits it`() {
        val s = session("Hello world")
        s.enterAt(0, 5)
        assertEquals(listOf("Hello", " world"), s.doc.rows.take(2).map { it.text.text })
        assertEquals("Hello\n\n world\n".replace(" world", "world"), s.markdown())
        assertEquals(s.id(1), s.cursor!!.rowId)
        assertEquals(0, s.cursor!!.start)
    }

    @Test fun `enter at the end creates an empty paragraph that is not written`() {
        val s = session("Hello")
        s.enterAt(0, 5)
        assertEquals(2, s.doc.rows.size)
        assertEquals("Hello", s.markdown()) // the empty paragraph adds nothing: still the original
    }

    @Test fun `enter after a heading continues with a paragraph`() {
        val s = session("# Title")
        s.enterAt(0, 5)
        assertEquals(RowKind.Heading(1), s.doc.rows[0].kind)
        assertEquals(RowKind.Paragraph, s.doc.rows[1].kind)
        s.type(1, "body")
        assertEquals("# Title\n\nbody\n", s.markdown())
    }

    @Test fun `enter at the start of a heading pushes it down`() {
        val s = session("# Title")
        s.enterAt(0, 0)
        assertEquals(listOf(RowKind.Paragraph, RowKind.Heading(1)), s.doc.rows.take(2).map { it.kind })
        assertEquals("# Title", s.markdown())
    }

    @Test fun `enter in a list item creates the next item`() {
        val s = session("- Milk\n- Bread")
        s.enterAt(1, 5)
        s.type(2, "Cheese")
        assertEquals("- Milk\n- Bread\n- Cheese\n", s.markdown())
    }

    @Test fun `enter in the middle of a list item splits it`() {
        val s = session("- MilkBread")
        s.enterAt(0, 4)
        assertEquals("- Milk\n- Bread\n", s.markdown())
    }

    @Test fun `enter on an empty list item leaves the list`() {
        val s = session("- a\n- b")
        s.enterAt(1, 1)  // new empty item after b
        s.enterAt(2, 0)  // enter on the empty item
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
        s.type(2, "after")
        assertEquals("- a\n- b\n\nafter\n", s.markdown())
    }

    @Test fun `enter on an empty nested item goes up one level`() {
        val s = session("- a\n  - b")
        s.enterAt(1, 1)
        assertEquals(1, s.doc.rows[2].depth)
        s.enterAt(2, 0)
        assertEquals(0, s.doc.rows[2].depth)
        s.type(2, "c")
        assertEquals("- a\n  - b\n- c\n", s.markdown())
    }

    @Test fun `enter in a task item creates an unchecked task`() {
        val s = session("- [x] done")
        s.enterAt(0, 4)
        s.type(1, "next")
        assertEquals("- [x] done\n- [ ] next\n", s.markdown())
    }

    @Test fun `enter in a numbered list continues the numbering`() {
        val s = session("1. one\n2. two")
        s.enterAt(1, 3)
        s.type(2, "three")
        assertEquals("1. one\n2. two\n3. three\n", s.markdown())
    }

    @Test fun `enter in a quote continues it and an empty quote line leaves it`() {
        val s = session("> first")
        s.enterAt(0, 5)
        s.type(1, "second")
        assertEquals("> first\n>\n> second\n", s.markdown())
        s.enterAt(1, 6)
        s.enterAt(2, 0)
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
        s.type(2, "outside")
        assertEquals("> first\n>\n> second\n\noutside\n", s.markdown())
    }

    @Test fun `enter in code inserts a line break and a second enter at the end leaves the block`() {
        val s = session("```\nline\n```\n")
        s.enterAt(0, 4)
        assertEquals("line\n", s.text(0))
        s.enterAt(0, 5)
        assertEquals("line", s.text(0))
        assertEquals(RowKind.Paragraph, s.doc.rows[1].kind)
        s.type(1, "after")
        assertEquals("```\nline\n```\n\nafter\n", s.markdown())
    }

    @Test fun `code keeps its whitespace and markdown-looking text`() {
        val s = session("```\n\n```\n")
        s.type(0, "  indented\n# not heading\n**not bold**\n- not list\n")
        assertEquals("```\n  indented\n# not heading\n**not bold**\n- not list\n\n```\n", s.markdown())
    }

    // ---- Backspace -------------------------------------------------------------------------------------------

    @Test fun `backspace at the start of a paragraph joins it with the previous one`() {
        val s = session("first\n\nsecond")
        s.backspaceAtStart(s.id(1))
        assertEquals("firstsecond\n", s.markdown())
        assertEquals(5, s.cursor!!.start)
    }

    @Test fun `backspace at the start of an empty list item turns it into a paragraph`() {
        val s = session("- a\n- b")
        s.enterAt(1, 1)
        s.backspaceAtStart(s.id(2))
        assertEquals(RowKind.Paragraph, s.doc.rows[2].kind)
    }

    @Test fun `backspace at the start of a list item removes the bullet first`() {
        val s = session("- a\n- b")
        s.backspaceAtStart(s.id(1))
        assertEquals(RowKind.Paragraph, s.doc.rows[1].kind)
        assertEquals("b", s.text(1))
        assertEquals("- a\n\nb\n", s.markdown())
    }

    @Test fun `backspace at the start of a nested item moves it out one level`() {
        val s = session("- a\n  - b")
        s.backspaceAtStart(s.id(1))
        assertEquals(0, s.doc.rows[1].depth)
        assertEquals("- a\n- b\n", s.markdown())
    }

    @Test fun `backspace at the start of a heading or quote makes it a paragraph`() {
        val h = session("# Title")
        h.backspaceAtStart(h.id(0))
        assertEquals("Title\n", h.markdown())
        val q = session("> quoted")
        q.backspaceAtStart(q.id(0))
        assertEquals("quoted\n", q.markdown())
    }

    @Test fun `backspace after a rule deletes the rule`() {
        val s = session("above\n\n---\n\nbelow")
        val below = s.doc.rows.indexOfFirst { it.text.text == "below" }
        s.backspaceAtStart(s.id(below))
        assertEquals("above\n\nbelow", s.markdown())
    }

    @Test fun `backspace on the very first row does nothing`() {
        val s = session("only")
        assertFalse(s.backspaceAtStart(s.id(0)))
        assertEquals("only", s.markdown())
    }

    @Test fun `backspace never merges text into a code block`() {
        val s = session("```\ncode\n```\n\ntext")
        val text = s.doc.rows.indexOfFirst { it.text.text == "text" }
        s.backspaceAtStart(s.id(text))
        assertEquals("code", s.text(0))
        assertEquals("text", s.text(text))
    }

    // ---- Inline formatting -----------------------------------------------------------------------------------

    @Test fun `bold on a selection`() {
        val s = session("make bold now")
        s.select(0, 5, 9)
        s.toggleStyle(SpanKind.Bold)
        assertEquals("make **bold** now\n", s.markdown())
    }

    @Test fun `italic strike and code on selections`() {
        val s = session("a b c d")
        s.select(0, 0, 1); s.toggleStyle(SpanKind.Italic)
        s.select(0, 2, 3); s.toggleStyle(SpanKind.Strike)
        s.select(0, 4, 5); s.toggleStyle(SpanKind.Code)
        assertEquals("*a* ~~b~~ `c` d\n", s.markdown())
    }

    @Test fun `toggling again removes the formatting`() {
        val s = session("make bold now")
        s.select(0, 5, 9)
        s.toggleStyle(SpanKind.Bold)
        s.toggleStyle(SpanKind.Bold)
        assertEquals("make bold now", s.markdown())
    }

    @Test fun `bold and italic on the same words`() {
        val s = session("both words")
        s.select(0, 0, 4)
        s.toggleStyle(SpanKind.Bold); s.toggleStyle(SpanKind.Italic)
        assertEquals("***both*** words\n", s.markdown())
    }

    @Test fun `partly bold selection becomes fully bold`() {
        val s = session("**ab**cd")
        s.select(0, 0, 4)
        s.toggleStyle(SpanKind.Bold)
        assertEquals("**abcd**\n", s.markdown())
    }

    @Test fun `bold with spaces in the selection keeps valid markdown`() {
        val s = session("x one two y")
        s.select(0, 1, 9) // " one two"
        s.toggleStyle(SpanKind.Bold)
        assertEquals("x **one two** y\n", s.markdown())
    }

    @Test fun `typing style toggled without a selection applies to the next typed text`() {
        val s = session("Hello ")
        s.select(0, 6)
        s.toggleStyle(SpanKind.Bold)
        s.type(0, "Hello world")
        assertEquals("Hello **world**\n", s.markdown())
    }

    @Test fun `typing after bold text continues the bold style`() {
        val s = session("**bold**")
        s.select(0, 4)
        s.type(0, "boldx", 5)
        assertEquals("**boldx**\n", s.markdown())
    }

    @Test fun `typing after inline code leaves the code`() {
        val s = session("`code`")
        s.select(0, 4)
        s.type(0, "codex", 5)
        assertEquals("`code`x\n", s.markdown())
    }

    @Test fun `formatting the first words of a paragraph leaves the rest untouched`() {
        val s = session("Intro\n\n__keep__ me   as is\n")
        s.select(0, 0, 5)
        s.toggleStyle(SpanKind.Bold)
        assertEquals("**Intro**\n\n__keep__ me   as is\n", s.markdown())
    }

    @Test fun `link on a selection and its removal`() {
        val s = session("see the site")
        s.select(0, 8, 12)
        s.setLink("https://example.org")
        assertEquals("see the [site](https://example.org)\n", s.markdown())
        assertEquals("https://example.org", s.linkAtCaret()?.href)
        s.removeLink()
        assertEquals("see the site", s.markdown())
    }

    @Test fun `changing a link while the caret is inside it`() {
        val s = session("[site](https://old.org)")
        s.select(0, 2)
        assertEquals("https://old.org", s.linkAtCaret()?.href)
        s.setLink("https://new.org")
        assertEquals("[site](https://new.org)\n", s.markdown())
    }

    @Test fun `link text can be formatted and edited`() {
        val s = session("[site](https://x.org)")
        s.select(0, 0, 4)
        s.toggleStyle(SpanKind.Bold)
        s.type(0, "siXte", 3) // typed inside the link text
        assertEquals("[**siXte**](https://x.org)\n", s.markdown())
    }

    @Test fun `formatting does nothing in code and raw rows`() {
        val s = session("```\ncode\n```\n")
        s.select(0, 0, 4)
        assertFalse(s.toggleStyle(SpanKind.Bold))
        assertEquals("```\ncode\n```\n", s.markdown())
    }

    // ---- Block formatting ------------------------------------------------------------------------------------

    @Test fun `heading levels`() {
        for (level in 1..6) {
            val s = session("Title")
            s.select(0, 0)
            s.setKind(RowKind.Heading(level))
            assertEquals("#".repeat(level) + " Title\n", s.markdown())
        }
    }

    @Test fun `heading back to normal text`() {
        val s = session("# Title")
        s.select(0, 0)
        s.setKind(RowKind.Paragraph)
        assertEquals("Title\n", s.markdown())
    }

    @Test fun `quote and code`() {
        val q = session("text"); q.select(0, 0); q.setKind(RowKind.Quote)
        assertEquals("> text\n", q.markdown())
        val c = session("text"); c.select(0, 0); c.setKind(RowKind.Code(null, CodeStyle.Fenced))
        assertEquals("```\ntext\n```\n", c.markdown())
    }

    @Test fun `a formatted paragraph becomes plain code`() {
        val s = session("a **b** c")
        s.select(0, 0)
        s.setKind(RowKind.Code(null, CodeStyle.Fenced))
        assertEquals("```\na b c\n```\n", s.markdown())
    }

    @Test fun `bullet list from several paragraphs joins into one list`() {
        val s = session("a\n\nb\n\nc")
        for (i in 0..2) { s.select(i, 0); s.toggleList(ordered = false) }
        assertEquals("- a\n- b\n- c\n", s.markdown())
    }

    @Test fun `numbered list and numbering`() {
        val s = session("a\n\nb\n\nc")
        for (i in 0..2) { s.select(i, 0); s.toggleList(ordered = true) }
        assertEquals("1. a\n2. b\n3. c\n", s.markdown())
        assertEquals(listOf(1, 2, 3), s.doc.rows.indices.mapNotNull { s.doc.numberOf(it) })
    }

    @Test fun `turning a list item back into a paragraph`() {
        val s = session("- a\n- b\n- c")
        s.select(1, 0)
        s.toggleList(ordered = false)
        assertEquals("- a\n\nb\n\n- c\n", s.markdown())
    }

    @Test fun `bullet list becomes numbered`() {
        val s = session("- a\n- b")
        s.select(0, 0)
        s.toggleList(ordered = true)
        assertEquals("1. a\n2. b\n", s.markdown())
    }

    @Test fun `task items`() {
        val s = session("Milk")
        s.select(0, 0)
        s.toggleTask()
        assertEquals("- [ ] Milk\n", s.markdown())
        s.setChecked(s.id(0), true)
        assertEquals("- [x] Milk\n", s.markdown())
        s.setChecked(s.id(0), false)
        assertEquals("- [ ] Milk\n", s.markdown())
        s.toggleTask()
        assertEquals("- Milk\n", s.markdown())
    }

    @Test fun `toggling a checkbox changes only that item and not the order`() {
        val s = session("- [ ] Milk\n- [ ] Bread\n- [ ] Cheese")
        s.setChecked(s.id(1), true)
        assertEquals("- [ ] Milk\n- [x] Bread\n- [ ] Cheese\n", s.markdown())
    }

    @Test fun `checking a box on a plain item does nothing`() {
        val s = session("- plain")
        assertFalse(s.setChecked(s.id(0), true))
        assertEquals("- plain", s.markdown().trimEnd())
    }

    @Test fun `indent and outdent`() {
        val s = session("- a\n- b\n- c")
        s.select(1, 0)
        s.indent()
        assertEquals("- a\n  - b\n- c\n", s.markdown())
        s.indent() // no sibling above at this level: not allowed
        assertEquals("- a\n  - b\n- c\n", s.markdown())
        s.outdent()
        assertEquals("- a\n- b\n- c", s.markdown())
    }

    @Test fun `indenting an item moves its children along`() {
        val s = session("- a\n- b\n  - b1\n- c")
        s.select(1, 0)
        s.indent()
        assertEquals("- a\n  - b\n    - b1\n- c\n", s.markdown())
    }

    @Test fun `the first list item cannot be indented`() {
        val s = session("- a\n- b")
        s.select(0, 0)
        assertFalse(s.indent())
    }

    @Test fun `outdenting a top-level item leaves the list`() {
        val s = session("- a\n- b")
        s.select(1, 0)
        s.outdent()
        assertEquals(RowKind.Paragraph, s.doc.rows[1].kind)
    }

    @Test fun `horizontal rule`() {
        val s = session("above")
        s.select(0, 5)
        s.insertRule()
        assertEquals("above\n\n---\n", s.markdown())
        s.type(s.doc.rows.lastIndex, "below")
        assertEquals("above\n\n---\n\nbelow\n", s.markdown())
    }

    @Test fun `the example from the product description can be built from scratch`() {
        val s = session("")
        s.type(0, "Shopping"); s.select(0, 0); s.setKind(RowKind.Heading(1))
        s.enterAt(0, 8)
        s.type(1, "Buy these things today.")
        s.select(1, 4, 16); s.toggleStyle(SpanKind.Bold)
        s.enterAt(1, 23)
        s.type(2, "Milk"); s.select(2, 4); s.toggleList(false)
        s.enterAt(2, 4); s.type(3, "Bread")
        s.enterAt(3, 5); s.type(4, "Cheese"); s.select(4, 6); s.toggleTask()
        assertEquals("# Shopping\n\nBuy **these things** today.\n\n- Milk\n- Bread\n- [ ] Cheese\n", s.markdown())
    }

    // ---- Untouched content stays as it was -------------------------------------------------------------------

    @Test fun `editing one paragraph leaves every other block byte identical`() {
        val original = "* odd   bullets\n*   second\n\n__strong__ and *em*\n\nSetext\n======\n\n<div>raw</div>\n\n   Edit me\n\n1. a\n1. b\n"
        val s = session(original)
        val i = s.doc.rows.indexOfFirst { it.text.text == "Edit me" }
        s.type(i, "Edited me")
        assertEquals(original.replace("   Edit me", "Edited me"), s.markdown())
    }

    @Test fun `an untouched document is never rewritten`() {
        val original = "\n\n__a__\n\n\n* b\n\n\n"
        val s = session(original)
        s.select(0, 0)
        s.moveCursor(Cursor(s.id(0), 1))
        assertEquals(original, s.markdown())
    }

    @Test fun `editing a raw block edits its markdown source`() {
        val s = session("<div>old</div>\n\ntext")
        s.type(0, "<div>new</div>")
        assertEquals("<div>new</div>\n\ntext", s.markdown())
    }

    @Test fun `a table is kept as raw text and survives edits around it`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        val s = session("before\n\n$table\n\nafter")
        s.type(0, "before!")
        assertEquals("before!\n\n$table\n\nafter", s.markdown())
    }

    // ---- Blank drafts ----------------------------------------------------------------------------------------

    @Test fun `an untouched blank editor writes nothing`() {
        assertEquals("", session("").markdown())
        assertEquals("", EditorSession(EditorDocument.empty()).markdown())
    }

    @Test fun `the first meaningful text makes content and deleting it again makes the document empty`() {
        val s = session("")
        s.type(0, "a")
        assertEquals("a\n", s.markdown())
        s.type(0, "")
        assertEquals("", s.markdown())
    }

    @Test fun `only whitespace is not content`() {
        val s = session("")
        s.type(0, "   ")
        assertTrue(s.markdown().isBlank())
    }

    @Test fun `empty rows from enter presses never become content`() {
        val s = session("")
        s.enterAt(0, 0); s.enterAt(1, 0); s.enterAt(2, 0)
        assertEquals("", s.markdown())
    }

    // ---- Undo / redo -----------------------------------------------------------------------------------------

    @Test fun `undo and redo of typing`() {
        val s = session("Hello")
        s.type(0, "Hello world")
        assertTrue(s.undo())
        assertEquals("Hello", s.text(0))
        assertTrue(s.redo())
        assertEquals("Hello world", s.text(0))
    }

    @Test fun `undo of formatting list conversion and checkbox`() {
        val s = session("Milk")
        s.select(0, 0, 4); s.toggleStyle(SpanKind.Bold)
        assertEquals("**Milk**\n", s.markdown())
        s.toggleTask()
        assertEquals("- [ ] **Milk**\n", s.markdown())
        s.setChecked(s.id(0), true)
        assertEquals("- [x] **Milk**\n", s.markdown())
        s.undo(); assertEquals("- [ ] **Milk**\n", s.markdown())
        s.undo(); assertEquals("**Milk**\n", s.markdown())
        s.undo(); assertEquals("Milk", s.markdown())
        s.redo(); s.redo(); s.redo()
        assertEquals("- [x] **Milk**\n", s.markdown())
    }

    @Test fun `undoing everything restores the original file byte for byte`() {
        val original = "* odd\n*  bullets\n\n__x__\n"
        val s = session(original)
        s.type(1, "changed")
        s.select(1, 0, 3); s.toggleStyle(SpanKind.Italic)
        s.enterAt(1, 3)
        while (s.undo()) { /* all the way back */ }
        assertEquals(original, s.markdown())
    }

    @Test fun `a burst of typing is one undo step`() {
        val s = session("")
        var text = ""
        for (c in "hello world") { text += c; s.type(0, text); now += 50 }
        assertEquals(1, s.history.size)
        s.undo()
        assertEquals("", s.text(0))
    }

    @Test fun `typing after a pause is a new undo step`() {
        val s = session("")
        s.type(0, "one"); now += 5_000
        s.type(0, "one two")
        assertEquals(2, s.history.size)
        s.undo()
        assertEquals("one", s.text(0))
    }

    @Test fun `a new edit clears the redo history`() {
        val s = session("a")
        s.type(0, "ab"); s.undo()
        assertTrue(s.history.canRedo)
        s.type(0, "ac")
        assertFalse(s.history.canRedo)
    }

    @Test fun `history is bounded`() {
        val s = EditorSession(EditorDocument.fromMarkdown("x"), clock = { now }, historyLimit = 30)
        repeat(200) { s.select(0, 0, 1); s.toggleStyle(SpanKind.Bold); now += 5_000 }
        assertEquals(30, s.history.size)
        var undone = 0
        while (s.undo()) undone++
        assertEquals(30, undone)
    }

    @Test fun `undo with nothing to undo is harmless`() {
        val s = session("x")
        assertFalse(s.undo())
        assertFalse(s.redo())
        assertEquals("x", s.text(0))
    }

    @Test fun `undo restores the caret row`() {
        val s = session("a\n\nb")
        s.select(1, 1)
        s.type(1, "bb", 2)
        s.undo()
        assertEquals(s.id(1), s.cursor!!.rowId)
    }

    // ---- Toolbar state ---------------------------------------------------------------------------------------

    @Test fun `active formatting is reported for the caret and the selection`() {
        val s = session("a **b** c")
        s.select(0, 3)
        assertEquals(setOf(SpanKind.Bold), s.activeKinds())
        s.select(0, 0, 5)
        assertEquals(emptySet<SpanKind>(), s.activeKinds())
        s.select(0, 2, 3)
        assertEquals(setOf(SpanKind.Bold), s.activeKinds())
    }

    @Test fun `moving the caret drops a pending typing style`() {
        val s = session("abc")
        s.select(0, 3)
        s.toggleStyle(SpanKind.Bold)
        assertEquals(setOf(SpanKind.Bold), s.typingStyle)
        s.select(0, 1)
        assertEquals(null, s.typingStyle)
    }

    @Test fun `edits never leave duplicate or missing row ids`() {
        val s = session("a\n\n- b\n- c\n\n> q\n")
        s.enterAt(0, 1); s.select(2, 0); s.indent(); s.toggleTask()
        val ids = s.doc.rows.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(0, ids.size)
    }
}
