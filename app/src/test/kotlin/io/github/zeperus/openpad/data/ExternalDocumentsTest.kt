package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.ExternalNotSupportedException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteLists
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteNotInTrashException
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** External documents (Storage Access Framework): edited in place, never copied, never deleted by openPad++. */
class ExternalDocumentsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var now = 1_000L
    private var ids = 0L
    private val uri = "content://docs/doc/shopping"

    private fun repo() = FileNoteRepository(root, clock = { now++ }, dispatcher = Dispatchers.IO, newId = { UUID(0, ++ids).toString() }, external = provider)

    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") && !it.path.contains("external-backups") }.toList()

    // ---- Opening ---------------------------------------------------------------------------------------------

    @Test fun `an external document is opened from its uri and edited in place`() = runBlocking {
        provider.put(uri, "# Shopping\n\n- milk\n", name = "Shopping.md")
        val r = repo()
        val info = r.openExternal(uri, persistent = true)
        assertEquals("Shopping", info.title)
        assertEquals(uri, info.externalUri)
        assertTrue(info.isExternal)
        val content = r.openNote(info.id)
        assertEquals("# Shopping\n\n- milk\n", content.text)
        assertFalse(content.readOnly)

        r.saveNote(info.id, "# Shopping\n\n- milk\n- bread\n")
        assertEquals("# Shopping\n\n- milk\n- bread\n", provider.text(uri)) // the original file changed
        assertEquals("# Shopping\n\n- milk\n- bread\n", r.openNote(info.id).text)
    }

    @Test fun `external documents are never copied into internal storage`() = runBlocking {
        provider.put(uri, "text", name = "a.md")
        val r = repo()
        val info = r.openExternal(uri, true)
        r.saveNote(info.id, "text 2")
        assertTrue("no note file expected: ${mdFiles()}", mdFiles().isEmpty())
    }

    @Test fun `opening the same uri twice gives the same note`() = runBlocking {
        provider.put(uri, "x", name = "a.md")
        val r = repo()
        val a = r.openExternal(uri, true)
        val b = r.openExternal(uri, true)
        assertEquals(a.id, b.id)
        assertEquals(1, r.listNotes().size)
    }

    @Test fun `title comes from the display name without the extension`() = runBlocking {
        for ((name, title) in listOf("Notes.md" to "Notes", "Notes.MD" to "Notes", "Plan.markdown" to "Plan", "readme.txt" to "readme.txt", "a.b.md" to "a.b")) {
            provider.put("content://x/$name", "t", name = name)
            assertEquals(name, title, repo().openExternal("content://x/$name", true).title)
        }
    }

    @Test fun `missing display name falls back to a default title`() = runBlocking {
        provider.put("content://x/none", "t")
        assertEquals("Untitled", repo().openExternal("content://x/none", true).title)
    }

    @Test fun `title follows a rename done outside the app`() = runBlocking {
        provider.put(uri, "t", name = "Old.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.names[uri] = "New.md"
        assertEquals("New", r.openNote(id).info.title)
        assertEquals("New", repo().listNotes().single().title)
    }

    // ---- Read only -------------------------------------------------------------------------------------------

    @Test fun `a read-only document is flagged and a write is refused by the provider`() = runBlocking {
        provider.put(uri, "locked", name = "Locked.md"); provider.readOnly += uri
        val r = repo()
        val id = r.openExternal(uri, true).id
        assertTrue(r.openNote(id).readOnly)
        try { r.saveNote(id, "changed"); fail() } catch (_: NoteStorageException) {}
        assertEquals("locked", provider.text(uri))
    }

    // ---- Data safety -----------------------------------------------------------------------------------------

    @Test fun `a recovery copy of the new text is kept before writing`() = runBlocking {
        provider.put(uri, "old", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.failWrites = true
        try { r.saveNote(id, "important new text"); fail() } catch (_: NoteSourceUnavailableException) {}
        val backup = File(root, "external-backups/${id.value}.md")
        assertEquals("important new text", backup.readText())
        assertEquals("old", provider.text(uri)) // the original is untouched by a failed write
    }

    @Test fun `a partial write is detected instead of believed`() = runBlocking {
        provider.put(uri, "old", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.truncateWritesTo = 3
        try { r.saveNote(id, "a long new text"); fail("expected verification to fail") } catch (_: NoteSourceUnavailableException) {}
        assertEquals("a long new text", File(root, "external-backups/${id.value}.md").readText())
        // after the provider recovers the same save succeeds
        provider.truncateWritesTo = null
        r.saveNote(id, "a long new text")
        assertEquals("a long new text", provider.text(uri))
    }

    @Test fun `a failed save does not break later operations in the same session`() = runBlocking {
        provider.put(uri, "old", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.failWrites = true
        try { r.saveNote(id, "x"); fail() } catch (_: NoteStorageException) {}
        provider.failWrites = false
        assertEquals("old", r.openNote(id).text)
        r.saveNote(id, "second try")
        assertEquals("second try", provider.text(uri))
    }

    @Test fun `a hanging provider times out instead of freezing the repository`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val r = FileNoteRepository(root, clock = { now++ }, newId = { UUID(0, ++ids).toString() }, external = provider, externalTimeoutMs = 150)
        val id = r.openExternal(uri, true).id
        provider.hang = true
        try { r.openNote(id); fail("expected a timeout") } catch (_: NoteSourceUnavailableException) {}
        provider.hang = false
        // the repository is not stuck: other operations and a retry work
        assertEquals(1, r.listNotes().size)
        assertEquals("t", r.openNote(id).text)
    }

    // ---- Content fidelity ------------------------------------------------------------------------------------

    @Test fun `utf8 byte order mark is preserved and not shown in the text`() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        provider.put(uri, "", name = "B.md", bytes = bom + "# Title\n".toByteArray())
        val r = repo()
        val id = r.openExternal(uri, true).id
        assertEquals("# Title\n", r.openNote(id).text)
        r.saveNote(id, "# Title\n\nmore\n")
        assertTrue(provider.files.getValue(uri).take(3).toByteArray().contentEquals(bom))
        assertEquals("# Title\n\nmore\n", r.openNote(id).text)
    }

    @Test fun `files without a byte order mark stay without`() = runBlocking {
        provider.put(uri, "plain", name = "B.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        r.openNote(id)
        r.saveNote(id, "plain 2")
        assertEquals('p'.code.toByte(), provider.files.getValue(uri)[0]) // starts with the text, not with a BOM
    }

    @Test fun `crlf unicode and emoji survive byte for byte`() = runBlocking {
        val text = "# Größe 😀\r\n\r\n- Äpfel\r\n- [x] Öl\r\n"
        provider.put(uri, text, name = "C.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        assertEquals(text, r.openNote(id).text)
        r.saveNote(id, text + "- neu 日本語\r\n")
        assertEquals(text + "- neu 日本語\r\n", provider.text(uri))
    }

    @Test fun `a file that is not utf8 is reported and never written`() = runBlocking {
        val latin = byteArrayOf(0x68, 0xE9.toByte(), 0x6C, 0x6C, 0x6F) // "héllo" in Latin-1
        provider.put(uri, "", name = "L.md", bytes = latin)
        val r = repo()
        val id = r.openExternal(uri, true).id
        try { r.openNote(id); fail() } catch (_: NoteUnreadableException) {}
        assertTrue(latin.contentEquals(provider.files.getValue(uri)))
    }

    @Test fun `a file larger than the limit is refused`() = runBlocking {
        provider.put(uri, "", name = "Big.md", bytes = ByteArray(9 * 1024 * 1024) { 'a'.code.toByte() })
        val r = repo()
        val id = r.openExternal(uri, true).id
        try { r.openNote(id); fail() } catch (_: NoteSourceUnavailableException) {}
    }

    @Test fun `an empty external file opens as an empty note`() = runBlocking {
        provider.put(uri, "", name = "E.md")
        val r = repo()
        assertEquals("", r.openNote(r.openExternal(uri, true).id).text)
    }

    // ---- Availability ----------------------------------------------------------------------------------------

    @Test fun `a vanished document is reported and its entry stays`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.files.remove(uri)
        try { r.openNote(id); fail() } catch (_: NoteSourceUnavailableException) {}
        assertEquals(1, r.listNotes().size)
        provider.put(uri, "back", name = "A.md")
        assertEquals("back", r.openNote(id).text)
    }

    @Test fun `revoked access is reported`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        provider.failReads = true
        try { r.openNote(id); fail() } catch (_: NoteSourceUnavailableException) {}
    }

    // ---- Persistence of the entry ----------------------------------------------------------------------------

    @Test fun `a persistent external document survives a restart with its metadata`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val first = repo()
        val id = first.openExternal(uri, true).id
        first.setFavorite(id, true)
        val restarted = repo().listNotes().single()
        assertEquals(id, restarted.id)
        assertEquals(uri, restarted.externalUri)
        assertTrue(restarted.favorite)
    }

    @Test fun `access that does not survive a restart is forgotten at the next start`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        provider.put("content://docs/doc/b", "b", name = "B.md")
        val first = repo()
        first.openExternal(uri, persistent = false)
        first.openExternal("content://docs/doc/b", persistent = true)
        assertEquals(2, first.listNotes().size)
        assertEquals(listOf("B"), repo().listNotes().map { it.title })
    }

    @Test fun `opening an ephemeral document again with persistent access upgrades it`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, false).id
        assertEquals(id, r.openExternal(uri, true).id)
        assertEquals(1, repo().listNotes().size)
    }

    // ---- Operations that do not apply ------------------------------------------------------------------------

    @Test fun `external documents cannot be renamed or trashed`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        try { r.renameNote(id, "New"); fail() } catch (_: ExternalNotSupportedException) {}
        try { r.moveToTrash(id); fail() } catch (_: ExternalNotSupportedException) {}
        try { r.deletePermanently(id); fail() } catch (_: NoteNotInTrashException) {}
        assertEquals("t", provider.text(uri))
        assertEquals(1, r.listNotes().size)
    }

    @Test fun `forgetting an external document never touches the file`() = runBlocking {
        provider.put(uri, "keep me", name = "A.md")
        val r = repo()
        val id = r.openExternal(uri, true).id
        r.saveNote(id, "keep me 2")
        r.forgetExternal(id)
        assertTrue(r.listNotes().isEmpty())
        assertEquals("keep me 2", provider.text(uri))
        assertFalse(File(root, "external-backups/${id.value}.md").exists())
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `forgetting an internal note is refused`() = runBlocking {
        val r = repo()
        val note = r.createNote("internal")
        try { r.forgetExternal(note.id); fail() } catch (_: NoteNotFoundException) {}
        assertEquals(1, r.listNotes().size)
    }

    // ---- Favorites, Recent, Files ----------------------------------------------------------------------------

    @Test fun `external documents take part in favorites recent and files`() = runBlocking {
        provider.put(uri, "t", name = "Ext.md")
        val r = repo()
        val internal = r.createNote("Internal")
        val ext = r.openExternal(uri, true)
        r.setFavorite(ext.id, true)
        val all = r.listNotes()
        assertEquals(setOf("Ext", "Internal"), all.map { it.title }.toSet())
        assertEquals(listOf("Ext"), NoteLists.favorites(all).map { it.title })
        assertEquals(listOf("Internal"), NoteLists.recent(all).map { it.title })
        r.markOpened(internal.id)
        r.setFavorite(ext.id, false)
        assertEquals(setOf("Ext", "Internal"), NoteLists.recent(r.listNotes()).map { it.title }.toSet())
    }

    @Test fun `an external title may equal an internal one`() = runBlocking {
        provider.put(uri, "t", name = "Shopping.md")
        val r = repo()
        r.createNote("Shopping")
        assertEquals("Shopping", r.openExternal(uri, true).title)
        assertEquals(2, r.listNotes().size)
    }

    @Test fun `ids of external documents are valid uuids so they can live in sessions`() = runBlocking {
        provider.put(uri, "t", name = "A.md")
        val id = repo().openExternal(uri, true).id
        assertNotEquals(NoteId(""), id)
        assertEquals(36, id.value.length)
        assertNull(repo().listNotes().single().let { if (it.id == id) null else it })
    }
}
