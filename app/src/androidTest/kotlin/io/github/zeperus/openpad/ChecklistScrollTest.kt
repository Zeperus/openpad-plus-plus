package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticking an item of a long Smart Checklist moves it to the bottom of the note - the view must stay where the user is looking.
 * (The caret sits at the end after a paste; a text change used to scroll the editor to it.)
 */
@RunWith(AndroidJUnit4::class)
class ChecklistScrollTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun scrollValue(): Float =
        rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrollMax(): Float =
        rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @Test fun tickingTheTopItemOfALongSmartChecklistDoesNotScrollToTheBottom() {
        runBlocking { t.app.settings.setEditorFontSize(24) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(listOf(""))
        val items = (1..40).map { "Item %02d".format(it) }
        t.setClipboard(items.joinToString("\n"))
        rule.overflowEdit("Paste as Checklist")
        rule.waitFor("40 checkboxes composed or the list built", { "${rule.rowTexts().size}" }) { rule.rowTexts() == items }
        // the caret is at the end (after the paste); the user scrolls up to the first items
        rule.onNodeWithTag("editor").performScrollToIndex(0)
        rule.waitForIdle()
        val before = scrollValue()
        assertEquals("at the top", 0f, before, 1f)
        rule.onAllNodesWithTag("checkbox")[0].performClick()
        rule.waitFor("Item 01 moved to the end", { rule.rowTexts().toString() }) { rule.rowTexts().last() == "Item 01" }
        Thread.sleep(1_000)
        rule.waitForIdle()
        assertTrue("the editor is longer than the screen (${scrollMax()})", scrollMax() > 200f)
        assertEquals("the view stays at the top", before, scrollValue(), 2f)
    }
}
