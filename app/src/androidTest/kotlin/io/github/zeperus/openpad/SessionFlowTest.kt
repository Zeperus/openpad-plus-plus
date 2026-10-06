package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Open documents, sessions and startup behaviour on a real Android runtime.
 *
 * The Android Test Orchestrator runs every test in a fresh process with cleared app data. "Yesterday's session" is
 * therefore seeded through the app's own repository, session store and settings *before* the activity starts, which
 * is exactly what a relaunch finds on disk. A process cannot kill itself and keep testing, so persistence is covered
 * from both sides: [openingNotesFromTheDrawerPersistsTheSessionInOrder] checks what the UI writes, the restore tests
 * check what the UI does with such a file.
 */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val root = File(app.filesDir, "openpad")

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Notes left over from an earlier session, plus the stored session and startup setting. */
    private fun seed(titles: List<String>, active: Int?, mode: StartupMode? = null): List<NoteId> = runBlocking {
        val ids = titles.map { app.repository.createNote(it).id }
        app.sessionStore.save(PersistedSession(ids.map { it.value }, active?.let { ids[it].value }))
        mode?.let { app.settings.setStartupMode(it) }
        ids
    }

    // ---- Reading the tab strip -------------------------------------------------------------------------------

    private fun tabNodes() = rule.onAllNodes(hasTestTag("tab")).fetchSemanticsNodes()

    private fun title(config: androidx.compose.ui.semantics.SemanticsConfiguration) =
        config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun tabTitles() = tabNodes().map { title(it.config) }

    private fun selectedTab() = tabNodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
        ?.let { title(it.config) }

    private fun tab(title: String) = hasTestTag("tab") and hasText(title)

    private fun waitForTabs(vararg expected: String) =
        rule.waitUntil(timeoutMillis = 8_000) { tabTitles() == expected.toList() }

    private fun mdCount(dir: String) = File(root, dir).listFiles { f -> f.name.endsWith(".md") }.orEmpty().size

    private fun openDrawer() {
        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
    }

    private fun drawerEntry(title: String) =
        hasText(title) and hasClickAction() and !hasSetTextAction() and !hasTestTag("tab")

    private fun overflow(item: String) {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(item).performScrollTo().performClick()
    }

    // ---- Startup modes ---------------------------------------------------------------------------------------

    @Test fun defaultStartupRestoresSavedNotesAndStartsOnABlankNote() {
        seed(listOf("Shopping", "Ideas"), active = 0) // no startup setting stored: the default applies
        launch()
        waitForTabs("Shopping", "Ideas", "New note")
        assertEquals("New note", selectedTab())
        assertEquals(2, mdCount("notes")) // the untouched blank note is not a file
    }

    @Test fun resumeSessionActivatesThePreviouslyActiveNote() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 1, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        assertEquals("Ideas", selectedTab())
        rule.waitUntil(timeoutMillis = 5_000) { rule.rowTexts() == listOf("Ideas") }
    }

    @Test fun blankNoteModeShowsOnlyABlankNoteButKeepsTheNotes() {
        seed(listOf("Shopping", "Ideas"), active = 0, mode = StartupMode.BlankNote)
        launch()
        waitForTabs("New note")
        assertEquals(2, mdCount("notes"))
        openDrawer()
        rule.onAllNodes(drawerEntry("Shopping")).onFirst().assertIsDisplayed()
    }

    @Test fun noPreviousSessionStartsWithExactlyOneBlankNote() {
        launch()
        waitForTabs("New note")
        assertEquals("New note", selectedTab())
        assertEquals(0, mdCount("notes"))
    }

    // ---- Open, switch, close ---------------------------------------------------------------------------------

    @Test fun openingAnAlreadyOpenNoteActivatesItInsteadOfAddingATab() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 2, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        openDrawer()
        rule.onAllNodes(drawerEntry("Shopping")).onFirst().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { selectedTab() == "Shopping" }
        assertEquals(listOf("Shopping", "Ideas", "Work"), tabTitles())
    }

    @Test fun openingAnotherNoteAppendsATab() {
        seed(listOf("Shopping", "Ideas"), active = 0, mode = StartupMode.BlankNote)
        launch()
        waitForTabs("New note")
        openDrawer()
        rule.onAllNodes(drawerEntry("Ideas")).onFirst().performClick()
        waitForTabs("Ideas", "New note")
        assertEquals("Ideas", selectedTab())
    }

    @Test fun tappingATabSwitchesTheNoteWithoutReordering() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 0, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        rule.onNode(tab("Work")).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { selectedTab() == "Work" }
        rule.waitUntil(timeoutMillis = 5_000) { rule.rowTexts() == listOf("Work") }
        assertEquals(listOf("Shopping", "Ideas", "Work"), tabTitles())
    }

    @Test fun closingATabViaLongPressKeepsTheNoteOnDiskAndInFiles() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 1, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")

        rule.onNode(tab("Ideas")).performTouchInput { longClick() }
        rule.onNodeWithText("Close").performClick()

        waitForTabs("Shopping", "Work")
        assertEquals("Work", selectedTab()) // the next tab became active
        assertEquals(3, mdCount("notes")) // closing is not deleting
        assertEquals(0, mdCount("trash"))
        openDrawer()
        rule.onAllNodes(drawerEntry("Ideas")).onFirst().assertIsDisplayed() // still in FILES
        rule.onNodeWithTag("drawer").performScrollToNode(hasText("Trash is empty"))
        rule.onNodeWithText("Trash is empty").assertIsDisplayed()
    }

    @Test fun closeOthersKeepsOnlyTheChosenTab() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 0, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        rule.onNode(tab("Work")).performTouchInput { longClick() }
        rule.onNodeWithText("Close others").performClick()
        waitForTabs("Work")
        assertEquals(3, mdCount("notes"))
    }

    @Test fun closeAllLeavesOneBlankNoteAndDeletesNothing() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 0, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        rule.onNode(tab("Ideas")).performTouchInput { longClick() }
        rule.onNodeWithText("Close all").performClick()
        waitForTabs("New note")
        assertEquals(3, mdCount("notes"))
    }

    @Test fun closeFromTheOverflowMenuClosesTheActiveTab() {
        seed(listOf("Shopping", "Ideas"), active = 0, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas")
        overflow("Close")
        waitForTabs("Ideas")
        assertEquals(2, mdCount("notes"))
    }

    // ---- Draft -> note, delete, rename -----------------------------------------------------------------------

    @Test fun typingInTheBlankNoteCreatesTheNoteAndItsTab() {
        seed(listOf("Shopping"), active = 0)
        launch()
        waitForTabs("Shopping", "New note")
        rule.typeInLastRow("Fresh idea")
        waitForTabs("Shopping", "Fresh idea")
        assertEquals("Fresh idea", selectedTab())
        assertEquals(2, mdCount("notes"))
    }

    @Test fun deletingTheActiveNoteClosesItsTabAndMovesItToTrash() {
        seed(listOf("Shopping", "Ideas", "Work"), active = 1, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas", "Work")
        overflow("Delete note…")
        rule.onNodeWithText("Move to Trash").performClick()
        waitForTabs("Shopping", "Work")
        assertEquals(2, mdCount("notes"))
        assertEquals(1, mdCount("trash"))
    }

    @Test fun renamingAnOpenNoteUpdatesItsTab() {
        seed(listOf("Shopping", "Ideas"), active = 1, mode = StartupMode.ResumeSession)
        launch()
        waitForTabs("Shopping", "Ideas")
        overflow("Rename…")
        rule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Better ideas")
        rule.onNodeWithText("Rename").performClick()
        waitForTabs("Shopping", "Better ideas")
        assertEquals("Better ideas", selectedTab())
    }

    // ---- Persistence written by the UI, settings screen ------------------------------------------------------

    @Test fun openingNotesFromTheDrawerPersistsTheSessionInOrder() {
        val ids = seed(listOf("Shopping", "Ideas", "Work"), active = null, mode = StartupMode.BlankNote)
        runBlocking { app.sessionStore.save(PersistedSession()) } // start the run with an empty stored session
        launch()
        waitForTabs("New note")

        openDrawer()
        rule.onAllNodes(drawerEntry("Work")).onFirst().performClick()
        waitForTabs("Work", "New note")
        openDrawer()
        rule.onAllNodes(drawerEntry("Shopping")).onFirst().performClick()
        waitForTabs("Work", "Shopping", "New note")

        rule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.sessionStore.load() }.noteIds == listOf(ids[2].value, ids[0].value)
        }
        val stored = runBlocking { app.sessionStore.load() }
        assertEquals(ids[0].value, stored.activeNoteId)
        assertTrue(stored.noteIds.size == 2) // the blank note is never persisted
    }

    @Test fun settingsOffersTheThreeStartupOptionsWithTheDefaultSelectedAndStoresAChoice() {
        launch()
        waitForTabs("New note")
        overflow("Settings")

        rule.onNodeWithText("Startup").assertIsDisplayed()
        rule.onNodeWithText("Resume session").assertIsDisplayed()
        rule.onNodeWithText("Blank note").assertIsDisplayed()
        rule.onNode(hasText("Resume + blank note") and isSelectable()).assertIsSelected()

        rule.onNode(hasText("Blank note") and isSelectable()).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { app.settings.startupMode() } == StartupMode.BlankNote }
        rule.onNode(hasText("Blank note") and isSelectable()).assertIsSelected()
    }
}
