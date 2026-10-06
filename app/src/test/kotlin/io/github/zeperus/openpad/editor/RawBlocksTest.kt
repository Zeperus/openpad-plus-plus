package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBlocksTest {
    // ---- Tables ------------------------------------------------------------------------------------------------

    @Test fun `a GFM table is recognized with header alignment and rows`() {
        val t = RawBlocks.classify("| Name | Qty | Note |\n|:-----|----:|:----:|\n| Milk | 2 | **cold** |\n| Bread | 1 | |\n") as RawBlock.Table
        assertEquals(listOf("Name", "Qty", "Note"), t.header)
        assertEquals(listOf(RawBlock.Align.Start, RawBlock.Align.End, RawBlock.Align.Center), t.aligns)
        assertEquals(listOf(listOf("Milk", "2", "**cold**"), listOf("Bread", "1", "")), t.rows)
    }

    @Test fun `tables without outer pipes and with short or long rows`() {
        val t = RawBlocks.classify("a | b\n--|--\n1\n1 | 2 | 3") as RawBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), t.rows)
    }

    @Test fun `escaped pipes and code spans stay in their cell`() {
        val t = RawBlocks.classify("| a | b |\n|---|---|\n| x \\| y | `p|q` |") as RawBlock.Table
        assertEquals(listOf(listOf("x | y", "`p|q`")), t.rows)
    }

    @Test fun `text that only looks a bit like a table is not one`() {
        assertEquals(RawBlock.Other, RawBlocks.classify("| a | b |\n| c | d |"))
        assertEquals(RawBlock.Other, RawBlocks.classify("just text"))
    }

    // ---- Images ------------------------------------------------------------------------------------------------

    @Test fun `an image paragraph is recognized`() {
        assertEquals(RawBlock.Image("a cat", "https://x.org/cat.png", null), RawBlocks.classify("![a cat](https://x.org/cat.png)"))
        assertEquals(RawBlock.Image("", "img/a.png", "Title"), RawBlocks.classify("![](img/a.png \"Title\")"))
        assertEquals(RawBlock.Image("x", "content://p/1", null), RawBlocks.classify("![x](<content://p/1>)"))
    }

    @Test fun `text around an image is not an image block`() {
        assertEquals(RawBlock.Other, RawBlocks.classify("see ![a](b) here"))
    }

    // ---- HTML --------------------------------------------------------------------------------------------------

    @Test fun `simple html becomes styled runs`() {
        val runs = HtmlSimple.parse("<p>Hello <strong>bold</strong> and <em>it</em><br>next <code>x</code></p>")!!
        assertEquals("Hello bold and it\nnext x", runs.joinToString("") { it.text }.trim())
        assertTrue(runs.any { it.bold && it.text == "bold" })
        assertTrue(runs.any { it.italic && it.text == "it" })
        assertTrue(runs.any { it.code && it.text == "x" })
    }

    @Test fun `pre keeps its whitespace`() {
        val runs = HtmlSimple.parse("<pre>a  b\n  c</pre>")!!
        assertEquals("a  b\n  c", runs.single().text)
        assertTrue(runs.single().code)
    }

    @Test fun `entities are decoded to text and never to markup`() {
        val runs = HtmlSimple.parse("<p>1 &lt; 2 &amp; &lt;script&gt;</p>")!!
        assertEquals("1 < 2 & <script>", runs.joinToString("") { it.text }.trim())
    }

    @Test fun `anything outside the allowlist is shown as source`() {
        for (html in listOf(
            "<script>alert(1)</script>",
            "<style>p{}</style>",
            "<a href=\"https://x.org\">x</a>",
            "<img src=\"x.png\">",
            "<div>block</div>",
            "<p onclick=\"evil()\">x</p>",
            "<iframe src=\"https://x.org\"></iframe>",
            "<!-- hidden -->",
            "<p>unclosed <strong>bold</p>",
            "<table><tr><td>x</td></tr></table>",
        )) {
            assertNull("must not be rendered: $html", HtmlSimple.parse(html))
            assertEquals(RawBlock.Html(null), RawBlocks.classify(html))
        }
    }

    @Test fun `html without tags in the allowlist never produces links or resources`() {
        val block = RawBlocks.classify("<details><summary>x</summary>y</details>")
        assertEquals(RawBlock.Html(null), block)
    }
}

class RawRowsInDocumentTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })

    @Test fun `a table is one raw row that is written back untouched`() {
        val md = "intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\noutro\n"
        val s = session(md)
        assertEquals(listOf(RowKind.Paragraph, RowKind.Raw, RowKind.Paragraph), s.doc.rows.map { it.kind })
        assertTrue(RawBlocks.classify(s.doc.rows[1].text.text) is RawBlock.Table)
        s.onText(s.doc.rows[0].id, "intro!", 6)
        assertEquals("intro!\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\noutro\n", s.markdown())
    }

    @Test fun `an image on its own is a raw row but stays an image paragraph in the file`() {
        val md = "before\n\n![a cat](https://x.org/cat.png)\n\nafter\n"
        val s = session(md)
        assertEquals(RowKind.Raw, s.doc.rows[1].kind)
        assertTrue(RawBlocks.classify(s.doc.rows[1].text.text) is RawBlock.Image)
        s.onText(s.doc.rows[2].id, "after!", 6)
        assertEquals("before\n\n![a cat](https://x.org/cat.png)\n\nafter!\n", s.markdown())
        // editing the source of the image row keeps it an image paragraph
        s.onText(s.doc.rows[1].id, "![a dog](https://x.org/dog.png)", 31)
        assertEquals("before\n\n![a dog](https://x.org/dog.png)\n\nafter!\n", s.markdown())
        assertEquals(RowKind.Raw, EditorDocument.fromMarkdown(s.markdown()).rows[1].kind)
    }

    @Test fun `an image inside a sentence stays text`() {
        val s = session("see ![a](b.png) here\n")
        assertEquals(RowKind.Paragraph, s.doc.rows[0].kind)
        assertEquals("see ![a](b.png) here\n", s.markdown())
    }

    @Test fun `html blocks and unknown markdown are never changed by editing around them`() {
        val md = "<div class=\"x\">\n<script>alert(1)</script>\n</div>\n\n[ref]: https://example.org\n\ntext\n"
        val s = session(md)
        assertTrue(s.doc.rows.count { it.kind == RowKind.Raw } >= 1)
        val last = s.doc.rows.last { it.kind == RowKind.Paragraph && it.text.text == "text" }
        s.onText(last.id, "text!", 5)
        assertEquals(md.replace("text\n", "text!\n"), s.markdown())
    }

    @Test fun `deleting a table by selection and undoing brings the exact table back`() {
        val md = "a\n\n| x | y |\n|---|---|\n| 1 | 2 |\n\nb\n"
        val s = session(md)
        s.deleteSelection(DocumentSelections.selectAll(s.doc)!!)
        assertEquals("", s.markdown().trim())
        s.undo()
        assertEquals(md, s.markdown())
    }
}
