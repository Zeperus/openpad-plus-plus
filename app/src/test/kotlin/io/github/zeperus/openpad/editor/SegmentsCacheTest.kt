package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Segments.splitFor] and [Segments.containing]: the one-entry cache must never give a result that [Segments.split] would not. */
class SegmentsCacheTest {
    private val md = "before\n\n---\n\nafter\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nend\n"

    /** The items as values that compare by content: a text item by its row ids and text, a block by its row id. */
    private fun shape(items: List<DocItem>): List<Any> = items.map {
        when (it) {
            is DocItem.Text -> it.segment.ids to it.segment.text
            is DocItem.Block -> it.row.id
        }
    }

    @Test fun `the same document and mode give the same list instance`() {
        val doc = EditorDocument.fromMarkdown(md)
        val first = Segments.splitFor(doc, emptySet())
        assertSame(first, Segments.splitFor(doc, emptySet()))
        // an equal set is the same mode
        assertSame(first, Segments.splitFor(doc, setOf<Long>()))
    }

    @Test fun `a different mode or a different document instance recomputes`() {
        val doc = EditorDocument.fromMarkdown(md)
        val table = doc.rows.first { it.kind == RowKind.Raw }
        val asBlocks = Segments.splitFor(doc, emptySet())
        val asSource = Segments.splitFor(doc, setOf(table.id))
        assertNotSame(asBlocks, asSource)
        assertEquals(shape(Segments.split(doc) { Segments.isBlock(it, emptySet()) }), shape(asBlocks))
        assertEquals(shape(Segments.split(doc) { Segments.isBlock(it, setOf(table.id)) }), shape(asSource))
        // the last mode asked for is the one that is cached
        assertSame(asSource, Segments.splitFor(doc, setOf(table.id)))

        // the same text in another instance is another document: its result is computed afresh
        val other = EditorDocument.fromMarkdown(md)
        val again = Segments.splitFor(other, setOf(table.id))
        assertNotSame(asSource, again)
        assertEquals(shape(asSource), shape(again))
    }

    @Test fun `containing finds the text segment of a row and no segment for a block`() {
        val doc = EditorDocument.fromMarkdown(md)
        val after = doc.rows.first { it.text.text == "after" }
        assertEquals("after", Segments.containing(doc, after.id, emptySet())?.text)

        val table = doc.rows.first { it.kind == RowKind.Raw }
        assertNull(Segments.containing(doc, table.id, emptySet()))
        // opened as source, the table is text again and is in the segment of its neighbours
        val asSource = Segments.containing(doc, table.id, setOf(table.id))
        assertTrue(asSource != null && table.id in asSource.ids)
    }
}
