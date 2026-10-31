package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Could not save your note" ([UserMessage.SaveFailed]): a failed write keeps the typed text in the editor and tells the
 * user, and the next change or flush writes the latest text again. The file on disk is never half-written by a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelSaveErrorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        var mode = StartupMode.ResumeSession
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
    }

    /** The view model under test, the repository it writes through, and the real repository used to read the disk. */
    private class Harness(val vm: NotesViewModel, val flaky: FlakyRepository, val disk: NoteRepository)

    private fun TestScope.launch(): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val disk = FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher)
        val flaky = FlakyRepository(disk)
        val vm = NotesViewModel(
            flaky,
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()), editorStateDispatcher = dispatcher)
        return Harness(vm, flaky, disk)
    }

    @Test fun `a failed autosave shows the message and keeps the typed text in the editor`() = runTest {
        val h = launch()
        h.vm.onTextChange("first version")
        advanceTimeBy(1_000); runCurrent()
        val id = h.vm.current!!.id
        assertNull(h.vm.message)

        h.flaky.failSaves = true
        h.vm.onTextChange("first version, edited")
        advanceTimeBy(1_000); runCurrent()

        assertEquals(UserMessage.SaveFailed, h.vm.message)
        assertTrue(h.vm.saveFailed)
        assertEquals("first version, edited", h.vm.text)
        assertEquals("first version", h.disk.openNote(id).text) // the old text is still what is on disk
    }

    @Test fun `the next change after a failure writes the latest text again`() = runTest {
        val h = launch()
        h.vm.onTextChange("draft")
        advanceTimeBy(1_000); runCurrent()
        val id = h.vm.current!!.id

        h.flaky.failSaves = true
        h.vm.onTextChange("draft two")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(UserMessage.SaveFailed, h.vm.message)
        assertTrue(h.vm.saveFailed)

        h.flaky.failSaves = false
        h.vm.onTextChange("draft two, latest")
        advanceTimeBy(1_000); runCurrent()

        assertFalse(h.vm.saveFailed)
        assertEquals("draft two, latest", h.disk.openNote(id).text)
        assertEquals("draft two, latest", h.vm.text)
    }

    @Test fun `flush retries a failed save without any new typing`() = runTest {
        val h = launch()
        h.vm.onTextChange("kept")
        advanceTimeBy(1_000); runCurrent()
        val id = h.vm.current!!.id

        h.flaky.failSaves = true
        h.vm.onTextChange("kept and more")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(UserMessage.SaveFailed, h.vm.message)
        assertTrue(h.vm.saveFailed)

        h.flaky.failSaves = false
        h.vm.flush(); runCurrent()

        assertFalse(h.vm.saveFailed)
        assertEquals("kept and more", h.disk.openNote(id).text)
    }

    @Test fun `a new draft that cannot be created stays in the editor and is created with the full text on retry`() = runTest {
        val h = launch()
        h.flaky.failSaves = true
        val text = "# Shopping\n- milk\n- bread"
        h.vm.onTextChange(text)
        advanceTimeBy(1_000); runCurrent()

        assertEquals(UserMessage.SaveFailed, h.vm.message)
        assertTrue(h.vm.saveFailed)
        assertEquals(text, h.vm.text)
        assertNull(h.vm.current) // still a draft: nothing was created
        assertTrue(h.disk.listNotes().isEmpty())

        h.flaky.failSaves = false
        h.vm.flush(); runCurrent()

        assertFalse(h.vm.saveFailed)
        val created = h.vm.current
        assertTrue("the draft is created on retry", created != null)
        assertEquals(text, h.disk.openNote(created!!.id).text)
        assertEquals(1, h.disk.listNotes().size)
    }

    @Test fun `messageShown clears the save error and the text stays in the editor`() = runTest {
        val h = launch()
        h.flaky.failSaves = true
        h.vm.onTextChange("unsaved words")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(UserMessage.SaveFailed, h.vm.message)

        h.vm.messageShown()

        assertNull(h.vm.message)
        assertTrue("the not-saved state lasts until a save succeeds", h.vm.saveFailed)
        assertEquals("unsaved words", h.vm.text)
    }

    /**
     * Passes everything through to [real] except the two calls that write note text, which can be made to fail.
     * Delegation keeps this compiling when new [NoteRepository] methods are added.
     */
    private class FlakyRepository(private val real: NoteRepository) : NoteRepository by real {
        var failSaves = false
        override suspend fun saveNote(id: NoteId, text: String): NoteInfo {
            if (failSaves) throw NoteStorageException("disk full")
            return real.saveNote(id, text)
        }
        override suspend fun createNote(text: String): NoteInfo {
            if (failSaves) throw NoteStorageException("disk full")
            return real.createNote(text)
        }
    }
}
