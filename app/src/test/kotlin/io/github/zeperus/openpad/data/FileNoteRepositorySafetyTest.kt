package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test(timeout = 20_000) fun `a provider that blocks forever does not block other notes and its abandoned save never runs`() = runBlocking {
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
        // once the stuck call returns, the document is usable again; the abandoned save was never queued, so nothing is written
        assertEquals("stuck", retryUntilFree { r.openNote(id) }.text)
        assertEquals("stuck", provider.text(uri))
        assertTrue(provider.writes.isEmpty())
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

    // ---- Failed writes and unreadable versions ---------------------------------------------------------------

    @Test fun `a write that fails midway is not mistaken for a change by another app on the retry`() = runBlocking {
        provider.put(uri, "old text", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.truncateThenFail = 3
        try { r.saveNote(id, "a long new text"); fail("expected the failed write to be reported") } catch (_: NoteSourceUnavailableException) {}
        provider.truncateThenFail = null
        r.saveNote(id, "a long new text") // the document holds the partial text, which is ours: no conflict
        assertEquals("a long new text", provider.text(uri))
    }

    @Test fun `an unreadable version written by another app is reported and never overwritten`() = runBlocking {
        provider.put(uri, "readable", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id) // the baseline: the last version that was read
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        provider.put(uri, "", bytes = invalid)
        try { r.openNote(id); fail("expected the unreadable document to be reported") } catch (_: NoteUnreadableException) {}
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        assertTrue(invalid.contentEquals(provider.files.getValue(uri)))
    }

    // ---- Concurrency of provider calls -----------------------------------------------------------------------

    @Test(timeout = 20_000) fun `a document with a stuck call fails fast on the next call while other documents keep working`() = runBlocking {
        val other = "content://docs/doc/other"
        provider.put(uri, "one", name = "One.md")
        provider.put(other, "two", name = "Two.md")
        val r = repo(externalTimeoutMs = 300)
        val id = r.openExternal(uri, persistent = true).id
        val otherId = r.openExternal(other, persistent = true).id
        r.openNote(id)
        r.openNote(otherId)
        val release = CountDownLatch(1)
        provider.blockThread = release
        provider.blockOnly = uri
        try {
            try { r.saveNote(id, "stuck"); fail("expected a timeout") } catch (_: NoteSourceUnavailableException) {}
            val started = System.nanoTime()
            try { r.saveNote(id, "second"); fail("expected an immediate refusal") } catch (_: NoteSourceUnavailableException) {}
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("the second call took $elapsedMs ms, it must not wait", elapsedMs < 250)
            r.saveNote(otherId, "two new")
            assertEquals("two new", provider.text(other))
            r.createNote("internal")
        } finally {
            release.countDown()
        }
        assertEquals(3, r.listNotes().size)
    }

    // ---- Checking for outside changes without moving the baseline --------------------------------------------

    @Test fun `checkExternal reports a change and does not move the baseline`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        assertNull(r.checkExternal(id)) // unchanged
        provider.put(uri, "changed elsewhere")
        assertEquals("changed elsewhere", r.checkExternal(id)?.text)
        assertEquals("changed elsewhere", provider.text(uri)) // the check never writes
        // the baseline still is the original: saving is still refused
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        assertEquals("changed elsewhere", provider.text(uri))
    }

    /** Retries [block] until the document accepts calls again: a stuck call releases it only when it returns. */
    private suspend fun <T> retryUntilFree(block: suspend () -> T): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            try {
                return block()
            } catch (e: NoteSourceUnavailableException) {
                if (System.nanoTime() > deadline) throw e
                delay(20)
            }
        }
    }
}
