package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FakeExternalDocuments
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.ExportTarget
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
import java.io.IOException

/**
 * The data-safety features of the view model: a document changed by another app is asked about instead of overwritten,
 * a failed save stays visible until a later save succeeds, the app coming back reloads unchanged external documents, and
 * "Export all notes" writes every internal note.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        var mode = StartupMode.ResumeSession
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
    }

    private fun TestScope.disk() = FileNoteRepository(root, clock = { tick++ }, dispatcher = UnconfinedTestDispatcher(testScheduler), external = provider)

    private fun TestScope.launch(repository: NoteRepository? = null, exportTarget: ExportTarget? = null): NotesViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return NotesViewModel(
            repository ?: disk(),
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
            exportTarget = exportTarget,
        )
    }

    private val uri = "content://docs/doc/plan"

    /** An external document opened here, changed by another app, then edited here: the next autosave meets the conflict. */
    private fun TestScope.conflictedViewModel(): NotesViewModel {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        vm.onTextChange("my edit")
        advanceTimeBy(1_000); runCurrent()
        return vm
    }

    // ---- A document changed by another app ---------------------------------------------------------------------

    @Test fun `a document changed by another app is not overwritten and the user is asked`() = runTest {
        val vm = conflictedViewModel()
        assertEquals("Plan", vm.externalConflict)
        assertEquals("changed elsewhere", provider.text(uri))
        assertFalse(vm.saveFailed)
    }

    @Test fun `reload shows the other app's version and keeps the own text in the recovery copy`() = runTest {
        val vm = conflictedViewModel()
        vm.reloadExternal(); runCurrent()
        assertEquals("changed elsewhere", vm.text)
        assertNull(vm.externalConflict)
        assertFalse(vm.saveFailed)
        val backups = File(root, "external-backups").listFiles() ?: emptyArray()
        assertTrue("own text kept in external-backups", backups.any { it.readText() == "my edit" })
    }

    @Test fun `keep mine overwrites the document with the editor text`() = runTest {
        val vm = conflictedViewModel()
        vm.keepMineExternal(); runCurrent()
        assertEquals("my edit", provider.text(uri))
        assertEquals("my edit", vm.text)
        assertNull(vm.externalConflict)
        assertFalse(vm.saveFailed)
    }

    @Test fun `dismissing the question keeps the document and asks again on the next save`() = runTest {
        val vm = conflictedViewModel()
        vm.dismissExternalConflict()
        assertNull(vm.externalConflict)
        vm.onTextChange("my edit, again")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Plan", vm.externalConflict)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `switching tabs during a conflict asks instead of failing silently`() = runTest {
        val vm = conflictedViewModel()
        vm.dismissExternalConflict()
        vm.newNote(); runCurrent()
        assertEquals("Plan", vm.externalConflict)
        assertEquals("my edit", vm.text) // the tab did not change, the text is still here
        assertEquals("Plan", vm.current?.title)
    }

    // ---- Coming back to the app ----------------------------------------------------------------------------------

    @Test fun `coming back to the app reloads an unchanged document that another app changed`() = runTest {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        vm.onAppResumed(); runCurrent()
        assertEquals("changed elsewhere", vm.text)
        assertNull(vm.externalConflict)
    }

    @Test fun `coming back to the app does not replace text that is still being typed`() = runTest {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        vm.onTextChange("typing") // no time passes: not autosaved yet
        vm.onAppResumed(); runCurrent()
        assertEquals("typing", vm.text)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    // ---- The "not saved" state -----------------------------------------------------------------------------------

    /** Passes everything through to [real] except the calls that write note text, which can be made to fail. */
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

    @Test fun `a failed autosave sets the not-saved state until a later save succeeds`() = runTest {
        val flaky = FlakyRepository(disk())
        val vm = launch(repository = flaky)
        vm.onTextChange("first"); advanceTimeBy(1_000); runCurrent()
        assertFalse(vm.saveFailed)

        flaky.failSaves = true
        vm.onTextChange("first, edited"); advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.saveFailed)
        assertEquals(UserMessage.SaveFailed, vm.message)

        flaky.failSaves = false
        vm.onTextChange("first, edited and saved"); advanceTimeBy(1_000); runCurrent()
        assertFalse(vm.saveFailed)
        assertEquals("first, edited and saved", disk().openNote(vm.current!!.id).text)
    }

    @Test fun `retrySave writes the pending text again and clears the not-saved state`() = runTest {
        val flaky = FlakyRepository(disk())
        val vm = launch(repository = flaky)
        vm.onTextChange("first"); advanceTimeBy(1_000); runCurrent()
        val id = vm.current!!.id

        flaky.failSaves = true
        vm.onTextChange("first, edited"); advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.saveFailed)

        flaky.failSaves = false
        vm.retrySave(); runCurrent()
        assertFalse(vm.saveFailed)
        assertEquals("first, edited", disk().openNote(id).text)
    }

    // ---- Export all notes ----------------------------------------------------------------------------------------

    /** Collects what an export writes (file name and text, in order); [failFor] names a file that cannot be written. */
    private class CollectingTarget(private val failFor: String? = null) : ExportTarget {
        val written = ArrayList<Pair<String, String>>()
        override suspend fun write(folderUri: String, fileName: String, text: String) {
            if (fileName == failFor) throw IOException("disk full")
            written += fileName to text
        }
    }

    @Test fun `export all writes every internal note and skips external documents`() = runTest {
        val target = CollectingTarget()
        provider.put(uri, "# Plan\n", name = "Plan.md")
        val vm = launch(exportTarget = target)
        vm.onTextChange("# Shopping\n- milk")
        vm.newNote()
        vm.onTextChange("# Ideas\nsome idea")
        vm.openExternal(uri, persistent = true)

        vm.exportAll("content://tree/export"); runCurrent()

        assertEquals(listOf("Ideas.md" to "# Ideas\nsome idea", "Shopping.md" to "# Shopping\n- milk"), target.written)
        val result = vm.exportResult!!
        assertEquals(2, result.written)
        assertEquals(emptyList<String>(), result.failed)
        assertEquals(1, result.skippedExternal)
        assertFalse(vm.exporting)
        vm.exportResultShown()
        assertNull(vm.exportResult)
    }

    @Test fun `a note that cannot be written is reported by title and the others are still exported`() = runTest {
        val target = CollectingTarget(failFor = "Shopping.md")
        val vm = launch(exportTarget = target)
        vm.onTextChange("# Shopping\n- milk")
        vm.newNote()
        vm.onTextChange("# Ideas\nsome idea")

        vm.exportAll("content://tree/export"); runCurrent()

        assertEquals(listOf("Ideas.md" to "# Ideas\nsome idea"), target.written)
        val result = vm.exportResult!!
        assertEquals(1, result.written)
        assertEquals(listOf("Shopping"), result.failed)
        assertFalse(vm.exporting)
    }
}
