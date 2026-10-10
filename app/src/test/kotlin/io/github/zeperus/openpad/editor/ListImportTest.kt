package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cleaning rules of "Paste as Checklist" and of conversions to lists: markers, task markers, chat headers, line endings. */
class ListImportTest {
    private fun texts(input: String) = ListImport.checklistItems(input).map { it.text }
    private fun one(line: String) = ListImport.checklistItems(line).singleOrNull()

    // ---- Line endings --------------------------------------------------------------------------------------------

    @Test fun `CRLF and CR are line breaks`() {
        assertEquals(listOf("Milk", "Bread", "Water"), texts("Milk\r\nBread\rWater"))
        assertEquals("a\nb\nc", ListImport.normalizeNewlines("a\r\nb\rc"))
    }

    @Test fun `a trailing line break gives no empty item`() {
        assertEquals(listOf("Milk", "Bread"), texts("Milk\nBread\n"))
        assertEquals(listOf("Milk", "Bread"), texts("Milk\r\nBread\r\n"))
    }

    @Test fun `lines keeps the logical structure including blank and final empty lines`() {
        assertEquals(listOf("a", "", "b", ""), ListImport.lines("a\r\n\r\nb\n"))
    }

    // ---- Plain lines and markers -----------------------------------------------------------------------------------

    @Test fun `plain lines are one item each`() {
        assertEquals(listOf("Milk", "Bread", "Water", "Cheese"), texts("Milk\nBread\nWater\nCheese"))
    }

    @Test fun `every common bullet is removed`() {
        for (marker in listOf("-", "*", "+", "•", "–", "—")) {
            assertEquals("marker $marker", "Beer", one("$marker Beer")!!.text)
        }
    }

