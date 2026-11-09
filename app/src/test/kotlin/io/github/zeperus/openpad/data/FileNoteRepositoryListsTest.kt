package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.FolderInfo
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteListsSnapshot
import io.github.zeperus.openpad.domain.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [FileNoteRepository.lists] reads notes, Trash and folders in one step and gives exactly the lists of the three single calls. */
class FileNoteRepositoryListsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private var now = 1_000L

    private fun repo() = FileNoteRepository(root, clock = { now++ }, dispatcher = Dispatchers.IO)

    private suspend fun NoteRepository.separately() = NoteListsSnapshot(listNotes(), listTrash(), listFolders())

    @Test fun `an empty store has empty lists`() = runBlocking {
        assertEquals(NoteListsSnapshot(emptyList(), emptyList(), emptyList()), repo().lists())
    }

    @Test fun `the lists are those of listNotes, listTrash and listFolders, in their orders`() = runBlocking {
        val repo = repo()
        val banana = repo.createNote("banana")
        repo.createNote("Apple")
        repo.createNote("cherry")
        val first = repo.createNote("first to trash")
        val second = repo.createNote("second to trash")
        repo.moveToTrash(first.id)
        repo.moveToTrash(second.id)
        val zoo = repo.createFolder("zoo")
        repo.createFolder("Archive")
        repo.moveNote(banana.id, zoo.id)

        val lists = repo.lists()

        assertEquals(repo.separately(), lists)
        assertEquals(listOf("Apple", "banana", "cherry"), lists.notes.map(NoteInfo::title)) // by title, ignoring case
        assertEquals(listOf("second to trash", "first to trash"), lists.trash.map(NoteInfo::title)) // most recently trashed first
        assertEquals(listOf("Archive", "zoo"), lists.folders.map(FolderInfo::name))
        assertEquals(zoo.id, lists.notes.single { it.title == "banana" }.folderId)
    }

    @Test fun `a fresh repository over the same files reads the same lists`() = runBlocking {
        val repo = repo()
        repo.createNote("one")
        repo.moveToTrash(repo.createNote("two").id)
        repo.createFolder("box")

        val reopened = repo() // nothing loaded yet: lists() loads the index first, like the single calls
        assertEquals(repo.separately(), reopened.lists())
    }
}
