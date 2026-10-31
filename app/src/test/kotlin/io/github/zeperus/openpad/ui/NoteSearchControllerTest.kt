package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FakeExternalDocuments
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteRepository
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
import java.io.File

/** The global note search on its own: debounce, stale results, clearing, the open note's unsaved text and the searching flag. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSearchControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var tick = 1_000L
    private val provider = FakeExternalDocuments()
    private lateinit var repository: NoteRepository
    private lateinit var controller: NoteSearchController
    private var listed: List<NoteInfo> = emptyList() // what the view model's list would show
    private var openNote: Pair<NoteId, String>? = null // the open note and its text on screen

    private fun TestScope.setUp() {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = FileNoteRepository(File(tmp.root, "store"), clock = { tick++ }, dispatcher = dispatcher, external = provider)
        controller = NoteSearchController(
            repository,
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
}
