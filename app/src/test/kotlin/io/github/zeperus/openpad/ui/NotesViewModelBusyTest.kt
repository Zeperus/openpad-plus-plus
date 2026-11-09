package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.AppLog
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.NoteContent
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The busy signal: an action that is still waiting or running after [NotesViewModel.BUSY_AFTER_MILLIS] makes [NotesViewModel.busy]
 * true until it ends, however it ends; quick actions never set it. And a failure leaves a trace in the log - never the note's text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelBusyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        override suspend fun startupMode() = StartupMode.ResumeSession
        override suspend fun setStartupMode(mode: StartupMode) = Unit
    }

    /** Opening a note waits for [gate] while one is set (a provider that hangs); saving can be made to fail. */
    private class GatedRepository(private val real: NoteRepository) : NoteRepository by real {
        var gate: CompletableDeferred<Unit>? = null
        var failSaves = false

        override suspend fun openNote(id: NoteId): NoteContent {
            gate?.await()
            return real.openNote(id)
        }

        override suspend fun saveNote(id: NoteId, text: String): NoteInfo {
            if (failSaves) throw NoteStorageException("disk full")
            return real.saveNote(id, text)
        }
    }

    private fun TestScope.launch(): Pair<NotesViewModel, GatedRepository> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = GatedRepository(FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher))
        val vm = NotesViewModel(
            repository,
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()), editorStateDispatcher = dispatcher)
        return vm to repository
    }

    /** A saved note "One" and the blank page after it (active). Returns the note's id. */
    private fun oneNote(vm: NotesViewModel): NoteId {
        vm.onTextChange("One")
        vm.newNote()
        return vm.notes.single().id
    }

    @Test fun `quick actions never set busy`() = runTest {
        val (vm, _) = launch()
        assertFalse(vm.busy)
        val one = oneNote(vm)
        vm.onTextChange("Two")
        vm.newNote()
        vm.openNote(one)
        vm.selectTab(DocumentTab.Draft)
        vm.toggleFavorite()
        assertFalse(vm.busy)
        advanceTimeBy(5_000); runCurrent() // no timer of a finished action may fire later
        assertFalse(vm.busy)
    }

    @Test fun `an action that hangs sets busy after the delay and clears it when it ends`() = runTest {
        val (vm, repository) = launch()
        val one = oneNote(vm)
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate

        vm.openNote(one)
        advanceTimeBy(NotesViewModel.BUSY_AFTER_MILLIS - 1); runCurrent()
        assertFalse("not yet: a short wait does not flicker", vm.busy)
        advanceTimeBy(2); runCurrent()
        assertTrue(vm.busy)

        repository.gate = null
        gate.complete(Unit); runCurrent()
        assertFalse(vm.busy)
        assertEquals("One", vm.text)
    }

    @Test fun `an action queued behind a hanging one counts too and busy ends with the last of them`() = runTest {
        val (vm, repository) = launch()
        val one = oneNote(vm)
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate

        vm.openNote(one)
        vm.newNote() // waits for the first action
        advanceTimeBy(NotesViewModel.BUSY_AFTER_MILLIS + 100); runCurrent()
        assertTrue(vm.busy)

        repository.gate = null
        gate.complete(Unit); runCurrent()
        assertFalse(vm.busy)
        assertEquals("", vm.text) // the queued "new note" ran after the note was opened
        advanceTimeBy(5_000); runCurrent()
        assertFalse(vm.busy)
    }

    @Test fun `busy is cleared when the hanging action fails`() = runTest {
        val (vm, repository) = launch()
        val one = oneNote(vm)
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate

        vm.openNote(one)
        advanceTimeBy(NotesViewModel.BUSY_AFTER_MILLIS + 1); runCurrent()
        assertTrue(vm.busy)

        repository.gate = null
        gate.completeExceptionally(NoteStorageException("provider gone")); runCurrent()
        assertFalse(vm.busy)
        assertEquals(UserMessage.ActionFailed, vm.message)
    }

    @Test fun `a failed save leaves a trace in the log without the note's text`() = runTest {
        val logged = ArrayList<Triple<String, String, Throwable?>>()
        val before = AppLog.sink
        AppLog.sink = { tag, message, error -> logged += Triple(tag, message, error) }
        try {
            val (vm, repository) = launch()
            vm.onTextChange("Secret first line\nsecret body")
            advanceTimeBy(1_000); runCurrent() // created
            repository.failSaves = true
            vm.onTextChange("Secret first line\nsecret body, edited")
            advanceTimeBy(1_000); runCurrent() // the autosave fails

            assertTrue(vm.saveFailed)
            assertTrue("the failure is logged", logged.any { it.third is NoteStorageException })
            assertTrue("no note text in the messages", logged.none { it.second.contains("ecret", ignoreCase = true) })
        } finally {
            AppLog.sink = before
        }
    }
}
