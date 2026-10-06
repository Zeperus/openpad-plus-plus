package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FakeExternalDocuments
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.SpanKind
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

/**
 * The rich editor inside the view model: what the keyboard and the formatting bar do ends up as Markdown on disk, per tab
 * the caret and undo history are kept, and a blank page never becomes a file by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelEditorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        override suspend fun startupMode() = StartupMode.ResumeSession
        override suspend fun setStartupMode(mode: StartupMode) = Unit
    }

    private fun TestScope.launch(): NotesViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return NotesViewModel(
            FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher, external = provider),
            FileSessionStore(File(root, "session.json"), dispatcher),
            settings,
            CoroutineScope(backgroundScope.coroutineContext + dispatcher + SupervisorJob()),
        )
    }

    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    private fun NotesViewModel.file(title: String) = File(root, "notes/${notes.first { it.title == title }.id.value}.md")

    /** Types [text] at the end of row [index] the way a text field reports it. */
    private fun NotesViewModel.type(index: Int, text: String) {
        val row = ui.doc.rows[index]
        val end = row.text.length
        onSelection(row.id, end, end)
        onRowText(row.id, row.text.text + text, end + text.length)
    }

    private fun NotesViewModel.select(index: Int, start: Int, end: Int) = onSelection(ui.doc.rows[index].id, start, end)

    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    // ---- Draft ---------------------------------------------------------------------------------------------

    @Test fun `a blank page with only an empty paragraph is not a note`() = runTest {
        val vm = launch()
        assertEquals(1, vm.ui.doc.rows.size)
        vm.onSelection(vm.ui.doc.rows[0].id, 0, 0) // the caret is placed
        settle()
        assertNull(vm.current)
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `typing only spaces or line breaks does not create a note`() = runTest {
        val vm = launch()
        vm.type(0, "   ")
        vm.onRowText(vm.ui.doc.rows[0].id, "   \n", 4)
        settle()
        assertNull(vm.current)
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `the first real content creates the note and puts it into the session`() = runTest {
        val vm = launch()
        vm.type(0, "Hello")
        settle()
        assertEquals("Hello", vm.current?.title)
        assertEquals("Hello\n", vm.file("Hello").readText())
        assertEquals(1, vm.tabs.count { it.isActive })
    }

    // ---- Typing and blocks ---------------------------------------------------------------------------------

    @Test fun `bold on a selection is written as Markdown`() = runTest {
        val vm = launch()
        vm.type(0, "Buy these things")
        vm.select(0, 4, 16)
        vm.toggleStyle(SpanKind.Bold)
        settle()
        assertEquals("Buy **these things**\n", File(root, "notes").listFiles()!!.single().readText())
        assertTrue(SpanKind.Bold in vm.ui.active)
    }

    @Test fun `a heading is written with hashes but shown without them`() = runTest {
        val vm = launch()
        vm.type(0, "Shopping")
        vm.setBlock(RowKind.Heading(1))
        settle()
        assertEquals("# Shopping", vm.file("Shopping").readText().trimEnd())
        assertEquals("Shopping", vm.ui.doc.rows[0].text.text) // no "#" in the editable text
        assertEquals(RowKind.Heading(1), vm.ui.doc.rows[0].kind)
    }

    @Test fun `Enter in a list continues it and Enter on an empty item leaves it`() = runTest {
        val vm = launch()
        vm.type(0, "milk")
        vm.toggleList(false)
        val first = vm.ui.doc.rows[0]
        vm.onRowText(first.id, "milk\n", 5) // Enter at the end
        assertEquals(2, vm.ui.doc.rows.size)
        assertTrue(vm.ui.doc.rows[1].kind is RowKind.ListItem)
        vm.type(1, "bread")
        vm.onRowText(vm.ui.doc.rows[1].id, "bread\n", 6)
        assertEquals(3, vm.ui.doc.rows.size)
        val empty = vm.ui.doc.rows[2]
        vm.onRowText(empty.id, "\n", 1) // Enter on the empty item
        assertEquals(RowKind.Paragraph, vm.ui.doc.rows[2].kind)
        assertEquals(3, vm.ui.doc.rows.size)
        vm.type(2, "after")
        assertEquals(listOf("milk", "bread", "after"), vm.ui.doc.rows.map { it.text.text })
        settle()
        assertEquals("- milk\n- bread\n\nafter", vm.file("milk").readText().trimEnd())
    }

    @Test fun `Backspace at the start of a list item turns it into a paragraph`() = runTest {
        val vm = launch()
        vm.type(0, "item")
        vm.toggleList(true)
        assertTrue(vm.ui.doc.rows[0].kind is RowKind.ListItem)
        vm.onBackspaceAtStart(vm.ui.doc.rows[0].id)
        assertEquals(RowKind.Paragraph, vm.ui.doc.rows[0].kind)
        assertEquals("item", vm.ui.doc.rows[0].text.text)
    }

    @Test fun `numbered lists are written with their numbers`() = runTest {
        val vm = launch()
        vm.type(0, "one")
        vm.toggleList(true)
        vm.onRowText(vm.ui.doc.rows[0].id, "one\n", 4)
        vm.type(1, "two")
        settle()
        assertEquals("1. one\n2. two", vm.file("one").readText().trimEnd())
    }

    @Test fun `ticking a task changes only that item and does not reorder`() = runTest {
        val vm = launch()
        vm.onTextChange("- [ ] milk\n- [ ] bread\n- [ ] cheese\n")
        settle()
        val rows = vm.ui.doc.rows
        vm.setChecked(rows[1].id, true)
        settle()
        assertEquals("- [ ] milk\n- [x] bread\n- [ ] cheese\n", vm.file("milk").readText())
        vm.setChecked(rows[1].id, false)
        settle()
        assertEquals("- [ ] milk\n- [ ] bread\n- [ ] cheese\n", vm.file("milk").readText())
    }

    @Test fun `pasted Markdown stays plain text`() = runTest {
        val vm = launch()
        vm.type(0, "# not a heading **nor bold**")
        settle()
        assertEquals(RowKind.Paragraph, vm.ui.doc.rows[0].kind)
        assertEquals("# not a heading **nor bold**", vm.ui.doc.rows[0].text.text)
        // written so that it reads back as exactly that text
        val reopened = launch().also { it.openNote(it.notes.single().id) }
        assertEquals("# not a heading **nor bold**", reopened.ui.doc.rows[0].text.text)
        assertEquals(RowKind.Paragraph, reopened.ui.doc.rows[0].kind)
    }

    // ---- Existing files ------------------------------------------------------------------------------------

    @Test fun `opening and switching tabs does not rewrite a file`() = runTest {
        val vm = launch()
        val odd = "* one\n*   two\n\n\n\nText   with  spaces\r\n"
        vm.onTextChange("x")
        settle()
        File(root, "notes").listFiles()!!.single().writeText(odd)
        val reloaded = launch()
        reloaded.openNote(reloaded.notes.single().id)
        reloaded.newNote()
        reloaded.openNote(reloaded.notes.single().id)
        reloaded.flush(); runCurrent()
        assertEquals(odd, File(root, "notes").listFiles()!!.single().readText())
    }

    @Test fun `editing one paragraph leaves the rest of the file as it was`() = runTest {
        val vm = launch()
        vm.onTextChange("x")
        settle()
        File(root, "notes").listFiles()!!.single().writeText("Intro   text\n\n\n* odd list\n*  of items\n\nLast one\n")
        val reloaded = launch()
        reloaded.openNote(reloaded.notes.single().id)
        reloaded.type(3, "!")
        reloaded.flush(); runCurrent()
        assertEquals("Intro   text\n\n\n* odd list\n*  of items\n\nLast one!\n", File(root, "notes").listFiles()!!.single().readText())
    }

    @Test fun `Clear results in an empty document and an empty file`() = runTest {
        val vm = launch()
        vm.type(0, "to be cleared")
        settle()
        vm.clear(); runCurrent()
        assertEquals("", vm.text)
        assertEquals(1, vm.ui.doc.rows.size)
        assertEquals("", vm.ui.doc.rows[0].text.text)
        assertEquals("", mdFiles().single().readText())
        assertNotNull(vm.current) // the note itself stays
    }

    // ---- Undo and per-tab state ----------------------------------------------------------------------------

    @Test fun `undo and redo cover typing, formatting, lists and checkboxes`() = runTest {
        val vm = launch()
        vm.type(0, "task")
        vm.toggleTask()
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        assertEquals("- [x] task", vm.text.trimEnd())
        vm.undo(); assertEquals("- [ ] task", vm.text.trimEnd())
        vm.undo(); assertEquals("task", vm.text.trimEnd())
        vm.undo(); assertEquals("", vm.text)
        assertFalse(vm.ui.canUndo)
        vm.redo(); vm.redo(); vm.redo()
        assertEquals("- [x] task", vm.text.trimEnd())
        assertFalse(vm.ui.canRedo)
    }

    @Test fun `every tab keeps its caret and its undo history`() = runTest {
        val vm = launch()
        vm.type(0, "first note")
        vm.select(0, 2, 5)
        vm.newNote()
        vm.type(0, "second note")
        vm.newNote()
        val first = vm.notes.first { it.title == "first note" }
        vm.openNote(first.id)
        assertEquals(2, vm.ui.cursor?.start)
        assertEquals(5, vm.ui.cursor?.end)
        assertTrue(vm.ui.canUndo)
        vm.undo()
        assertEquals("", vm.text)
        vm.redo()
        assertEquals("first note", vm.text.trimEnd())
    }

    @Test fun `a seeded note keeps its undo history across tab switches`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FileNoteRepository(root, clock = { tick++ }, dispatcher = dispatcher, external = provider)
        val one = repo.createNote("One")
        val two = repo.createNote("Two")
        FileSessionStore(File(root, "session.json"), dispatcher).save(io.github.zeperus.openpad.domain.PersistedSession(listOf(one.id.value, two.id.value), one.id.value))
        val vm = launch()
        assertEquals("One", vm.ui.doc.rows[0].text.text)
        vm.type(0, "!")
        settle()
        vm.selectTab(io.github.zeperus.openpad.domain.DocumentTab.Saved(two.id))
        assertEquals("Two", vm.ui.doc.rows[0].text.text)
        vm.selectTab(io.github.zeperus.openpad.domain.DocumentTab.Saved(one.id))
        assertEquals("One!", vm.ui.doc.rows[0].text.text)
        assertTrue("undo history kept", vm.ui.canUndo)
        vm.undo()
        assertEquals("One", vm.ui.doc.rows[0].text.text)
    }

    @Test fun `a tab whose file changed meanwhile starts fresh instead of overwriting it`() = runTest {
        val vm = launch()
        vm.type(0, "mine")
        vm.newNote()
        val note = vm.notes.single()
        settle()
        File(root, "notes/${note.id.value}.md").writeText("changed elsewhere")
        vm.openNote(note.id)
        assertEquals("changed elsewhere", vm.text)
        assertFalse(vm.ui.canUndo) // no history of text that is not on disk any more
        vm.flush(); runCurrent()
        assertEquals("changed elsewhere", File(root, "notes/${note.id.value}.md").readText())
    }

    // ---- Read-only external --------------------------------------------------------------------------------

    @Test fun `a read-only document is shown formatted and cannot be changed`() = runTest {
        val uri = "content://docs/doc/locked"
        provider.put(uri, "# Locked\n\n- [ ] item\n", name = "Locked.md"); provider.readOnly += uri
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        assertTrue(vm.readOnly)
        assertEquals(RowKind.Heading(1), vm.ui.doc.rows[0].kind)
        vm.type(0, "!")
        vm.setChecked(vm.ui.doc.rows[1].id, true)
        vm.toggleStyle(SpanKind.Bold)
        vm.clear(); runCurrent()
        vm.flush(); runCurrent()
        assertEquals("# Locked\n\n- [ ] item\n", provider.text(uri))
        assertEquals("Locked", vm.ui.doc.rows[0].text.text)
    }

    @Test fun `an external document is edited in place with the same editor`() = runTest {
        val uri = "content://docs/doc/ext"
        provider.put(uri, "# Ext\n\n- [ ] item\n", name = "Ext.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        vm.setChecked(vm.ui.doc.rows[1].id, true)
        vm.flush(); runCurrent()
        assertEquals("# Ext\n\n- [x] item\n", provider.text(uri))
        assertTrue(File(root, "notes").listFiles().isNullOrEmpty()) // no internal copy
    }

    // ---- Smart checklist -----------------------------------------------------------------------------------

    @Test fun `smart checklist is off by default and ticking keeps the order`() = runTest {
        val vm = launch()
        vm.onTextChange("- [ ] A\n- [ ] B\n- [ ] C\n")
        settle()
        assertFalse(vm.current!!.smartChecklist)
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        settle()
        assertEquals("- [x] A\n- [ ] B\n- [ ] C\n", vm.file("A").readText())
    }

    @Test fun `turning smart checklist on sorts once and from then on ticking moves items`() = runTest {
        val vm = launch()
        vm.onTextChange("- [x] A\n- [ ] B\n- [ ] C\n")
        settle()
        vm.toggleSmartChecklist(); runCurrent()
        assertTrue(vm.current!!.smartChecklist)
        settle()
        assertEquals("- [ ] B\n- [ ] C\n- [x] A\n", vm.file("B").readText())
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        settle()
        assertEquals("- [ ] C\n- [x] A\n- [x] B\n".replace("- [x] A\n- [x] B", "- [x] A\n- [x] B"), vm.file("C").readText())
        // the mode is metadata: it is not in the file, and it survives a restart
        assertFalse(vm.file("C").readText().contains("smart", ignoreCase = true))
        val again = launch()
        again.openNote(again.notes.single().id)
        assertTrue(again.current!!.smartChecklist)
        again.setChecked(again.ui.doc.rows[0].id, true)
        again.flush(); runCurrent()
        assertEquals("- [x] A\n- [x] B\n- [x] C\n", again.file("A").readText())
    }

    @Test fun `smart checklist can be turned off again and a blank page cannot have it`() = runTest {
        val vm = launch()
        vm.toggleSmartChecklist(); runCurrent()
        assertNull(vm.current) // nothing to attach it to
        vm.onTextChange("- [ ] A\n- [ ] B\n")
        settle()
        vm.toggleSmartChecklist(); runCurrent()
        vm.toggleSmartChecklist(); runCurrent()
        assertFalse(vm.current!!.smartChecklist)
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        settle()
        assertEquals("- [x] A\n- [ ] B\n", vm.file("A").readText())
    }

    @Test fun `undo after ticking in smart mode restores the order in one step`() = runTest {
        val vm = launch()
        vm.onTextChange("- [ ] A\n- [ ] B\n- [ ] C\n")
        settle()
        vm.toggleSmartChecklist(); runCurrent()
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        assertEquals("- [ ] B\n- [ ] C\n- [x] A\n", vm.text)
        vm.undo()
        assertEquals("- [ ] A\n- [ ] B\n- [ ] C\n", vm.text)
    }

    // ---- Focus: the same row stays the focused one -----------------------------------------------------------

    @Test fun `structural edits do not ask for a new focus`() = runTest {
        val vm = launch()
        vm.type(0, "item")
        val id = vm.ui.doc.rows[0].id
        vm.toggleList(false)
        vm.toggleTask()
        vm.onBackspaceAtStart(id)
        vm.onBackspaceAtStart(id)
        assertEquals(id, vm.ui.cursor?.rowId)
        // no focus request was made for a row that merely changed kind: the field stays where it is
        assertNull(vm.focusRequest?.takeIf { it.rowId == id && it.token > 1 })
    }

    // ---- New checklist --------------------------------------------------------------------------------------

    @Test fun `a new checklist is a blank page with one empty task and creates nothing by itself`() = runTest {
        val vm = launch()
        vm.newChecklist(); runCurrent()
        assertEquals(1, vm.ui.doc.rows.size)
        assertEquals(false, (vm.ui.doc.rows[0].kind as RowKind.ListItem).checked)
        assertEquals(vm.ui.doc.rows[0].id, vm.focusRequest?.rowId)
        settle()
        assertNull(vm.current)
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `typing into a new checklist creates a smart checklist note`() = runTest {
        val vm = launch()
        vm.newChecklist(); runCurrent()
        vm.type(0, "Milk")
        settle()
        assertTrue(vm.current!!.smartChecklist)
        assertEquals("- [ ] Milk\n", vm.file("Milk").readText())
        vm.onRowText(vm.ui.doc.rows[0].id, "Milk\n", 5)
        vm.type(1, "Bread")
        vm.setChecked(vm.ui.doc.rows[0].id, true)
        settle()
        assertEquals("- [ ] Bread\n- [x] Milk\n", vm.file("Bread").readText())
    }

    @Test fun `a blank new note after a new checklist is a plain page again`() = runTest {
        val vm = launch()
        vm.newChecklist(); runCurrent()
        vm.newNote(); runCurrent()
        assertEquals(RowKind.Paragraph, vm.ui.doc.rows[0].kind)
        vm.type(0, "plain")
        settle()
        assertFalse(vm.current!!.smartChecklist)
    }

    @Test fun `a smart checklist note that is out of order is put in order when opened`() = runTest {
        val vm = launch()
        vm.onTextChange("- [x] A\n- [ ] B\n")
        settle()
        vm.toggleSmartChecklist(); runCurrent()
        settle()
        File(root, "notes/${vm.notes.single().id.value}.md").writeText("- [x] A\n- [ ] B\n- [x] C\n- [ ] D\n")
        val again = launch()
        again.openNote(again.notes.single().id); runCurrent()
        assertEquals("- [ ] B\n- [ ] D\n- [x] A\n- [x] C\n", again.text)
        again.flush(); runCurrent()
        assertEquals("- [ ] B\n- [ ] D\n- [x] A\n- [x] C\n", File(root, "notes/${again.notes.single().id.value}.md").readText())
    }
}
