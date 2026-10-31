package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExportTest {
    private fun note(id: String, title: String, externalUri: String? = null, trashedAt: Long? = null) = NoteInfo(
        id = NoteId(id),
        title = title,
        createdAt = 0L,
        updatedAt = 0L,
        trashedAt = trashedAt,
        autoTitle = false,
        externalUri = externalUri,
    )

    private fun names(vararg notes: NoteInfo): List<String> = NoteExport.plan(notes.toList()).map { it.fileName }

    @Test fun `external documents are skipped`() =
        assertEquals(listOf("Mine.md"), names(note("1", "Mine"), note("2", "Outside", externalUri = "content://x/doc/2")))

    @Test fun `trashed notes are skipped`() =
        assertEquals(listOf("Mine.md"), names(note("1", "Mine"), note("2", "Old", trashedAt = 5L)))

    @Test fun `equal titles get unique names case-insensitively`() =
        assertEquals(listOf("A.md", "a 2.md"), names(note("1", "A"), note("2", "a")))

    @Test fun `three case variants count up`() =
        assertEquals(
            listOf("Shopping.md", "shopping 2.md", "SHOPPING 3.md"),
            names(note("1", "Shopping"), note("2", "shopping"), note("3", "SHOPPING")),
        )

    @Test fun `generated names never collide with a later title`() =
        // Sorted: "Shopping" (2), "Shopping" (3), "Shopping 2" (1). The third one must not take "Shopping 2" again.
        assertEquals(
            listOf("Shopping.md", "Shopping 2.md", "Shopping 2 2.md"),
            names(note("1", "Shopping 2"), note("2", "Shopping"), note("3", "Shopping")),
        )

    @Test fun `forbidden characters are replaced by spaces`() {
        assertEquals(listOf("a b c.md"), names(note("1", "a/b:c?")))
        assertEquals(listOf("etc passwd.md"), names(note("1", "../../etc/passwd")))
    }

    @Test fun `every generated name is a safe file name`() {
        val result = names(note("1", "a/b\\c"), note("2", "con"), note("3", "x".repeat(400)), note("4", "ä".repeat(300)))
        for (name in result) assertTrue(name, NoteFileName.isSafeFileName(name))
    }

    @Test fun `blank or unusable titles use the default title`() =
        assertEquals(
            listOf("Untitled.md", "Untitled 2.md", "Untitled 3.md"),
            names(note("1", "   "), note("2", ""), note("3", "...")),
        )

    @Test fun `order is by title case-insensitively regardless of input order`() {
        val apple = note("1", "Apple")
        val banana = note("2", "banana")
        val cherry = note("3", "Cherry")
        assertEquals(listOf("Apple.md", "banana.md", "Cherry.md"), names(cherry, banana, apple))
        assertEquals(listOf("Apple.md", "banana.md", "Cherry.md"), names(apple, banana, cherry))
    }

    @Test fun `equal titles are ordered by id`() {
        val items = NoteExport.plan(listOf(note("b", "X"), note("a", "X")))
        assertEquals(listOf(NoteId("a"), NoteId("b")), items.map { it.id })
        assertEquals(listOf("X.md", "X 2.md"), items.map { it.fileName })
    }
}
