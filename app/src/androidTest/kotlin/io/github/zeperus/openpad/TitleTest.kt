package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The title in the top bar: while it is automatic (the first line, or "Untitled") a tap renames the note; once the user chose a
 * name only a long press does, so that it is not changed by accident. The title is metadata: the Markdown file is never touched.
 */
@RunWith(AndroidJUnit4::class)
class TitleTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun title(): String =
        rule.onNodeWithTag("title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun dialogOpen() = rule.onAllNodes(hasTestTag("name-field")).fetchSemanticsNodes().isNotEmpty()

    private fun renameInDialog(name: String) {
        rule.waitFor("the rename dialog", { title() }) { dialogOpen() }
        rule.onNodeWithTag("name-field").performTextReplacement(name)
        rule.onNodeWithText("Rename").performClick()
        rule.waitFor("the dialog closes", { title() }) { !dialogOpen() }
    }

    private fun notes() = runBlocking { t.app.repository.listNotes() }

    // ---- E: tap on an automatic title ---------------------------------------------------------------------------

    @Test fun tappingTheUntitledTitleOfABlankNoteRenamesItAndTheNoteStays() {
        launch()
        rule.waitFor("the Untitled title", { title() }) { title() == "Untitled" }
        rule.onNodeWithTag("title").performClick()
        renameInDialog("Groceries")
        rule.waitFor("the new title", { title() }) { title() == "Groceries" }
        // naming a blank note is intent: the (empty) note exists and persists
        rule.waitFor("the note", { notes().toString() }) { notes().map { it.title } == listOf("Groceries") }
        assertTrue(notes().single().hasExplicitTitle)
        assertEquals("", t.mdFiles().single().readText())
        // typing the first line afterwards does not rename it
        rule.typeInLastRow("Shopping Monday")
        rule.waitFor("the text is saved", { t.mdFiles().single().readText() }) { t.mdFiles().single().readText().startsWith("Shopping Monday") }
        assertEquals("Groceries", title())
    }

    @Test fun tappingAnAutomaticTitleOffersRenameAndTheNewTitleSticks() {
        t.seed("Shopping list\n\n- milk\n")
        launch()
        rule.waitFor("the automatic title", { title() }) { title() == "Shopping list" }
        val file = t.mdFiles().single()
        val before = file.readBytes()
        rule.onNodeWithTag("title").performClick()
        renameInDialog("Groceries")
        rule.waitFor("the new title", { title() }) { title() == "Groceries" }
        assertEquals(listOf("Groceries"), notes().map { it.title })
        assertTrue("the Markdown file is not touched by a rename", before.contentEquals(file.readBytes()))
        // editing the first line afterwards: the chosen title stays
        rule.placeCaret(0, 13)
        rule.typeInLastRow("!")
        rule.waitFor("the text is saved", { file.readText() }) { file.readText().startsWith("Shopping list!") }
        assertEquals("Groceries", title())
    }

    // ---- F: an explicit title needs a long press ---------------------------------------------------------------

    @Test fun anExplicitTitleIsRenamedByALongPressNotByATap() {
        t.seed("Shopping list\n")
        val id = notes().single().id
        runBlocking { t.app.repository.renameNote(id, "Groceries") }
        launch()
        rule.waitFor("the explicit title", { title() }) { title() == "Groceries" }
        rule.onNodeWithTag("title").performClick()
        rule.waitForIdle()
        Thread.sleep(500)
        assertFalse("a tap must not open the rename dialog", dialogOpen())
        rule.onNodeWithTag("title").performTouchInput { longClick() }
        renameInDialog("Weekly shopping")
        rule.waitFor("the new title", { title() }) { title() == "Weekly shopping" }
        assertEquals(id, notes().single().id) // the same note
        assertEquals("Weekly shopping", notes().single().title)
    }
}
