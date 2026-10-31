package io.github.zeperus.openpad.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLabelTest {
    @Test fun `a text of up to 60 characters is the label unchanged`() {
        assertEquals("Buy milk", taskLabel("Buy milk"))
        val exactly60 = "x".repeat(60)
        assertEquals(exactly60, taskLabel(exactly60))
    }

    @Test fun `a longer text is cut at the last space before 60 characters, with an ellipsis`() {
        // 87 characters; the spaces are at 40 and 56, so the cut is at 56
        val text = "a".repeat(40) + " " + "b".repeat(15) + " " + "c".repeat(30)
        assertEquals("a".repeat(40) + " " + "b".repeat(15) + "…", taskLabel(text))
    }

    @Test fun `a space at or before position 30 is no cut point - the text is cut at 60`() {
        val text = "a".repeat(10) + " " + "b".repeat(80)
        assertEquals("a".repeat(10) + " " + "b".repeat(49) + "…", taskLabel(text))
    }

    @Test fun `a long text without spaces is cut at 60, with an ellipsis`() {
        assertEquals("x".repeat(60) + "…", taskLabel("x".repeat(100)))
    }

    @Test fun `the cut never splits an emoji`() {
        val emoji = String(Character.toChars(0x1F600)) // two chars: a high and a low surrogate
        // the high surrogate sits at index 59, the last char before the cut at 60: the cut moves to 59
        assertEquals("x".repeat(59) + "…", taskLabel("x".repeat(59) + emoji + "y".repeat(10)))
        // the whole emoji lies before the cut: it stays
        assertEquals("x".repeat(58) + emoji + "…", taskLabel("x".repeat(58) + emoji + "y".repeat(10)))
    }
}
