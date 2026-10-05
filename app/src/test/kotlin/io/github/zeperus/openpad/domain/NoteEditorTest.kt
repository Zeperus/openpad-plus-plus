package io.github.zeperus.openpad.domain

import io.github.zeperus.openpad.data.FileNoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteEditorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "store")
    private fun repo() = FileNoteRepository(root)
    private fun noteFile(e: NoteEditor) = File(root, "notes/${e.info!!.id.value}.md")
    private fun mdFiles() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    @Test fun `blank draft never creates a file`() = runBlocking {
        val editor = NoteEditor(repo())
        assertTrue(editor.isDraft)
        assertFalse(editor.save())
        editor.onTextChanged("   \n\t ")
        assertFalse(editor.hasUnsavedChanges)
        assertFalse(editor.save())
        assertTrue(mdFiles().isEmpty())
        assertTrue(repo().listNotes().isEmpty())
    }

    @Test fun `opening and abandoning many blank notes leaves no clutter`() = runBlocking {
        val r = repo()
        repeat(10) { NoteEditor(r).save() }
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `first meaningful text materializes the note and later saves update it`() = runBlocking {
        val r = repo()
        val editor = NoteEditor(r)
        editor.onTextChanged("# Plan")
        assertTrue(editor.hasUnsavedChanges)
        assertTrue(editor.save())
        assertFalse(editor.isDraft)
        assertEquals("Plan", editor.info!!.title)

        editor.onTextChanged("# Plan\nstep 1")
        assertTrue(editor.save())
        assertFalse(editor.save()) // nothing changed: no write
        assertEquals(1, mdFiles().size)
        assertEquals("# Plan\nstep 1", noteFile(editor).readText())
    }

    @Test fun `text typed and erased before the first save creates nothing`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("oops")
        editor.onTextChanged("")
        assertFalse(editor.save())
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `emptying a saved note keeps the file`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Keep")
        editor.save()
        editor.onTextChanged("")
        assertTrue(editor.save())
        assertEquals("", noteFile(editor).readText())
        assertEquals(1, repo().listNotes().size)
    }

    @Test fun `clear empties the text but keeps the note`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Important\nwords")
        editor.save()
        editor.clear()
        assertEquals("", editor.text)
        assertNotNull(editor.info)
        assertEquals("", noteFile(editor).readText())
        assertEquals(1, repo().listNotes().size)
    }

    @Test fun `clear on a draft creates nothing`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("unsaved")
        editor.clear()
        assertEquals("", editor.text)
        assertTrue(editor.isDraft)
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `reopened note continues as saved note`() = runBlocking {
        val r = repo()
        val id = r.createNote("Existing\nbody").id
        val editor = NoteEditor(r, r.openNote(id))
        assertFalse(editor.isDraft)
        assertFalse(editor.hasUnsavedChanges)
        editor.onTextChanged("Existing\nbody\nmore")
        assertTrue(editor.save())
        assertEquals("Existing\nbody\nmore", r.openNote(id).text)
    }

    @Test fun `rename of a draft with content creates the note first`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("some words")
        assertTrue(editor.rename("Named"))
        assertEquals("Named", editor.info!!.title)
        assertEquals("some words", noteFile(editor).readText())
    }

    @Test fun `rename of a blank draft does nothing`() = runBlocking {
        val editor = NoteEditor(repo())
        assertFalse(editor.rename("Named"))
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `move to trash saves pending text first`() = runBlocking {
        val r = repo()
        val editor = NoteEditor(r)
        editor.onTextChanged("Doomed")
        editor.save()
        editor.onTextChanged("Doomed\nlast words")
        val trashed = editor.moveToTrash()
        assertNotNull(trashed)
        assertEquals("Doomed\nlast words", r.openNote(trashed!!.id).text)
        assertTrue(r.listNotes().isEmpty())
    }

    @Test fun `move to trash of a draft is a no-op`() = runBlocking {
        val editor = NoteEditor(repo())
        assertNull(editor.moveToTrash())
        assertTrue(mdFiles().isEmpty())
    }
}
