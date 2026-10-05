package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private fun NotesViewModel.noteFile(title: String) =
        File(root, "notes/${notes.first { it.title == title }.id.value}.md")

    private fun NotesViewModel.trashFile(title: String) =
        File(root, "trash/${trash.first { it.title == title }.id.value}.md")

    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    /** A view model over real files in [root]; "restarting the app" is just calling this again. */
    private fun TestScope.newViewModel(): NotesViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FileNoteRepository(root, dispatcher = dispatcher)
        return NotesViewModel(repo, CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()))
    }

    @Test fun `starts with a blank draft and no files`() = runTest {
        val vm = newViewModel()
        assertEquals("", vm.text)
        assertNull(vm.current)
        assertFalse(vm.hasNote)
        assertTrue(vm.notes.isEmpty())
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `typing autosaves a real md file and lists it under Files`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("# Shopping\n- milk")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Shopping", vm.current?.title)
        assertEquals("# Shopping\n- milk", vm.noteFile("Shopping").readText())
        assertEquals(listOf("Shopping"), vm.notes.map { it.title })
    }

    @Test fun `nothing is written before the idle delay`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("hello")
        advanceTimeBy(300); runCurrent()
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `flush writes pending text immediately`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("quick")
        vm.flush(); runCurrent()
        assertEquals("quick", vm.noteFile("quick").readText())
    }

    @Test fun `note is still there after restarting the app`() = runTest {
        val first = newViewModel()
        first.onTextChange("Persistent\nbody")
        first.flush(); runCurrent()

        val second = newViewModel()
        assertEquals("", second.text) // fresh blank page ...
        assertEquals(listOf("Persistent"), second.notes.map { it.title }) // ... but the note is in Files
        second.openNote(second.notes.single().id)
        assertEquals("Persistent\nbody", second.text)
    }

    @Test fun `new note on a blank page does not stack up drafts`() = runTest {
        val vm = newViewModel()
        repeat(5) { vm.newNote() }
        assertTrue(mdFiles().isEmpty())
        assertNull(vm.current)
    }

    @Test fun `new note saves the current one and shows a blank page`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("First")
        vm.newNote()
        assertEquals("", vm.text)
        assertNull(vm.current)
        assertEquals("First", vm.noteFile("First").readText())
        assertEquals(1, mdFiles().size)
    }

    @Test fun `switching notes saves pending edits of the previous one`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("One"); vm.newNote()
        vm.onTextChange("Two"); vm.newNote()
        val one = vm.notes.first { it.title == "One" }
        vm.openNote(one.id)
        vm.onTextChange("One\nedited") // not yet autosaved
        vm.openNote(vm.notes.first { it.title == "Two" }.id)
        assertEquals("Two", vm.text)
        assertEquals("One\nedited", vm.noteFile("One").readText())
    }

    @Test fun `rename updates title and file`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Draft text"); vm.flush(); runCurrent()
        assertEquals(RenameResult.Ok, vm.rename("Better name"))
        assertEquals("Better name", vm.current?.title)
        assertTrue(vm.noteFile("Better name").exists())
        assertEquals(listOf("Better name"), vm.notes.map { it.title })
    }

    @Test fun `rename reports invalid and taken names`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Taken"); vm.newNote()
        vm.onTextChange("Other"); vm.flush(); runCurrent()
        assertEquals(RenameResult.InvalidName, vm.rename("???"))
        assertEquals(RenameResult.NameTaken, vm.rename("taken"))
        assertEquals("Other", vm.current?.title)
    }

    @Test fun `renaming a blank page is refused`() = runTest {
        val vm = newViewModel()
        assertEquals(RenameResult.Failed, vm.rename("Name"))
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `clear empties the text but keeps the note and file`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Keep\nme"); vm.flush(); runCurrent()
        vm.clear()
        assertEquals("", vm.text)
        assertNotNull(vm.current)
        assertEquals("", vm.noteFile("Keep").readText())
        assertEquals(listOf("Keep"), vm.notes.map { it.title })
        assertTrue(vm.trash.isEmpty())
    }

    @Test fun `delete moves the note to Trash and shows a blank page`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Doomed\nbody"); vm.flush(); runCurrent()
        vm.deleteCurrent()
        assertEquals("", vm.text)
        assertNull(vm.current)
        assertTrue(vm.notes.isEmpty())
        assertEquals(listOf("Doomed"), vm.trash.map { it.title })
        assertEquals("Doomed\nbody", vm.trashFile("Doomed").readText())
    }

    @Test fun `deleting a page with unsaved text still puts that text in Trash`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("typed just now")
        vm.deleteCurrent()
        assertEquals("typed just now", vm.trashFile("typed just now").readText())
    }

    @Test fun `restore returns a trashed note to Files`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Comeback"); vm.flush(); runCurrent()
        vm.deleteCurrent()
        vm.restore(vm.trash.single().id)
        assertTrue(vm.trash.isEmpty())
        assertEquals(listOf("Comeback"), vm.notes.map { it.title })
        assertTrue(vm.noteFile("Comeback").exists())
    }

    @Test fun `permanent delete removes a trashed note for good`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Gone"); vm.flush(); runCurrent()
        vm.deleteCurrent()
        vm.deletePermanently(vm.trash.single().id)
        assertTrue(vm.trash.isEmpty())
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `closing a note by opening another never deletes it`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Stay"); vm.newNote()
        assertEquals(listOf("Stay"), vm.notes.map { it.title })
        assertTrue(vm.trash.isEmpty())
    }
}
