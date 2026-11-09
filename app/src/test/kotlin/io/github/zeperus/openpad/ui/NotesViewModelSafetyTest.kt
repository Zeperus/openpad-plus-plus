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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
            exportTarget = exportTarget, editorStateDispatcher = dispatcher)
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
        assertTrue(vm.conflictDialogVisible)
        assertEquals("changed elsewhere", provider.text(uri))
        assertFalse(vm.saveFailed)
    }

    @Test fun `reload shows the other app's version and keeps the own text as a note`() = runTest {
        val vm = conflictedViewModel()
        vm.reloadExternal("Plan (my version)"); runCurrent()
        assertEquals("changed elsewhere", vm.text)
        assertNull(vm.externalConflict)
        assertFalse(vm.conflictDialogVisible)
        assertFalse(vm.saveFailed)
        val copy = vm.notes.single { it.title == "Plan (my version)" }
        assertFalse(copy.isExternal)
        assertEquals("my edit", disk().openNote(copy.id).text)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `a second reload tap does nothing`() = runTest {
        val vm = conflictedViewModel()
        vm.reloadExternal("Plan (my version)")
        vm.reloadExternal("Plan (my version)") // the double tap
        runCurrent()
        assertEquals(1, vm.notes.count { it.title == "Plan (my version)" })
        assertEquals("changed elsewhere", vm.text)
        assertNull(vm.externalConflict)
    }

    @Test fun `keep mine overwrites the document with the editor text`() = runTest {
        val vm = conflictedViewModel()
        vm.keepMineExternal(); runCurrent()
        assertEquals("my edit", provider.text(uri))
        assertEquals("my edit", vm.text)
        assertNull(vm.externalConflict)
        assertFalse(vm.conflictDialogVisible)
        assertFalse(vm.saveFailed)
    }

    @Test fun `dismissing the question keeps the conflict and asks again on the next save`() = runTest {
        val vm = conflictedViewModel()
        vm.dismissExternalConflict()
        assertEquals("Plan", vm.externalConflict) // the conflict stays, only the dialog is hidden
        assertFalse(vm.conflictDialogVisible)
        vm.onTextChange("my edit, again")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Plan", vm.externalConflict)
        assertTrue(vm.conflictDialogVisible)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `a dismissed conflict can be shown again`() = runTest {
        val vm = conflictedViewModel()
        vm.dismissExternalConflict()
        vm.showConflictDialog()
        assertTrue(vm.conflictDialogVisible)
    }

    @Test fun `switching tabs during a conflict asks again instead of failing silently`() = runTest {
        val vm = conflictedViewModel()
        vm.dismissExternalConflict()
        vm.newNote(); runCurrent()
        assertEquals("Plan", vm.externalConflict)
        assertTrue(vm.conflictDialogVisible)
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

    @Test fun `text typed while the document is read on coming back is kept and the conflict is asked`() = runTest {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        provider.onRead = { vm.onTextChange("typed meanwhile") }
        vm.onAppResumed(); runCurrent()
        provider.onRead = null
        assertEquals("typed meanwhile", vm.text)
        assertEquals("Plan", vm.externalConflict)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `text typed during the read on coming back still meets the conflict in the next autosave`() = runTest {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        provider.onRead = { vm.onTextChange("typed meanwhile") }
        vm.onAppResumed(); runCurrent()
        provider.onRead = null

        advanceTimeBy(1_000); runCurrent() // the pending autosave runs
        assertEquals("Plan", vm.externalConflict)
        assertEquals("typed meanwhile", vm.text)
        assertEquals("changed elsewhere", provider.text(uri)) // the other app's version is not overwritten
    }

    @Test fun `after coming back and taking the other version, the next edit is saved without a conflict`() = runTest {
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        vm.onAppResumed(); runCurrent()
        assertEquals("changed elsewhere", vm.text)

        vm.onTextChange("changed elsewhere, and my line")
        advanceTimeBy(1_000); runCurrent()
        assertNull(vm.externalConflict)
        assertFalse(vm.saveFailed)
        assertEquals("changed elsewhere, and my line", provider.text(uri))
    }

    // ---- The "not saved" state -----------------------------------------------------------------------------------

    /** Passes everything through to [real] except the calls that write note text, which can be made to fail. */
    private class FlakyRepository(private val real: NoteRepository) : NoteRepository by real {
        var failSaves = false
        var failTrash = false
        override suspend fun saveNote(id: NoteId, text: String): NoteInfo {
            if (failSaves) throw NoteStorageException("disk full")
            return real.saveNote(id, text)
        }
        override suspend fun createNote(text: String): NoteInfo {
            if (failSaves) throw NoteStorageException("disk full")
            return real.createNote(text)
        }
        override suspend fun moveToTrash(id: NoteId): NoteInfo {
            if (failTrash) throw NoteStorageException("trash broken")
            return real.moveToTrash(id)
        }
    }

    @Test fun `an action that fails for another reason is not reported as a save failure`() = runTest {
        val flaky = FlakyRepository(disk())
        val vm = launch(repository = flaky)
        vm.onTextChange("first"); advanceTimeBy(1_000); runCurrent()

        flaky.failSaves = true
        vm.onTextChange("first, edited"); advanceTimeBy(1_000); runCurrent()
        assertEquals(UserMessage.SaveFailed, vm.message) // the autosave failed earlier

        flaky.failSaves = false
        flaky.failTrash = true
        vm.deleteCurrent(); runCurrent() // the save before the trash works; the trash itself fails

        assertEquals(UserMessage.ActionFailed, vm.message)
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

    @Test fun `a failed save before an action is reported as a save failure`() = runTest {
        val flaky = FlakyRepository(disk())
        val vm = launch(repository = flaky)
        vm.onTextChange("first"); advanceTimeBy(1_000); runCurrent()

        flaky.failSaves = true
        vm.onTextChange("first, edited") // no time passes: the action below saves it first, and that save fails
        vm.newNote(); runCurrent()

        assertEquals(UserMessage.SaveFailed, vm.message)
        assertTrue(vm.saveFailed)
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

    /** Records the file names written, but every write waits until [gate] is completed, so an export can be seen running. */
    private class GatedTarget(private val gate: CompletableDeferred<Unit>) : ExportTarget {
        val written = ArrayList<String>()
        override suspend fun write(folderUri: String, fileName: String, text: String) {
            gate.await()
            written += fileName
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

    @Test fun `export all still writes the internal notes while an external document has a conflict`() = runTest {
        val target = CollectingTarget()
        provider.put(uri, "original", name = "Plan.md")
        val vm = launch(exportTarget = target)
        vm.onTextChange("# Shopping\n- milk"); advanceTimeBy(1_000); runCurrent()
        vm.openExternal(uri, persistent = true)
        provider.put(uri, "changed elsewhere")
        vm.onTextChange("my edit"); advanceTimeBy(1_000); runCurrent()
        assertEquals("Plan", vm.externalConflict)

        vm.exportAll("content://tree/export"); runCurrent()

        assertEquals(listOf("Shopping.md" to "# Shopping\n- milk"), target.written)
        val result = vm.exportResult!!
        assertEquals(1, result.written)
        assertEquals(1, result.skippedExternal)
        assertFalse(vm.exporting)
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `a second export while one is running does nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val target = GatedTarget(gate)
        val vm = launch(exportTarget = target)
        vm.onTextChange("# Shopping\n- milk")
        vm.newNote()
        vm.onTextChange("# Ideas\nsome idea")

        vm.exportAll("content://tree/export"); runCurrent()
        assertTrue(vm.exporting)
        vm.exportAll("content://tree/export"); runCurrent()

        gate.complete(Unit); runCurrent()
        assertFalse(vm.exporting)
        assertEquals(listOf("Ideas.md", "Shopping.md"), target.written) // each note once
        assertEquals(2, vm.exportResult!!.written)
    }
}
