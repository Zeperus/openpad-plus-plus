package io.github.zeperus.openpad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** End-to-end checks of the Milestone 2 flows on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class NotesFlowTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val notesDir = File(app.filesDir, "openpad/notes")

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeActivity() {
        scenario?.close()
    }

    /** A drawer entry: clickable, unlike the same words in the title bar or the editor - and not an open tab. */
    private fun drawerEntry(title: String) =
        hasText(title) and hasClickAction() and !hasSetTextAction() and !hasTestTag("tab")

    @Before fun emptyStore() = runBlocking {
        val repo = app.repository
        repo.listNotes().forEach { repo.moveToTrash(it.id) }
        repo.listTrash().forEach { repo.deletePermanently(it.id) }
    }

    private fun openDrawer() {
        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
    }

    // Files on disk are named after the note's id, so look the id up by title.
    private fun noteFile(title: String) = runBlocking {
        File(notesDir, app.repository.listNotes().first { it.title == title }.id.value + ".md")
    }

    private fun trashFile(title: String) = runBlocking {
        File(app.filesDir, "openpad/trash/" + app.repository.listTrash().first { it.title == title }.id.value + ".md")
    }

    private fun mdFiles() = notesDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().toList()

    @Test fun typingCreatesARealMarkdownFileAndListsItUnderFiles() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("# Shopping\nmilk")
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isNotEmpty() }

        assertEquals("# Shopping\nmilk", noteFile("Shopping").readText())
        openDrawer()
        rule.onNodeWithText("FILES").assertIsDisplayed()
        // a new note is "used", so it is listed under RECENT and under FILES
        rule.onAllNodes(drawerEntry("Shopping")).onFirst().assertIsDisplayed()
    }

    @Test fun anExistingNoteShowsUpInFilesAndOpensWithItsText() {
        // Created before the UI exists, as it would be from an earlier session.
        runBlocking { app.repository.createNote("Earlier note\nbody text") }
        launch() // cold start: the list must be loaded from disk and shown

        openDrawer()
        rule.onAllNodes(drawerEntry("Earlier note")).onFirst().assertIsDisplayed().performClick()
        rule.onNode(hasSetTextAction()).assertTextEquals("Earlier note\nbody text")
    }

    @Test fun blankNewNotesLeaveNothingBehind() {
        launch()
        repeat(3) {
            openDrawer()
            rule.onNodeWithText("+ New Note").performClick()
            rule.waitForIdle()
        }
        Thread.sleep(1_500) // longer than the autosave delay
        assertTrue(mdFiles().isEmpty())
    }

    @Test fun deleteMovesToTrashAndRestoreBringsItBack() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("Doomed")
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isNotEmpty() }

        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete note…").performClick()
        rule.onNodeWithText("Move to Trash").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isEmpty() }
        assertTrue(trashFile("Doomed").exists())

        openDrawer()
        rule.onNodeWithContentDescription("Restore: Doomed").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isNotEmpty() }
        assertEquals("Doomed", noteFile("Doomed").readText())
    }

    @Test fun clearKeepsTheNoteButEmptiesIt() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("Keep me")
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isNotEmpty() }

        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Clear note…").performClick()
        rule.onNodeWithText("Clear", substring = false).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { noteFile("Keep me").readText().isEmpty() }
        assertTrue(noteFile("Keep me").exists())
    }

    // ---- Favorites and Recent --------------------------------------------------------------------------------

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun drawerEntryCount(title: String) =
        rule.onAllNodes(drawerEntry(title)).fetchSemanticsNodes().size

    /** Selecting the already-open note just closes the drawer. */
    private fun closeDrawerVia(title: String) {
        rule.onAllNodes(drawerEntry(title)).onFirst().performClick()
        rule.waitForIdle()
    }

    @Test fun drawerSectionsAppearInOrderAndEmptyOnesAreHidden() {
        runBlocking {
            val repo = app.repository
            val fav = repo.createNote("Pinned")
            repo.createNote("Alpha")
            repo.createNote("Beta")
            repo.setFavorite(fav.id, true)
        }
        launch()
        openDrawer()
        rule.onNodeWithText("FAVORITES").assertIsDisplayed()
        assertTrue(top("FAVORITES") < top("RECENT"))
        assertTrue(top("RECENT") < top("FILES"))
        assertTrue(top("FILES") < top("TRASH"))
        // FILES is the complete list: the favorite is there too, but not twice in Recent
        assertEquals(2, drawerEntryCount("Pinned")) // Favorites + Files
        assertEquals(2, drawerEntryCount("Alpha")) // Recent + Files
    }

    @Test fun favoritesAndRecentAreHiddenWhenThereIsNothingToShow() {
        // Only a trashed favorite exists: neither Favorites nor Recent has anything to list.
        runBlocking {
            val note = app.repository.createNote("Binned")
            app.repository.setFavorite(note.id, true)
            app.repository.moveToTrash(note.id)
        }
        launch()
        openDrawer()
        rule.onNodeWithText("FAVORITES").assertDoesNotExist()
        rule.onNodeWithText("RECENT").assertDoesNotExist()
        rule.onNodeWithText("FILES").assertIsDisplayed()
        rule.onNodeWithText("TRASH").assertIsDisplayed()
    }

    @Test fun favoriteToggleInTheMenuShowsAndHidesTheFavoritesSection() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("Pinned")
        rule.waitUntil(timeoutMillis = 5_000) { mdFiles().isNotEmpty() }
        val before = noteFile("Pinned").readBytes()

        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Add to Favorites").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { app.repository.listNotes().single().favorite } }
        openDrawer()
        rule.onNodeWithText("FAVORITES").assertIsDisplayed()
        assertEquals(2, drawerEntryCount("Pinned")) // Favorites + Files; not in Recent
        assertTrue(before.contentEquals(noteFile("Pinned").readBytes())) // Markdown untouched
        closeDrawerVia("Pinned")

        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Remove from Favorites").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { !app.repository.listNotes().single().favorite } }
        openDrawer()
        rule.onNodeWithText("FAVORITES").assertDoesNotExist()
        rule.onNodeWithText("RECENT").assertIsDisplayed() // unfavorited note is a recent note again
        assertEquals(2, drawerEntryCount("Pinned")) // Recent + Files
    }
}
