package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteListsTest {
    private fun note(
        title: String,
        opened: Long? = null,
        updated: Long = 0,
        favorite: Boolean = false,
        trashed: Boolean = false,
        id: String = title,
    ) = NoteInfo(
        id = NoteId(id), title = title, createdAt = 0, updatedAt = updated,
        trashedAt = if (trashed) 1 else null, autoTitle = true, favorite = favorite, lastOpenedAt = opened,
    )

    private fun titles(list: List<NoteInfo>) = list.map { it.title }

    // ---- Recent ----------------------------------------------------------------------------------------------

    @Test fun `no notes means no recent notes`() = assertTrue(NoteLists.recent(emptyList()).isEmpty())

    @Test fun `notes that were never opened are not recent`() =
        assertTrue(NoteLists.recent(listOf(note("A"), note("B"))).isEmpty())

    @Test fun `one to three notes are all recent, newest first`() {
        assertEquals(listOf("A"), titles(NoteLists.recent(listOf(note("A", opened = 5)))))
        assertEquals(
            listOf("C", "B", "A"),
            titles(NoteLists.recent(listOf(note("A", opened = 1), note("B", opened = 2), note("C", opened = 3)))),
        )
    }

    @Test fun `more than three notes are limited to the three most recently opened`() {
        val notes = (1..6).map { note("N$it", opened = it.toLong()) }
        assertEquals(listOf("N6", "N5", "N4"), titles(NoteLists.recent(notes)))
    }

    @Test fun `favorites are excluded from recent`() {
        val notes = listOf(note("Fav", opened = 100, favorite = true), note("A", opened = 1), note("B", opened = 2))
        assertEquals(listOf("B", "A"), titles(NoteLists.recent(notes)))
    }

    @Test fun `favorites do not use up recent slots`() {
        val notes = listOf(
            note("F1", opened = 10, favorite = true), note("F2", opened = 9, favorite = true),
            note("A", opened = 4), note("B", opened = 3), note("C", opened = 2), note("D", opened = 1),
        )
        assertEquals(listOf("A", "B", "C"), titles(NoteLists.recent(notes)))
    }

    @Test fun `unfavoriting a recently used note makes it recent again`() {
        val favorite = note("Used", opened = 50, favorite = true)
        val others = listOf(note("A", opened = 1), note("B", opened = 2))
        assertEquals(listOf("B", "A"), titles(NoteLists.recent(others + favorite)))
        assertEquals(listOf("Used", "B", "A"), titles(NoteLists.recent(others + favorite.copy(favorite = false))))
    }

    @Test fun `trashed notes are never recent`() {
        val notes = listOf(note("Gone", opened = 99, trashed = true), note("A", opened = 1))
        assertEquals(listOf("A"), titles(NoteLists.recent(notes)))
    }

    @Test fun `recency survives a rename because it is keyed to the note not the title`() {
        val before = listOf(note("Old name", opened = 5, id = "x"), note("B", opened = 4))
        val after = listOf(before[0].copy(title = "New name"), before[1])
        assertEquals(listOf("New name", "B"), titles(NoteLists.recent(after)))
    }

    @Test fun `ties are broken by updatedAt then title then id regardless of input order`() {
        val a = note("alpha", opened = 7, updated = 1, id = "1")
        val b = note("Beta", opened = 7, updated = 9, id = "2") // newer update wins
        val c = note("charlie", opened = 7, updated = 1, id = "3")
        val d = note("Charlie", opened = 7, updated = 1, id = "0") // same title as c ignoring case: id decides
        val expected = listOf("Beta", "alpha", "Charlie") // d (id 0) before c (id 3); limit 3 cuts the last
        listOf(listOf(a, b, c, d), listOf(d, c, b, a), listOf(c, a, d, b)).forEach {
            assertEquals(expected, titles(NoteLists.recent(it)))
        }
    }

    @Test fun `limit can be changed`() {
        val notes = (1..5).map { note("N$it", opened = it.toLong()) }
        assertEquals(2, NoteLists.recent(notes, limit = 2).size)
        assertEquals(3, NoteLists.RECENT_LIMIT)
    }

    // ---- Favorites -------------------------------------------------------------------------------------------

    @Test fun `no favorites means an empty list so the section can be hidden`() =
        assertTrue(NoteLists.favorites(listOf(note("A"), note("B", opened = 1))).isEmpty())

    @Test fun `favorites are listed alphabetically ignoring case`() {
        val notes = listOf(note("banana", favorite = true), note("Apple", favorite = true), note("plain"))
        assertEquals(listOf("Apple", "banana"), titles(NoteLists.favorites(notes)))
    }

    @Test fun `trashed favorites are not shown`() {
        val notes = listOf(note("Gone", favorite = true, trashed = true), note("Kept", favorite = true))
        assertEquals(listOf("Kept"), titles(NoteLists.favorites(notes)))
    }

    @Test fun `a favorite is never in both favorites and recent`() {
        val notes = listOf(note("Fav", opened = 9, favorite = true), note("A", opened = 1))
        val inFavorites = NoteLists.favorites(notes).map { it.id }.toSet()
        assertTrue(NoteLists.recent(notes).none { it.id in inFavorites })
    }
}
