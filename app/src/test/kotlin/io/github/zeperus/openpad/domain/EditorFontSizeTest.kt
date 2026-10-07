package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFontSizeTest {
    @Test fun `the default is the body size of Alpha 6, 16 sp, and the range is 12 to 28 in steps of 1`() {
        assertEquals(16, EditorFontSize.DEFAULT)
        assertEquals(12, EditorFontSize.MIN)
        assertEquals(28, EditorFontSize.MAX)
        assertEquals(1, EditorFontSize.STEP)
    }

    @Test fun `increase and decrease move by one step`() {
        assertEquals(17, EditorFontSize.increased(16))
        assertEquals(15, EditorFontSize.decreased(16))
    }

    @Test fun `the minimum and the maximum clamp`() {
        assertEquals(12, EditorFontSize.decreased(12))
        assertEquals(28, EditorFontSize.increased(28))
        assertEquals(12, EditorFontSize.clamp(-5))
        assertEquals(28, EditorFontSize.clamp(400))
        var v = EditorFontSize.DEFAULT
        repeat(100) { v = EditorFontSize.increased(v) }
        assertEquals(28, v)
        repeat(100) { v = EditorFontSize.decreased(v) }
        assertEquals(12, v)
    }

    @Test fun `a stored value outside the range or missing is the default, not a limit`() {
        assertEquals(16, EditorFontSize.fromStored(null))
        assertEquals(16, EditorFontSize.fromStored(0))
        assertEquals(16, EditorFontSize.fromStored(11))
        assertEquals(16, EditorFontSize.fromStored(29))
        assertEquals(16, EditorFontSize.fromStored(Int.MAX_VALUE))
        assertEquals(12, EditorFontSize.fromStored(12))
        assertEquals(28, EditorFontSize.fromStored(28))
        assertEquals(22, EditorFontSize.fromStored(22))
        assertTrue(EditorFontSize.isValid(20))
        assertFalse(EditorFontSize.isValid(30))
    }
}
