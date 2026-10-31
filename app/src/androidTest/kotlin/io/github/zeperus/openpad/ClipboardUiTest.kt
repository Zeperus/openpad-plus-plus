package io.github.zeperus.openpad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copy and paste: the clipboard keeps the logical lines (a paragraph that only wraps on the screen is copied without
 * line breaks), and pasting keeps them. Pasting here means: the clipboard is set and the menu item is used, or the
 * text is committed to the field in one edit (exactly what the system's Paste does; the CI emulator has no keyboard).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardUiTest {
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

    private fun launchBlank(size: Int? = null) {
        size?.let { runBlocking { t.app.settings.setEditorFontSize(it) } }
        launch()
        rule.waitForRowTexts(listOf(""))
    }

    private fun launchWith(markdown: String, rows: List<String>, size: Int? = null) {
        size?.let { runBlocking { t.app.settings.setEditorFontSize(it) } }
        t.seed(markdown)
        launch()
        rule.waitForRowTexts(rows)
    }

    private fun focusField() {
        rule.field().performTouchInput { click(Offset(width / 2f, 4f * density)) }
        rule.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.field().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun text() = rule.fieldTexts().first()

    private fun longPressWord(word: String) {
        val at = text().indexOf(word)
        val box = layout().getBoundingBox(at + 1 + word.length / 2)
        rule.field().performTouchInput { longClick(Offset(box.center.x, box.center.y)) }
        rule.waitFor("a selected word", { "${rule.selectionRange()}" }) { rule.selectionRange()?.collapsed == false }
    }

    private fun toolbar(label: String): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.findObject(By.text(label)), 5_000)?.let { return it }
        device.findObject(By.clazz("android.widget.ImageButton").desc("More options"))?.click()
        return device.wait(Until.findObject(By.text(label)), 3_000)
    }

    private fun bounds() = rule.field().fetchSemanticsNode().boundsInWindow

    /** The screen position just right of the last character of the text, in the middle of its line. */
    private fun pastTheEnd(): Offset {
        val l = layout()
        val box = l.getBoundingBox(l.layoutInput.text.length - 1)
        val b = bounds()
        return Offset(b.left + box.right + 12f * density, b.top + box.center.y)
    }

    /** Grabs the end handle of the selection (drawn by the system) and drags it to [target]; a few grip points are tried until the selection moves. */
    private fun dragEndHandleTo(target: Offset) {
        val before = rule.selectionRange()
        val b = bounds()
        val edge = rule.selectionRange()!!.max
        val rect = layout().getCursorRect((edge + 1).coerceIn(0, layout().layoutInput.text.length))
        for ((dx, dy) in listOf(12f to 12f, 8f to 18f, 16f to 8f, 12f to 20f)) {
            val x = b.left + rect.left + dx * density
            val y = b.top + rect.bottom + dy * density
            device.swipe(x.toInt(), y.toInt(), (target.x + dx).toInt(), (target.y + dy * density).toInt(), 60)
            rule.waitForIdle()
            Thread.sleep(300)
            if (rule.selectionRange() != before) return
        }
    }

    /** Long press the first word, drag the end handle past the end of the text and copy with the system toolbar (the plain Copy). */
    private fun selectToTheEndAndCopy(firstWord: String) {
        t.clearClipboard()
        longPressWord(firstWord)
        dragEndHandleTo(pastTheEnd())
        val range = rule.selectionRange()!!
        assertTrue("the selection reaches the end of the text ($range of ${text().length})", range.max >= text().length - 1)
        (toolbar("Copy") ?: throw AssertionError("no Copy in the toolbar")).click()
        rule.waitFor("the clipboard", { "${t.clipboardText()}" }) { !t.clipboardText().isNullOrEmpty() }
    }

    @Test fun copyingTwoLinesGivesTwoLinesOnTheClipboardAndPasteKeepsThem() {
        launchWith("Hello\n\nWorld\n", listOf("Hello", "World"))
        selectToTheEndAndCopy("Hello")
        assertEquals("Hello\nWorld", t.clipboardText())
        // pasted into another, blank note: two logical lines again
        val copied = t.clipboardText()!!
        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ New Note").performClick()
        rule.waitForRowTexts(listOf(""))
        rule.field().performTextInput(copied)
        rule.waitForRowTexts(listOf("Hello", "World"))
    }

    @Test fun anIntentionalBlankLineSurvivesTheCopy() {
        launchBlank()
        focusField()
        rule.field().performTextInput("Line one")
        rule.field().performTextInput("\n")
        rule.field().performTextInput("\n")
        rule.field().performTextInput("Line three")
        rule.waitForRowTexts(listOf("Line one", "", "Line three"))
        selectToTheEndAndCopy("Line")
        assertEquals("Line one\n\nLine three", t.clipboardText())
    }

    @Test fun aParagraphThatOnlyWrapsOnTheScreenIsCopiedWithoutLineBreaks() {
        val long = "This is a long sentence that happens to wrap because the phone screen is narrow, and it goes on and on so that it wraps on any phone, " +
            "also a wide one, and also at a small text size, because it is long enough for every screen we test on."
        launchWith("$long\n", listOf(long), size = 20)
        assertTrue("the paragraph wraps on the screen", layout().lineCount > 1)
        selectToTheEndAndCopy("This")
        assertEquals(long, t.clipboardText())
        assertFalse('\n' in t.clipboardText()!!)
    }

    @Test fun aChecklistIsCopiedAsOneItemPerLine() {
        launchWith("- [ ] Milk\n- [x] Bread\n- [ ] Water\n", listOf("Milk", "Bread", "Water"))
        selectToTheEndAndCopy("Milk")
        assertEquals("☐ Milk\n☑ Bread\n☐ Water", t.clipboardText())
    }
}
