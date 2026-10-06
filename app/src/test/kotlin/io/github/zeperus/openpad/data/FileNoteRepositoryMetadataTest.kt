package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteLists
import io.github.zeperus.openpad.domain.NoteNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/** Favorite and last-opened metadata: persistence, interaction with rename/trash, and index migration. */
class FileNoteRepositoryMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var now = 1_000L
    private var idCounter = 0L

    private fun repo() = FileNoteRepository(
        root, clock = { now++ }, dispatcher = Dispatchers.IO, newId = { UUID(0, ++idCounter).toString() },
    )

    private fun id(n: Long) = UUID(0, n).toString()

    // ---- Favorite --------------------------------------------------------------------------------------------

    @Test fun `new notes are not favorites`() = runBlocking {
        assertFalse(repo().createNote("A").favorite)
    }

    @Test fun `favorite state persists across restarts and unfavorite works`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        assertTrue(r.setFavorite(a.id, true).favorite)
        assertTrue(repo().listNotes().single().favorite)

        assertFalse(repo().setFavorite(a.id, false).favorite)
        assertFalse(repo().listNotes().single().favorite)
    }

    @Test fun `favoriting never modifies the markdown file`() = runBlocking {
        val r = repo()
        val a = r.createNote("# A\nbody")
        val file = File(root, "notes/${a.id.value}.md")
        val before = file.readBytes()
        val modified = file.lastModified()
        r.setFavorite(a.id, true)
        r.markOpened(a.id)
        r.setFavorite(a.id, false)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(modified, file.lastModified())
    }

    // ---- Smart checklist -------------------------------------------------------------------------------------

    @Test fun `smart checklist is off by default, persists, and never touches the Markdown`() = runBlocking {
        val r = repo()
        val a = r.createNote("- [ ] A")
        assertFalse(a.smartChecklist)
        assertTrue(r.setSmartChecklist(a.id, true).smartChecklist)
        assertEquals("- [ ] A", File(root, "notes/${a.id.value}.md").readText())
        val again = repo()
        assertTrue(again.listNotes().single().smartChecklist)
        again.saveNote(a.id, "- [x] A")
        again.renameNote(a.id, "Shopping")
        assertTrue(repo().listNotes().single().smartChecklist) // survives edits and a rename
        assertFalse(repo().setSmartChecklist(a.id, false).smartChecklist)
        assertFalse(repo().listNotes().single().smartChecklist)
    }

    // ---- Folders ---------------------------------------------------------------------------------------------

    @Test fun `folders are created, renamed, listed sorted and persist`() = runBlocking {
        val r = repo()
        r.createFolder("Work")
        val home = r.createFolder("  home   stuff ")
        assertEquals(listOf("home stuff", "Work"), repo().listFolders().map { it.name })
        assertEquals("Private", r.renameFolder(home.id, "Private").name)
        assertEquals(listOf("Private", "Work"), repo().listFolders().map { it.name })
    }

    @Test fun `folder names are validated`() = runBlocking {
        val r = repo()
        r.createFolder("Work")
        for (bad in listOf("", "   ", "a\nb", "x".repeat(61))) {
            try { r.createFolder(bad); fail("accepted '$bad'") } catch (e: io.github.zeperus.openpad.domain.InvalidFolderNameException) { /* expected */ }
        }
        try { r.createFolder("WORK"); fail("duplicate accepted") } catch (e: io.github.zeperus.openpad.domain.FolderNameConflictException) { /* expected */ }
        val other = r.createFolder("Other")
        try { r.renameFolder(other.id, "work"); fail("rename to duplicate accepted") } catch (e: io.github.zeperus.openpad.domain.FolderNameConflictException) { /* expected */ }
        assertEquals("Other", r.renameFolder(other.id, "Other").name) // renaming to itself is fine
    }

    @Test fun `a note can be moved into a folder and back, nothing else changes`() = runBlocking {
        val r = repo()
        val folder = r.createFolder("Work")
        val a = r.createNote("Alpha text")
        val before = File(root, "notes/${a.id.value}.md").readBytes()
        val moved = r.moveNote(a.id, folder.id)
        assertEquals(folder.id, moved.folderId)
        assertEquals(a.title, moved.title)
        assertEquals(a.id, moved.id)
        assertTrue(before.contentEquals(File(root, "notes/${a.id.value}.md").readBytes())) // the file does not move or change
        assertEquals(folder.id, repo().listNotes().single().folderId) // persisted
        assertNull(repo().moveNote(a.id, null).folderId)
        try { repo().moveNote(a.id, "no-such-folder"); fail("moved into a missing folder") } catch (e: io.github.zeperus.openpad.domain.FolderNotFoundException) { /* expected */ }
    }

    @Test fun `only an empty folder can be deleted and its notes are never lost`() = runBlocking {
        val r = repo()
        val folder = r.createFolder("Work")
        val a = r.createNote("Alpha")
        r.moveNote(a.id, folder.id)
        try { r.deleteFolder(folder.id); fail("deleted a folder with notes") } catch (e: io.github.zeperus.openpad.domain.FolderNotEmptyException) { /* expected */ }
        assertEquals(1, repo().listNotes().size)
        r.moveNote(a.id, null)
        r.deleteFolder(folder.id)
        assertTrue(repo().listFolders().isEmpty())
        assertEquals(1, repo().listNotes().size)
    }

    @Test fun `favorites and recent do not care about folders and trash keeps the folder until it is deleted`() = runBlocking {
        val r = repo()
        val folder = r.createFolder("Work")
        val a = r.createNote("Alpha")
        r.setFavorite(a.id, true)
        r.moveNote(a.id, folder.id)
        assertTrue(r.listNotes().single().favorite)
        r.moveToTrash(a.id)
        r.deleteFolder(folder.id) // only a trashed note was in it: allowed, the note comes back unfiled
        val restored = r.restoreFromTrash(a.id)
        assertNull(restored.folderId)
        assertTrue(restored.favorite)
    }

    @Test fun `an index from an older version loads without folders`() = runBlocking {
        val r = repo()
        r.createNote("Old note")
        File(root, "index.json").writeText(File(root, "index.json").readText().replace(Regex("\"folders\"[^]]*]"), "").replace(",\n  \n", "\n"))
        val loaded = repo()
        assertEquals(1, loaded.listNotes().size)
        assertTrue(loaded.listFolders().isEmpty())
    }

    @Test fun `a note filed in a folder that vanished from the index is unfiled on load`() = runBlocking {
        val r = repo()
        val folder = r.createFolder("Work")
        val a = r.createNote("Alpha")
        r.moveNote(a.id, folder.id)
        val text = File(root, "index.json").readText().replace(Regex("\"folders\": \\[.*?\\]", RegexOption.DOT_MATCHES_ALL), "\"folders\": []")
        File(root, "index.json").writeText(text)
        assertNull(repo().listNotes().single().folderId)
    }

    @Test fun `favorite survives a rename and later edits`() = runBlocking {
        val r = repo()
        val a = r.createNote("Old")
        r.setFavorite(a.id, true)
        assertTrue(r.renameNote(a.id, "New").favorite)
        assertTrue(r.saveNote(a.id, "New\nmore text").favorite)
        assertTrue(repo().listNotes().single().favorite)
    }

    @Test fun `favorite survives trash and restore`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        r.setFavorite(a.id, true)
        assertTrue(r.moveToTrash(a.id).favorite)
        assertTrue(repo().listTrash().single().favorite)
        assertTrue(r.restoreFromTrash(a.id).favorite)
        assertTrue(repo().listNotes().single().favorite)
    }

    @Test fun `favorite on a trashed or unknown note is refused`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        r.moveToTrash(a.id)
        try { r.setFavorite(a.id, true); fail() } catch (_: NoteNotFoundException) {}
        try { r.setFavorite(NoteId("nope"), true); fail() } catch (_: NoteNotFoundException) {}
    }

    @Test fun `setting the same favorite state again does not rewrite the index`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        val index = File(root, "index.json")
        val before = index.readText()
        index.setLastModified(1_000)
        r.setFavorite(a.id, false)
        assertEquals(before, index.readText())
        assertEquals(1_000L, index.lastModified())
    }

    // ---- lastOpenedAt ----------------------------------------------------------------------------------------

    @Test fun `creating a note counts as using it`() = runBlocking {
        val a = repo().createNote("A")
        assertEquals(a.createdAt, a.lastOpenedAt)
    }

    @Test fun `markOpened updates the timestamp and persists`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        val opened = r.markOpened(a.id)
        assertTrue(opened.lastOpenedAt!! > a.lastOpenedAt!!)
        assertEquals(opened.lastOpenedAt, repo().listNotes().single().lastOpenedAt)
    }

    @Test fun `saving does not count as opening`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        assertEquals(a.lastOpenedAt, r.saveNote(a.id, "A\nedited").lastOpenedAt)
    }

    @Test fun `recency survives rename trash and restore`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        val opened = r.markOpened(a.id).lastOpenedAt
        assertEquals(opened, r.renameNote(a.id, "B").lastOpenedAt)
        assertEquals(opened, r.moveToTrash(a.id).lastOpenedAt)
        assertEquals(opened, r.restoreFromTrash(a.id).lastOpenedAt)
        assertEquals(opened, repo().listNotes().single().lastOpenedAt)
    }

    @Test fun `recent after restart reflects the order notes were opened in`() = runBlocking {
        val first = repo()
        val a = first.createNote("A")
        val b = first.createNote("B")
        val c = first.createNote("C")
        val d = first.createNote("D")
        first.markOpened(a.id) // A is now the most recent
        first.setFavorite(c.id, true)

        val restarted = repo().listNotes()
        assertEquals(listOf("A", "D", "B"), NoteLists.recent(restarted).map { it.title })
        assertEquals(listOf("C"), NoteLists.favorites(restarted).map { it.title })
        assertEquals(setOf(a.id, b.id, c.id, d.id), restarted.map { it.id }.toSet())
    }

    @Test fun `trashed notes drop out of recent and favorites but come back with their metadata`() = runBlocking {
        val r = repo()
        val a = r.createNote("A")
        r.setFavorite(a.id, true)
        val b = r.createNote("B")
        r.moveToTrash(a.id); r.moveToTrash(b.id)
        assertTrue(NoteLists.recent(r.listNotes()).isEmpty())
        assertTrue(NoteLists.favorites(r.listNotes()).isEmpty())
        r.restoreFromTrash(a.id); r.restoreFromTrash(b.id)
        assertEquals(listOf("B"), NoteLists.recent(r.listNotes()).map { it.title })
        assertEquals(listOf("A"), NoteLists.favorites(r.listNotes()).map { it.title })
    }

    // ---- Index compatibility ---------------------------------------------------------------------------------

    @Test fun `a version 2 index without the new fields loads and seeds lastOpenedAt from updatedAt`() = runBlocking {
        File(root, "notes").mkdirs()
        File(root, "notes/${id(10)}.md").writeText("Alpha")
        File(root, "notes/${id(11)}.md").writeText("Beta")
        File(root, "index.json").writeText(
            """{ "version": 2, "notes": [
              {"id":"${id(10)}","title":"Alpha","createdAt":1,"updatedAt":50,"trashedAt":null,"autoTitle":true},
              {"id":"${id(11)}","title":"Beta","createdAt":2,"updatedAt":60,"autoTitle":false}
            ] }""",
        )
        val notes = repo().listNotes()
        assertEquals(listOf(false, false), notes.map { it.favorite })
        assertEquals(listOf(50L, 60L), notes.map { it.lastOpenedAt })
        assertEquals(listOf("Beta", "Alpha"), NoteLists.recent(notes).map { it.title })
        // rewritten with the current version, so the seeding happens exactly once
        assertTrue(File(root, "index.json").readText().contains("\"version\": 5"))
    }

    @Test fun `seeding happens once - a later restart keeps explicitly stored timestamps`() = runBlocking {
        File(root, "notes").mkdirs()
        File(root, "notes/${id(10)}.md").writeText("Alpha")
        File(root, "index.json").writeText(
            """{ "version": 2, "notes": [{"id":"${id(10)}","title":"Alpha","createdAt":1,"updatedAt":50}] }""",
        )
        val r = repo()
        r.listNotes()
        val opened = r.markOpened(NoteId(id(10))).lastOpenedAt
        assertEquals(opened, repo().listNotes().single().lastOpenedAt)
    }

    @Test fun `an old index loses no notes and no text when upgraded`() = runBlocking {
        File(root, "notes").mkdirs(); File(root, "trash").mkdirs()
        File(root, "notes/${id(20)}.md").writeText("keep me")
        File(root, "trash/${id(21)}.md").writeText("in trash")
        File(root, "index.json").writeText(
            """{ "notes": [
              {"id":"${id(20)}","title":"One","createdAt":1,"updatedAt":2},
              {"id":"${id(21)}","title":"Two","createdAt":1,"updatedAt":3,"trashedAt":9}
            ] }""",
        )
        val r = repo()
        assertEquals("keep me", r.openNote(NoteId(id(20))).text)
        assertEquals("in trash", r.openNote(NoteId(id(21))).text)
        assertEquals(listOf("One"), r.listNotes().map { it.title })
        assertEquals(listOf("Two"), r.listTrash().map { it.title })
    }

    @Test fun `adopted files were never opened and so are not recent`() = runBlocking {
        File(root, "notes").mkdirs()
        File(root, "notes/Manual.md").writeText("by hand")
        val note = repo().listNotes().single()
        assertNull(note.lastOpenedAt)
        assertTrue(NoteLists.recent(listOf(note)).isEmpty())
    }

    @Test fun `unknown future fields in the index are ignored`() = runBlocking {
        File(root, "notes").mkdirs()
        File(root, "notes/${id(30)}.md").writeText("x")
        File(root, "index.json").writeText(
            """{ "version": 3, "extra": 1, "notes": [
              {"id":"${id(30)}","title":"X","createdAt":1,"updatedAt":2,"favorite":true,"lastOpenedAt":5,"color":"red"}
            ] }""",
        )
        val note = repo().listNotes().single()
        assertTrue(note.favorite)
        assertEquals(5L, note.lastOpenedAt)
    }

    @Test fun `a version 3 index keeps notes that were never opened out of recent when it is upgraded`() = runBlocking {
        File(root, "notes").mkdirs()
        File(root, "notes/${id(40)}.md").writeText("never opened")
        File(root, "index.json").writeText(
            """{ "version": 3, "notes": [{"id":"${id(40)}","title":"Never","createdAt":1,"updatedAt":50}] }""",
        )
        val note = repo().listNotes().single()
        assertNull(note.lastOpenedAt) // not invented by the upgrade
        assertTrue(File(root, "index.json").readText().contains("\"version\": 5"))
    }
}
