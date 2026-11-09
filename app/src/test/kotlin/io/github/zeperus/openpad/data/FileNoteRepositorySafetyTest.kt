package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.ExternalDocuments
import io.github.zeperus.openpad.domain.NoteChangedExternallyException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Data safety of the storage layer: failed deletes, providers that block, documents changed by another app. */
class FileNoteRepositorySafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private val provider = FakeExternalDocuments()
    private var now = 1_000L
    private var ids = 0L
    private val uri = "content://docs/doc/safety"

    private fun repo(
        deleteFile: (File) -> Boolean = File::delete,
        externalTimeoutMs: Long = 15_000L,
        external: ExternalDocuments = provider,
    ) = FileNoteRepository(
        root = root,
        clock = { now++ },
        dispatcher = Dispatchers.IO,
        newId = { UUID(0, ++ids).toString() },
        indexWriter = AtomicFiles::writeText,
        external = external,
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

    @Test(timeout = 20_000)
    fun `a provider that blocks forever does not block other notes and its abandoned save never runs`() = runBlocking {
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

    @Test(timeout = 20_000)
    fun `a document with a stuck call fails fast on the next call while other documents keep working`() = runBlocking {
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

    @Test fun `checkExternal without a baseline reads nothing`() = runBlocking {
        provider.put(uri, "outside", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id // nothing has been read yet, so there is nothing to compare with
        val reads = AtomicInteger()
        provider.onRead = { reads.incrementAndGet() }
        try {
            assertNull(r.checkExternal(id))
        } finally {
            provider.onRead = null
        }
        assertEquals(0, reads.get())
    }

    @Test fun `a version changed elsewhere is offered once and only adopting it lets the next save through`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.put(uri, "changed elsewhere")
        val version = checkNotNull(r.checkExternal(id))
        assertEquals("changed elsewhere", version.text)
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        r.adoptExternal(id, version)
        assertNull(r.checkExternal(id))
        r.saveNote(id, "our text")
        assertEquals("our text", provider.text(uri))
    }

    @Test fun `a failed write whose re-read also fails keeps the baseline so an outside change is still detected`() = runBlocking {
        provider.put(uri, "old text", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.truncateThenFail = 3
        provider.failReadsAfterWrite = true
        try { r.saveNote(id, "new text"); fail("expected the failed write to be reported") } catch (_: NoteSourceUnavailableException) {}
        provider.truncateThenFail = null
        provider.failReadsAfterWrite = false
        provider.failReads = false
        provider.put(uri, "changed elsewhere")
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `our own write that reports a failure but lands later is not a conflict on the retry`() = runBlocking {
        provider.put(uri, "old text", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.failAfterStoring = true
        provider.failReadsAfterWrite = true
        try { r.saveNote(id, "first attempt"); fail("expected the failed write to be reported") } catch (_: NoteSourceUnavailableException) {}
        provider.failAfterStoring = false
        provider.failReadsAfterWrite = false
        provider.failReads = false
        // the document holds our first attempt, which the earlier failed write left there: it is ours, not a change by another app
        r.saveNote(id, "other text")
        assertEquals("other text", provider.text(uri))
    }

    @Test fun `peekText reads an external document without moving the baseline or writing the index`() = runBlocking {
        provider.put(uri, "original", name = "Doc.md")
        val r = repo()
        val id = r.openExternal(uri, persistent = true).id
        r.openNote(id)
        provider.put(uri, "changed elsewhere")
        val index = File(root, "index.json")
        val indexBefore = index.readBytes()
        val modifiedBefore = index.lastModified()
        val probesBefore = provider.accessChecks.get()
        assertEquals("changed elsewhere", r.peekText(id))
        assertArrayEquals(indexBefore, index.readBytes())
        assertEquals(modifiedBefore, index.lastModified())
        assertEquals(probesBefore, provider.accessChecks.get())
        // the baseline did not move: saving still detects the outside change
        try { r.saveNote(id, "our text"); fail("expected a conflict") } catch (_: NoteChangedExternallyException) {}
        assertEquals("changed elsewhere", provider.text(uri))
    }

    @Test fun `peekText returns the text of an internal note`() = runBlocking {
        val r = repo()
        val info = r.createNote("internal text")
        assertEquals("internal text", r.peekText(info.id))
    }

    @Test fun `a provider exception that is not a storage exception is reported as an unavailable document`() = runBlocking {
        provider.put(uri, "text", name = "Doc.md")
        var broken = true
        val flaky = object : ExternalDocuments by provider {
            override suspend fun read(uri: String, maxBytes: Int): ByteArray =
                if (broken) throw IllegalStateException("provider broke") else provider.read(uri, maxBytes)
        }
        val r = repo(external = flaky)
        val id = r.openExternal(uri, persistent = true).id
        try {
            r.openNote(id)
            fail("expected the provider failure to be reported as unavailable")
        } catch (e: NoteSourceUnavailableException) {
            assertEquals("provider broke", e.message)
        }
        broken = false
        assertEquals("text", r.openNote(id).text) // the failed call released the document
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
