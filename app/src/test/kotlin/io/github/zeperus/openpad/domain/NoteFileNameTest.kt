package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFileNameTest {
    @Test fun `plain title gets md extension`() =
        assertEquals("Shopping.md", NoteFileName.toFileName("Shopping"))

    @Test fun `illegal characters are replaced and whitespace collapsed`() =
        assertEquals("a b c", NoteFileName.sanitize("a/b:  c"))

    @Test fun `empty or blank title falls back to default`() {
        assertEquals("Untitled", NoteFileName.sanitize(""))
        assertEquals("Untitled", NoteFileName.sanitize("  ... "))
    }

    @Test fun `existing md extension is not doubled`() =
        assertEquals("Shopping.md", NoteFileName.toFileName("Shopping.MD"))

    @Test fun `reserved names are altered`() {
        assertEquals("con-", NoteFileName.sanitize("con"))
        assertEquals("NUL-", NoteFileName.sanitize("NUL"))
    }

    @Test fun `unique names avoid collisions case-insensitively`() {
        val existing = listOf("Shopping.md", "shopping 2.md")
        assertEquals("Shopping 3.md", NoteFileName.unique("Shopping", existing))
        assertEquals("Todo.md", NoteFileName.unique("Todo", existing))
    }

    @Test fun `title of file name strips extension`() {
        assertEquals("Shopping", NoteFileName.titleOf("Shopping.md"))
        assertEquals("readme.txt", NoteFileName.titleOf("readme.txt"))
    }
}

class NoteFileNameSafetyTest {
    @Test fun `sanitizeOrNull rejects names without usable characters`() {
        assertEquals(null, NoteFileName.sanitizeOrNull(""))
        assertEquals(null, NoteFileName.sanitizeOrNull("???"))
        assertEquals(null, NoteFileName.sanitizeOrNull(" . .. "))
        assertEquals("a", NoteFileName.sanitizeOrNull("a?"))
    }

    @Test fun `path traversal attempts cannot produce separators`() {
        val result = NoteFileName.toFileName("../../etc/passwd")
        assertEquals(false, result.contains('/'))
        assertEquals(false, result.startsWith("."))
        assertEquals("etc passwd.md", result)
    }

    @Test fun `long multi-byte titles stay within the file system limit`() {
        val name = NoteFileName.toFileName("ä".repeat(300))
        assertEquals(true, name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(true, NoteFileName.isSafeFileName(NoteFileName.unique("😀".repeat(200), emptyList())))
    }

    @Test fun `isSafeFileName rejects traversal and separators`() {
        for (bad in listOf("", ".", "..", "a/b.md", "a\\b.md", "x\u0000.md")) {
            assertEquals("'$bad'", false, NoteFileName.isSafeFileName(bad))
        }
        assertEquals(true, NoteFileName.isSafeFileName("Shopping.md"))
    }
}
