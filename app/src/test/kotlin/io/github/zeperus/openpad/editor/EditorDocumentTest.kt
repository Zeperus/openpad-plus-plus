package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.Block
import io.github.zeperus.openpad.markdown.Gen
import io.github.zeperus.openpad.markdown.MarkdownParser
import io.github.zeperus.openpad.markdown.MarkdownSerializer
import io.github.zeperus.openpad.markdown.OpenPadDocument
import io.github.zeperus.openpad.markdown.RoundTripTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDocumentTest {
    private fun kinds(md: String) = EditorDocument.fromMarkdown(md).rows.map { it.kind::class.simpleName }
    private fun blocksOf(doc: EditorDocument) = doc.toBlocks().first.filter { it != Block.Paragraph(emptyList()) }

    // ---- Rows ------------------------------------------------------------------------------------------------

    @Test fun `the example from the product description`() {
        val doc = EditorDocument.fromMarkdown("# Shopping\n\nBuy **these things** today.\n\n- Milk\n- Bread\n- [ ] Cheese\n")
        val rows = doc.rows
        assertEquals(RowKind.Heading(1), rows[0].kind)
        assertEquals("Shopping", rows[0].text.text)
        assertEquals("Buy these things today.", rows[1].text.text) // no ** in what the user sees
        assertEquals(listOf(Span(SpanKind.Bold, 4, 16)), rows[1].text.spans)
        assertEquals(listOf("Milk", "Bread", "Cheese"), rows.drop(2).take(3).map { it.text.text })
        assertEquals(listOf<Boolean?>(null, null, false), rows.drop(2).take(3).map { (it.kind as RowKind.ListItem).checked })
    }

    @Test fun `visible text never contains markdown syntax for supported constructs`() {
        val doc = EditorDocument.fromMarkdown("# Title\n\n**bold** *it* ~~s~~ `c` [l](u)\n\n- a\n- [x] b\n\n1. one\n\n> quote\n\n---\n\n```\ncode\n```\n")
        val visible = doc.rows.joinToString("\n") { it.text.text }
        for (syntax in listOf("#", "**", "~~", "](", "- [", "```", "> ")) assertFalse("'$syntax' visible in: $visible", visible.contains(syntax))
    }

    @Test fun `kinds of rows`() {
        assertEquals(listOf("Heading", "Paragraph", "ListItem", "ListItem", "Quote", "Rule", "Code", "Paragraph"),
            kinds("# h\n\np\n\n- a\n- b\n\n> q\n\n---\n\n```\nc\n```\n"))
    }

    @Test fun `nested lists become rows with depth`() {
        val doc = EditorDocument.fromMarkdown("- a\n  - b\n    - c\n- d\n")
        assertEquals(listOf(0, 1, 2, 0), doc.rows.filter { it.isListItem }.map { it.depth })
        assertEquals(listOf("a", "b", "c", "d"), doc.rows.filter { it.isListItem }.map { it.text.text })
    }

    @Test fun `numbering of ordered lists`() {
        val doc = EditorDocument.fromMarkdown("3. a\n4. b\n   1. x\n   2. y\n5. c\n")
        val numbers = doc.rows.indices.map { doc.numberOf(it) }
        assertEquals(listOf(3, 4, 1, 2, 5), numbers.filterNotNull())
    }

    @Test fun `text that the editor cannot structure becomes an editable raw row with the original source`() {
        val withCode = "- a\n\n  ```\n  code\n  ```\n- b\n"
        val doc = EditorDocument.fromMarkdown(withCode)
        assertEquals(RowKind.Raw, doc.rows[0].kind)
        assertEquals(withCode.trimEnd('\n'), doc.rows[0].text.text)

        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(table, EditorDocument.fromMarkdown(table).rows[0].text.text)
        assertEquals(RowKind.Raw, EditorDocument.fromMarkdown("<div>x</div>").rows[0].kind)
        assertEquals(RowKind.Raw, EditorDocument.fromMarkdown("> - a\n> - b\n").rows[0].kind) // a quote with a list inside
    }

    @Test fun `there is always a paragraph to type into`() {
        assertEquals(listOf("Paragraph"), kinds(""))
        assertEquals(listOf("Code", "Paragraph"), kinds("```\nx\n```\n"))
        assertEquals(listOf("Rule", "Paragraph"), kinds("---\n"))
        assertEquals(listOf("Raw", "Paragraph"), kinds("<div>x</div>"))
    }

    @Test fun `an empty document is one empty paragraph and writes nothing`() {
        val doc = EditorDocument.empty()
        assertEquals(1, doc.rows.size)
        assertEquals("", doc.toMarkdown())
    }

    @Test fun `row ids are unique and stable`() {
        val doc = EditorDocument.fromMarkdown("a\n\nb\n\n- c\n- d\n")
        assertEquals(doc.rows.size, doc.rows.map { it.id }.toSet().size)
    }

    // ---- Writing back ----------------------------------------------------------------------------------------

    @Test fun `an unedited document is written back as the exact original text`() {
        for ((name, md) in RoundTripTest.corpus) assertEquals(name, md, EditorDocument.fromMarkdown(md).toMarkdown())
    }

    @Test fun `rows turn back into exactly the parsed blocks`() {
        for ((name, md) in RoundTripTest.corpus) {
            assertEquals(name, MarkdownParser.parseDocument(md).blocks, blocksOf(EditorDocument.fromMarkdown(md)))
        }
    }

    @Test fun `rows turn back into exactly the parsed blocks for random documents`() {
        repeat(1500) { seed ->
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 700_000).document()))
            assertEquals("seed ${seed + 700_000}:\n$md", MarkdownParser.parseDocument(md).blocks, blocksOf(EditorDocument.fromMarkdown(md)))
        }
    }

    private fun touchedEverything(md: String): EditorDocument {
        val doc = EditorDocument.fromMarkdown(md)
        return doc.withRows(doc.rows.map { it.copy(touched = true) })
    }

    /** What the editor shows and edits: kinds, text, formatting spans and depth - not ids, markers or tree shapes. */
    private fun rowsOf(md: String): List<Any> = EditorDocument.fromMarkdown(md).rows.filterNot { it.kind == RowKind.Paragraph && it.text.isEmpty }.map { r ->
        val kind: Any = when (val k = r.kind) {
            is RowKind.ListItem -> Triple("item", k.list.ordered, k.checked)
            is RowKind.Code -> "code:${k.info}"
            else -> k
        }
        listOf(kind, r.text.text, look(r.text), r.depth)
    }

    /** How every visible character looks: its formatting and link target. Formatting of whitespace is invisible and ignored. */
    private fun look(t: RichText): List<Any> = t.text.indices.filter { !t.text[it].isWhitespace() }.map { i ->
        listOf(t.kindsAt(i).sorted(), t.linkAt(i)?.let { it.href to it.title })
    }

    @Test fun `even when every row counts as edited the document reads back the same`() {
        for ((name, md) in RoundTripTest.corpus) {
            val rewritten = touchedEverything(md).toMarkdown()
            assertEquals("$name\nrewritten:\n$rewritten", rowsOf(md), rowsOf(rewritten))
        }
    }

    @Test fun `random documents read back the same when fully regenerated from rows`() {
        repeat(1500) { seed ->
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 800_000).document()))
            val rewritten = touchedEverything(md).toMarkdown()
            val a = rowsOf(md)
            val b = rowsOf(rewritten)
            if (a != b) {
                val i = a.indices.firstOrNull { a[it] != b.getOrNull(it) } ?: 0
                throw AssertionError("seed ${seed + 800_000} row $i differs:\n  original:  ${a.getOrNull(i)}\n  rewritten: ${b.getOrNull(i)}")
            }
        }
    }

    @Test fun `unsupported blocks survive being touched`() {
        val md = "<div>\n  raw\n</div>\n\n| a |\n|---|\n| 1 |\n\ntext\n"
        val out = touchedEverything(md).toMarkdown()
        assertTrue(out.contains("<div>\n  raw\n</div>"))
        assertTrue(out.contains("| a |\n|---|\n| 1 |"))
    }

    @Test fun `empty rows left in the editor do not reach the file`() {
        val doc = EditorDocument.fromMarkdown("a\n\nb\n")
        val rows = doc.rows.toMutableList()
        rows.add(1, EditorRow(900, RowKind.Paragraph, RichText(""), touched = true))
        assertEquals("a\n\nb\n", doc.withRows(rows).toMarkdown())
    }
}
