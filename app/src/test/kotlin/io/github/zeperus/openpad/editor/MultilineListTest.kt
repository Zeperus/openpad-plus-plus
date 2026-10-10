package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several lines and lists (Alpha 8): copy keeps the logical lines, converting several lines makes one item per line, pasting several
 * lines into a list makes sibling items, Paste as Checklist. Everything on the pure session, with Undo/Redo.
 */
class MultilineListTest {
    private var now = 0L
    private fun session(md: String, smart: Boolean = false) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now }).also { it.smartChecklist = smart }

    /** "o text" open task, "x text" done task, "• text" bullet, "1. text" numbered item (any), "P text" paragraph, "H2 text", "Q text", "" for an empty paragraph. */
    private fun EditorSession.shape() = doc.rows.mapIndexed { i, r ->
        val k = r.kind
        val t = r.text.text.replace("\n", "⏎")
        when {
            k is RowKind.ListItem && k.checked == true -> "x $t"
            k is RowKind.ListItem && k.checked == false -> "o $t"
            k is RowKind.ListItem && k.list.ordered -> "${doc.numberOf(i)}. $t"
            k is RowKind.ListItem -> "• $t"
            k is RowKind.Heading -> "H${k.level} $t"
            k == RowKind.Quote -> "Q $t"
            else -> if (t.isEmpty()) "" else "P $t"
        }
    }

    private fun EditorSession.all() = DocumentSelections.selectAll(doc)!!
    private fun EditorSession.sel(a: Int, ao: Int, b: Int, bo: Int) = DocumentSelection(DocumentPosition(doc.rows[a].id, ao), DocumentPosition(doc.rows[b].id, bo))
    private fun EditorSession.at(i: Int, offset: Int = doc.rows[i].text.length) = moveCursor(Cursor(doc.rows[i].id, offset))
    private fun EditorSession.copy() = selectedText(all())

    /** What a text field reports when [text] is pasted at [offset] of row [i] (replacing nothing). */
    private fun EditorSession.paste(i: Int, offset: Int, text: String, end: Int = offset) {
        val old = doc.rows[i].text.text
        onText(doc.rows[i].id, old.substring(0, offset) + text + old.substring(end), offset + text.length)
    }

    // ---- Copy / cut / paste: logical lines --------------------------------------------------------------------------

    @Test fun `copy of two and three lines keeps the line breaks`() {
        assertEquals("Hello\nWorld", session("Hello\n\nWorld").copy())
        assertEquals("Hello\nWorld\nTest", session("Hello\n\nWorld\n\nTest").copy())
    }

    @Test fun `copy never joins lines and never adds spaces`() {
        val text = session("Hello\n\nWorld\n\nTest").copy()
        assertFalse(text == "HelloWorldTest")
        assertFalse(text == "Hello World Test")
    }

    @Test fun `copy of a list keeps one item per line`() {
        assertEquals("• Milk\n• Bread\n• Water", session("- Milk\n- Bread\n- Water\n").copy())
        assertEquals("☐ Milk\n☑ Bread", session("- [ ] Milk\n- [x] Bread\n").copy())
        assertEquals("1. a\n2. b", session("1. a\n2. b\n").copy())
    }

    @Test fun `an empty row is an empty line in the copy`() {
        val s = session("Line one\n\nLine three")
        s.paste(0, 8, "\n\n") // typed: Enter, Enter
        // the model has: Line one, (empty), Line three
        assertEquals(listOf("P Line one", "", "P Line three"), s.shape())
        assertEquals("Line one\n\nLine three", s.copy())
    }

    @Test fun `a paragraph that wraps on screen has no line break in the copy`() {
        val long = "This is a long sentence that happens to wrap because the phone screen is narrow, and it keeps going so that it wraps more than once on any phone."
        val s = session(long)
        assertEquals(long, s.copy())
        assertFalse('\n' in s.copy())
        assertFalse('\n' in s.selectedText(s.sel(0, 5, 0, 60)))
    }

    @Test fun `a line break inside a paragraph is kept`() {
        val s = session("one  \ntwo\n")
        assertEquals("one\ntwo", s.copy())
    }

    @Test fun `Unicode, umlauts and emoji survive the copy`() {
        assertEquals("Grüße 🍗\nÄpfel", session("Grüße 🍗\n\nÄpfel").copy())
    }

    @Test fun `a heading above a list copies as lines`() {
        assertEquals("Shopping\n☐ Milk\n☑ Bread", session("# Shopping\n\n- [ ] Milk\n- [x] Bread\n").copy())
    }

    @Test fun `copy as Markdown keeps the block structure`() {
        val s = session("# Shopping\n\n- [ ] Milk\n- [x] Bread\n")
        assertEquals("# Shopping\n\n- [ ] Milk\n- [x] Bread", s.selectedMarkdown(s.all()))
    }

    @Test fun `cut across rows keeps the lines of the copy and is one undo step`() {
        val s = session("Alpha\n\nBeta\n\nGamma\n")
        val before = s.markdown()
        val sel = s.sel(0, 2, 2, 3)
        assertEquals("pha\nBeta\nGam", s.selectedText(sel))
        assertTrue(s.deleteSelection(sel))
        assertEquals(listOf("P Alma"), s.shape())
        assertTrue(s.undo())
        assertEquals(before, s.markdown())
        assertFalse(s.undo())
        assertTrue(s.redo())
        assertEquals(listOf("P Alma"), s.shape())
    }

    @Test fun `paste as Markdown keeps headings, lists, checklists and paragraphs`() {
        val s = session("")
        s.at(0)
        assertTrue(s.pasteMarkdown("# Shopping\n\ntext\n\n- [ ] Milk\n- [x] Bread\n"))
        assertEquals(listOf("H1 Shopping", "P text", "o Milk", "x Bread"), s.shape())
    }

    @Test fun `normal paste in a paragraph keeps the lines (CRLF too)`() {
        for (text in listOf("Milk\nBread\nWater", "Milk\r\nBread\r\nWater", "Milk\rBread\rWater")) {
            val s = session("")
            s.at(0)
            s.paste(0, 0, text)
            assertEquals(text, "Milk\nBread\nWater", s.doc.rows[0].text.text)
            assertEquals(1, s.doc.rows.size)
        }
    }

    @Test fun `normal paste keeps list markers and headers as they are`() {
        val s = session("")
        s.at(0)
        s.paste(0, 0, "- [x] Bread\n[12:30] Sybille: Bier")
        assertEquals("- [x] Bread\n[12:30] Sybille: Bier", s.doc.rows[0].text.text)
    }

    // ---- Several lines into a list ---------------------------------------------------------------------------------

    @Test fun `three lines pasted into an empty checklist item are three items`() {
        val s = session("- [ ] x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        assertEquals(listOf("o "), s.shape())
        s.paste(0, 0, "Milk\nBread\nWater")
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
        assertEquals("- [ ] Milk\n- [ ] Bread\n- [ ] Water\n", s.markdown())
    }

    @Test fun `three lines pasted into an empty bullet and numbered item`() {
        val b = session("- x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        b.paste(0, 0, "One\nTwo\nThree")
        assertEquals(listOf("• One", "• Two", "• Three"), b.shape())
        val n = session("1. x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        n.paste(0, 0, "One\r\nTwo\r\nThree")
        assertEquals(listOf("1. One", "2. Two", "3. Three"), n.shape())
    }

    @Test fun `pasting into a populated item appends the lines after the caret`() {
        val s = session("- [ ] Milk\n")
        s.paste(0, 4, "\nBread\nWater")
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
    }

    @Test fun `pasting in the middle of an item keeps the text after the caret`() {
        val s = session("- [ ] Milk\n")
        s.paste(0, 2, "AAA\nBBB")
        assertEquals(listOf("o MiAAA", "o BBBlk"), s.shape())
        assertEquals(s.doc.rows[1].id, s.cursor!!.rowId)
        assertEquals(3, s.cursor!!.start) // after BBB, in front of "lk"
    }

    @Test fun `three lines in the middle keep the tail on the last one`() {
        val s = session("• x\n".replace("• ", "- "))
        s.onText(s.doc.rows[0].id, "Milk", 4)
        s.paste(0, 2, "A\nB\nC")
        assertEquals(listOf("• MiA", "• B", "• Clk"), s.shape())
    }

    @Test fun `pasting over a selection inside an item replaces it`() {
        val s = session("- [ ] Milk and cheese\n")
        s.paste(0, 5, "X\nY", end = 9) // " and " replaced... "Milk" + " and cheese": replaces 5..9 ("and ")
        assertEquals(listOf("o Milk X", "o Ycheese"), s.shape())
    }

    @Test fun `a done item stays done, the new items are open`() {
        val s = session("- [x] Milk\n")
        s.paste(0, 4, "\nBread")
        assertEquals(listOf("x Milk", "o Bread"), s.shape())
    }

    @Test fun `a blank line between pasted lines separates two lists`() {
        val s = session("- [ ] x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        s.paste(0, 0, "Milk\n\nBread")
        assertEquals(listOf("o Milk", "", "o Bread"), s.shape())
    }

    @Test fun `a trailing line break leaves the caret on a new empty item`() {
        val s = session("- [ ] x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        s.paste(0, 0, "Milk\nBread\n")
        assertEquals(listOf("o Milk", "o Bread", "o "), s.shape())
        assertEquals(s.doc.rows[2].id, s.cursor!!.rowId)
    }

    @Test fun `nested items stay under their parent when lines are pasted into it`() {
        val s = session("- a\n  - child\n- z\n")
        s.paste(0, 1, "\nb")
        assertEquals(listOf("• a", "• child", "• b", "• z"), s.shape())
        assertEquals(listOf(0, 1, 0, 0), s.doc.rows.map { it.depth })
    }

    @Test fun `a multiline paste in a list is one undo step`() {
        val s = session("- [ ] x\n").also { it.onText(it.doc.rows[0].id, "", 0) }
        val before = s.markdown()
        val steps = s.history.size
        s.paste(0, 0, "Milk\nBread\nWater")
        assertEquals(steps + 1, s.history.size)
        assertTrue(s.undo())
        assertEquals(listOf("o "), s.shape())
        assertEquals(before, s.markdown())
        assertTrue(s.redo())
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
    }

    @Test fun `several lines pasted into a heading keep every line`() {
        val s = session("# Title\n")
        s.paste(0, 5, "A\nB\nC")
        assertEquals(listOf("H1 TitleA", "P B", "P C"), s.shape())
    }

    @Test fun `a multiline paste in a checklist of a smart note puts new items above the completed ones`() {
        val s = session("- [ ] Milk\n- [x] Bread\n", smart = true)
        s.paste(0, 4, "\nWater\nCheese")
        assertEquals(listOf("o Milk", "o Water", "o Cheese", "x Bread"), s.shape())
    }

    @Test fun `without smart mode the order is not touched`() {
        val s = session("- [x] Bread\n- [ ] Milk\n")
        s.paste(1, 4, "\nWater")
        assertEquals(listOf("x Bread", "o Milk", "o Water"), s.shape())
    }

    // ---- Several lines converted to a list --------------------------------------------------------------------------

    private fun lines(vararg l: String) = session("").also { s -> s.at(0); s.paste(0, 0, l.joinToString("\n")) }

    @Test fun `three lines in one row become three checklist items`() {
        val s = lines("Milk", "Bread", "Water", "Cheese")
        s.toggleTask()
        assertEquals(listOf("o Milk", "o Bread", "o Water", "o Cheese"), s.shape())
        assertEquals("- [ ] Milk\n- [ ] Bread\n- [ ] Water\n- [ ] Cheese\n", s.markdown())
    }

    @Test fun `three lines become three bullets and three numbered items`() {
        val b = lines("Alpha", "Beta", "Gamma")
        b.toggleList(false)
        assertEquals(listOf("• Alpha", "• Beta", "• Gamma"), b.shape())
        val n = lines("Alpha", "Beta", "Gamma")
        n.toggleList(true)
        assertEquals(listOf("1. Alpha", "2. Beta", "3. Gamma"), n.shape())
    }

    @Test fun `separate paragraphs selected together become items`() {
        val s = session("Milk\n\nBread\n\nWater\n")
        s.toggleTask(s.all())
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
        assertEquals(1, s.doc.rows.map { (it.kind as RowKind.ListItem).list.id }.distinct().size)
    }

    @Test fun `a selection that ends at the start of a row does not convert that row`() {
        val s = session("Milk\n\nBread\n\nWater\n")
        s.toggleTask(s.sel(0, 0, 2, 0))
        assertEquals(listOf("o Milk", "o Bread", "P Water"), s.shape())
    }

    @Test fun `source markers are cleaned when converting`() {
        val s = lines("- Milk", "* Bread", "+ Water", "• Cheese", "– Eggs", "— Beer")
        s.toggleTask()
        assertEquals(listOf("o Milk", "o Bread", "o Water", "o Cheese", "o Eggs", "o Beer"), s.shape())
    }

    @Test fun `a markdown task marker keeps its state and leaves the text`() {
        val s = lines("- [ ] Milk", "- [x] Bread", "- [X] Cheese")
        s.toggleTask()
        assertEquals(listOf("o Milk", "x Bread", "x Cheese"), s.shape())
    }

    @Test fun `converting to bullets drops task markers too`() {
        val s = lines("- [x] Milk", "☐ Bread")
        s.toggleList(false)
        assertEquals(listOf("• Milk", "• Bread"), s.shape())
    }

    @Test fun `a single line with a marker is cleaned`() {
        val s = lines("- Milk")
        s.toggleTask()
        assertEquals(listOf("o Milk"), s.shape())
        val b = lines("• Milk")
        b.toggleList(true)
        assertEquals(listOf("1. Milk"), b.shape())
    }

    @Test fun `one long wrapped paragraph stays one item`() {
        val long = "This is a long grocery note that wraps on a narrow phone screen but is a single logical line without any line break"
        val s = session(long)
        s.at(0)
        s.toggleTask()
        assertEquals(listOf("o $long"), s.shape())
    }

    @Test fun `blank lines separate groups and are never empty items`() {
        val s = session("Milk\n\nBread\n")
        s.onText(s.doc.rows[0].id, "Milk\n", 5) // Enter at the end of Milk: an empty row between the two
        assertEquals(listOf("P Milk", "", "P Bread"), s.shape())
        s.toggleTask(s.all())
        assertEquals(listOf("o Milk", "", "o Bread"), s.shape())
        val ids = s.doc.rows.filter { it.kind is RowKind.ListItem }.map { (it.kind as RowKind.ListItem).list.id }
        assertEquals(2, ids.distinct().size)
    }

    @Test fun `two groups survive a save and reload with all their items`() {
        val s = session("Milk\n\nBread\n")
        s.onText(s.doc.rows[0].id, "Milk\n", 5)
        s.toggleTask(s.all())
        val md = s.markdown()
        val back = EditorDocument.fromMarkdown(md)
        assertEquals(listOf("Milk", "Bread"), back.rows.filter { it.kind is RowKind.ListItem }.map { it.text.text })
        assertEquals(md, back.toMarkdown())
    }

    @Test fun `blank lines at the ends are dropped`() {
        val s = lines("", "Milk", "Bread", "")
        s.toggleTask()
        assertEquals(listOf("o Milk", "o Bread"), s.shape())
    }

    @Test fun `converting is one undo step and redo recreates it`() {
        val s = lines("Milk", "Bread", "Water")
        val before = s.markdown()
        val steps = s.history.size
        s.toggleTask()
        assertEquals(steps + 1, s.history.size)
        assertTrue(s.undo())
        assertEquals(before, s.markdown())
        assertEquals(listOf("P Milk⏎Bread⏎Water"), s.shape())
        assertTrue(s.redo())
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
    }

    @Test fun `converting several checklist items to checklist again turns them back`() {
        val s = session("- [ ] a\n- [ ] b\n")
        s.toggleTask(s.all())
        assertEquals(listOf("• a", "• b"), s.shape()) // like the single item: a task becomes a plain item
        s.toggleList(false, s.all())
        assertEquals(listOf("P a", "P b"), s.shape())
    }

    @Test fun `items of a list switch their kind and keep their nesting`() {
        val s = session("- a\n  - b\n- c\n")
        s.toggleList(true, s.all())
        assertEquals(listOf("1. a", "1. b", "2. c"), s.shape())
        assertEquals(listOf(0, 1, 0), s.doc.rows.map { it.depth })
    }

    @Test fun `converted lines join the list directly above`() {
        val s = session("- a\n\nb\n")
        s.at(1)
        s.paste(1, 1, "\nc") // the row now holds the lines b and c
        s.toggleList(false)
        assertEquals(listOf("• a", "• b", "• c"), s.shape())
    }

    @Test fun `converting in a smart checklist keeps unchecked above completed`() {
        val s = session("- [x] Done\n\nMilk\n\nBread\n", smart = true)
        s.toggleTask(s.sel(1, 0, 2, 5))
        assertEquals(listOf("o Milk", "o Bread", "x Done"), s.shape())
    }

    // ---- Paste as Checklist ------------------------------------------------------------------------------------------

    @Test fun `Paste as Checklist in an empty note`() {
        val s = session("")
        assertTrue(s.pasteAsChecklist("Milk\nBread\nWater"))
        assertEquals(listOf("o Milk", "o Bread", "o Water"), s.shape())
        assertEquals("- [ ] Milk\n- [ ] Bread\n- [ ] Water\n", s.markdown())
    }

    @Test fun `Paste as Checklist cleans a chat export`() {
        val s = session("")
        s.pasteAsChecklist("[12:30] Sybille: Bier\n- Cola\n• Wasser\n[12:31] Patrick: Bacon\nToastbrot")
        assertEquals(listOf("o Bier", "o Cola", "o Wasser", "o Bacon", "o Toastbrot"), s.shape())
    }

    @Test fun `Paste as Checklist keeps ordinary colon text`() {
        val s = session("")
        s.pasteAsChecklist("Note: buy milk\nServer: production\nURL: https://example.com\nImportant: - remember this")
        assertEquals(listOf("o Note: buy milk", "o Server: production", "o URL: https://example.com", "o Important: - remember this"), s.shape())
    }

    @Test fun `Paste as Checklist keeps the done state of markdown tasks`() {
        val s = session("")
        s.pasteAsChecklist("- [ ] Milk\n- [x] Bread")
        assertEquals(listOf("o Milk", "x Bread"), s.shape())
    }

    @Test fun `Paste as Checklist in a smart note puts open items above the completed ones`() {
        val s = session("- [ ] Milk\n- [x] Bread\n", smart = true)
        s.at(0)
        s.pasteAsChecklist("Water\nCheese")
        assertEquals(listOf("o Milk", "o Water", "o Cheese", "x Bread"), s.shape())
    }

    @Test fun `Paste as Checklist with checked imports in a smart note`() {
        val s = session("- [ ] Milk\n", smart = true)
        s.at(0)
        s.pasteAsChecklist("- [x] Bread\nWater")
        assertEquals(listOf("o Milk", "o Water", "x Bread"), s.shape())
    }

    @Test fun `Paste as Checklist without smart mode keeps the order`() {
        val s = session("- [ ] Milk\n- [x] Bread\n")
        s.at(1)
        s.pasteAsChecklist("Water\nCheese")
        assertEquals(listOf("o Milk", "x Bread", "o Water", "o Cheese"), s.shape())
    }

    @Test fun `Paste as Checklist goes below a text row and replaces a blank one`() {
        val s = session("Intro\n\nOutro\n")
        s.at(0)
        s.pasteAsChecklist("A\nB")
        assertEquals(listOf("P Intro", "o A", "o B", "P Outro"), s.shape())
        val blank = session("Intro\n")
        blank.onText(blank.doc.rows[0].id, "", 0)
        blank.pasteAsChecklist("A\nB")
        assertEquals(listOf("o A", "o B"), blank.shape())
    }

    @Test fun `Paste as Checklist in place of a blank row keeps that row's id for the caret`() {
        val s = session("Intro\n")
        s.onText(s.doc.rows[0].id, "", 0)
        val id = s.doc.rows[0].id
        s.at(0)
        s.pasteAsChecklist("A\nB")
        assertEquals(id, s.doc.rows.last().id)
        assertEquals(id, s.cursor!!.rowId)
    }

    @Test fun `Paste as Checklist inside a checklist joins it`() {
        val s = session("- [ ] Milk\n  - [ ] 2 l\n- [ ] Eggs\n")
        s.at(0)
        s.pasteAsChecklist("Bread")
        assertEquals(listOf("o Milk", "o 2 l", "o Bread", "o Eggs"), s.shape())
        assertEquals(listOf(0, 1, 0, 0), s.doc.rows.map { it.depth })
    }

    @Test fun `Paste as Checklist replaces a selection`() {
        val s = session("- Milk\n\nbefore\n\nafter\n".replace("- Milk\n\n", ""))
        val sel = s.sel(0, 0, 1, 5)
        assertTrue(s.pasteAsChecklist("A\nB", sel))
        assertEquals(listOf("o A", "o B"), s.shape())
    }

    @Test fun `Paste as Checklist is one undo step and the caret ends after the last item`() {
        val s = session("")
        val before = s.markdown()
        val steps = s.history.size
        s.pasteAsChecklist("A\nB\nC")
        assertEquals(steps + 1, s.history.size)
        assertEquals(s.doc.rows.last().id, s.cursor!!.rowId)
        assertEquals(1, s.cursor!!.start)
        assertTrue(s.undo())
        assertEquals(before, s.markdown())
        assertTrue(s.redo())
        assertEquals(listOf("o A", "o B", "o C"), s.shape())
    }

    @Test fun `Undo after replacing a selection brings the original text back`() {
        val s = session("- x\n\nplain text\n\nmore\n".replace("- x\n\n", ""))
        val original = s.markdown()
        assertTrue(s.pasteAsChecklist("[12:30] Sybille: Bier\n[12:31] Patrick: Cola", s.all()))
        assertEquals(listOf("o Bier", "o Cola"), s.shape())
        assertTrue(s.undo())
        assertEquals(original, s.markdown())
    }

    @Test fun `nothing to import changes nothing`() {
        val s = session("keep")
        assertFalse(s.pasteAsChecklist("\n  \n-\n"))
        assertEquals(listOf("P keep"), s.shape())
    }

    @Test fun `the real shopping list pastes as exactly 34 items`() {
        val text = (
            "[10.10., 12:27] ❤ Mäuschen ❤: - Shampoo\n- Duschgel\n- Desinfektionsmittel\n- Lenor\n- Spaghetti\n- Borritos\n- Tacco-Sauce\n- Tacco-Gewürz\n" +
                "- Hackfleisch 2x\n- Hähnchen\n- Lyoner\n- Rahmfleisch\n- Cola?\n- Karotten\n- Salat\n- Tomaten\n- Brokkoli\n- Knoblauch\n- Gouda\n- Ofenkäse\n" +
                "- Schmelzkäse\n- Ketchup\n- Dosentomaten\n- Blätterteig\n- Eier\n- Ciabatta-Brot\n- Brot\n- Burger-Brötchen\n- Pommes\n" +
                "[10.10., 12:42] ❤ Mäuschen ❤: - Reis\n[10.10., 12:44] ❤ Mäuschen ❤: - Hühnerbrühe\n[10.10., 12:51] Patrick Wilkens: - Nürnberger\n- Bacon\n- Toastbrot"
            )
        val s = session("", smart = true)
        assertTrue(s.pasteAsChecklist(text))
        val items = s.doc.rows.filter { it.kind is RowKind.ListItem }.map { it.text.text }
        assertEquals(34, items.size)
        assertEquals("Shampoo", items.first())
        assertEquals("Toastbrot", items.last())
        assertEquals(listOf("Pommes", "Reis", "Hühnerbrühe", "Nürnberger", "Bacon", "Toastbrot"), items.subList(28, 34))
        assertTrue(s.doc.rows.all { (it.kind as? RowKind.ListItem)?.checked == false })
        val md = s.markdown()
        assertFalse("Mäuschen" in md || "12:27" in md || "Patrick" in md)
        assertEquals(34, md.lines().count { it.startsWith("- [ ] ") })
        assertTrue(s.undo())
        assertEquals(listOf(""), s.shape())
    }

    @Test fun `normal paste of the real shopping list keeps it as it is`() {
        val text = "[10.10., 12:27] ❤ Mäuschen ❤: - Shampoo\n- Duschgel"
        val s = session("")
        s.at(0)
        s.paste(0, 0, text)
        assertEquals(text, s.doc.rows[0].text.text)
        assertEquals(text, s.copy())
    }
}
