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

    /**
     * Grabs a selection handle and drags it to [target] on the screen. The handle is drawn by the system (a popup window) under the
     * edge of the selection: the start handle with its top-right corner at the edge, the end handle with its top-left corner there,
     * both roughly 25 dp square. A few grip points inside that square are tried until one moves the selection (an attempt that grabs
     * nothing only touches the text and changes nothing).
     */
    private fun dragHandle(edgeOffset: Int, isStart: Boolean, target: Offset) {
        val before = selection()
        val b = bounds()
        val rect = layout().getCursorRect((edgeOffset + 1).coerceIn(0, layout().layoutInput.text.length))
        for ((dx, dy) in listOf(12f to 12f, 8f to 18f, 16f to 8f, 12f to 20f)) {
            val x = b.left + rect.left + (if (isStart) -dx else dx) * density
            val y = b.top + rect.bottom + dy * density
            device.swipe(x.toInt(), y.toInt(), target.x.toInt() + (if (isStart) -dx else dx).toInt(), (target.y + dy * density).toInt(), 60)
            rule.waitForIdle()
            Thread.sleep(300)
            if (selection() != before) return
        }
    }

    private fun dragEndHandleTo(target: Offset) = dragHandle(selection().max, isStart = false, target = target)

    private fun dragStartHandleTo(target: Offset) = dragHandle(selection().min, isStart = true, target = target)

    /** The floating toolbar's item [label]; looks into its overflow too. Null if it is not offered at all. */
    private fun toolbar(label: String): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.findObject(By.text(label)), 5_000)?.let { return it }
        device.findObject(By.clazz("android.widget.ImageButton").desc("More options"))?.click()
        return device.wait(Until.findObject(By.text(label)), 3_000)
    }

    /** Everything with a text or description on the screen, for failure messages. */
    private fun toolbarItems(): String {
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        fun attr(node: String, name: String) = Regex(" $name=\"([^\"]*)\"").find(node)?.groupValues?.get(1).orEmpty()
        return Regex("<node [^>]*>").findAll(out.toString()).map { it.value }
            .filter { attr(it, "text").isNotEmpty() || attr(it, "content-desc").isNotEmpty() }
            .joinToString(" | ") { attr(it, "class").substringAfterLast('.') + ":" + attr(it, "text") + "/" + attr(it, "content-desc") }
    }

    @Test fun aLongPressOffersCopyCutAndCopyAsMarkdownInTheSelectionToolbar() {
        t.seed("Paragraph one\n\nParagraph two\n")
        launch()
        rule.waitForRowTexts(listOf("Paragraph one", "Paragraph two"))
        longPressWord("one")
        for (item in listOf("Copy", "Cut")) toolbar(item) ?: throw AssertionError("no $item in the toolbar; on screen: ${toolbarItems()}")
        toolbar("Copy as Markdown") ?: throw AssertionError("no Copy as Markdown in the toolbar; on screen: ${toolbarItems()}")
    }

    // ---- A: paragraph -> paragraph -------------------------------------------------------------------------------

    @Test fun dragTheSelectionHandleFromOneParagraphIntoTheNextAndCopy() = dragAcrossAndCopy(null)

    /** The same at other text sizes: the handles, the long press and the copy all follow the real layout. */
    @Test fun dragTheSelectionHandleAcrossParagraphsAndCopyAt12sp() = dragAcrossAndCopy(12)

    @Test fun dragTheSelectionHandleAcrossParagraphsAndCopyAt28sp() = dragAcrossAndCopy(28)

    private fun dragAcrossAndCopy(size: Int?) {
        size?.let { kotlinx.coroutines.runBlocking { t.app.settings.setEditorFontSize(it) } }
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
        val secondStart = text().indexOf("Paragraph two")
        assertTrue("the selection starts in the first paragraph (${range})", range.min < secondStart)
        // (at another text size the handle is grabbed a few pixels off its point: "well into the second paragraph" is what counts there)
        val needed = if (size == null) secondStart + "Paragraph two".length - 1 else secondStart + 4
        assertTrue("and now reaches into the second paragraph (${range})", range.max >= needed)
        // copy through the system toolbar: readable text of both paragraphs
        val copy = toolbar("Copy") ?: throw AssertionError("the selection toolbar did not show Copy; on screen: ${toolbarItems()}")
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
        val cut = toolbar("Cut") ?: throw AssertionError("the selection toolbar did not show Cut; on screen: ${toolbarItems()}")
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
        val copy = toolbar("Copy as Markdown") ?: throw AssertionError("the selection toolbar did not show Copy as Markdown; on screen: ${toolbarItems()}; selection ${selection()} of \"${text()}\"")
        copy.click()
        rule.waitFor("the clipboard", { "${t.clipboardText()}" }) { t.clipboardText()?.contains("- [x] Bread") == true }
        assertTrue(t.clipboardText()!!.contains("# Shopping"))
        assertTrue(t.clipboardText()!!.contains("- [ ] Milk"))
    }
}
