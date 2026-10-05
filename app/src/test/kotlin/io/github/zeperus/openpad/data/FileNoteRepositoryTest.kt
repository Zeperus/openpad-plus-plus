package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteNotInTrashException
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class FileNoteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var now = 1_000L
    private var idCounter = 0L

    /** While true, every index write fails like a crash right before the index is replaced. */
    private var indexWriteFails = false

    private fun repo() = FileNoteRepository(
        root = root,
        clock = { now++ },
        dispatcher = Dispatchers.IO,
        newId = { UUID(0, ++idCounter).toString() },
        indexWriter = { file, text ->
            if (indexWriteFails) throw IOException("simulated crash before index write")
            AtomicFiles.writeText(file, text)
        },
    )

    private fun noteFile(id: NoteId) = File(root, "notes/${id.value}.md")
    private fun noteFile(info: NoteInfo) = noteFile(info.id)
    private fun trashFile(info: NoteInfo) = File(root, "trash/${info.id.value}.md")
    private fun nextId(n: Long) = UUID(0, n).toString()

    private suspend fun failingIndexWrites(block: suspend () -> Unit) {
        indexWriteFails = true
        try {
            block()
            fail("expected the simulated crash")
        } catch (_: NoteStorageException) {
            // expected
        } finally {
            indexWriteFails = false
        }
    }

    // ---- Create / save / open --------------------------------------------------------------------------------

    @Test fun `create stores a real md file named by id with the exact text`() = runBlocking {
        val info = repo().createNote("# Shopping\n\n- [ ] Milk\n")
        assertEquals("Shopping", info.title)
        assertEquals("Shopping.md", info.exportFileName)
        assertEquals("# Shopping\n\n- [ ] Milk\n", noteFile(info).readText())
    }

    @Test fun `create without usable first line falls back to Untitled and numbers titles`() = runBlocking {
        val r = repo()
        assertEquals("Untitled", r.createNote("").title)
        assertEquals("Untitled 2", r.createNote("").title)
        assertEquals("Untitled 3", r.createNote("   \n").title)
        assertEquals(3, notesDirFiles().size)
    }

    @Test fun `same first line twice gets numbered titles but separate files`() = runBlocking {
        val r = repo()
        val a = r.createNote("Todo")
        val b = r.createNote("Todo")
        assertEquals(listOf("Todo", "Todo 2"), listOf(a.title, b.title))
        assertNotEquals(a.id, b.id)
    }

    @Test fun `save then reopen returns the saved text`() = runBlocking {
        val r = repo()
        val id = r.createNote("a").id
        r.saveNote(id, "a\nb")
        assertEquals("a\nb", r.openNote(id).text)
    }

    @Test fun `notes survive recreating the repository`() = runBlocking {
        val first = repo()
        val a = first.createNote("Alpha\ntext")
        val b = first.createNote("Beta")
        first.moveToTrash(b.id)

        val second = repo()
        assertEquals(listOf(a.id), second.listNotes().map { it.id })
        assertEquals(listOf(b.id), second.listTrash().map { it.id })
        assertEquals("Alpha\ntext", second.openNote(a.id).text)
        assertEquals(a.createdAt, second.listNotes().single().createdAt)
    }

    @Test fun `text is preserved byte-exact including CRLF unicode and trailing whitespace`() = runBlocking {
        val text = "Täst 😀\r\n  indented  \r\n\r\n"
        val info = repo().createNote(text)
        assertEquals(text, repo().openNote(info.id).text)
        assertEquals(text, noteFile(info).readText())
    }

    @Test fun `saving leaves no temp files and replaces content completely`() = runBlocking {
        val r = repo()
        val info = r.createNote("long original content")
        r.saveNote(info.id, "short")
        assertEquals("short", noteFile(info).readText())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    // ---- Titles ----------------------------------------------------------------------------------------------

    @Test fun `title follows first line until the user renames`() = runBlocking {
        val r = repo()
        val id = r.createNote("Draft").id
        assertEquals("Groceries", r.saveNote(id, "# Groceries\nmilk").title)
        assertEquals("Mine", r.renameNote(id, "Mine").title)
        assertEquals("Mine", r.saveNote(id, "# Something else").title)
        assertEquals("# Something else", r.openNote(id).text)
    }

    @Test fun `emptying an auto-titled note keeps its title`() = runBlocking {
        val r = repo()
        val info = r.createNote("Keep me")
        assertEquals("Keep me", r.saveNote(info.id, "").title)
        assertEquals("", noteFile(info).readText())
    }

    @Test fun `collision suffix is not re-evaluated on every save`() = runBlocking {
        val r = repo()
        r.createNote("Todo")
        val second = r.createNote("Todo")
        assertEquals("Todo 2", second.title)
        assertEquals("Todo 2", r.saveNote(second.id, "Todo\nmore").title)
    }

    @Test fun `retitling never moves or renames the file`() = runBlocking {
        val r = repo()
        val info = r.createNote("Old")
        val before = notesDirFiles()
        r.saveNote(info.id, "Brand new first line")
        r.renameNote(info.id, "Manual")
        assertEquals(before, notesDirFiles())
    }

    // ---- Rename ----------------------------------------------------------------------------------------------

    @Test fun `rename keeps id file and content`() = runBlocking {
        val r = repo()
        val created = r.createNote("Old\nbody")
        val renamed = r.renameNote(created.id, "New name")
        assertEquals(created.id, renamed.id)
        assertEquals("New name", renamed.title)
        assertEquals("Old\nbody", noteFile(created).readText())
        assertEquals("New name", repo().listNotes().single().title)
        assertEquals(created.id, repo().listNotes().single().id)
    }

    @Test fun `rename rejects names without usable characters`() = runBlocking {
        val r = repo()
        val info = r.createNote("Note")
        for (bad in listOf("", "   ", "???", "...")) {
            try { r.renameNote(info.id, bad); fail("expected failure for '$bad'") } catch (_: InvalidNoteNameException) {}
        }
        assertEquals("Note", r.listNotes().single().title)
    }

    @Test fun `rename to an existing title fails without touching either note`() = runBlocking {
        val r = repo()
        val one = r.createNote("One")
        val two = r.createNote("Two")
        try { r.renameNote(two.id, "one"); fail() } catch (_: NoteNameConflictException) {}
        assertEquals(setOf("One", "Two"), r.listNotes().map { it.title }.toSet())
        assertEquals("One", r.openNote(one.id).text)
    }

    @Test fun `case-only rename of the same note is allowed`() = runBlocking {
        val r = repo()
        val id = r.createNote("note").id
        assertEquals("Note", r.renameNote(id, "Note").title)
    }

    @Test fun `titles with path syntax cannot escape the store`() = runBlocking {
        val r = repo()
        val info = r.createNote("Safe")
        val renamed = r.renameNote(info.id, "../../evil")
        assertEquals("evil", renamed.title)
        assertFalse(File(tmp.root, "evil.md").exists())
        assertFalse(File(root, "evil.md").exists())
        assertTrue(noteFile(info).exists())
    }

    @Test fun `tampered index entries with unsafe ids are ignored`() = runBlocking {
        val outside = File(tmp.root, "secret.md").apply { writeText("do not touch") }
        repo().createNote("Real")
        val index = File(root, "index.json")
        index.writeText(
            index.readText().replace(Regex("\"id\": \"[^\"]+\""), "\"id\": \"../../secret\""),
        )
        val r = repo()
        // the entry is dropped and the real file is adopted again under its own (valid) id
        assertEquals(listOf("Real"), r.listNotes().map { it.title })
        assertEquals("do not touch", outside.readText())
    }

    @Test fun `operations on unknown ids fail cleanly`() = runBlocking {
        val r = repo()
        val ghost = NoteId("nope")
        for (op in listOf<suspend () -> Unit>(
            { r.openNote(ghost) }, { r.saveNote(ghost, "x") }, { r.renameNote(ghost, "x") }, { r.moveToTrash(ghost) },
        )) {
            try { op(); fail() } catch (_: NoteNotFoundException) {}
        }
        try { r.restoreFromTrash(ghost); fail() } catch (_: NoteNotInTrashException) {}
        try { r.deletePermanently(ghost); fail() } catch (_: NoteNotInTrashException) {}
    }

    // ---- Trash -----------------------------------------------------------------------------------------------

    @Test fun `move to trash moves the file and hides the note from Files`() = runBlocking {
        val r = repo()
        val info = r.createNote("Bye\ntext")
        val trashed = r.moveToTrash(info.id)
        assertTrue(trashed.isTrashed)
        assertFalse(noteFile(info).exists())
        assertEquals("Bye\ntext", trashFile(info).readText())
        assertTrue(r.listNotes().isEmpty())
        assertEquals(listOf(info.id), r.listTrash().map { it.id })
        assertEquals("Bye\ntext", r.openNote(info.id).text) // still readable in Trash
    }

    @Test fun `trashed notes cannot be saved or renamed`() = runBlocking {
        val r = repo()
        val info = r.createNote("X")
        r.moveToTrash(info.id)
        try { r.saveNote(info.id, "changed"); fail() } catch (_: NoteNotFoundException) {}
        try { r.renameNote(info.id, "Y"); fail() } catch (_: NoteNotFoundException) {}
        assertEquals("X", trashFile(info).readText())
    }

    @Test fun `restore brings the note back with its content and id`() = runBlocking {
        val r = repo()
        val info = r.createNote("Back\nagain")
        r.moveToTrash(info.id)
        val restored = r.restoreFromTrash(info.id)
        assertFalse(restored.isTrashed)
        assertEquals(info.id, restored.id)
        assertEquals("Back\nagain", noteFile(info).readText())
        assertTrue(r.listTrash().isEmpty())
    }

    @Test fun `restore never overwrites a note that took the title meanwhile`() = runBlocking {
        val r = repo()
        val old = r.createNote("Plan\nold")
        r.moveToTrash(old.id)
        val newer = r.createNote("Plan\nnew")
        val restored = r.restoreFromTrash(old.id)
        assertEquals("Plan 2", restored.title)
        assertEquals("Plan\nnew", noteFile(newer).readText())
        assertEquals("Plan\nold", noteFile(old).readText())
    }

    @Test fun `trashing two notes with the same title keeps both`() = runBlocking {
        val r = repo()
        val a = r.createNote("Same\na")
        r.moveToTrash(a.id)
        val b = r.createNote("Same\nb")
        r.moveToTrash(b.id)
        assertEquals("Same\na", r.openNote(a.id).text)
        assertEquals("Same\nb", r.openNote(b.id).text)
        assertEquals(2, root.resolve("trash").listFiles()!!.size)
    }

    @Test fun `permanent delete removes the file and metadata`() = runBlocking {
        val r = repo()
        val info = r.createNote("Gone")
        r.moveToTrash(info.id)
        r.deletePermanently(info.id)
        assertFalse(trashFile(info).exists())
        assertTrue(r.listTrash().isEmpty())
        assertTrue(repo().listTrash().isEmpty())
    }

    @Test fun `permanent delete refuses active notes`() = runBlocking {
        val r = repo()
        val info = r.createNote("Active")
        try { r.deletePermanently(info.id); fail() } catch (_: NoteNotInTrashException) {}
        assertTrue(noteFile(info).exists())
    }

    @Test fun `listNotes is sorted by title ignoring case`() = runBlocking {
        val r = repo()
        r.createNote("banana"); r.createNote("Apple"); r.createNote("cherry")
        assertEquals(listOf("Apple", "banana", "cherry"), r.listNotes().map { it.title })
    }

    // ---- Index loss / adoption -------------------------------------------------------------------------------

    @Test fun `notes keep their ids when the index is lost because the id is the file name`() = runBlocking {
        val first = repo()
        val kept = first.createNote("Kept\ntext")
        val trashed = first.createNote("Trashed")
        first.moveToTrash(trashed.id)
        File(root, "index.json").delete()

        val r = repo()
        assertEquals(listOf(kept.id), r.listNotes().map { it.id })
        assertEquals("Kept", r.listNotes().single().title) // re-derived from the first line
        assertEquals("Kept\ntext", r.openNote(kept.id).text)
        assertEquals(listOf(trashed.id), r.listTrash().map { it.id })
    }

    @Test fun `a corrupt index is kept aside and notes are adopted with their ids`() = runBlocking {
        val info = repo().createNote("Survivor")
        File(root, "index.json").writeText("{ this is not json")
        val r = repo()
        assertEquals(listOf(info.id), r.listNotes().map { it.id })
        assertEquals("{ this is not json", File(root, "index.json.corrupt").readText())
    }

    @Test fun `md files dropped into the directory by hand are adopted under a new id`() = runBlocking {
        repo().createNote("A")
        File(root, "notes/Manual.md").writeText("hand made")
        val r = repo()
        val manual = r.listNotes().single { it.title == "Manual" }
        assertEquals("hand made", r.openNote(manual.id).text)
        assertTrue(noteFile(manual).exists())
        assertFalse(File(root, "notes/Manual.md").exists())
    }

    @Test fun `metadata for a vanished file is dropped`() = runBlocking {
        val info = repo().createNote("Vanishing")
        noteFile(info).delete()
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `a file that is not valid UTF-8 is reported and its bytes are never changed`() = runBlocking {
        val bytes = byteArrayOf(0x68, 0x69, 0xFF.toByte(), 0xFE.toByte(), 0x0A)
        File(root, "notes").mkdirs()
        File(root, "notes/Latin.md").writeBytes(bytes)
        val r = repo()
        val info = r.listNotes().single()
        try { r.openNote(info.id); fail() } catch (_: NoteUnreadableException) {}
        assertTrue(bytes.contentEquals(noteFile(info).readBytes()))
    }

    // ---- Crash consistency -----------------------------------------------------------------------------------

    @Test fun `interrupted write leaves the previous content intact and temp files are cleaned`() = runBlocking {
        val info = repo().createNote("Precious\noriginal")
        File(noteFile(info).path + ".tmp").writeText("Precious\nhalf wri") // crash in the middle of a save
        File(root, "index.json.tmp").writeText("{\"version\": 2, \"no") // ... or of an index write
        val r = repo()
        assertEquals("Precious\noriginal", r.openNote(info.id).text)
        assertEquals(listOf(info.id), r.listNotes().map { it.id })
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun `crash during rename leaves the old title and the same id and text`() = runBlocking {
        val r = repo()
        val info = r.createNote("Before\nbody")
        failingIndexWrites { r.renameNote(info.id, "After") }

        // same process: the next operation reloads from disk instead of trusting half-applied memory
        assertEquals("Before", r.listNotes().single().title)
        // after a restart: identical
        val restarted = repo().listNotes().single()
        assertEquals(info.id, restarted.id)
        assertEquals("Before", restarted.title)
        assertEquals("Before\nbody", repo().openNote(info.id).text)
        // and the rename can simply be done again
        assertEquals("After", r.renameNote(info.id, "After").title)
        assertEquals(info.id, repo().listNotes().single().id)
    }

    @Test fun `rename that completed before a crash is fully applied after restart`() = runBlocking {
        val r = repo()
        val info = r.createNote("Before")
        r.renameNote(info.id, "After")
        val restarted = repo().listNotes().single()
        assertEquals(info.id, restarted.id)
        assertEquals("After", restarted.title)
        assertFalse(restarted.autoTitle)
    }

    @Test fun `crash after moving a file to trash but before the index write keeps id and text`() = runBlocking {
        val r = repo()
        val info = r.createNote("Half moved\nbody")
        failingIndexWrites { r.moveToTrash(info.id) }
        assertFalse(noteFile(info).exists()) // the move itself did happen

        val trashed = repo().listTrash().single()
        assertEquals(info.id, trashed.id)
        assertEquals("Half moved", trashed.title)
        assertEquals("Half moved\nbody", repo().openNote(info.id).text)
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `trash crash keeps the id even when another trashed note has the same title`() = runBlocking {
        val r = repo()
        val a = r.createNote("Same\na")
        r.moveToTrash(a.id)
        val b = r.createNote("Same\nb")
        failingIndexWrites { r.moveToTrash(b.id) }

        val restarted = repo()
        assertEquals(setOf(a.id, b.id), restarted.listTrash().map { it.id }.toSet())
        assertEquals("Same\nb", restarted.openNote(b.id).text)
    }

    @Test fun `crash after restoring a file but before the index write keeps id and text`() = runBlocking {
        val r = repo()
        val info = r.createNote("Back")
        r.moveToTrash(info.id)
        failingIndexWrites { r.restoreFromTrash(info.id) }

        val restarted = repo()
        assertEquals(listOf(info.id), restarted.listNotes().map { it.id })
        assertTrue(restarted.listTrash().isEmpty())
    }

    @Test fun `crash right after creating a note adopts the file under the same id`() = runBlocking {
        val r = repo()
        failingIndexWrites { r.createNote("# Fresh\nwords") }
        val adopted = repo().listNotes().single()
        assertEquals(nextId(1), adopted.id.value) // the id the failed create had picked
        assertEquals("Fresh", adopted.title)
        assertEquals("# Fresh\nwords", repo().openNote(adopted.id).text)
    }

    @Test fun `crash after saving text but before the index write keeps the new text`() = runBlocking {
        val r = repo()
        val info = r.createNote("Doc")
        failingIndexWrites { r.saveNote(info.id, "Doc\nnew words") }
        assertEquals("Doc\nnew words", repo().openNote(info.id).text)
        assertEquals(info.id, repo().listNotes().single().id)
    }

    @Test fun `crash during permanent delete drops the metadata of the deleted file`() = runBlocking {
        val r = repo()
        val info = r.createNote("Bye")
        r.moveToTrash(info.id)
        failingIndexWrites { r.deletePermanently(info.id) }
        assertTrue(repo().listTrash().isEmpty())
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `a note present in both directories is never overwritten or lost`() = runBlocking {
        val info = repo().createNote("Twin\nactive")
        trashFile(info).apply { parentFile!!.mkdirs(); writeText("Twin\nstale copy") }
        val r = repo()
        assertEquals("Twin\nactive", r.openNote(info.id).text)
        // the stray copy is kept as a separate note rather than destroyed
        assertEquals(1, r.listTrash().size)
        assertNotEquals(info.id, r.listTrash().single().id)
        assertEquals("Twin\nstale copy", r.openNote(r.listTrash().single().id).text)
    }

    // ---- Migration from the version-1 layout (files named after their title) ---------------------------------

    private fun writeLegacyIndex(vararg entries: String) {
        File(root, "index.json").apply {
            parentFile!!.mkdirs()
            writeText("""{ "version": 1, "notes": [ ${entries.joinToString(",")} ] }""")
        }
    }

    @Test fun `version 1 notes are migrated to id-named files keeping their ids`() = runBlocking {
        val activeId = nextId(900)
        val trashedId = nextId(901)
        File(root, "notes").mkdirs(); File(root, "trash").mkdirs()
        File(root, "notes/Shopping.md").writeText("# Shopping\nmilk")
        File(root, "trash/Old idea.md").writeText("old")
        writeLegacyIndex(
            """{"id":"$activeId","fileName":"Shopping.md","createdAt":10,"updatedAt":20,"trashedAt":null,"autoTitle":false}""",
            """{"id":"$trashedId","fileName":"Old idea.md","createdAt":11,"updatedAt":21,"trashedAt":30}""",
        )

        val r = repo()
        val active = r.listNotes().single()
        assertEquals(NoteId(activeId), active.id)
        assertEquals("Shopping", active.title)
        assertFalse(active.autoTitle)
        assertEquals(20L, active.updatedAt)
        assertEquals("# Shopping\nmilk", r.openNote(active.id).text)
        assertEquals(NoteId(trashedId), r.listTrash().single().id)
        assertEquals("Old idea", r.listTrash().single().title)
        assertEquals(30L, r.listTrash().single().trashedAt)

        assertEquals(listOf("$activeId.md"), notesDirFiles())
        assertFalse(File(root, "notes/Shopping.md").exists())
        assertTrue(File(root, "trash/$trashedId.md").exists())
        assertFalse("index is rewritten without legacy names", File(root, "index.json").readText().contains("fileName"))
        assertTrue(File(root, "index.json").readText().contains("\"version\": 3"))
    }

    @Test fun `a minimal version 1 index without optional fields still loads`() = runBlocking {
        val id = nextId(902)
        File(root, "notes").mkdirs()
        File(root, "notes/Plain.md").writeText("x")
        File(root, "index.json").writeText("""{"notes":[{"id":"$id","fileName":"Plain.md","createdAt":1,"updatedAt":2}]}""")
        val note = repo().listNotes().single()
        assertEquals(NoteId(id), note.id)
        assertEquals("Plain", note.title)
        assertTrue(note.autoTitle)
    }

    @Test fun `migration interrupted after renaming the file but before the index write still loads`() = runBlocking {
        val id = nextId(903)
        File(root, "notes").mkdirs()
        File(root, "notes/$id.md").writeText("already migrated") // renamed, index still legacy
        writeLegacyIndex("""{"id":"$id","fileName":"Shopping.md","createdAt":1,"updatedAt":2}""")
        val r = repo()
        assertEquals(NoteId(id), r.listNotes().single().id)
        assertEquals("Shopping", r.listNotes().single().title)
        assertEquals("already migrated", r.openNote(NoteId(id)).text)
    }

    @Test fun `a legacy entry whose file sits in the other directory follows the file`() = runBlocking {
        val id = nextId(904)
        File(root, "trash").mkdirs()
        File(root, "trash/Moved.md").writeText("moved before crash")
        writeLegacyIndex("""{"id":"$id","fileName":"Moved.md","createdAt":1,"updatedAt":2,"trashedAt":null}""")
        val r = repo()
        assertTrue(r.listNotes().isEmpty())
        assertEquals(NoteId(id), r.listTrash().single().id)
    }

    private fun notesDirFiles() = File(root, "notes").list().orEmpty().sorted()
}
