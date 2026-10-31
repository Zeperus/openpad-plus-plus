package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.editor.RowKind
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
 * Destructive actions can be taken back: Clear is one undo step, and a note moved to the Trash can be brought back. The smart
 * checklist switch says what it did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelUndoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        var mode = StartupMode.ResumeSession
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
    }

    /** The view model under test and the real repository used to read what is on disk. */
    private class Harness(val vm: NotesViewModel, val disk: FileNoteRepository)

    private fun TestScope.newHarness(): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val disk = FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher)
        val vm = NotesViewModel(
            disk,
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()), editorStateDispatcher = dispatcher)
        return Harness(vm, disk)
    }

    /** Lets the autosave run. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test fun `clear is one step - undo brings the whole text back - and the emptied note is saved at once`() = runTest {
        val h = newHarness()
        val original = "# Title\n\n- a\n- b\n"
        h.vm.onTextChange(original)
        settle()
        val id = h.vm.current!!.id

        h.vm.clear(); runCurrent()
        assertTrue(h.vm.text.isBlank())
        assertEquals("", h.disk.openNote(id).text) // written at once, without waiting for the autosave

        h.vm.undo(); runCurrent()
        assertEquals(original.trimEnd('\n'), h.vm.text.trimEnd('\n'))
        settle()
        assertEquals(original.trimEnd('\n'), h.disk.openNote(id).text.trimEnd('\n'))
    }

    @Test fun `clear also takes out tables, rules and code blocks`() = runTest {
        val h = newHarness()
        val original = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n---\n\n```\ncode\n```\n"
        h.vm.onTextChange(original)
        settle()
        val id = h.vm.current!!.id

        h.vm.clear(); settle()

        assertTrue(h.vm.ui.doc.rows.all { it.kind == RowKind.Paragraph && it.text.isEmpty })
        assertEquals("", h.disk.openNote(id).text)

        h.vm.undo(); runCurrent()
        assertEquals(original.trimEnd('\n'), h.vm.text.trimEnd('\n'))
    }

    @Test fun `moving a note to Trash says so, and undo brings it back open with its text`() = runTest {
        val h = newHarness()
        val vm = h.vm
        vm.onTextChange("Keep me\nbody text")
        settle()
        val id = vm.current!!.id

        vm.deleteCurrent(); runCurrent()
        assertEquals(UserMessage.MovedToTrash, vm.message)
        assertEquals(listOf(id), vm.trash.map { it.id })

        vm.undoTrash(); runCurrent()
        assertEquals(id, vm.current?.id)
        assertFalse(vm.trash.any { it.id == id })
        assertEquals("Keep me\nbody text", vm.text)
    }

    @Test fun `undo of a Trash move does nothing when the note was restored by hand`() = runTest {
        val h = newHarness()
        val vm = h.vm
        vm.onTextChange("Again")
        settle()
        val id = vm.current!!.id
        vm.deleteCurrent(); runCurrent()

        vm.restore(id); runCurrent()
        vm.undoTrash(); runCurrent()

        assertEquals(listOf(id), vm.notes.map { it.id })
        assertTrue(vm.trash.isEmpty())
        assertNull(vm.current) // restoring does not reopen the note, and the undo must not either
    }

    @Test fun `turning smart checklist on says so, turning it off does not`() = runTest {
        val h = newHarness()
        val vm = h.vm
        vm.onTextChange("- [x] A\n- [ ] B\n")
        settle()

        vm.toggleSmartChecklist(); runCurrent()
        assertTrue(vm.current!!.smartChecklist)
        assertEquals(UserMessage.SmartChecklistOn, vm.message)

        vm.messageShown()
        vm.toggleSmartChecklist(); runCurrent()
        assertFalse(vm.current!!.smartChecklist)
        assertNull(vm.message)
    }
}
