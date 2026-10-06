package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.data.FakeExternalDocuments
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.DocumentTab
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** External documents (file picker / "Open with") inside the tab model. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelExternalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var tick = 1_000L
    private val settings = object : SettingsStore {
        var mode = StartupMode.ResumeSession
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
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

    private val uri = "content://docs/doc/notes"
    private fun NotesViewModel.titles() = tabs.map { if (it.tab == DocumentTab.Draft) "blank" else it.title }
    private fun NotesViewModel.activeTitle() = tabs.first { it.isActive }.let { if (it.tab == DocumentTab.Draft) "blank" else it.title }

    @Test fun `opening an external document adds a tab and shows its text`() = runTest {
        provider.put(uri, "# Plan\n\n- [ ] one\n", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        assertEquals(listOf("Plan"), vm.titles().filter { it != "blank" })
        assertEquals("Plan", vm.activeTitle())
        assertEquals("# Plan\n\n- [ ] one\n", vm.text)
        assertEquals(true, vm.current?.isExternal)
        assertFalse(vm.readOnly)
        assertEquals(listOf("Plan"), vm.notes.map { it.title })
    }

    @Test fun `typing is written back to the original file`() = runTest {
        provider.put(uri, "start", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, true)
        vm.onTextChange("start and more")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("start and more", provider.text(uri))
        assertTrue("no internal copy expected", root.walkTopDown().none { it.isFile && it.name.endsWith(".md") && !it.path.contains("external-backups") })
    }

    @Test fun `switching tabs flushes edits of an external document`() = runTest {
        provider.put(uri, "ext", name = "Ext.md")
        val vm = launch()
        vm.onTextChange("internal"); vm.newNote()
        vm.openExternal(uri, true)
        vm.onTextChange("ext edited")
        vm.selectTab(vm.tabs.first { it.title == "internal" }.tab)
        assertEquals("ext edited", provider.text(uri))
        assertEquals("internal", vm.text)
    }

    @Test fun `opening the same document twice does not add a second tab`() = runTest {
        provider.put(uri, "x", name = "Plan.md")
        val vm = launch()
        vm.openExternal(uri, true)
        vm.openExternal(uri, true)
        assertEquals(1, vm.tabs.count { it.title == "Plan" })
        assertEquals(1, vm.notes.size)
    }

    @Test fun `a read-only document is shown but cannot be changed`() = runTest {
        provider.put(uri, "locked text", name = "Locked.md"); provider.readOnly += uri
        val vm = launch()
        vm.openExternal(uri, true)
        assertTrue(vm.readOnly)
        vm.onTextChange("vandalized")
        vm.clear()
        vm.flush(); advanceTimeBy(10_000); runCurrent()
        assertEquals("locked text", vm.text)
        assertEquals("locked text", provider.text(uri))
        assertTrue(provider.writes.isEmpty())
    }

    @Test fun `read-only state follows the tab`() = runTest {
        provider.put(uri, "locked", name = "Locked.md"); provider.readOnly += uri
        val vm = launch()
        vm.onTextChange("mine"); vm.newNote()
        vm.openExternal(uri, true)
        assertTrue(vm.readOnly)
        vm.selectTab(vm.tabs.first { it.title == "mine" }.tab)
        assertFalse(vm.readOnly)
        vm.onTextChange("mine too")
        vm.flush(); runCurrent()
        assertEquals("mine too", vm.text)
    }

    @Test fun `removing an external document never deletes the file`() = runTest {
        provider.put(uri, "keep", name = "Keep.md")
        val vm = launch()
        vm.openExternal(uri, true)
        vm.onTextChange("keep edited")
        vm.deleteCurrent()
        assertEquals("keep edited", provider.text(uri)) // pending text was written, the file is still there
        assertTrue(vm.notes.isEmpty())
        assertTrue(vm.trash.isEmpty())
        assertEquals(listOf("blank"), vm.titles())
    }

    @Test fun `the session restores external documents`() = runTest {
        provider.put(uri, "ext", name = "Ext.md")
        val first = launch()
        first.onTextChange("internal"); first.newNote()
        first.openExternal(uri, true)
        first.flush(); runCurrent()

        val second = launch()
        assertEquals(listOf("internal", "Ext"), second.titles())
        assertEquals("Ext", second.activeTitle())
        assertEquals("ext", second.text)
    }

    @Test fun `a document that became unavailable is dropped from the restored session with a message`() = runTest {
        provider.put(uri, "ext", name = "Ext.md")
        val first = launch()
        first.onTextChange("internal"); first.newNote()
        first.openExternal(uri, true)
        first.flush(); runCurrent()

        provider.files.remove(uri) // deleted or access revoked in the meantime
        val second = launch()
        assertEquals(UserMessage.SourceUnavailable, second.message)
        assertEquals(listOf("internal"), second.titles())
        assertEquals("internal", second.text)
        assertEquals(setOf("internal", "Ext"), second.notes.map { it.title }.toSet()) // still listed in FILES
        assertEquals(2, second.notes.size)
    }

    @Test fun `a document that cannot be read at all is not kept`() = runTest {
        provider.put(uri, "", name = "Bad.md", bytes = byteArrayOf(0x68, 0xE9.toByte(), 0xFF.toByte()))
        val vm = launch()
        vm.openExternal(uri, true)
        assertEquals(UserMessage.NoteUnreadable, vm.message)
        assertTrue(vm.notes.isEmpty())
        assertEquals(listOf("blank"), vm.titles())
    }

    @Test fun `opening a missing document leaves no entry behind`() = runTest {
        val vm = launch()
        vm.openExternal("content://docs/doc/gone", true)
        assertEquals(UserMessage.SourceUnavailable, vm.message)
        assertTrue(vm.notes.isEmpty())
    }

    @Test fun `favorite and recent work for external documents`() = runTest {
        provider.put(uri, "t", name = "Ext.md")
        val vm = launch()
        vm.openExternal(uri, true)
        assertEquals(listOf("Ext"), vm.recent.map { it.title })
        vm.toggleFavorite()
        assertEquals(listOf("Ext"), vm.favorites.map { it.title })
        assertTrue(vm.recent.isEmpty())
    }

    @Test fun `external documents cannot be renamed`() = runTest {
        provider.put(uri, "t", name = "Ext.md")
        val vm = launch()
        vm.openExternal(uri, true)
        assertEquals(RenameResult.Failed, vm.rename("New"))
        assertEquals("Ext", vm.current?.title)
    }

    @Test fun `clear empties a writable external document and keeps it listed`() = runTest {
        provider.put(uri, "content", name = "Ext.md")
        val vm = launch()
        vm.openExternal(uri, true)
        vm.clear()
        assertEquals("", provider.text(uri))
        assertEquals(1, vm.notes.size)
    }

    @Test fun `share hands out the title and the current markdown including unsaved text`() = runTest {
        provider.put(uri, "old", name = "Shopping.md")
        val vm = launch()
        vm.openExternal(uri, true)
        vm.onTextChange("# Shopping\n- milk")
        var shared: Pair<String, String>? = null
        vm.share { title, markdown -> shared = title to markdown }
        assertEquals("Shopping" to "# Shopping\n- milk", shared)
        assertEquals("# Shopping\n- milk", provider.text(uri))
    }

    @Test fun `share of an internal note and of a blank page`() = runTest {
        val vm = launch()
        var calls = 0
        vm.share { _, _ -> calls++ }
        assertEquals(0, calls) // nothing to share on an empty page
        vm.onTextChange("# Idea\ntext")
        var shared: Pair<String, String>? = null
        vm.share { t, m -> shared = t to m }
        assertEquals("Idea" to "# Idea\ntext", shared)
        assertNotNull(vm.current)
        assertNull(vm.current?.externalUri)
    }

    // ---- Why a document is read-only, and getting write access ------------------------------------------------

    @Test fun `the reason for read-only is carried to the screen`() = runTest {
        provider.put(uri, "x", name = "A.md"); provider.readOnly += uri
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        assertTrue(vm.readOnly)
        assertEquals(io.github.zeperus.openpad.domain.ReadOnlyReason.NoWriteGrant, vm.readOnlyReason)
        val other = "content://docs/doc/other"
        provider.put(other, "y", name = "B.md"); provider.providerReadOnly += other
        vm.openExternal(other, persistent = true)
        assertEquals(io.github.zeperus.openpad.domain.ReadOnlyReason.ProviderRefuses, vm.readOnlyReason)
        val unknown = "content://docs/doc/unknown"
        provider.put(unknown, "z", name = "C.md"); provider.undetermined += unknown
        vm.openExternal(unknown, persistent = true)
        assertEquals(io.github.zeperus.openpad.domain.ReadOnlyReason.Unavailable, vm.readOnlyReason)
        assertTrue(vm.readOnly)
    }

    @Test fun `a writable document has no reason and is edited in place`() = runTest {
        provider.put(uri, "# T\n", name = "T.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        assertFalse(vm.readOnly)
        assertNull(vm.readOnlyReason)
        vm.onTextChange("# T\n\nedited\n")
        vm.flush(); runCurrent()
        assertEquals("# T\n\nedited\n", provider.text(uri))
    }

    @Test fun `a read-only document cannot be changed and nothing is written`() = runTest {
        provider.put(uri, "keep", name = "K.md"); provider.readOnly += uri
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        vm.onTextChange("changed")
        vm.onRowText(vm.ui.doc.rows[0].id, "keep!", 5)
        vm.toggleStyle(io.github.zeperus.openpad.editor.SpanKind.Bold)
        vm.flush(); runCurrent()
        assertEquals("keep", provider.text(uri))
        assertEquals(0, provider.writes.size)
        // copy and share still work
        var shared: String? = null
        vm.share { _, markdown -> shared = markdown }
        runCurrent()
        assertEquals("keep", shared)
    }

    @Test fun `opening through the picker again upgrades a read-only document to writable`() = runTest {
        val viaOpenWith = "content://other.provider/doc/readme"
        val viaPicker = "content://docs/doc/readme"
        provider.put(viaOpenWith, "# Readme\n", name = "README.md"); provider.readOnly += viaOpenWith
        provider.put(viaPicker, "# Readme\n", name = "README.md")
        val vm = launch()
        vm.openExternal(viaOpenWith, persistent = false)
        assertTrue(vm.readOnly)
        val oldId = vm.current!!.id
        vm.upgradeExternal(viaPicker, persistent = true); runCurrent()
        assertFalse(vm.readOnly)
        assertTrue(vm.current!!.id != oldId)
        assertTrue("the read-only entry is gone", vm.notes.none { it.id == oldId })
        assertEquals(1, vm.notes.size)
        vm.onTextChange("# Readme\n\nnow edited\n")
        vm.flush(); runCurrent()
        assertEquals("# Readme\n\nnow edited\n", provider.text(viaPicker))
        assertEquals("# Readme\n", provider.text(viaOpenWith))
    }

    @Test fun `if the picker also gives read-only access both stay and the reason remains`() = runTest {
        val a = "content://one/doc/a"
        val b = "content://two/doc/a"
        provider.put(a, "x", name = "A.md"); provider.readOnly += a
        provider.put(b, "x", name = "A.md"); provider.providerReadOnly += b
        val vm = launch()
        vm.openExternal(a, persistent = false)
        vm.upgradeExternal(b, persistent = true); runCurrent()
        assertTrue(vm.readOnly)
        assertEquals(io.github.zeperus.openpad.domain.ReadOnlyReason.ProviderRefuses, vm.readOnlyReason)
        assertEquals(2, vm.notes.size)
    }

    @Test fun `a document whose access was withdrawn is dropped with a message instead of showing nothing`() = runTest {
        provider.put(uri, "x", name = "A.md")
        val vm = launch()
        vm.openExternal(uri, persistent = true)
        vm.closeCurrent(); runCurrent()
        provider.failReads = true
        vm.openNote(vm.notes.single().id); runCurrent()
        assertEquals(UserMessage.SourceUnavailable, vm.message)
    }
}
