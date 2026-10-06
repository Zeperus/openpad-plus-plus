package io.github.zeperus.openpad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selection across paragraphs, lists and checklists on a real runtime: select, copy, copy as Markdown, cut, undo. */
@RunWith(AndroidJUnit4::class)
class SelectionTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun selectAllFromTheMenu() {
        rule.overflowEdit("Select all")
        rule.waitFor("the selection bar", { "" }) { rule.tagCount("selection-bar") == 1 }
    }

    // ---- A. selecting across paragraphs ---------------------------------------------------------------------

    @Test fun aLongPressDragFromOneParagraphIntoAnotherSelectsAcrossThemAndCopies() {
        t.seed("Alpha one\n\nBeta two\n\nGamma three\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Alpha one", "Beta two", "Gamma three"))
        val editor = rule.onNodeWithTag("editor")
        val editorTop = editor.fetchSemanticsNode().boundsInRoot.topLeft
        val first = rule.row(0).fetchSemanticsNode().boundsInRoot
        val last = rule.row(2).fetchSemanticsNode().boundsInRoot
        // press at the far right of the first row (the end of its text), hold, drag down into the third row
        editor.performTouchInput {
            val start = Offset(first.right - 4f, first.center.y) - editorTop
            val end = Offset(last.right - 4f, last.center.y) - editorTop
            down(start)
            advanceEventTime(900)
            moveTo(Offset(start.x, (start.y + end.y) / 2))
            advanceEventTime(100)
            moveTo(end)
            advanceEventTime(100)
            up()
        }
        rule.waitFor("a selection across rows", { "bars=${rule.tagCount("selection-bar")}" }) { rule.tagCount("selection-bar") == 1 }
        assertEquals(2, rule.tagCount("selection-handle-start") + rule.tagCount("selection-handle-end"))
        rule.barButton("Copy").performClick()
        rule.waitFor("the clipboard", { t.clipboardText().toString() }) { t.clipboardText() == "Beta two\n\nGamma three" }
    }

    @Test fun selectAllCoversEverythingAndCopyIsReadableText() {
        t.seed("# Shopping\n\n- One\n- Two\n\n- [ ] Milk\n- [x] Bread\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Shopping", "One", "Two", "Milk", "Bread"))
        selectAllFromTheMenu()
        rule.barButton("Copy").performClick()
        rule.waitFor("the clipboard", { t.clipboardText().toString().replace("\n", "⏎") }) {
            t.clipboardText() == "Shopping\n\n• One\n• Two\n\n☐ Milk\n☑ Bread"
        }
    }

    // ---- C. copy as Markdown ----------------------------------------------------------------------------------

    @Test fun copyAsMarkdownKeepsTheStructure() {
        t.seed("# Shopping\n\n- [ ] Milk\n- [x] Bread\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Shopping", "Milk", "Bread"))
        selectAllFromTheMenu()
        rule.barButton("Copy as Markdown").performClick()
        rule.waitFor("the clipboard", { t.clipboardText().toString() }) { t.clipboardText() == "# Shopping\n\n- [ ] Milk\n- [x] Bread" }
    }

    // ---- B. cut and undo --------------------------------------------------------------------------------------

    @Test fun cutAcrossBlocksIsOneUndoStep() {
        val files = t.seed("Alpha one\n\nBeta two\n\n- a\n- b\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Alpha one", "Beta two", "a", "b"))
        selectAllFromTheMenu()
        rule.barButton("Cut").performClick()
        rule.waitForRowTexts(listOf(""))
        assertEquals("Alpha one\n\nBeta two\n\n• a\n• b", t.clipboardText())
        rule.waitFor("the emptied file", { files[0].readText() }) { files[0].readText().isBlank() }
        rule.formatButton("Undo").performClick()
        rule.waitForRowTexts(listOf("Alpha one", "Beta two", "a", "b"))
        rule.waitFor("the restored file", { files[0].readText() }) { files[0].readText() == "Alpha one\n\nBeta two\n\n- a\n- b\n" }
        assertTrue(true)
    }

    @Test fun tappingAnywhereEndsTheSelection() {
        t.seed("One\n\nTwo\n")
        launch()
        rule.waitForRowTexts(listOf("One", "Two"))
        selectAllFromTheMenu()
        rule.barButton("Done").performClick()
        rule.waitFor("no selection bar", { "" }) { rule.tagCount("selection-bar") == 0 }
        assertEquals(1, rule.tagCount("formatting-bar"))
    }
}
