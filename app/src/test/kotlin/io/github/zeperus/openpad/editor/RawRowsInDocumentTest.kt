package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
