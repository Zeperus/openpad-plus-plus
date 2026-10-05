package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import java.util.UUID

/** Open documents, sessions and startup behaviour of the view model, on real files. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val sessionFile get() = File(root, "session.json")
    private var tick = 1_000L

    private class InMemorySettings(var mode: StartupMode = StartupMode.Default) : SettingsStore {
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
    }

    private val settings = InMemorySettings()

    /** "Launching the app": a fresh view model over the same files, settings and session. */
    private fun TestScope.launch(mode: StartupMode? = null): NotesViewModel {
        mode?.let { settings.mode = it }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return NotesViewModel(
            FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher),
            FileSessionStore(sessionFile, dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
        )
    }

    private fun seedRepo() = FileNoteRepository(root, clock = { tick++ }, dispatcher = Dispatchers.IO)

    /** Creates saved notes without a view model (like notes left over from earlier sessions). */
    private fun seed(vararg titles: String): List<NoteId> = runBlocking { titles.map { seedRepo().createNote(it).id } }

    private fun storeSession(ids: List<NoteId>, active: NoteId?) = runBlocking {
        FileSessionStore(sessionFile).save(PersistedSession(ids.map { it.value }, active?.value))
    }

    private fun storedSession() = runBlocking { FileSessionStore(sessionFile).load() }

    private fun NotesViewModel.tabTitles() =
        tabs.map { if (it.tab == DocumentTab.Draft) "blank" else it.title }

    private fun NotesViewModel.activeTitle() = tabs.first { it.isActive }.let { if (it.tab == DocumentTab.Draft) "blank" else it.title }

    private fun NotesViewModel.tab(title: String): DocumentTab = tabs.first { it.title == title }.tab

    private fun NotesViewModel.id(title: String) = notes.first { it.title == title }.id

    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    private fun NotesViewModel.titles(list: List<io.github.zeperus.openpad.domain.NoteInfo>) = list.map { it.title }

    /** Seeds Shopping | Ideas | Work as the previous session with Ideas active. */
    private fun previousSession(): List<NoteId> {
        val ids = seed("Shopping", "Ideas", "Work")
        storeSession(ids, ids[1])
        return ids
    }

    // ---- Startup modes ---------------------------------------------------------------------------------------

    @Test fun `a new installation uses resume plus blank note`() = runTest {
        val vm = launch()
        assertTrue(vm.ready)
        assertEquals(StartupMode.ResumeAndBlank, vm.startupMode)
        assertEquals(listOf("blank"), vm.tabTitles())
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `resume session restores the notes and the previously active one`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
        assertEquals("Ideas", vm.activeTitle())
        assertEquals("Ideas", vm.text) // the active note's content is shown
        assertEquals("Ideas", vm.current?.title)
    }

    @Test fun `resume plus blank restores the notes and adds one active blank note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        assertEquals(listOf("Shopping", "Ideas", "Work", "blank"), vm.tabTitles())
        assertEquals("blank", vm.activeTitle())
        assertEquals("", vm.text)
        assertNull(vm.current)
    }

    @Test fun `blank note starts with a single blank page and does not restore`() = runTest {
        previousSession()
        val vm = launch(StartupMode.BlankNote)
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals(3, vm.notes.size) // the notes are untouched and still in FILES
        assertEquals(3, vm.recent.size)
    }

    @Test fun `blank note replaces the stored session at once so an old one cannot resurface`() = runTest {
        previousSession()
        launch(StartupMode.BlankNote)
        assertEquals(PersistedSession(), storedSession())
    }

    @Test fun `with nothing to restore every mode shows exactly one blank note`() = runTest {
        for (mode in StartupMode.entries) {
            assertEquals(mode.name, listOf("blank"), launch(mode).tabTitles())
        }
    }

    @Test fun `after blank note mode a later resume restores only what happened in that run`() = runTest {
        previousSession()
        val blankRun = launch(StartupMode.BlankNote)
        blankRun.openNote(blankRun.id("Work"))
        val resumed = launch(StartupMode.ResumeSession)
        assertEquals(listOf("Work"), resumed.tabTitles())
    }

    @Test fun `restoring the session does not count as using the note again`() = runTest {
        val ids = previousSession()
        val before = runBlocking { seedRepo().listNotes() }.associateBy { it.id }
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(before[ids[1]]!!.lastOpenedAt, vm.notes.first { it.id == ids[1] }.lastOpenedAt)
    }

    @Test fun `repeated launches never accumulate blank tabs or files`() = runTest {
        previousSession()
        val filesBefore = mdFiles().size
        repeat(6) {
            val vm = launch(StartupMode.ResumeAndBlank)
            assertEquals(listOf("Shopping", "Ideas", "Work", "blank"), vm.tabTitles())
            vm.flush(); runCurrent()
        }
        assertEquals(filesBefore, mdFiles().size)
        assertEquals(3, storedSession().noteIds.size)
    }

    @Test fun `deleted and trashed notes are ignored when the session is restored`() = runTest {
        val ids = previousSession()
        runBlocking { seedRepo().moveToTrash(ids[1]) } // the active one was trashed meanwhile
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(listOf("Shopping", "Work"), vm.tabTitles())
        assertEquals("Work", vm.activeTitle()) // neighbour: the next remaining tab
    }

    @Test fun `ids that never existed are dropped without error`() = runTest {
        val ids = seed("Real")
        storeSession(listOf(NoteId(UUID.randomUUID().toString()), ids[0], NoteId("garbage")), ids[0])
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(listOf("Real"), vm.tabTitles())
    }

    @Test fun `a damaged session file means a blank start and never loses notes`() = runTest {
        seed("Precious")
        sessionFile.writeText("{ broken ]]")
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals(listOf("Precious"), vm.titles(vm.notes))
    }

    @Test fun `a restored note that is not valid utf-8 is skipped with a message instead of crashing`() = runTest {
        val good = seed("Good")[0]
        val badId = UUID.randomUUID().toString()
        File(root, "notes").mkdirs()
        File(root, "notes/$badId.md").writeBytes(byteArrayOf(0x68, 0xFF.toByte(), 0xFE.toByte()))
        storeSession(listOf(NoteId(badId), good), NoteId(badId))
        val vm = launch(StartupMode.ResumeSession)
        assertEquals(UserMessage.NoteUnreadable, vm.message)
        assertEquals(listOf("Good"), vm.tabTitles())
        assertEquals("Good", vm.text)
    }

    @Test fun `the session survives view model recreation`() = runTest {
        val first = launch(StartupMode.ResumeSession)
        first.onTextChange("One"); first.newNote()
        first.onTextChange("Two"); first.newNote()
        first.onTextChange("Three"); first.flush(); runCurrent()
        first.selectTab(first.tab("Two"))

        val second = launch(StartupMode.ResumeSession)
        assertEquals(listOf("One", "Two", "Three"), second.tabTitles())
        assertEquals("Two", second.activeTitle())
    }

    @Test fun `choosing a startup mode is stored and applied on the next launch`() = runTest {
        previousSession()
        val vm = launch()
        vm.chooseStartupMode(StartupMode.BlankNote)
        assertEquals(StartupMode.BlankNote, vm.startupMode)
        assertEquals(StartupMode.BlankNote, settings.mode)
        assertEquals(listOf("blank"), launch().tabTitles())
    }

    // ---- Opening and switching -------------------------------------------------------------------------------

    @Test fun `opening a note appends a tab and activates it`() = runTest {
        val vm = launch(StartupMode.BlankNote)
        seed("Late")
        val relaunched = launch(StartupMode.BlankNote)
        relaunched.openNote(relaunched.id("Late"))
        assertEquals(listOf("Late", "blank"), relaunched.tabTitles())
        assertEquals("Late", relaunched.activeTitle())
        assertEquals("blank", vm.tabTitles().single())
    }

    @Test fun `opening an already open note activates it instead of adding a duplicate tab`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.openNote(vm.id("Shopping"))
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
        assertEquals("Shopping", vm.activeTitle())
        vm.openNote(vm.id("Ideas")); vm.openNote(vm.id("Shopping")); vm.openNote(vm.id("Work"))
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
    }

    @Test fun `opening from any drawer section behaves the same`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.openNote(vm.id("Work")); vm.toggleFavorite()
        vm.openNote(vm.favorites.first().id) // from FAVORITES
        vm.openNote(vm.recent.first().id) // from RECENT
        vm.openNote(vm.notes.first().id) // from FILES
        assertEquals(1, vm.tabs.count { it.title == "Work" })
        assertEquals(4, vm.tabs.size)
    }

    @Test fun `switching tabs keeps the order and loads the other note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("Work"))
        assertEquals("Work", vm.text)
        vm.selectTab(vm.tab("Shopping"))
        assertEquals("Shopping", vm.text)
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
    }

    @Test fun `switching saves pending edits of the previous note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.onTextChange("Ideas\nfresh thought") // not yet autosaved
        vm.selectTab(vm.tab("Work"))
        vm.selectTab(vm.tab("Ideas"))
        assertEquals("Ideas\nfresh thought", vm.text)
    }

    @Test fun `tab order and recent order are separate`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("Shopping"))
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles()) // activating never reorders tabs
        assertEquals("Shopping", vm.recent.first().title) // but it is now the most recently used
        vm.selectTab(vm.tab("Work"))
        assertEquals(listOf("Work", "Shopping", "Ideas"), vm.titles(vm.recent))
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
    }

    @Test fun `typing alone does not reorder recent`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("Shopping"))
        vm.selectTab(vm.tab("Ideas"))
        vm.onTextChange("Ideas\nlots of typing"); vm.flush(); runCurrent()
        assertEquals(listOf("Ideas", "Shopping", "Work"), vm.titles(vm.recent))
    }

    // ---- Blank drafts ----------------------------------------------------------------------------------------

    @Test fun `an untouched blank note never becomes a file`() = runTest {
        val vm = launch()
        vm.flush(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(mdFiles().isEmpty())
        assertTrue(storedSession().noteIds.isEmpty())
    }

    @Test fun `new note never creates a second draft`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.newNote(); vm.newNote(); vm.newNote()
        assertEquals(listOf("Shopping", "Ideas", "Work", "blank"), vm.tabTitles())
        assertEquals("blank", vm.activeTitle())
        vm.selectTab(vm.tab("Work")); vm.newNote()
        assertEquals(1, vm.tabs.count { it.tab == DocumentTab.Draft })
        assertEquals("blank", vm.activeTitle())
    }

    @Test fun `closing an untouched blank note leaves no file and no session entry`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        val filesBefore = mdFiles().size
        vm.closeTab(DocumentTab.Draft)
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
        assertEquals(filesBefore, mdFiles().size)
    }

    @Test fun `typing turns the draft into a saved note that joins the session`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.onTextChange("# Fresh idea")
        assertEquals(listOf("Shopping", "Ideas", "Work", "blank"), vm.tabTitles()) // not saved yet
        advanceTimeBy(1_000); runCurrent() // autosave
        assertEquals(listOf("Shopping", "Ideas", "Work", "Fresh idea"), vm.tabTitles())
        assertEquals("Fresh idea", vm.activeTitle())
        val id = vm.id("Fresh idea")
        assertEquals(id, vm.current?.id)
        assertTrue(id.value in storedSession().noteIds) // persisted under its stable id
        assertEquals(id.value, storedSession().activeNoteId)
        assertTrue(vm.recent.any { it.id == id }) // and it is a recent note now
    }

    @Test fun `a draft with text becomes a note before the next new note starts`() = runTest {
        val vm = launch()
        vm.onTextChange("First thought")
        vm.newNote()
        assertEquals(listOf("First thought", "blank"), vm.tabTitles())
        assertEquals("blank", vm.activeTitle())
        assertEquals("", vm.text)
        assertEquals(1, mdFiles().size)
    }

    @Test fun `opening another note while the draft has text saves the text as a note`() = runTest {
        val ids = seed("Existing")
        val vm = launch()
        vm.onTextChange("Typed into the draft")
        vm.openNote(ids[0])
        assertEquals(listOf("Typed into the draft", "Existing"), vm.tabTitles())
        assertEquals("Existing", vm.activeTitle())
        assertEquals(2, mdFiles().size)
    }

    @Test fun `a new note after a materialized draft is a fresh single blank page`() = runTest {
        val vm = launch()
        vm.onTextChange("Alpha"); vm.flush(); runCurrent()
        assertEquals(listOf("Alpha"), vm.tabTitles())
        vm.newNote()
        assertEquals(listOf("Alpha", "blank"), vm.tabTitles())
        assertEquals(1, mdFiles().size)
    }

    // ---- Closing ---------------------------------------------------------------------------------------------

    @Test fun `closing the active tab activates the next one`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession) // Ideas is active
        vm.closeTab(vm.tab("Ideas"))
        assertEquals(listOf("Shopping", "Work"), vm.tabTitles())
        assertEquals("Work", vm.activeTitle())
        assertEquals("Work", vm.text)
    }

    @Test fun `closing the last active tab activates the previous one, a following blank note counts as next`() = runTest {
        val ids = previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("Work"))
        vm.closeTab(vm.tab("Work"))
        assertEquals("Ideas", vm.activeTitle())

        storeSession(ids, ids[1]) // back to Shopping | Ideas | Work
        val withBlank = launch(StartupMode.ResumeAndBlank) // Shopping | Ideas | Work | blank
        withBlank.selectTab(withBlank.tab("Work"))
        withBlank.closeTab(withBlank.tab("Work"))
        assertEquals("blank", withBlank.activeTitle())
    }

    @Test fun `closing an inactive tab keeps the active note and its unsaved text`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.onTextChange("Ideas\nin progress")
        vm.closeTab(vm.tab("Work"))
        assertEquals(listOf("Shopping", "Ideas"), vm.tabTitles())
        assertEquals("Ideas", vm.activeTitle())
        assertEquals("Ideas\nin progress", vm.text)
    }

    @Test fun `closing never deletes trashes or unfavorites a note and keeps it in recent`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("Work")); vm.toggleFavorite()
        vm.selectTab(vm.tab("Shopping"))
        val filesBefore = mdFiles().map { it.name to it.readText() }.toSet()

        vm.closeTab(vm.tab("Work"))
        vm.closeTab(vm.tab("Shopping"))

        assertEquals(filesBefore, mdFiles().map { it.name to it.readText() }.toSet())
        assertTrue(vm.trash.isEmpty())
        assertEquals(3, vm.notes.size) // FILES still has everything
        assertEquals(listOf("Work"), vm.titles(vm.favorites))
        assertTrue(vm.recent.any { it.title == "Shopping" })
    }

    @Test fun `closing the only tab leaves one blank note`() = runTest {
        val ids = seed("Solo")
        storeSession(ids, ids[0])
        val vm = launch(StartupMode.ResumeSession)
        vm.closeCurrent()
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals("", vm.text)
        assertEquals(1, mdFiles().size)
    }

    @Test fun `closing the active blank note with text keeps the text as a note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.onTextChange("Do not lose me")
        vm.closeTab(DocumentTab.Draft)
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
        assertEquals(listOf("Do not lose me"), vm.titles(vm.notes.filter { it.title == "Do not lose me" }))
        assertEquals(4, mdFiles().size)
    }

    @Test fun `close others keeps one tab`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.closeOthers(vm.tab("Ideas"))
        assertEquals(listOf("Ideas"), vm.tabTitles())
        assertEquals("Ideas", vm.activeTitle())
        assertEquals(3, vm.notes.size)
        assertEquals(3, mdFiles().size)
    }

    @Test fun `close others on the active blank note with text saves it`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.onTextChange("Typed")
        vm.closeOthers(DocumentTab.Draft)
        assertEquals(listOf("Typed"), vm.tabTitles())
        assertEquals("Typed", vm.text)
        assertEquals(4, mdFiles().size)
    }

    @Test fun `close others saves unsaved text of the tab that gets closed`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.onTextChange("Ideas\nunsaved")
        vm.closeOthers(vm.tab("Work"))
        vm.openNote(vm.id("Ideas"))
        assertEquals("Ideas\nunsaved", vm.text)
    }

    @Test fun `close all leaves one blank note and deletes nothing`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.closeAll()
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals("", vm.text)
        assertEquals(3, vm.notes.size)
        assertEquals(3, mdFiles().size)
        assertTrue(vm.trash.isEmpty())
    }

    @Test fun `after close all and quitting without typing every mode starts with one blank note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.closeAll(); vm.flush(); runCurrent()
        assertTrue(storedSession().noteIds.isEmpty())
        for (mode in StartupMode.entries) {
            assertEquals(mode.name, listOf("blank"), launch(mode).tabTitles())
        }
        assertEquals(3, mdFiles().size)
    }

    @Test fun `close all keeps text typed into the blank note as a closed note`() = runTest {
        val vm = launch()
        vm.onTextChange("Kept")
        vm.closeAll()
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals(listOf("Kept"), vm.titles(vm.notes))
    }

    // ---- Deleting open notes ---------------------------------------------------------------------------------

    @Test fun `deleting the active note closes its tab and activates a neighbour`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession) // Ideas active
        vm.deleteCurrent()
        assertEquals(listOf("Shopping", "Work"), vm.tabTitles())
        assertEquals("Work", vm.activeTitle())
        assertEquals(listOf("Ideas"), vm.titles(vm.trash))
        assertFalse(storedSession().noteIds.contains(vm.trash.single().id.value))
    }

    @Test fun `deleting the only open note leaves a blank note`() = runTest {
        val ids = seed("Solo")
        storeSession(ids, ids[0])
        val vm = launch(StartupMode.ResumeSession)
        vm.deleteCurrent()
        assertEquals(listOf("blank"), vm.tabTitles())
        assertEquals(listOf("Solo"), vm.titles(vm.trash))
    }

    @Test fun `deleting a blank note that has text moves that text to Trash and closes its tab`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeAndBlank)
        vm.onTextChange("Throwaway")
        vm.deleteCurrent()
        assertEquals(listOf("Shopping", "Ideas", "Work"), vm.tabTitles())
        assertEquals(listOf("Throwaway"), vm.titles(vm.trash))
        assertEquals("Work", vm.activeTitle())
    }

    @Test fun `restoring from Trash does not reopen the note`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.deleteCurrent()
        vm.restore(vm.trash.single().id)
        assertEquals(listOf("Shopping", "Work"), vm.tabTitles())
        assertEquals(3, vm.notes.size)
        assertEquals(listOf("Shopping", "Work"), launch(StartupMode.ResumeSession).tabTitles())
    }

    // ---- Rename ----------------------------------------------------------------------------------------------

    @Test fun `renaming an open note updates its tab title without changing id order or count`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        val idsBefore = vm.session.noteIds
        assertEquals(RenameResult.Ok, vm.rename("Better ideas"))
        assertEquals(listOf("Shopping", "Better ideas", "Work"), vm.tabTitles())
        assertEquals(idsBefore, vm.session.noteIds)
        assertEquals("Better ideas", vm.activeTitle())
        assertEquals(idsBefore.map { it.value }, storedSession().noteIds)
    }

    @Test fun `renaming a blank note with text creates the note and its tab`() = runTest {
        val vm = launch()
        vm.onTextChange("some words")
        assertEquals(RenameResult.Ok, vm.rename("Named"))
        assertEquals(listOf("Named"), vm.tabTitles())
        assertEquals(vm.id("Named"), vm.current?.id)
        assertEquals(1, storedSession().noteIds.size)
    }

    // ---- Favorites / Recent regression -----------------------------------------------------------------------

    @Test fun `open tabs do not change what Favorites Recent and Files contain`() = runTest {
        val ids = seed("A", "B", "C", "D", "E")
        storeSession(ids, ids[0])
        val vm = launch(StartupMode.ResumeSession)
        vm.selectTab(vm.tab("B")); vm.toggleFavorite()
        vm.selectTab(vm.tab("E"))
        vm.selectTab(vm.tab("D"))
        vm.selectTab(vm.tab("C"))

        assertEquals(5, vm.notes.size) // FILES: everything
        assertEquals(listOf("B"), vm.titles(vm.favorites))
        assertEquals(3, vm.recent.size) // limit of 3
        assertEquals(listOf("C", "D", "E"), vm.titles(vm.recent))
        assertTrue(vm.recent.none { it.favorite }) // never both Favorites and Recent
    }

    @Test fun `closing a favorite tab keeps it a favorite and out of recent`() = runTest {
        previousSession()
        val vm = launch(StartupMode.ResumeSession)
        vm.toggleFavorite() // Ideas
        vm.closeCurrent()
        assertEquals(listOf("Ideas"), vm.titles(vm.favorites))
        assertTrue(vm.recent.none { it.title == "Ideas" })
        assertNotNull(vm.notes.firstOrNull { it.title == "Ideas" })
    }
}
