package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realistic sizes, generous limits (the limits catch accidental O(n^2), not slow machines). The measured times are printed so a
 * run shows how much room there is.
 */
class EditorPerformanceTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })

    private inline fun timed(name: String, limitMs: Long, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PERF $name: ${ms}ms (limit ${limitMs}ms)")
        assertTrue("$name took ${ms}ms", ms < limitMs)
    }

    private val words = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor".split(" ")
    private fun paragraph(i: Int) = (0 until 60).joinToString(" ") { words[(i + it) % words.size] } + " **bold $i** and `code`."

    @Test fun `typing in a note of several thousand words`() {
        val s = session((1..120).joinToString("\n\n") { paragraph(it) } + "\n") // ~8000 words
        val row = s.doc.rows[60]
        var text = row.text.text
        s.moveCursor(Cursor(row.id, text.length))
        timed("300 keystrokes in an 8000-word note", 4_000) {
            repeat(300) { text += "x"; s.onText(row.id, text, text.length); s.markdown() }
        }
    }

    @Test fun `a 500 item list`() {
        val s = session((1..500).joinToString("\n") { "- item number $it" } + "\n")
        assertEquals(500, s.doc.rows.size)
        val mid = s.doc.rows[250]
        var text = mid.text.text
        timed("200 keystrokes in a 500 item list", 4_000) { repeat(200) { text += "y"; s.onText(mid.id, text, text.length); s.markdown() } }
        timed("50 Enter presses in a 500 item list", 4_000) {
            repeat(50) { val r = s.doc.rows[100 + it]; s.onText(r.id, r.text.text + "\n", r.text.length + 1) }
            s.markdown()
        }
        timed("indent + outdent in a 500 item list", 2_000) { repeat(20) { s.moveCursor(Cursor(s.doc.rows[200 + it].id, 0)); s.indent(); s.outdent() } }
    }

    @Test fun `a 100 item smart checklist`() {
        val s = session((1..100).joinToString("\n") { "- [ ] task $it" } + "\n")
        s.smartChecklist = true
        timed("check all 100 items in a smart checklist", 4_000) {
            repeat(100) { s.setChecked(s.doc.rows.first { r -> (r.kind as RowKind.ListItem).checked == false }.id, true) }
        }
        assertTrue(s.doc.rows.all { (it.kind as RowKind.ListItem).checked == true })
        timed("uncheck 50 of them", 4_000) { repeat(50) { s.setChecked(s.doc.rows.last { r -> (r.kind as RowKind.ListItem).checked == true }.id, false) } }
        assertEquals(50, s.doc.rows.count { (it.kind as RowKind.ListItem).checked == false })
        val firstDone = s.doc.rows.indexOfFirst { (it.kind as RowKind.ListItem).checked == true }
        assertTrue(s.doc.rows.drop(firstDone).all { (it.kind as RowKind.ListItem).checked == true }) // unchecked always above completed
    }

    @Test fun `table-heavy markdown`() {
        val table = "| a | b | c |\n|---|:-:|--:|\n| 1 | **2** | 3 |\n| 4 | 5 | 6 |\n"
        val md = (1..200).joinToString("\n") { "Intro $it\n\n$table" }
        timed("load 200 tables", 3_000) { session(md) }
        val s = session(md)
        assertEquals(200, s.doc.rows.count { it.kind == RowKind.Raw })
        timed("edit a paragraph among 200 tables and write", 2_000) { s.onText(s.doc.rows[100].id, "Intro changed", 13); s.markdown() }
        timed("classify 200 tables", 1_000) { s.doc.rows.filter { it.kind == RowKind.Raw }.forEach { RawBlocks.classify(it.text.text) } }
    }

    @Test fun `selecting, copying and cutting everything in a big note`() {
        val s = session((1..500).joinToString("\n") { "- [ ] item $it" } + "\n\n" + (1..100).joinToString("\n\n") { paragraph(it) } + "\n")
        val all = DocumentSelections.selectAll(s.doc)!!
        timed("plain text of everything", 1_500) { DocumentSelections.plainText(s.doc, all) }
        timed("markdown of everything", 3_000) { DocumentSelections.markdown(s.doc, all) }
        timed("cut everything", 2_000) { s.deleteSelection(all) }
        assertEquals("", s.markdown().trim())
    }

    @Test fun `find in a long note and search across many notes`() {
        val doc = EditorDocument.fromMarkdown((1..300).joinToString("\n\n") { paragraph(it) })
        timed("find 'ipsum' in 300 paragraphs", 1_000) { assertTrue(FindInNote.matches(doc, "ipsum").size > 300) }
        val text = (1..80).joinToString("\n") { "line $it with some words and a needle? no" } + "\nthe needle is here"
        val notes = (1..300).map { NoteInfo(NoteId("n$it"), "Note $it", 0, 0, null, false) to text }
        timed("search 300 notes of 3 KB", 1_500) { assertEquals(300, NoteSearch.search("NEEDLE is", notes).size) }
    }
}
