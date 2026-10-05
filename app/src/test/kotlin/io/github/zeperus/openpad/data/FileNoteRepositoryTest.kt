package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteNotInTrashException
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNoteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var now = 1_000L
    private var idCounter = 0

    private fun repo() = FileNoteRepository(
        root, clock = { now++ }, newId = { "id-${idCounter++}" },
    )

    private fun notesFile(name: String) = File(root, "notes/$name")
    private fun trashFile(name: String) = File(root, "trash/$name")

    @Test fun `create stores a real md file named after the first line`() = runBlocking {
        val info = repo().createNote("# Shopping\n\n- [ ] Milk\n")
        assertEquals("Shopping.md", info.fileName)
        assertEquals("# Shopping\n\n- [ ] Milk\n", notesFile("Shopping.md").readText())
    }

    @Test fun `create without usable first line falls back to Untitled and avoids collisions`() = runBlocking {
        val r = repo()
        assertEquals("Untitled.md", r.createNote("").fileName)
        assertEquals("Untitled 2.md", r.createNote("").fileName)
        assertEquals("Untitled 3.md", r.createNote("   \n").fileName)
    }

    @Test fun `same first line twice gets numbered files`() = runBlocking {
        val r = repo()
        assertEquals("Todo.md", r.createNote("Todo").fileName)
        assertEquals("Todo 2.md", r.createNote("Todo").fileName)
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
        val r = repo()
        val text = "Täst 😀\r\n  indented  \r\n\r\n"
        val id = r.createNote(text).id
        assertEquals(text, repo().openNote(id).text)
        assertEquals(text, notesFile("Täst 😀.md").readText())
    }

    @Test fun `saving leaves no temp files and replaces content completely`() = runBlocking {
        val r = repo()
        val id = r.createNote("long original content").id
        r.saveNote(id, "short")
        assertEquals("short", notesFile("long original content.md").let { f ->
            // title follows first line: file is now named after the new text
            (f.takeIf { it.exists() } ?: notesFile("short.md")).readText()
        })
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun `title follows first line until the user renames`() = runBlocking {
        val r = repo()
        val id = r.createNote("Draft").id
        assertEquals("Groceries.md", r.saveNote(id, "# Groceries\nmilk").fileName)
        assertTrue(notesFile("Groceries.md").exists())
        assertFalse(notesFile("Draft.md").exists())

        assertEquals("Mine.md", r.renameNote(id, "Mine").fileName)
        assertEquals("Mine.md", r.saveNote(id, "# Something else").fileName)
        assertEquals("# Something else", notesFile("Mine.md").readText())
    }

    @Test fun `emptying an auto-titled note keeps its name`() = runBlocking {
        val r = repo()
        val id = r.createNote("Keep me").id
        assertEquals("Keep me.md", r.saveNote(id, "").fileName)
        assertEquals("", notesFile("Keep me.md").readText())
    }

    @Test fun `collision suffix is not re-evaluated on every save`() = runBlocking {
        val r = repo()
        r.createNote("Todo")
        val second = r.createNote("Todo")
        assertEquals("Todo 2.md", second.fileName)
        assertEquals("Todo 2.md", r.saveNote(second.id, "Todo\nmore").fileName)
    }

    @Test fun `rename moves the file keeps content and identity`() = runBlocking {
        val r = repo()
        val created = r.createNote("Old\nbody")
        val renamed = r.renameNote(created.id, "New name")
        assertEquals(created.id, renamed.id)
        assertEquals("New name.md", renamed.fileName)
        assertFalse(notesFile("Old.md").exists())
        assertEquals("Old\nbody", notesFile("New name.md").readText())
        assertEquals("New name", repo().listNotes().single().title)
    }

    @Test fun `rename rejects names without usable characters`() = runBlocking {
        val r = repo()
        val id = r.createNote("Note").id
        for (bad in listOf("", "   ", "???", "...")) {
            try { r.renameNote(id, bad); fail("expected failure for '$bad'") } catch (_: InvalidNoteNameException) {}
        }
        assertTrue(notesFile("Note.md").exists())
    }

    @Test fun `rename to an existing name fails without touching either note`() = runBlocking {
        val r = repo()
        r.createNote("One")
        val two = r.createNote("Two")
        try { r.renameNote(two.id, "one"); fail() } catch (e: NoteNameConflictException) {}
        assertTrue(notesFile("One.md").exists())
        assertTrue(notesFile("Two.md").exists())
    }

    @Test fun `case-only rename of the same note is allowed`() = runBlocking {
        val r = repo()
        val id = r.createNote("note").id
        assertEquals("Note.md", r.renameNote(id, "Note").fileName)
        assertEquals(listOf("Note.md"), root.resolve("notes").list()!!.toList())
    }

    @Test fun `rename cannot escape the notes directory`() = runBlocking {
        val r = repo()
        val id = r.createNote("Safe").id
        val info = r.renameNote(id, "../../evil")
        assertEquals("evil.md", info.fileName)
        assertTrue(notesFile("evil.md").exists())
        assertFalse(File(tmp.root, "evil.md").exists())
        assertFalse(File(root, "evil.md").exists())
    }

    @Test fun `tampered index entries with unsafe file names are ignored`() = runBlocking {
        val outside = File(tmp.root, "secret.md").apply { writeText("do not touch") }
        repo().createNote("Real")
        val index = File(root, "index.json")
        index.writeText(
            index.readText().replace("\"fileName\": \"Real.md\"", "\"fileName\": \"../../secret.md\""),
        )
        val r = repo()
        assertEquals(listOf("Real.md"), r.listNotes().map { it.fileName })
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
        assertFalse(notesFile("Bye.md").exists())
        assertEquals("Bye\ntext", trashFile("Bye.md").readText())
        assertTrue(r.listNotes().isEmpty())
        assertEquals(listOf(info.id), r.listTrash().map { it.id })
        assertEquals("Bye\ntext", r.openNote(info.id).text) // still readable in Trash
    }

    @Test fun `trashed notes cannot be saved or renamed`() = runBlocking {
        val r = repo()
        val id = r.createNote("X").id
        r.moveToTrash(id)
        try { r.saveNote(id, "changed"); fail() } catch (_: NoteNotFoundException) {}
        try { r.renameNote(id, "Y"); fail() } catch (_: NoteNotFoundException) {}
        assertEquals("X", trashFile("X.md").readText())
    }

    @Test fun `restore brings the note back with its content`() = runBlocking {
        val r = repo()
        val id = r.createNote("Back\nagain").id
        r.moveToTrash(id)
        val restored = r.restoreFromTrash(id)
        assertFalse(restored.isTrashed)
        assertEquals("Back\nagain", notesFile("Back.md").readText())
        assertTrue(r.listTrash().isEmpty())
        assertEquals(listOf(id), r.listNotes().map { it.id })
    }

    @Test fun `restore never overwrites a note that took the name meanwhile`() = runBlocking {
        val r = repo()
        val old = r.createNote("Plan\nold").id
        r.moveToTrash(old)
        r.createNote("Plan\nnew")
        val restored = r.restoreFromTrash(old)
        assertEquals("Plan 2.md", restored.fileName)
        assertEquals("Plan\nnew", notesFile("Plan.md").readText())
        assertEquals("Plan\nold", notesFile("Plan 2.md").readText())
    }

    @Test fun `trashing two notes with the same name keeps both`() = runBlocking {
        val r = repo()
        val a = r.createNote("Same\na").id
        r.moveToTrash(a)
        val b = r.createNote("Same\nb").id
        r.moveToTrash(b)
        assertEquals(setOf("a", "b"), setOf(r.openNote(a).text.substringAfter('\n'), r.openNote(b).text.substringAfter('\n')))
        assertEquals(2, root.resolve("trash").listFiles()!!.size)
    }

    @Test fun `permanent delete removes the file and metadata`() = runBlocking {
        val r = repo()
        val id = r.createNote("Gone").id
        r.moveToTrash(id)
        r.deletePermanently(id)
        assertFalse(trashFile("Gone.md").exists())
        assertTrue(r.listTrash().isEmpty())
        assertTrue(repo().listTrash().isEmpty())
    }

    @Test fun `permanent delete refuses active notes`() = runBlocking {
        val r = repo()
        val id = r.createNote("Active").id
        try { r.deletePermanently(id); fail() } catch (_: NoteNotInTrashException) {}
        assertTrue(notesFile("Active.md").exists())
    }

    @Test fun `listNotes is sorted by title ignoring case`() = runBlocking {
        val r = repo()
        r.createNote("banana"); r.createNote("Apple"); r.createNote("cherry")
        assertEquals(listOf("Apple", "banana", "cherry"), r.listNotes().map { it.title })
    }

    // ---- Recovery --------------------------------------------------------------------------------------------

    @Test fun `files are adopted when the index is missing`() = runBlocking {
        val first = repo()
        first.createNote("Kept\ntext")
        val trashed = first.createNote("Trashed").id
        first.moveToTrash(trashed)
        File(root, "index.json").delete()

        val r = repo()
        assertEquals(listOf("Kept"), r.listNotes().map { it.title })
        assertEquals("Kept\ntext", r.openNote(r.listNotes().single().id).text)
        assertEquals(listOf("Trashed"), r.listTrash().map { it.title })
    }

    @Test fun `a corrupt index is kept aside and files are adopted`() = runBlocking {
        repo().createNote("Survivor")
        File(root, "index.json").writeText("{ this is not json")
        val r = repo()
        assertEquals(listOf("Survivor"), r.listNotes().map { it.title })
        assertEquals("{ this is not json", File(root, "index.json.corrupt").readText())
    }

    @Test fun `md files dropped into the directory by hand are adopted`() = runBlocking {
        repo().createNote("A")
        notesFile("Manual.md").writeText("hand made")
        assertEquals(listOf("A", "Manual"), repo().listNotes().map { it.title })
    }

    @Test fun `metadata for a vanished file is dropped`() = runBlocking {
        repo().createNote("Vanishing")
        notesFile("Vanishing.md").delete()
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `crash after moving a file to trash but before the index write is repaired`() = runBlocking {
        val id = repo().createNote("Half moved").id
        notesFile("Half moved.md").renameTo(trashFile("Half moved.md"))
        val r = repo()
        assertTrue(r.listNotes().isEmpty())
        assertEquals(listOf(id), r.listTrash().map { it.id })
    }

    @Test fun `interrupted write leaves the previous content intact and temp files are cleaned`() = runBlocking {
        val id = repo().createNote("Precious\noriginal").id
        // Simulate a crash in the middle of a save: partial temp file next to the real file.
        File(root, "notes/Precious.md.tmp").writeText("Precious\nhalf wri")
        val r = repo()
        assertEquals("Precious\noriginal", r.openNote(id).text)
        assertFalse(File(root, "notes/Precious.md.tmp").exists())
    }

    @Test fun `a file that is not valid UTF-8 is reported and never overwritten`() = runBlocking {
        val bytes = byteArrayOf(0x68, 0x69, 0xFF.toByte(), 0xFE.toByte(), 0x0A)
        notesFile("Latin.md").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val r = repo()
        val id = r.listNotes().single().id
        try { r.openNote(id); fail() } catch (_: NoteUnreadableException) {}
        assertTrue(bytes.contentEquals(notesFile("Latin.md").readBytes()))
    }
}
