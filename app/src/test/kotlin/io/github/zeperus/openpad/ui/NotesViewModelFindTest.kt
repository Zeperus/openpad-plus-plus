package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
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
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Find in note: the matches are searched again only when the document or the query changes, not on every caret move. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelFindTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        override suspend fun startupMode() = StartupMode.ResumeSession
        override suspend fun setStartupMode(mode: StartupMode) = Unit
    }

    private fun TestScope.launch(): NotesViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return NotesViewModel(
            FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher),
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()), editorStateDispatcher = dispatcher)
    }

    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    /** Types [text] at the end of row [index] the way a text field reports it. */
    private fun NotesViewModel.type(index: Int, text: String) {
        val row = ui.doc.rows[index]
        val end = row.text.length
        onSelection(row.id, end, end)
        onRowText(row.id, row.text.text + text, end + text.length)
    }

    @Test fun `a caret move keeps the matches, an edit and its undo search the document again`() = runTest {
        val vm = launch()
        vm.onTextChange("one two\n\nTWO three\n\ntwo")
        settle()
        vm.startFind("two")
        assertEquals(3, vm.find!!.matches.size)
        val found = vm.find

        vm.onSelection(vm.ui.doc.rows[0].id, 2, 2) // only the caret moved
        assertSame("no new search for a caret move", found, vm.find)
        assertEquals(3, vm.find!!.matches.size)

        vm.type(2, " and two")
        assertEquals(4, vm.find!!.matches.size) // the new text is found
        vm.undo()
        assertEquals(3, vm.find!!.matches.size) // the old text is found again
    }

    @Test fun `a new query searches the document`() = runTest {
        val vm = launch()
        vm.onTextChange("one two\n\nTWO three\n\ntwo")
        settle()
        vm.startFind("two")
        assertEquals(3, vm.find!!.matches.size)
        vm.setFindQuery("three")
        assertEquals(1, vm.find!!.matches.size)
    }
}
