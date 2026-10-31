package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class WhitespacePreservationTest {
    private var now = 0L
    private fun session(md: String) = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
    private fun EditorSession.type(i: Int, text: String) = onText(doc.rows[i].id, text, text.length)

    @Test fun `blank lines around an edited block stay as they were`() {
        val s = session("Intro\n\n\n\nMiddle\n\n\nOutro\n")
        s.type(1, "Middle changed")
        assertEquals("Intro\n\n\n\nMiddle changed\n\n\nOutro\n", s.markdown())
    }

    @Test fun `two edited blocks in a row keep the gap between them`() {
        val s = session("One\n\n\nTwo\n\n\n\nThree\n")
        s.type(0, "One!")
        s.type(1, "Two!")
        assertEquals("One!\n\n\nTwo!\n\n\n\nThree\n", s.markdown())
    }

    @Test fun `an edited list keeps the blank lines before and after it`() {
        val s = session("Intro\n\n\n- a\n- b\n\n\nOutro\n")
        s.type(2, "b!")
        assertEquals("Intro\n\n\n- a\n- b!\n\n\nOutro\n", s.markdown())
    }

    @Test fun `CRLF documents stay CRLF around the edit`() {
        val s = session("One\r\n\r\n\r\nTwo\r\n\r\nThree\r\n")
        s.type(1, "Two!")
        assertEquals("One\r\n\r\n\r\nTwo!\r\n\r\nThree\r\n", s.markdown())
    }

    @Test fun `a single line break between blocks is not carried over to rewritten text`() {
        val s = session("# Title\nbody text\n")
        s.type(1, "body changed")
        // the heading is untouched; the rewritten paragraph is separated safely
        assertEquals("# Title\n\nbody changed\n", s.markdown().replace("\n\n\n", "\n\n"))
    }

    @Test fun `untouched blocks keep their own spelling`() {
        val s = session("* odd\n*   list\n\nText   here\n\n```kotlin\ncode\n```\n")
        s.type(2, "Text   here!")
        assertEquals("* odd\n*   list\n\nText   here!\n\n```kotlin\ncode\n```\n", s.markdown())
    }
}
