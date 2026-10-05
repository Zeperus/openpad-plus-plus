package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.OpenDocuments
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.domain.StartupPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class FileSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "openpad/session.json")
    private fun store() = FileSessionStore(file)

    // ---- Round trip ------------------------------------------------------------------------------------------

    @Test fun `no file means no session`() = runBlocking {
        assertEquals(PersistedSession(), store().load())
    }

    @Test fun `order and active note survive a restart`() = runBlocking {
        store().save(PersistedSession(listOf("c", "a", "b"), "a"))
        val loaded = store().load() // a brand new store instance, like after process death
        assertEquals(listOf("c", "a", "b"), loaded.noteIds)
        assertEquals("a", loaded.activeNoteId)
    }

    @Test fun `no active note is stored as absent`() = runBlocking {
        store().save(PersistedSession(listOf("a"), null))
        assertNull(store().load().activeNoteId)
        assertFalse(file.readText().contains("activeNoteId"))
    }

    @Test fun `an empty session round trips`() = runBlocking {
        store().save(PersistedSession())
        assertEquals(PersistedSession(), store().load())
    }

    @Test fun `the last save wins`() = runBlocking {
        val s = store()
        s.save(PersistedSession(listOf("a"), "a"))
        s.save(PersistedSession(listOf("a", "b"), "b"))
        s.save(PersistedSession(emptyList(), null))
        assertEquals(PersistedSession(), store().load())
    }

    @Test fun `the directory is created on demand and no temp files are left`() = runBlocking {
        store().save(PersistedSession(listOf("a"), "a"))
        assertTrue(file.isFile)
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    // ---- Damaged and unexpected content ----------------------------------------------------------------------

    @Test fun `empty or garbage files mean no session and never throw`() = runBlocking {
        file.parentFile!!.mkdirs()
        for (content in listOf("", "   ", "not json", "{ \"noteIds\": [", "null", "[]", "42", "\"text\"")) {
            file.writeText(content)
            assertEquals("'$content'", PersistedSession(), store().load())
        }
    }

    @Test fun `binary garbage means no session`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01, 0x7B))
        assertEquals(PersistedSession(), store().load())
    }

    @Test fun `entries of the wrong type are skipped instead of discarding the whole session`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("""{ "noteIds": ["a", 1, null, {"x": 1}, ["n"], true, "b"], "activeNoteId": 5 }""")
        val loaded = store().load()
        assertEquals(listOf("a", "b"), loaded.noteIds)
        assertNull(loaded.activeNoteId)
    }

    @Test fun `wrong container types are tolerated`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("""{ "noteIds": "a,b", "activeNoteId": ["a"] }""")
        assertEquals(PersistedSession(), store().load())
    }

    @Test fun `unknown fields and future versions are ignored`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("""{ "version": 99, "noteIds": ["a"], "activeNoteId": "a", "tabColors": {"a": "red"} }""")
        assertEquals(PersistedSession(listOf("a"), "a"), store().load())
    }

    @Test fun `a damaged file is simply replaced by the next save`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("{ broken")
        store().save(PersistedSession(listOf("a"), "a"))
        assertEquals(PersistedSession(listOf("a"), "a"), store().load())
    }

    @Test fun `an interrupted write leaves the previous session intact`() = runBlocking {
        store().save(PersistedSession(listOf("a", "b"), "b"))
        File(file.path + ".tmp").writeText("{ \"noteIds\": [\"a\", \"b\", \"c\"") // crash in the middle of a save
        assertEquals(PersistedSession(listOf("a", "b"), "b"), store().load())
    }

    @Test fun `a broken session never touches note files or the note index`() = runBlocking {
        val repo = FileNoteRepository(File(tmp.root, "openpad"))
        val note = repo.createNote("Precious\ntext")
        file.writeText("{ nonsense")
        store().load()
        store().save(PersistedSession())
        assertEquals("Precious\ntext", FileNoteRepository(File(tmp.root, "openpad")).openNote(note.id).text)
    }

    // ---- Together with the notes: sessions across restarts ---------------------------------------------------

    private fun newId() = UUID.randomUUID().toString()

    @Test fun `a session is restored across repository and store recreation`() = runBlocking {
        val dir = File(tmp.root, "openpad")
        val repo = FileNoteRepository(dir)
        val a = repo.createNote("Shopping").id
        val b = repo.createNote("Ideas").id
        val c = repo.createNote("Work").id

        val open = OpenDocuments.blank().open(a).open(b).open(c).activate(DocumentTab.Saved(b))
        FileSessionStore(File(dir, "session.json")).save(open.toPersisted())

        // "Restart": everything is rebuilt from disk.
        val restartedRepo = FileNoteRepository(dir)
        val persisted = FileSessionStore(File(dir, "session.json")).load()
        val existing = restartedRepo.listNotes().map { it.id }.toSet()

        val resumed = StartupPlanner.initial(StartupMode.ResumeSession, persisted, existing)
        assertEquals(listOf(a, b, c), resumed.noteIds)
        assertEquals(b, resumed.activeNoteId)

        val plusBlank = StartupPlanner.initial(StartupMode.ResumeAndBlank, persisted, existing)
        assertEquals(listOf(a, b, c), plusBlank.noteIds)
        assertEquals(DocumentTab.Draft, plusBlank.active)
    }

    @Test fun `trashed and permanently deleted notes disappear from the restored session`() = runBlocking {
        val dir = File(tmp.root, "openpad")
        val repo = FileNoteRepository(dir)
        val a = repo.createNote("A").id
        val b = repo.createNote("B").id
        val c = repo.createNote("C").id
        FileSessionStore(File(dir, "session.json")).save(
            OpenDocuments.blank().open(a).open(b).open(c).activate(DocumentTab.Saved(b)).toPersisted(),
        )
        repo.moveToTrash(b) // trashed: no longer an active note
        repo.moveToTrash(c)
        repo.deletePermanently(c) // gone for good

        val restarted = FileNoteRepository(dir)
        val resumed = StartupPlanner.initial(
            StartupMode.ResumeSession,
            FileSessionStore(File(dir, "session.json")).load(),
            restarted.listNotes().map { it.id }.toSet(),
        )
        assertEquals(listOf(a), resumed.noteIds)
        assertEquals(a, resumed.activeNoteId)
        // and Restore does not reopen it by itself
        restarted.restoreFromTrash(b)
        assertFalse(resumed.isOpen(b))
    }

    @Test fun `renaming a note leaves the stored session untouched and the ids stable`() = runBlocking {
        val dir = File(tmp.root, "openpad")
        val repo = FileNoteRepository(dir)
        val a = repo.createNote("Old name").id
        val b = repo.createNote("Other").id
        val open = OpenDocuments.blank().open(a).open(b)
        val store = FileSessionStore(File(dir, "session.json"))
        store.save(open.toPersisted())
        val before = file.readText()

        repo.renameNote(a, "New name")

        assertEquals(before, file.readText())
        assertEquals(open.toPersisted(), store.load())
        val titles = open.items(repo.listNotes(), "New note").map { it.title }
        assertEquals(listOf("New name", "Other", "New note"), titles)
        assertEquals(listOf(a, b), open.noteIds)
        assertEquals(NoteId(a.value), a)
    }

    @Test fun `ids of notes that never existed are ignored without error`() = runBlocking {
        val dir = File(tmp.root, "openpad")
        val repo = FileNoteRepository(dir)
        val real = repo.createNote("Real").id
        FileSessionStore(File(dir, "session.json")).save(PersistedSession(listOf(newId(), real.value, newId()), real.value))
        val resumed = StartupPlanner.initial(
            StartupMode.ResumeSession,
            FileSessionStore(File(dir, "session.json")).load(),
            repo.listNotes().map { it.id }.toSet(),
        )
        assertEquals(listOf(real), resumed.noteIds)
    }
}
