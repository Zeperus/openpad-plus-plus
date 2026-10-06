package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.BlockNormalizer
import io.github.zeperus.openpad.markdown.Gen
import io.github.zeperus.openpad.markdown.HtmlSpans
import io.github.zeperus.openpad.markdown.MarkdownParser
import io.github.zeperus.openpad.markdown.MarkdownSerializer
import io.github.zeperus.openpad.markdown.OpenPadDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random sequences of editing operations on random (adversarial) documents. After every operation:
 *  - the rows are consistent (unique ids, always something to type into),
 *  - writing the document and reading it back gives exactly what the editor shows,
 * and at the end undoing everything restores the original file byte for byte.
 */
class EditorPropertyTest {
    private var now = 0L

    private val atoms = listOf("a", "bc", " ", "x y", "*", "_", "#", "- ", "1. ", "`", "[", "]", "(", ")", "~", "ä", "😀", "&", "<", ">", "\\", "---", "[ ]", "[x] ", "\n")

    /** Spaces and tabs at line edges and blank edges cannot be written in Markdown and are not part of what is compared. */
    private fun visible(text: String): String = text.split("\n").joinToString("\n") { it.trim(' ', '\t') }.trim('\n')

    /** What the user sees of a row: kind, text, how each visible character is formatted, depth. */
    private fun look(r: EditorRow): List<Any> {
        val text = if (r.kind is RowKind.Code || r.kind == RowKind.Raw) r.text.text else visible(r.text.text)
        val kind: Any = when (val k = r.kind) {
            is RowKind.ListItem -> Triple("item", k.list.ordered, if (text.isEmpty()) null else k.checked)
            is RowKind.Code -> "code"
            else -> k
        }
        val t = r.text
        val format = t.text.indices.filter { !t.text[it].isWhitespace() }.map { i -> listOf(t.kindsAt(i).sorted(), t.linkAt(i)?.let { it.href to it.title }) }
        return listOf(kind, text, format, r.depth)
    }

    private fun blank(r: EditorRow) = r.kind == RowKind.Paragraph && visible(r.text.text).isEmpty()

    /**
     * What the editor holds, as the document model: this is what the saved file must mean when read again. (Reading a file
     * back into *rows* can be coarser: a list item with unusual contents is then kept as a raw row, which is lossless but
     * looks different, so the comparison is made on the model.)
     */
    private fun shown(doc: EditorDocument) = doc.toBlocks().first.mapNotNull { BlockNormalizer.compareForm(it) }

    private fun reloaded(s: EditorSession) =
        HtmlSpans.fold(MarkdownParser.parseDocument(s.markdown()).blocks).mapNotNull { BlockNormalizer.compareForm(it) }

    private fun diff(x: String, y: String): String {
        var i = 0
        while (i < x.length && i < y.length && x[i] == y[i]) i++
        val from = maxOf(0, i - 80)
        return " shown:    …${x.substring(from, minOf(x.length, i + 160))}\n reloaded: …${y.substring(from, minOf(y.length, i + 160))}"
    }

    private fun pick(r: Random, s: EditorSession): EditorRow = s.doc.rows[r.nextInt(s.doc.rows.size)]

    private fun randomText(r: Random) = buildString { repeat(r.nextInt(1, 5)) { append(atoms[r.nextInt(atoms.size)]) } }

