package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FakeExternalDocuments
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The global note search on its own: debounce, stale results, clearing, the open note's unsaved text and the searching flag. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSearchControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var tick = 1_000L
    private val provider = FakeExternalDocuments()
    private lateinit var repository: NoteRepository
    private lateinit var counting: CountingRepository // the repository the controller searches through
    private lateinit var controller: NoteSearchController
    private var listed: List<NoteInfo> = emptyList() // what the view model's list would show
    private var openNote: Pair<NoteId, String>? = null // the open note and its text on screen

    private fun TestScope.setUp() {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = FileNoteRepository(File(tmp.root, "store"), clock = { tick++ }, dispatcher = dispatcher, external = provider)
        counting = CountingRepository(repository)
        controller = NoteSearchController(
            counting,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
            notes = { listed },
            openText = { openNote },
            computeDispatcher = dispatcher,
        )
    }

    /** Creates one note per text (the title is the first line) and updates the list the search reads. */
    private suspend fun createNotes(vararg texts: String) {
        texts.forEach { repository.createNote(it) }
        listed = repository.listNotes()
    }

    @Test fun `results appear after the debounce and searching is true until then`() = runTest {
        setUp()
        createNotes("Shopping\nmilk and bread", "Ideas\nnothing here")
        controller.search("milk")
        assertTrue(controller.searching)
        assertTrue(controller.results.isEmpty())
        advanceTimeBy(149); runCurrent()
        assertTrue(controller.searching)
        assertTrue(controller.results.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("Shopping"), controller.results.map { it.note.title })
        assertFalse(controller.searching)
    }

    @Test fun `a new query drops the old results at once`() = runTest {
        setUp()
        createNotes("Shopping\nmilk", "Bakery\nbread")
        controller.search("milk"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Shopping"), controller.results.map { it.note.title })

        controller.search("bread")
        assertTrue(controller.results.isEmpty())
        assertTrue(controller.searching)
        advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Bakery"), controller.results.map { it.note.title })
        assertFalse(controller.searching)
    }

    @Test fun `a superseded query never publishes its results`() = runTest {
        setUp()
        createNotes("Shopping\nmilk", "Bakery\nbread")
        controller.search("milk"); advanceTimeBy(100)
        controller.search("bread"); advanceTimeBy(100); runCurrent()
        assertTrue(controller.results.isEmpty())
        assertTrue(controller.searching)
        advanceTimeBy(50); runCurrent()
        assertEquals(listOf("Bakery"), controller.results.map { it.note.title })
    }

    @Test fun `a blank query clears the search and nothing comes back afterwards`() = runTest {
        setUp()
        createNotes("Shopping\nmilk")
        controller.search("milk"); advanceTimeBy(150); runCurrent()
        assertEquals(1, controller.results.size)

        controller.search("   ")
        assertTrue(controller.results.isEmpty())
        assertFalse(controller.searching)

        controller.search("milk"); advanceTimeBy(150); runCurrent()
        controller.clear()
        assertEquals("", controller.query)
        assertTrue(controller.results.isEmpty())
        assertFalse(controller.searching)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(controller.results.isEmpty())
    }

    @Test fun `cancel stops a search in progress`() = runTest {
        setUp()
        createNotes("Shopping\nmilk")
        controller.search("milk")
        controller.cancel()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(controller.results.isEmpty())
        assertFalse(controller.searching)
    }

    @Test fun `the open note is searched with its unsaved text`() = runTest {
        setUp()
        createNotes("Draft\nold text")
        openNote = listed.single().id to "Draft\nfresh text"

        controller.search("old text"); advanceTimeBy(150); runCurrent()
        assertTrue(controller.results.isEmpty())

        controller.search("fresh text"); advanceTimeBy(150); runCurrent()
        val hit = controller.results.single()
        assertEquals("Draft", hit.note.title)
        assertEquals("fresh text", hit.snippet)
    }

    @Test fun `searching an external document does not change what a later save compares against`() = runTest {
        setUp()
        provider.put("content://docs/doc/plan", "original", name = "Plan.md")
        val id = repository.openExternal("content://docs/doc/plan", persistent = true).id
        repository.openNote(id) // the app has read the document: this is the baseline
        listed = repository.listNotes()
        provider.put("content://docs/doc/plan", "changed elsewhere")

        controller.search("changed"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Plan"), controller.results.map { it.note.title })

        try {
            repository.saveNote(id, "my edit")
            fail("the search moved the baseline: the other app's change would be overwritten")
        } catch (_: NoteChangedExternallyException) {
            // expected: the change is still a conflict
        }
        assertEquals("changed elsewhere", provider.text("content://docs/doc/plan"))
    }

    @Test fun `two queries in a row read each internal note only once`() = runTest {
        setUp()
        createNotes("Shopping\nmilk and bread", "Bakery\nbread")
        val ids = listed.map { it.id }
        controller.search("milk"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf(1, 1), ids.map { counting.readsOf(it) })

        controller.search("bread"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Bakery", "Shopping"), controller.results.map { it.note.title }.sorted())
        assertEquals(listOf(1, 1), ids.map { counting.readsOf(it) })
    }

    @Test fun `a note changed since the last search is read again`() = runTest {
        setUp()
        createNotes("Shopping\nmilk", "Bakery\nbread")
        val shopping = listed.first { it.title == "Shopping" }.id
        val bakery = listed.first { it.title == "Bakery" }.id
        controller.search("milk"); advanceTimeBy(150); runCurrent()

        repository.saveNote(shopping, "Shopping\neggs and milk")
        listed = repository.listNotes()
        controller.search("eggs"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Shopping"), controller.results.map { it.note.title })
        assertEquals(2, counting.readsOf(shopping))
        assertEquals(1, counting.readsOf(bakery))
    }

    @Test fun `after clear the next search reads the files again`() = runTest {
        setUp()
        createNotes("Shopping\nmilk")
        val id = listed.single().id
        controller.search("milk"); advanceTimeBy(150); runCurrent()
        assertEquals(1, counting.readsOf(id))

        controller.clear()
        controller.search("milk"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Shopping"), controller.results.map { it.note.title })
        assertEquals(2, counting.readsOf(id))
    }

    @Test fun `an external document is read on every search`() = runTest {
        setUp()
        provider.put("content://docs/doc/plan", "original", name = "Plan.md")
        val id = repository.openExternal("content://docs/doc/plan", persistent = true).id
        listed = repository.listNotes()

        controller.search("original"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Plan"), controller.results.map { it.note.title })
        assertEquals(1, counting.readsOf(id))

        provider.put("content://docs/doc/plan", "changed elsewhere")
        controller.search("changed"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf("Plan"), controller.results.map { it.note.title })
        assertEquals(2, counting.readsOf(id))
    }
}

/** Passes everything to [inner] and counts the [peekText] reads per note, so the tests can see how often files are read. */
private class CountingRepository(private val inner: NoteRepository) : NoteRepository by inner {
    private val reads = HashMap<NoteId, Int>()

    fun readsOf(id: NoteId): Int = reads[id] ?: 0

    override suspend fun peekText(id: NoteId): String {
        reads[id] = readsOf(id) + 1
        return inner.peekText(id)
    }
}
