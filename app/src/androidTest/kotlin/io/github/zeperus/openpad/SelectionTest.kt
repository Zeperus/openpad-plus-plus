package io.github.zeperus.openpad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Selection across paragraphs, lists and checklists with **real touch**: a long press selects a word, and the selection handle is
 * dragged with the finger (by UiAutomator, so the system's own handle window is what is touched) from one paragraph into the next.
 * None of this calls an internal selection API.
 */
@RunWith(AndroidJUnit4::class)
class SelectionTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    // ---- geometry of the field -----------------------------------------------------------------------------------

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.field().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun bounds(): Rect = rule.field().fetchSemanticsNode().boundsInWindow

    /** The screen position of the character at [offset], vertically in the middle of its line. */
    private fun point(offset: Int, rightEdge: Boolean = false): Offset {
        val layout = layout()
        val box = layout.getBoundingBox((offset + 1).coerceIn(0, layout.layoutInput.text.length - 1))
        val b = bounds()
        return Offset(b.left + (if (rightEdge) box.right else box.center.x), b.top + box.center.y)
    }

    private fun text() = rule.fieldTexts().first()

    private fun selection() = rule.selectionRange()!!

    private fun longPressWord(word: String) {
        val at = text().indexOf(word)
        val box = layout().getBoundingBox(at + 1 + word.length / 2)
        rule.field().performTouchInput { longClick(Offset(box.center.x, box.center.y)) }
        rule.waitFor("a selected word", { "${selection()}" }) { !selection().collapsed }
    }

    /** Takes the end handle (it sits under the end of the selection) and drags it to [target] on the screen. */
    private fun dragEndHandleTo(target: Offset) {
        val b = bounds()
        val layout = layout()
        val end = layout.getCursorRect(selection().max)
        val handle = Offset(b.left + end.left, b.top + end.bottom + 12 * density)
        device.swipe(handle.x.toInt(), handle.y.toInt(), target.x.toInt(), (target.y + 12 * density).toInt(), 40)
        rule.waitForIdle()
    }

    private fun dragStartHandleTo(target: Offset) {
        val b = bounds()
        val start = layout().getCursorRect(selection().min)
        val handle = Offset(b.left + start.left, b.top + start.bottom + 12 * density)
        device.swipe(handle.x.toInt(), handle.y.toInt(), target.x.toInt(), (target.y + 12 * density).toInt(), 40)
        rule.waitForIdle()
    }

    private fun toolbar(label: String) = device.wait(Until.findObject(By.text(label)), 5_000)

    // ---- A: paragraph -> paragraph -------------------------------------------------------------------------------

    @Test fun dragTheSelectionHandleFromOneParagraphIntoTheNextAndCopy() {
        t.seed("Paragraph one\n\nParagraph two\n\nParagraph three\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Paragraph one", "Paragraph two", "Paragraph three"))
        longPressWord("one")
        val first = selection()
        assertTrue("the long press selected inside the first paragraph", first.max <= "Paragraph one".length)
        // the end handle goes down into the second paragraph, to the end of its text
        dragEndHandleTo(point(text().indexOf("two") + 2, rightEdge = true))
        val range = selection()
        val secondStart = "Paragraph one\n".length
        assertTrue("the selection starts in the first paragraph (${range})", range.min < secondStart)
        assertTrue("and now reaches into the second paragraph (${range})", range.max >= secondStart + "Paragraph two".length - 1)
        // copy through the system toolbar: readable text of both paragraphs
        val copy = toolbar("Copy") ?: throw AssertionError("the selection toolbar did not show Copy")
        copy.click()
        rule.waitFor("the clipboard", { "${t.clipboardText()}" }) { t.clipboardText()?.contains("Paragraph two") == true }
        assertTrue(t.clipboardText()!!.startsWith("one") || t.clipboardText()!!.startsWith("Paragraph one"))
    }

    // ---- B: paragraph -> list item --------------------------------------------------------------------------------

    @Test fun dragTheSelectionFromAParagraphIntoAListItem() {
        t.seed("Intro text\n\n- item one\n- item two\n")
        launch()
        rule.waitForRowTexts(listOf("Intro text", "item one", "item two"))
        longPressWord("text")
        dragEndHandleTo(point(text().indexOf("item two") + 6, rightEdge = true))
        val range = selection()
        assertTrue("the selection reaches into the second list item ($range)", range.max >= text().indexOf("item two") + 4)
        assertTrue(range.min < "Intro text\n".length)
    }

    // ---- backwards ----------------------------------------------------------------------------------------------

    @Test fun dragTheStartHandleBackwardsIntoThePreviousParagraph() {
        t.seed("Paragraph one\n\nParagraph two\n")
        launch()
        rule.waitForRowTexts(listOf("Paragraph one", "Paragraph two"))
        longPressWord("two")
        dragStartHandleTo(point(text().indexOf("one"), rightEdge = false))
        val range = selection()
        assertTrue("the selection now starts in the first paragraph ($range)", range.min < "Paragraph one".length)
        assertTrue(range.max > "Paragraph one\n".length)
    }

    // ---- C: cut and undo ----------------------------------------------------------------------------------------

    @Test fun cutAcrossParagraphsIsOneUndoStep() {
        val files = t.seed("Alpha one\n\nBeta two\n\nGamma three\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Alpha one", "Beta two", "Gamma three"))
        longPressWord("one")
        dragEndHandleTo(point(text().indexOf("Gamma") + 3, rightEdge = true))
        assertTrue(selection().max > text().indexOf("Gamma"))
        val cut = toolbar("Cut") ?: throw AssertionError("the selection toolbar did not show Cut")
        cut.click()
        rule.waitFor("the text is gone", { rule.rowTexts().toString() }) { rule.rowTexts().size == 1 }
        rule.waitFor("the file follows", { files[0].readText() }) { files[0].readText() != "Alpha one\n\nBeta two\n\nGamma three\n" }
        rule.formatButton("Undo").performClick()
        rule.waitForRowTexts(listOf("Alpha one", "Beta two", "Gamma three"))
        rule.waitFor("the restored file", { files[0].readText() }) { files[0].readText() == "Alpha one\n\nBeta two\n\nGamma three\n" }
    }

    // ---- D: copy as Markdown ----------------------------------------------------------------------------------

    @Test fun copyAsMarkdownKeepsTheStructure() {
        t.seed("# Shopping\n\n- [ ] Milk\n- [x] Bread\n")
        t.clearClipboard()
        launch()
        rule.waitForRowTexts(listOf("Shopping", "Milk", "Bread"))
        longPressWord("Shopping")
        dragEndHandleTo(point(text().indexOf("Bread") + 4, rightEdge = true))
        val copy = toolbar("Copy as Markdown") ?: throw AssertionError("the selection toolbar did not show Copy as Markdown")
        copy.click()
        rule.waitFor("the clipboard", { "${t.clipboardText()}" }) { t.clipboardText()?.contains("- [x] Bread") == true }
        assertTrue(t.clipboardText()!!.contains("# Shopping"))
        assertTrue(t.clipboardText()!!.contains("- [ ] Milk"))
    }
}