    @Test fun `mixed bullet characters in one paste`() {
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), texts("- A\n* B\n+ C\n• D\n– E\n— F\nG"))
    }

    @Test fun `a marker needs a blank after it`() {
        assertEquals("-5 degrees", one("-5 degrees")!!.text)
        assertEquals("*bold* text", one("*bold* text")!!.text)
        assertEquals("--", one("--")!!.text)
        assertEquals("—Tür", one("—Tür")!!.text)
    }

    @Test fun `indented markers are cleaned and the items are flat`() {
        assertEquals(listOf("Parent", "Child"), texts("- Parent\n    - Child"))
    }

    @Test fun `unicode, emoji and umlauts survive`() {
        assertEquals(listOf("Hähnchen 🍗", "Äpfel & Öl", "日本語"), texts("- Hähnchen 🍗\r\n• Äpfel & Öl\n日本語"))
    }

    @Test fun `whitespace-only lines and empty markers give nothing`() {
        assertEquals(listOf("A", "B"), texts("A\n   \n\t\n-\n- \n- [ ]\nB"))
    }

    @Test fun `blank lines are ignored for the import`() {
        assertEquals(listOf("Milk", "Bread"), texts("Milk\n\n\nBread"))
    }

    @Test fun `the checklist keeps the order of the clipboard`() {
        assertEquals(listOf("Z", "A", "M"), texts("Z\nA\nM"))
    }

    // ---- Task markers --------------------------------------------------------------------------------------------

    @Test fun `Markdown task markers set the state and disappear from the text`() {
        val items = ListImport.checklistItems("- [ ] Milk\n- [x] Bread\n- [X] Cheese\n* [ ] Eggs")
        assertEquals(listOf("Milk", "Bread", "Cheese", "Eggs"), items.map { it.text })
        assertEquals(listOf(false, true, true, false), items.map { it.checked })
    }

    @Test fun `box glyphs from our own plain copy are understood`() {
        val items = ListImport.checklistItems("☐ Milk\n☑ Bread")
        assertEquals(listOf("Milk", "Bread"), items.map { it.text })
        assertEquals(listOf(false, true), items.map { it.checked })
    }

    @Test fun `a bracket that is not a task marker stays`() {
        assertEquals("[x]Bread", one("[x]Bread")!!.text)
        assertEquals("[y] Bread", one("[y] Bread")!!.text)
        assertNull(one("[x] "))
    }

    @Test fun `plain lines have no task state`() {
        assertNull(one("Milk")!!.checked)
    }

    // ---- Messenger headers -----------------------------------------------------------------------------------------

    @Test fun `WhatsApp header with a bullet after the sender`() {
        assertEquals("Bier", one("[10.10., 12:42] Sybille: - Bier")!!.text)
    }

    @Test fun `WhatsApp header without a bullet after the sender`() {
        assertEquals("Bier", one("[10.10., 12:42] Sybille: Bier")!!.text)
    }

    @Test fun `short time-only header`() {
        assertEquals("Bier", one("[12:42] Sybille: Bier")!!.text)
        assertEquals("Bier", one("[12:42] Sybille: - Bier")!!.text)
    }

    @Test fun `other date and time forms`() {
        for (header in listOf("[10.10.25, 12:42:07]", "[10/10/2025, 12:42 PM]", "[2025-10-10, 12:42]", "[10.10.2025 12:42]", "[1.1., 9:05]")) {
            assertEquals(header, "Bier", one("$header Sybille: Bier")!!.text)
        }
        assertEquals("Bier", one("10.10.25, 12:42 - Sybille: Bier")!!.text) // the format of a WhatsApp text export
        assertEquals("Bier", one("10/10/2025, 12:42 PM - Sybille: - Bier")!!.text)
    }

    @Test fun `emoji and spaces in the sender`() {
        assertEquals("Reis", one("[10.10., 12:42] ❤ Mäuschen ❤: Reis")!!.text)
        assertEquals("Nürnberger", one("[10.10., 12:51] Patrick Wilkens: Nürnberger")!!.text)
    }

    @Test fun `directional marks in front of a chat line are ignored`() {
        assertEquals("Bier", one("\u200E[10.10., 12:42] Sybille: Bier")!!.text)
    }

    @Test fun `header and task marker together`() {
        val item = one("[12:42] Sybille: - [x] Bier")!!
        assertEquals("Bier", item.text)
        assertEquals(true, item.checked)
    }

    @Test fun `a header without a message gives no item`() {
        assertEquals(emptyList<String>(), texts("[12:42] Sybille:"))
        assertEquals(listOf("Bier"), texts("[12:42] Sybille:\nBier"))
    }

    @Test fun `mixed input`() {
        assertEquals(
            listOf("Bier", "Cola", "Wasser", "Bacon", "Toastbrot"),
            texts("[12:30] Sybille: Bier\n- Cola\n• Wasser\n[12:31] Patrick: Bacon\nToastbrot"),
        )
    }

    @Test fun `several senders are not grouped, sorted or named`() {
        assertEquals(listOf("Cola", "Bacon", "Bread"), texts("[12:30] Sybille: Cola\n[12:31] Patrick: Bacon\n[12:32] Sybille: Bread"))
    }

    @Test fun `message without a bullet, several lines`() {
        assertEquals(listOf("Bier", "Cola", "Bacon"), texts("[12:30] Sybille: Bier\n[12:31] Sybille: Cola\n[12:32] Patrick: Bacon"))
    }

    @Test fun `CRLF messages`() {
        assertEquals(listOf("Bier", "Bacon"), texts("[12:30] Sybille: Bier\r\n[12:31] Patrick: Bacon\r\n"))
    }

    // ---- Conservative detection ------------------------------------------------------------------------------------

    @Test fun `ordinary text with a colon is never touched`() {
        assertEquals("Note: buy milk", one("Note: buy milk")!!.text)
        assertEquals("Server: production", one("Server: production")!!.text)
        assertEquals("URL: https://example.com", one("URL: https://example.com")!!.text)
        assertEquals("Important: - remember this", one("Important: - remember this")!!.text)
        assertEquals("Important: - Bier", one("Important: - Bier")!!.text)
    }

    @Test fun `times that are not message headers stay`() {
        assertEquals("12:42 Sybille: Bier", one("12:42 Sybille: Bier")!!.text)
        assertEquals("Meeting [12:42] Sybille: Bier", one("Meeting [12:42] Sybille: Bier")!!.text)
        assertEquals("[12:42] Bier", one("[12:42] Bier")!!.text) // no sender: not a message header
        assertEquals("[12:42]Sybille: Bier", one("[12:42]Sybille: Bier")!!.text)
    }

    @Test fun `a header needs a colon followed by a blank`() {
        assertEquals("[12:42] Sybille:Bier", one("[12:42] Sybille:Bier")!!.text)
        assertEquals("[12:42] 5:30", one("[12:42] 5:30")!!.text)
    }

    @Test fun `messageStart finds nothing in ordinary text`() {
        assertNull(ListImport.messageStart("Note: buy milk"))
        assertNull(ListImport.messageStart("[draft] Sybille: x"))
        assertTrue(ListImport.messageStart("[12:42] S: x")!! > 0)
        assertFalse(ListImport.hasMarker("Milk"))
        assertTrue(ListImport.hasMarker("- Milk"))
        assertTrue(ListImport.hasMarker("[ ] Milk"))
    }

    // ---- The real shopping list ----------------------------------------------------------------------------------

    private val fixture = """
[10.10., 12:27] ❤ Mäuschen ❤: - Shampoo
- Duschgel
- Desinfektionsmittel
- Lenor
- Spaghetti
- Borritos
- Tacco-Sauce
- Tacco-Gewürz
- Hackfleisch 2x
- Hähnchen
- Lyoner
- Rahmfleisch
- Cola?
- Karotten
- Salat
- Tomaten
- Brokkoli
- Knoblauch
- Gouda
- Ofenkäse
- Schmelzkäse
- Ketchup
- Dosentomaten
- Blätterteig
- Eier
- Ciabatta-Brot
- Brot
- Burger-Brötchen
- Pommes
[10.10., 12:42] ❤ Mäuschen ❤: - Reis
[10.10., 12:44] ❤ Mäuschen ❤: - Hühnerbrühe
[10.10., 12:51] Patrick Wilkens: - Nürnberger
- Bacon
- Toastbrot
""".trim()

    private val expected = listOf(
        "Shampoo", "Duschgel", "Desinfektionsmittel", "Lenor", "Spaghetti", "Borritos", "Tacco-Sauce", "Tacco-Gewürz", "Hackfleisch 2x", "Hähnchen",
        "Lyoner", "Rahmfleisch", "Cola?", "Karotten", "Salat", "Tomaten", "Brokkoli", "Knoblauch", "Gouda", "Ofenkäse", "Schmelzkäse", "Ketchup",
        "Dosentomaten", "Blätterteig", "Eier", "Ciabatta-Brot", "Brot", "Burger-Brötchen", "Pommes", "Reis", "Hühnerbrühe", "Nürnberger", "Bacon", "Toastbrot",
    )

    @Test fun `the real shopping list gives exactly 34 clean items in order`() {
        val items = texts(fixture)
        assertEquals(34, items.size)
        assertEquals("Shampoo", items.first())
        assertEquals("Toastbrot", items.last())
        assertEquals(expected, items)
        for ((a, b) in listOf("Pommes" to "Reis", "Reis" to "Hühnerbrühe", "Hühnerbrühe" to "Nürnberger", "Nürnberger" to "Bacon", "Bacon" to "Toastbrot")) {
            assertEquals("$a is directly followed by $b", items.indexOf(a) + 1, items.indexOf(b))
        }
        for (item in items) {
            for (bad in listOf("[10.10.", "12:27", "12:42", "12:44", "12:51", "Mäuschen", "Patrick Wilkens")) assertFalse("'$item' contains $bad", bad in item)
            for (bad in listOf("-", "*", "+", "•", "– ", "—")) assertFalse("'$item' starts with $bad", item.startsWith(bad))
        }
    }

    @Test fun `the real shopping list with CRLF line endings`() {
        assertEquals(expected, texts(fixture.replace("\n", "\r\n")))
    }

    @Test fun `none of the real shopping list is checked`() {
        assertTrue(ListImport.checklistItems(fixture).all { it.checked == null })
    }
}
