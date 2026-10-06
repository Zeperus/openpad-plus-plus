package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.runBlocking
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
    private var tick = 1_000L // deterministic, strictly increasing clock shared across "restarts"

    /** Survives "restarts" like the DataStore file does. */
    private class InMemorySettings(var mode: StartupMode = StartupMode.Default) : SettingsStore {
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
    }

    private val settings = InMemorySettings()

    private val sessionFile get() = File(root, "session.json")

    /** The session as it is on disk right now. */
    private fun storedSession() = runBlocking { FileSessionStore(sessionFile).load() }

    private fun TestScope.newViewModel(mode: StartupMode? = null): NotesViewModel {
        mode?.let { settings.mode = it }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher)
        return NotesViewModel(
            repo,
            FileSessionStore(sessionFile, dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
        )
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

    @Test fun `naming a blank page makes it an empty note that stays`() = runTest {
        val vm = newViewModel()
        assertTrue(vm.canRename)
        assertFalse(vm.hasExplicitTitle)
        assertEquals(RenameResult.Ok, vm.rename("Name"))
        assertEquals("Name", vm.current?.title)
        assertTrue(vm.hasExplicitTitle)
        assertEquals(1, mdFiles().size)
        assertEquals("", vm.noteFile("Name").readText())
        // a "restart": the titled empty note is still there
        assertEquals(listOf("Name"), newViewModel().notes.map { it.title })
    }

    @Test fun `typing in an automatically titled note keeps following the first line until it is renamed`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Shopping list"); vm.flush(); runCurrent()
        assertEquals("Shopping list", vm.current?.title)
        assertFalse(vm.hasExplicitTitle) // a tap on the title renames it
        assertEquals(RenameResult.Ok, vm.rename("Groceries"))
        assertTrue(vm.hasExplicitTitle) // from now on a tap does nothing, a long press renames
        vm.onTextChange("Shopping Monday"); vm.flush(); runCurrent()
        assertEquals("Groceries", vm.current?.title)
        assertEquals("Shopping Monday", vm.noteFile("Groceries").readText())
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

    // ---- Favorites -------------------------------------------------------------------------------------------

    private fun NotesViewModel.titles(list: List<io.github.zeperus.openpad.domain.NoteInfo>) = list.map { it.title }

    /** Creates notes titled [titles] (each saved) and leaves a blank page open. */
    private fun NotesViewModel.createNotes(vararg titles: String) {
        titles.forEach { onTextChange(it); newNote() }
    }

    private fun NotesViewModel.open(title: String) = openNote(notes.first { it.title == title }.id)

    @Test fun `no favorites means the favorites list is empty`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B")
        assertTrue(vm.favorites.isEmpty())
    }

    @Test fun `toggle favorite adds and removes a note from favorites without duplicating it in files`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B")
        vm.open("A")
        vm.toggleFavorite()
        assertEquals(listOf("A"), vm.titles(vm.favorites))
        assertEquals(true, vm.current?.favorite)
        assertEquals(listOf("A", "B"), vm.titles(vm.notes)) // FILES stays the complete list

        vm.toggleFavorite()
        assertTrue(vm.favorites.isEmpty())
        assertEquals(false, vm.current?.favorite)
    }

    @Test fun `favoriting a draft with text creates the note and favorites it`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Fresh idea")
        vm.toggleFavorite()
        assertEquals(listOf("Fresh idea"), vm.titles(vm.favorites))
        assertEquals("Fresh idea", vm.noteFile("Fresh idea").readText())
    }

    @Test fun `favoriting a blank page does nothing`() = runTest {
        val vm = newViewModel()
        vm.toggleFavorite()
        assertTrue(vm.favorites.isEmpty())
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `favorite state persists across restarts and the markdown stays untouched`() = runTest {
        val first = newViewModel()
        first.onTextChange("# Pinned\nbody")
        first.toggleFavorite()
        val file = first.noteFile("Pinned")
        val bytes = file.readBytes()

        val second = newViewModel()
        assertEquals(listOf("Pinned"), second.titles(second.favorites))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test fun `renaming keeps the favorite`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Old"); vm.toggleFavorite()
        assertEquals(RenameResult.Ok, vm.rename("Fresh name"))
        assertEquals(listOf("Fresh name"), vm.titles(vm.favorites))
    }

    @Test fun `deleting a favorite removes it from favorites and restoring brings it back`() = runTest {
        val vm = newViewModel()
        vm.onTextChange("Star"); vm.toggleFavorite()
        vm.deleteCurrent()
        assertTrue(vm.favorites.isEmpty())
        assertTrue(vm.recent.isEmpty())
        vm.restore(vm.trash.single().id)
        assertEquals(listOf("Star"), vm.titles(vm.favorites))
    }

    // ---- Recent ----------------------------------------------------------------------------------------------

    @Test fun `a new note counts as used and is recent`() = runTest {
        val vm = newViewModel()
        assertTrue(vm.recent.isEmpty())
        vm.onTextChange("First"); vm.flush(); runCurrent()
        assertEquals(listOf("First"), vm.titles(vm.recent))
    }

    @Test fun `opening a note moves it to the top of recent`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B", "C")
        assertEquals(listOf("C", "B", "A"), vm.titles(vm.recent))
        vm.open("A")
        assertEquals(listOf("A", "C", "B"), vm.titles(vm.recent))
    }

    @Test fun `recent is limited to three`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B", "C", "D", "E")
        assertEquals(listOf("E", "D", "C"), vm.titles(vm.recent))
        assertEquals(5, vm.notes.size)
    }

    @Test fun `favorites never appear in recent and unfavoriting returns them`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B", "C")
        vm.open("C"); vm.toggleFavorite()
        assertEquals(listOf("C"), vm.titles(vm.favorites))
        assertEquals(listOf("B", "A"), vm.titles(vm.recent))

        vm.toggleFavorite() // C is still the open note, and was used most recently
        assertEquals(listOf("C", "B", "A"), vm.titles(vm.recent))
    }

    @Test fun `typing in a note does not reorder recent`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B")
        vm.open("A"); vm.newNote(); vm.open("B")
        vm.onTextChange("B\nedit edit"); vm.flush(); runCurrent()
        assertEquals(listOf("B", "A"), vm.titles(vm.recent))
        vm.open("A") // A used again, B only edited
        vm.onTextChange("A\nlots of typing"); vm.flush(); runCurrent()
        assertEquals(listOf("A", "B"), vm.titles(vm.recent))
    }

    @Test fun `deleted notes leave recent and renamed notes keep their place`() = runTest {
        val vm = newViewModel()
        vm.createNotes("A", "B", "C")
        vm.open("B")
        assertEquals(RenameResult.Ok, vm.rename("Bee"))
        assertEquals(listOf("Bee", "C", "A"), vm.titles(vm.recent))
        vm.deleteCurrent()
        assertEquals(listOf("C", "A"), vm.titles(vm.recent))
    }

    @Test fun `recent and favorites are identical after an app restart`() = runTest {
        val first = newViewModel()
        first.createNotes("A", "B", "C", "D")
        first.open("B"); first.toggleFavorite()
        first.open("A")
        val recent = first.titles(first.recent)
        val favorites = first.titles(first.favorites)

        val second = newViewModel()
        assertEquals(recent, second.titles(second.recent))
        assertEquals(favorites, second.titles(second.favorites))
        assertEquals(listOf("A", "D", "C"), second.titles(second.recent))
    }
}
