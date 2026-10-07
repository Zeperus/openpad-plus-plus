package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileEditorStateStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The text size is a visual preference: it must never touch a note's content, undo history, saving or the existence of a blank page. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorFontSizeViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var tick = 1_000L

    /** Survives "restarts" like the DataStore file does. */
    private class Settings : SettingsStore {
        var size: Int? = null
        override suspend fun startupMode() = StartupMode.ResumeSession
        override suspend fun setStartupMode(mode: StartupMode) = Unit
        override suspend fun editorFontSize() = io.github.zeperus.openpad.domain.EditorFontSize.fromStored(size)
        override suspend fun setEditorFontSize(sp: Int) { size = sp }
    }

    private val settings = Settings()

    private fun TestScope.launch(): NotesViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return NotesViewModel(
            FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher),
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
            FileEditorStateStore(File(root, "editor-state.json"), dispatcher),
        )
    }

    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    @Test fun `the default size is 16 sp`() = runTest {
        assertEquals(16, launch().editorFontSize)
    }

    @Test fun `increasing and decreasing move by one sp and stop at the limits`() = runTest {
        val vm = launch()
        vm.increaseEditorFontSize(); assertEquals(17, vm.editorFontSize)
        vm.decreaseEditorFontSize(); vm.decreaseEditorFontSize(); assertEquals(15, vm.editorFontSize)
        repeat(30) { vm.decreaseEditorFontSize() }
        assertEquals(12, vm.editorFontSize)
        repeat(40) { vm.increaseEditorFontSize() }
        assertEquals(28, vm.editorFontSize)
        vm.chooseEditorFontSize(1000); assertEquals(28, vm.editorFontSize)
        vm.chooseEditorFontSize(-1); assertEquals(12, vm.editorFontSize)
    }

    @Test fun `the size is saved and comes back after a restart`() = runTest {
        val first = launch()
        first.chooseEditorFontSize(22); runCurrent()
        assertEquals(22, settings.size)
        assertEquals(22, launch().editorFontSize)
    }

    @Test fun `a corrupted stored size means the default`() = runTest {
        settings.size = 9999
        assertEquals(16, launch().editorFontSize)
    }

    @Test fun `changing the size does not change the Markdown, the undo history or the saved file`() = runTest {
        val vm = launch()
        vm.onTextChange("# Plan\n\n- [ ] milk\n")
        vm.flush(); runCurrent()
        val file = mdFiles().single()
        val before = file.readBytes()
        val modified = file.lastModified()
        val canUndo = vm.ui.canUndo
        val text = vm.text
        val rows = vm.ui.doc.rows.map { it.id to it.text.text }
        val id = vm.current!!.id
        vm.chooseEditorFontSize(24); vm.chooseEditorFontSize(12); vm.increaseEditorFontSize()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(canUndo, vm.ui.canUndo)
        assertEquals(text, vm.text)
        assertEquals(rows, vm.ui.doc.rows.map { it.id to it.text.text })
        assertEquals(id, vm.current!!.id)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(modified, file.lastModified()) // not even rewritten
        assertEquals(1, mdFiles().size)
    }

    @Test fun `changing the size on a blank page creates no note and no undo step`() = runTest {
        val vm = launch()
        vm.chooseEditorFontSize(20); vm.chooseEditorFontSize(28)
        advanceTimeBy(5_000); runCurrent()
        assertNull(vm.current)
        assertTrue(mdFiles().isEmpty())
        assertFalse(vm.ui.canUndo)
        assertFalse(vm.hasNote)
    }

    @Test fun `the caret keeps its logical position`() = runTest {
        val vm = launch()
        vm.onTextChange("one two three")
        val row = vm.ui.doc.rows[0].id
        vm.onSelection(row, 4, 7)
        val cursor = vm.ui.cursor
        vm.chooseEditorFontSize(28)
        assertEquals(cursor, vm.ui.cursor)
    }
}
