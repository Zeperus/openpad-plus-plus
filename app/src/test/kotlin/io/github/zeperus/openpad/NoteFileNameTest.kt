package io.github.zeperus.openpad

import io.github.zeperus.openpad.domain.NoteFileName
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
