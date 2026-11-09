package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppLogTest {
    @Test fun `an error is described by its classes and frames, never by its message`() {
        val error = NoteStorageException("Could not read Diary of Anna.md", IOException("/storage/emulated/0/Documents/Secret plan.md"))

        val text = AppLog.describe(error)

        assertTrue(text, text.startsWith(NoteStorageException::class.java.name))
        assertTrue(text, text.contains("caused by java.io.IOException"))
        assertTrue("the place it was thrown is kept", text.contains("    at "))
        assertFalse(text, text.contains("Anna"))
        assertFalse(text, text.contains("Secret"))
    }

    @Test fun `no error is described as nothing`() {
        assertEquals("", AppLog.describe(null))
    }

    @Test fun `a failing sink never throws into the caller`() {
        val before = AppLog.sink
        try {
            AppLog.sink = { _, _, _ -> throw IllegalStateException("log unavailable") }
            AppLog.e("test", "something failed")
        } finally {
            AppLog.sink = before
        }
    }
}