    private fun step(r: Random, s: EditorSession) {
        val row = pick(r, s)
        val len = row.text.length
        when (r.nextInt(16)) {
            0, 1, 2 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw) { // type
                val at = r.nextInt(len + 1)
                val inserted = randomText(r)
                s.moveCursor(Cursor(row.id, at))
                s.onText(row.id, row.text.text.substring(0, at) + inserted + row.text.text.substring(at), at + inserted.length)
            }
            3 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw && len > 0) { // delete a range
                val a = r.nextInt(len); val b = minOf(len, a + r.nextInt(1, 6))
                s.onText(row.id, row.text.text.removeRange(a, b), a)
            }
            4 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw) s.onText(row.id, row.text.text.let { t -> val at = r.nextInt(t.length + 1); t.substring(0, at) + "\n" + t.substring(at) }, 0) // Enter
            5 -> s.backspaceAtStart(row.id)
            6, 7 -> if (len > 0 && row.kind !is RowKind.Code && row.kind != RowKind.Raw && row.kind != RowKind.Rule) {
                val a = r.nextInt(len); val b = minOf(len, a + r.nextInt(1, 8))
                s.moveCursor(Cursor(row.id, a, b))
                s.toggleStyle(listOf(SpanKind.Bold, SpanKind.Italic, SpanKind.Strike, SpanKind.Code)[r.nextInt(4)])
            }
            8 -> if (len > 0 && row.kind !is RowKind.Code && row.kind != RowKind.Raw && row.kind != RowKind.Rule) {
                val a = r.nextInt(len); s.moveCursor(Cursor(row.id, a, minOf(len, a + r.nextInt(1, 6))))
                if (r.nextBoolean()) s.setLink(listOf("https://x.org", "a b", "x(1)")[r.nextInt(3)]) else s.removeLink()
            }
            9 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw) {
                s.moveCursor(Cursor(row.id, 0))
                s.setKind(listOf(RowKind.Paragraph, RowKind.Heading(1), RowKind.Heading(3), RowKind.Quote, RowKind.Code(null, io.github.zeperus.openpad.markdown.CodeStyle.Fenced))[r.nextInt(5)])
            }
            10 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw) { s.moveCursor(Cursor(row.id, 0)); s.toggleList(r.nextBoolean()) }
            11 -> if (row.kind != RowKind.Rule && row.kind != RowKind.Raw) { s.moveCursor(Cursor(row.id, 0)); s.toggleTask() }
            12 -> { s.moveCursor(Cursor(row.id, 0)); if (r.nextBoolean()) s.indent() else s.outdent() }
            13 -> s.setChecked(row.id, r.nextBoolean())
            14 -> s.undo()
            else -> if (r.nextInt(3) == 0) s.redo() else if (row.kind != RowKind.Rule) { s.moveCursor(Cursor(row.id, len)); s.insertRule() }
        }
    }

    @Test fun `random editing keeps the rows consistent and what is shown equals what is saved`() {
        var failures = 0
        val report = StringBuilder()
        repeat(1500) { seed ->
            val r = Random(seed)
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 1_000_000).document()))
            val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
            s.smartChecklist = seed % 2 == 0 // half of the sessions run in smart checklist mode
            now = 0
            s.moveCursor(Cursor(s.doc.rows[0].id, 0))
            for (n in 0 until 25) {
                step(r, s)
                now += 400
                val ids = s.doc.rows.map { it.id }
                assertTrue("seed $seed step $n: ids", ids.size == ids.toSet().size && ids.isNotEmpty())
                val a = shown(s.doc)
                val b = reloaded(s)
                if (a != b) {
                    failures++
                    if (failures <= 4) {
                        val i = a.indices.firstOrNull { a[it] != b.getOrNull(it) } ?: 0
                        report.append("\n--- seed $seed step $n block $i\n ROWS: ${s.doc.rows.joinToString(" | ") { r -> (r.kind as? RowKind.ListItem)?.let { "L${it.list.id}${it.list.marker}d${r.depth}" } ?: r.kind.toString().take(6) }}  ORIGINS ${s.doc.toBlocks().second}\n MD: ${s.markdown().take(500).replace("\n", "⏎")}\n ${diff(a.getOrNull(i).toString(), b.getOrNull(i).toString())}\n")
                    }
                    break
                }
            }
        }
        assertEquals("$failures of 1500 editing sessions did not survive saving and reloading:$report", 0, failures)
    }

    @Test fun `undoing everything restores the original file byte for byte`() {
        repeat(300) { seed ->
            val r = Random(seed + 5_000)
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 2_000_000).document()))
            val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
            s.moveCursor(Cursor(s.doc.rows[0].id, 0))
            repeat(20) { step(r, s); now += 400 }
            while (s.undo()) { /* back to the start */ }
            assertEquals("seed $seed", md, s.markdown())
        }
    }

    @Test fun `redo after undo returns to the last state`() {
        repeat(200) { seed ->
            val r = Random(seed + 9_000)
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 3_000_000).document()))
            val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
            s.moveCursor(Cursor(s.doc.rows[0].id, 0))
            repeat(15) { step(r, s); now += 400 }
            val last = s.markdown()
            var undone = 0
            while (s.undo()) undone++
            repeat(undone) { s.redo() }
            assertEquals("seed $seed", last, s.markdown())
        }
    }

    @Test fun `a long note stays fast while typing`() {
        val md = (1..400).joinToString("\n\n") { i -> "Paragraph $i with **bold**, *italic* and `code`. " + "Lorem ipsum dolor sit amet. ".repeat(4) } + "\n"
        val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
        val middle = s.doc.rows.size / 2
        var text = s.doc.rows[middle].text.text
        s.moveCursor(Cursor(s.doc.rows[middle].id, text.length))
        repeat(5) { text += "x"; s.onText(s.doc.rows[middle].id, text, text.length); s.markdown() } // warm up
        val start = System.nanoTime()
        repeat(200) { text += "y"; s.onText(s.doc.rows[middle].id, text, text.length); s.markdown() }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("200 keystrokes in a 400-paragraph note took ${ms}ms", ms < 4_000)
    }
}
