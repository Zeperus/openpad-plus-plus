package io.github.zeperus.openpad.domain

import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.FindInNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun note(title: String) = NoteInfo(NoteId(title), title, 0, 0, null, false)
    private fun search(query: String, vararg notes: Pair<String, String?>) = NoteSearch.search(query, notes.map { note(it.first) to it.second })

    @Test fun `title and content are searched and title matches come first`() {
        val hits = search("milk", "Zebra" to "buy milk today", "Milk list" to "eggs", "Other" to "nothing")
        assertEquals(listOf("Milk list", "Zebra"), hits.map { it.note.title })
        assertTrue(hits[0].titleMatch)
        assertEquals("buy milk today", hits[1].snippet)
        assertEquals(4 until 8, hits[1].snippetMatch)
    }

    @Test fun `case does not matter, also for german letters`() {
        assertEquals(1, search("GRÖSSE", "a" to "Die Größe passt").size.let { search("größe", "a" to "Die GRÖßE passt").size })
        assertEquals(1, search("ÄPFEL", "a" to "frische äpfel").size)
        assertEquals(1, search("straße", "Straße" to null).size)
    }

    @Test fun `unicode and emoji text is found`() {
        assertEquals(1, search("日本", "a" to "これは日本語です").size)
        assertEquals(1, search("😀", "a" to "hi 😀 there").size)
    }

    @Test fun `no match gives nothing and a blank query gives nothing`() {
        assertTrue(search("zzz", "a" to "text").isEmpty())
        assertTrue(search("  ", "a" to "text").isEmpty())
    }

    @Test fun `the snippet is the line of the first match without its markdown marker`() {
        val hit = search("bread", "n" to "# Shopping\n\n- [ ] Milk\n- [x] Bread and butter\n").single()
        assertEquals("Bread and butter", hit.snippet)
        assertEquals(0 until 5, hit.snippetMatch)
        assertEquals(1, hit.matches)
    }

    @Test fun `occurrences are counted and long lines are shortened around the match`() {
        val long = "x".repeat(200) + " needle " + "y".repeat(200)
        val hit = search("needle", "n" to "needle needle\n$long").single()
        assertEquals(3, hit.matches)
        val hit2 = search("needle", "n" to long).single()
        assertTrue(hit2.snippet!!.length < 100)
        assertEquals("needle", hit2.snippet!!.substring(hit2.snippetMatch!!.first, hit2.snippetMatch!!.last + 1))
    }

    @Test fun `title only matches have no snippet`() {
        assertNull(search("list", "Shopping list" to "milk").single().snippet)
    }

    // ---- Find in note ------------------------------------------------------------------------------------------

    @Test fun `find looks at visible text not at markdown punctuation`() {
        val doc = EditorDocument.fromMarkdown("# Title\n\nSome **bold** text and bold again\n\n- [ ] bold item\n")
        val matches = FindInNote.matches(doc, "BOLD")
        assertEquals(3, matches.size)
        assertEquals(listOf(5 to 9, 19 to 23, 0 to 4), matches.map { it.start to it.end })
        assertTrue(FindInNote.matches(doc, "**").isEmpty())
        assertTrue(FindInNote.matches(doc, "").isEmpty())
    }

    @Test fun `find skips blocks that are drawn as formatted content`() {
        val doc = EditorDocument.fromMarkdown("| needle | b |\n|---|---|\n| 1 | 2 |\n\nneedle outside\n")
        assertEquals(1, FindInNote.matches(doc, "needle").size)
    }
}
