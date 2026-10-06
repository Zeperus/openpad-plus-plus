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
    private inline fun <reified T : Throwable> refused(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is T) return else throw e }
        throw AssertionError("expected ${T::class.simpleName}")
    }

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

    @Test fun `naming a blank draft makes it a note - an explicit title is intent, the empty body does not make it vanish`() = runBlocking {
        val r = repo()
        val editor = NoteEditor(r)
        assertTrue(editor.rename("Groceries"))
        assertFalse(editor.isDraft)
        assertEquals("Groceries", editor.info!!.title)
        assertTrue(editor.info!!.hasExplicitTitle)
        assertEquals("", noteFile(editor).readText())
        // it is still there after a "restart", and saving the empty text does not remove it
        assertFalse(editor.save())
        assertEquals(listOf("Groceries"), repo().listNotes().map { it.title })
        assertEquals(1, mdFiles().size)
    }

    @Test fun `an invalid or taken name for a blank draft creates nothing`() = runBlocking {
        val r = repo()
        r.createNote("taken")
        val editor = NoteEditor(r)
        refused<InvalidNoteNameException> { editor.rename("???") }
        refused<NoteNameConflictException> { editor.rename("TAKEN") }
        assertTrue(editor.isDraft)
        assertEquals(1, mdFiles().size)
    }

    @Test fun `an automatic title follows the first line`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Shopping list\nmilk")
        editor.save()
        assertEquals("Shopping list", editor.info!!.title)
        assertFalse(editor.info!!.hasExplicitTitle)
        editor.onTextChanged("Monday list\nmilk")
        editor.save()
        assertEquals("Monday list", editor.info!!.title)
    }

    @Test fun `an explicit title overrides the automatic one and is not changed by editing the first line`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Shopping list\nmilk")
        editor.save()
        assertTrue(editor.rename("Groceries"))
        assertTrue(editor.info!!.hasExplicitTitle)
        editor.onTextChanged("Shopping Monday\nmilk")
        editor.save()
        assertEquals("Groceries", editor.info!!.title)
        assertEquals("Groceries", repo().listNotes().single().title)
    }

    @Test fun `renaming keeps the note id and does not touch the Markdown file`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("# Plan\n\n- [ ] a\n")
        editor.save()
        val id = editor.info!!.id
        val bytes = noteFile(editor).readBytes()
        assertTrue(editor.rename("Better name"))
        assertEquals(id, editor.info!!.id)
        assertTrue(bytes.contentEquals(noteFile(editor).readBytes()))
        assertEquals(1, mdFiles().size)
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

    @Test fun `favorite on a draft with content creates the note first`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Starred")
        assertTrue(editor.setFavorite(true))
        assertEquals(true, editor.info!!.favorite)
        assertEquals("Starred", noteFile(editor).readText())
    }

    @Test fun `favorite on a blank draft does nothing`() = runBlocking {
        val editor = NoteEditor(repo())
        assertFalse(editor.setFavorite(true))
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun `saving after favoriting keeps the favorite`() = runBlocking {
        val editor = NoteEditor(repo())
        editor.onTextChanged("Starred")
        editor.setFavorite(true)
        editor.onTextChanged("Starred\nmore")
        editor.save()
        assertEquals(true, editor.info!!.favorite)
    }
}
