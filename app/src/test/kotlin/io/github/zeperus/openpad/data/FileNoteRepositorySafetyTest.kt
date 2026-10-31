package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Data safety of the storage layer: failed deletes, providers that block, documents changed by another app. */
class FileNoteRepositorySafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var now = 1_000L
    private var ids = 0L
    private val uri = "content://docs/doc/safety"

    private fun repo(deleteFile: (File) -> Boolean = File::delete, externalTimeoutMs: Long = 15_000L) = FileNoteRepository(
        root = root,
        clock = { now++ },
        dispatcher = Dispatchers.IO,
        newId = { UUID(0, ++ids).toString() },
        indexWriter = AtomicFiles::writeText,
        external = provider,
        externalTimeoutMs = externalTimeoutMs,
        deleteFile = deleteFile,
    )

    private fun trashFile(id: NoteId) = File(root, "trash/${id.value}.md")
    private fun backupFile(id: NoteId) = File(root, "external-backups/${id.value}.md")

    // ---- Permanent delete ------------------------------------------------------------------------------------

    @Test fun `a trash file that cannot be deleted keeps the note in the Trash`() = runBlocking {
        val first = repo()
        val info = first.createNote("Stubborn")
        first.moveToTrash(info.id)
        val failing = repo(deleteFile = { false })
        try { failing.deletePermanently(info.id); fail("expected the failed delete to be reported") } catch (_: NoteStorageException) {}
        assertTrue(trashFile(info.id).exists())
        assertEquals(listOf(info.id), failing.listTrash().map { it.id })
        // after a restart the note is still in the Trash, not re-adopted as something new
        assertEquals(listOf(info.id), repo().listTrash().map { it.id })
    }

    @Test fun `a trashed note whose file is already gone is removed without calling the deleter`() = runBlocking {
        val r = repo(deleteFile = { false }) // would fail if it were asked to delete a file that does not exist
        val info = r.createNote("Vanished")
        r.moveToTrash(info.id)
        assertTrue(trashFile(info.id).delete())
        r.deletePermanently(info.id)
        assertTrue(r.listTrash().isEmpty())
        assertTrue(repo().listTrash().isEmpty())
    }

    @Test fun `a normal permanent delete removes the file through the deleter`() = runBlocking {
        val deleted = ArrayList<File>()
        val r = repo(deleteFile = { deleted += it; it.delete() })
        val info = r.createNote("Normal")
        r.moveToTrash(info.id)
        r.deletePermanently(info.id)
        assertEquals(1, deleted.size)
        assertFalse(trashFile(info.id).exists())
        assertTrue(repo().listTrash().isEmpty())
    }

    // ---- Provider that blocks --------------------------------------------------------------------------------

    @Test fun `a provider that blocks forever does not block other notes`() = runBlocking {
        provider.put(uri, "stuck", name = "Stuck.md")
        val r = repo(externalTimeoutMs = 300)
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id) // reads the document once, so the save below has a baseline to check against
        val release = CountDownLatch(1)
        provider.blockThread = release
        try {
            withTimeout(5_000) {
                try { r.saveNote(id, "new text"); fail("expected a timeout") } catch (_: NoteSourceUnavailableException) {}
                r.createNote("internal")
            }
        } finally {
            release.countDown()
        }
        assertEquals(2, r.listNotes().size)
    }

    // ---- Document changed by another app ---------------------------------------------------------------------

    @Test fun `a document changed by another app is not overwritten and our text is kept as a recovery copy`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.put(uri, "changed elsewhere")
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        assertEquals("changed elsewhere", provider.text(uri))
        assertEquals("our text", backupFile(id).readText())
    }

    @Test fun `after the user keeps their version the next save overwrites the outside change`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.put(uri, "changed elsewhere")
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        r.overrideExternalChange(id)
        r.saveNote(id, "our text")
        assertEquals("our text", provider.text(uri))
    }

    @Test fun `normal open and save sequences never report a conflict`() = runBlocking {
        provider.put(uri, "v0", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        r.saveNote(id, "v1")
        r.saveNote(id, "v2")
        assertEquals("v2", provider.text(uri))
        assertEquals("v2", r.openNote(id).text)
        r.saveNote(id, "v3")
        assertEquals("v3", provider.text(uri))
    }

    @Test fun `a change to exactly the text being saved is not a conflict`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.put(uri, "same text")
        r.saveNote(id, "same text")
        assertEquals("same text", provider.text(uri))
    }

    @Test fun `overriding a change is refused for internal notes`() = runBlocking {
        val r = repo()
        val info = r.createNote("internal")
        try { r.overrideExternalChange(info.id); fail() } catch (_: NoteNotFoundException) {}
    }
}
