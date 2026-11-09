package io.github.zeperus.openpad.ui.editor

import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.FindMatch
import io.github.zeperus.openpad.editor.Numbering
import io.github.zeperus.openpad.editor.RichText
import io.github.zeperus.openpad.editor.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks a segment field uses to skip work when nothing changed: each must say "the same" only where doing the work again would
 * give exactly the same result.
 */
class SegmentFieldSkipTest {
    private fun segment(md: String) = Segment(EditorDocument.fromMarkdown(md).rows)

    @Test fun `fieldShows agrees with comparing against fieldTextOf`() {
        val seg = segment("# Title\n\n- a\n- b\n\ntext\n")
        val shown = fieldTextOf(seg)
        assertTrue(fieldShows(shown, seg.text))
        assertTrue(fieldShows(StringBuilder(shown), seg.text)) // any CharSequence, not only a String
        assertFalse(fieldShows(seg.text, seg.text)) // the invisible first character is missing
        assertFalse(fieldShows(shown + "x", seg.text))
        assertFalse(fieldShows(shown.dropLast(1), seg.text))
        assertFalse(fieldShows(shown.substring(0, 3) + "?" + shown.substring(4), seg.text)) // same length, one character differs
        assertFalse(fieldShows("x" + seg.text, seg.text)) // same length, another first character
    }

    @Test fun `fieldShows for an empty segment`() {
        val empty = Segment(EditorDocument.empty().rows)
        assertEquals("", empty.text)
        assertTrue(fieldShows(FIELD_PREFIX, empty.text))
        assertFalse(fieldShows("", empty.text))
    }

    @Test fun `sameChars is content equality without copies`() {
        assertTrue(sameChars("abc", "abc"))
        assertTrue(sameChars(StringBuilder("abc"), "abc"))
        assertTrue(sameChars("", StringBuilder()))
        assertFalse(sameChars("abc", "abd"))
        assertFalse(sameChars("abc", StringBuilder("ab")))
        val s = StringBuilder("same")
        assertTrue(sameChars(s, s))
    }

    @Test fun `sameRows holds for new segments of the same row objects only`() {
        val rows = EditorDocument.fromMarkdown("1. a\n2. b\n").rows
        val a = Segment(rows)
        assertTrue(sameRows(a, a))
        assertTrue(sameRows(a, Segment(rows.toList()))) // a new segment of the very same rows
        assertFalse(sameRows(a, Segment(listOf(rows[0], rows[1].copy())))) // an equal but different row object
        assertFalse(sameRows(a, Segment(listOf(rows[0])))) // fewer rows
        assertFalse(sameRows(a, Segment(listOf(rows[1], rows[0])))) // another order
    }

    @Test fun `a decoration draws the same only for the same rows, equal matches and an equal current match`() {
        val rows = EditorDocument.fromMarkdown("alpha\n\nbeta\n").rows
        val seg = Segment(rows)
        val id = rows[0].id
        val matches = mapOf(id to listOf(0..1))
        val current = FindMatch(id, 0, 2)
        val d = DecorationInput(seg, emptyMap(), matches, current)

        assertTrue(d.drawsSame(seg, matches, current))
        assertTrue(d.drawsSame(Segment(rows), HashMap(matches), FindMatch(id, 0, 2))) // equal, not the same instances
        assertFalse(d.drawsSame(seg, emptyMap(), current))
        assertFalse(d.drawsSame(seg, mapOf(id to listOf(1..2)), current))
        assertFalse(d.drawsSame(seg, matches, null))
        assertFalse(d.drawsSame(seg, matches, FindMatch(id, 1, 3)))
        val changed = listOf(rows[0].copy(text = RichText.of("alphx", emptyList()))) + rows.drop(1)
        assertFalse(d.drawsSame(Segment(changed), matches, current))
    }

    @Test fun `numbers are computed once per segment and always agree with Numbering`() {
        val rows = EditorDocument.fromMarkdown("3. a\n4. b\n   1. x\n5. c\n").rows
        val memo = SegmentNumbers()
        val seg = Segment(rows)
        val first = memo.of(seg)
        assertEquals(Numbering.of(rows), first)
        assertSame(first, memo.of(seg))
        assertSame(first, memo.of(Segment(rows.toList()))) // the same row objects: the same numbers

        val fewer = Segment(rows.drop(1))
        val second = memo.of(fewer)
        assertNotSame(first, second)
        assertEquals(Numbering.of(fewer.rows), second)
        assertEquals(Numbering.of(rows), memo.of(seg)) // back to the first segment: computed again, still right
    }
}
