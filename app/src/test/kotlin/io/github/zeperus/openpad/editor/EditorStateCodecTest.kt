package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateCodecTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.type(i: Int, text: String) { now += 5_000; onText(doc.rows[i].id, text, text.length) }

    @Test fun `caret and history survive capture and restore`() {
        val s = session("alpha\n\nbeta\n")
        s.type(0, "alpha!")
        s.type(1, "beta!")
        s.moveCursor(Cursor(s.doc.rows[1].id, 1, 3))
        val state = EditorStateCodec.capture(s)
        val restored = EditorStateCodec.restore(s.markdown(), state)!!
        assertEquals(s.markdown(), restored.markdown())
        assertEquals(1, restored.doc.indexOf(restored.cursor!!.rowId))
        assertEquals(1, restored.cursor!!.start)
        assertEquals(3, restored.cursor!!.end)
        assertTrue(restored.undo())
        assertEquals("alpha!\n\nbeta\n", restored.markdown())
        assertTrue(restored.undo())
        assertEquals("alpha\n\nbeta\n", restored.markdown())
        assertFalse(restored.undo())
        assertTrue(restored.redo())
        assertEquals("alpha!\n\nbeta\n", restored.markdown())
    }

    @Test fun `redo steps are remembered too`() {
        val s = session("a\n")
        s.type(0, "ab")
        s.undo()
        val restored = EditorStateCodec.restore(s.markdown(), EditorStateCodec.capture(s))!!
        assertTrue(restored.redo())
        assertEquals("ab\n", restored.markdown())
    }

    @Test fun `different text means nothing is restored`() {
        val s = session("alpha\n")
        s.type(0, "alpha!")
        val state = EditorStateCodec.capture(s)
        assertNull(EditorStateCodec.restore("alpha!!\n", state))
        assertNull(EditorStateCodec.restore("alpha!\n", state.copy(version = 99)))
    }

    @Test fun `a stored caret that does not fit is clamped instead of failing`() {
        val s = session("short\n")
        val state = EditorStateCodec.capture(s).copy(cursor = PersistedCursor(row = 40, start = 500, end = 900))
        val restored = EditorStateCodec.restore(s.markdown(), state)!!
        assertEquals(0, restored.doc.indexOf(restored.cursor!!.rowId))
        assertEquals(5, restored.cursor!!.start)
        assertEquals(5, restored.cursor!!.end)
    }

    @Test fun `the history is bounded in steps`() {
        val s = session("x\n")
        repeat(80) { s.onText(s.doc.rows[0].id, "x" + "y".repeat(it + 1), it + 2); now += 5_000 }
        val state = EditorStateCodec.capture(s)
        assertTrue(state.undo.size <= EditorStateCodec.MAX_STEPS)
        val restored = EditorStateCodec.restore(s.markdown(), state)!!
        var steps = 0
        while (restored.undo()) steps++
        assertEquals(state.undo.size, steps)
    }

    @Test fun `the newest steps are kept when the size budget runs out`() {
        val big = "word ".repeat(10_000) // 50k characters per step
        val s = session(big + "\n")
        repeat(20) { s.onText(s.doc.rows[0].id, big + "edit $it", big.length + 6); now += 5_000 }
        val state = EditorStateCodec.capture(s)
        assertTrue(state.undo.sumOf { it.markdown.length } <= EditorStateCodec.MAX_HISTORY_CHARS)
        assertTrue(state.undo.isNotEmpty())
        assertTrue(state.undo.last().markdown.contains("edit 18")) // the step right before the last edit
    }

    @Test fun `a very large document keeps its caret but no history`() {
        val huge = "line of text\n\n".repeat(20_000)
        val s = session(huge)
        s.type(0, "changed first line")
        val state = EditorStateCodec.capture(s)
        assertTrue(state.undo.isEmpty())
        assertNotNull(EditorStateCodec.restore(s.markdown(), state))
    }

    @Test fun `restoring never throws on corrupt steps`() {
        val s = session("a\n")
        val state = EditorStateCodec.capture(s).copy(undo = listOf(PersistedStep("\u0000\uD800 broken", PersistedCursor(-3, -1, 99))))
        val restored = EditorStateCodec.restore(s.markdown(), state)
        assertNotNull(restored)
        restored!!.undo() // may do something odd to the text, but not crash
    }

    @Test fun `fingerprints differ for different text and are stable`() {
        assertEquals(EditorStateCodec.fingerprint("a"), EditorStateCodec.fingerprint("a"))
        assertTrue(EditorStateCodec.fingerprint("a") != EditorStateCodec.fingerprint("b"))
        assertEquals(32, EditorStateCodec.fingerprint("anything").length)
    }
}
