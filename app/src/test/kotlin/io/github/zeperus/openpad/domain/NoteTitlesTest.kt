package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTitlesTest {
    @Test fun `first non-blank line is used`() =
        assertEquals("Shopping", NoteTitles.derive("\n  \nShopping\nmilk"))

    @Test fun `markdown block markers are stripped`() {
        assertEquals("Shopping", NoteTitles.derive("# Shopping"))
        assertEquals("Milk", NoteTitles.derive("- [ ] Milk"))
        assertEquals("Milk", NoteTitles.derive("> - [x] Milk"))
        assertEquals("First", NoteTitles.derive("1. First"))
    }

    @Test fun `nothing meaningful gives null`() {
        assertNull(NoteTitles.derive(""))
        assertNull(NoteTitles.derive("  \n\t\n"))
        assertNull(NoteTitles.derive("#  "))
    }

    @Test fun `long lines are truncated`() =
        assertEquals(60, NoteTitles.derive("x".repeat(200))!!.length)
}
