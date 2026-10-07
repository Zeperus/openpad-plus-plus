package io.github.zeperus.openpad

import android.util.TypedValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor text size (Settings -> Editor -> Text size): the editor follows it at once and after a restart, rows stay compact
 * (1.5 x the text, no gaps), checkboxes and markers keep their column, and - the critical part - tapping still puts the caret where it
 * belongs at every size (the Alpha 6 hit testing uses the real layout, nothing is measured in pixels of the old size).
 */
@RunWith(AndroidJUnit4::class)
class FontSizeTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())
    private val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    private val density get() = metrics.density

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    private fun launch(markdown: String, rows: List<String>, size: Int? = null) {
        t.seed(markdown)
        size?.let { runBlocking { t.app.settings.setEditorFontSize(it) } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(rows)
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.field().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun width() = rule.field().fetchSemanticsNode().size.width.toFloat()
    private fun rowStart(row: Int): Int = 1 + rule.fieldTexts()[0].split("\n").take(row).sumOf { it.length + 1 }
    private fun midY(offset: Int): Float = layout().let { l -> l.getLineForOffset(offset).let { (l.getLineTop(it) + l.getLineBottom(it)) / 2f } }
    private fun lineHeight(offset: Int): Float = layout().let { l -> l.getLineForOffset(offset).let { l.getLineBottom(it) - l.getLineTop(it) } }

    private fun tap(x: Float, y: Float) {
        rule.field().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    private fun tapRightOf(offset: Int) = tap(width() - 6f * density, midY(offset))

    private fun typeAndRows(text: String): List<String> {
        rule.field().performTextInput(text)
        rule.waitForIdle()
        Thread.sleep(200)
        return rule.rowTexts()
    }

    private fun openSettings() {
        rule.overflow("Settings")
        rule.waitFor("the settings", { "" }) { rule.onAllNodes(hasTestTag("font-size-value")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sizeShown(): String =
        rule.onNodeWithTag("font-size-value").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun back() {
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitFor("the editor", { "" }) { rule.onAllNodes(hasTestTag("segment")).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---- A / B: the setting ----------------------------------------------------------------------------------------

    @Test fun changingTheSizeInSettingsMakesTheEditorTextLargerAtOnce() {
        launch("Hello\n", listOf("Hello"))
        val before = lineHeight(rowStart(0))
        assertEquals("a body line is 1.5 x 16 sp", sp(16f) * 1.5f, before, 1.5f)
        openSettings()
        assertEquals("16 sp", sizeShown())
        // the preview follows the buttons immediately
        val previewBefore = rule.onNodeWithTag("font-preview-body").fetchSemanticsNode().size.height
        repeat(6) { rule.onNodeWithTag("font-size-plus").performClick() }
        rule.waitFor("22 sp", { sizeShown() }) { sizeShown() == "22 sp" }
        assertTrue("the preview grew", rule.onNodeWithTag("font-preview-body").fetchSemanticsNode().size.height > previewBefore)
        back()
        val after = lineHeight(rowStart(0))
        assertEquals("a body line is 1.5 x 22 sp", sp(22f) * 1.5f, after, 1.5f)
        assertTrue(after > before)
        assertEquals("the Markdown file is untouched", "Hello\n", t.mdFiles().single().readText())
    }

    @Test fun theSizeIsSavedAndStillThereAfterReopeningTheApp() {
        launch("Hello\n", listOf("Hello"))
        openSettings()
        repeat(6) { rule.onNodeWithTag("font-size-plus").performClick() }
        rule.waitFor("22 sp", { sizeShown() }) { sizeShown() == "22 sp" }
        rule.waitFor("saved", { "" }) { runBlocking { t.app.settings.editorFontSize() } == 22 }
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(listOf("Hello"))
        assertEquals(sp(22f) * 1.5f, lineHeight(rowStart(0)), 1.5f)
        openSettings()
        assertEquals("22 sp", sizeShown())
    }

    @Test fun theButtonsStopAtTheLimits() {
        launch("Hello\n", listOf("Hello"), size = 12)
        openSettings()
        assertEquals("12 sp", sizeShown())
        rule.onNodeWithTag("font-size-minus").performClick()
        rule.waitForIdle()
        assertEquals("12 sp", sizeShown())
        repeat(20) { rule.onNodeWithTag("font-size-plus").performClick() }
        rule.waitFor("28 sp", { sizeShown() }) { sizeShown() == "28 sp" }
        rule.onNodeWithTag("font-size-plus").performClick()
        rule.waitForIdle()
        assertEquals("28 sp", sizeShown())
    }

    // ---- C: caret hit testing at every size -------------------------------------------------------------------------

    private fun caretWorksAt(size: Int) {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"), size)
        assertEquals(sp(size.toFloat()) * 1.5f, lineHeight(rowStart(0)), 1.5f)
        // right of the text of a row that is followed by another: the end of THAT row
        tapRightOf(rowStart(0))
        assertEquals(5, rule.selectionRange()!!.start)
        assertEquals(listOf("HelloX", "World"), typeAndRows("X"))
        // between letters
        val l = layout()
        tap(l.getBoundingBox(rowStart(0) + 2).left, midY(rowStart(0)))
        assertEquals(2, rule.selectionRange()!!.start)
        // left of the second row
        tap(2f, midY(rowStart(1)))
        assertEquals(rowStart(1) - 1, rule.selectionRange()!!.start)
        // an already focused row: right of the text moves the caret again
        tapRightOf(rowStart(1))
        assertEquals(listOf("HelloX", "WorldY"), typeAndRows("Y"))
    }

    @Test fun caretHitTestingAt12sp() = caretWorksAt(12)

    @Test fun caretHitTestingAt16sp() = caretWorksAt(16)

    @Test fun caretHitTestingAt20sp() = caretWorksAt(20)

    @Test fun caretHitTestingAt24sp() = caretWorksAt(24)

    @Test fun caretHitTestingAt28sp() = caretWorksAt(28)

    // ---- D: wrapped text at a large size ------------------------------------------------------------------------------

    @Test fun wrappedLinesMapTheTapToTheTappedVisualLineAt28sp() {
        val long = "This is a long paragraph that wraps onto several visual lines at a large text size so it needs a few"
        launch("$long\n\nNext\n", listOf(long, "Next"), 28)
        val l = layout()
        assertTrue("the paragraph wraps (${l.lineCount} lines)", l.lineCount >= 4)
        val lastLine = l.getLineForOffset(rowStart(0) + long.length)
        // right of the first visual line: still on that line
        tap(width() - 6f * density, (l.getLineTop(0) + l.getLineBottom(0)) / 2f)
        val first = rule.selectionRange()!!.start
        assertTrue("on the first visual line ($first)", l.getLineForOffset(first + 1) == 0 && first > 0)
        // right of the second line
        tap(width() - 6f * density, (l.getLineTop(1) + l.getLineBottom(1)) / 2f)
        val second = rule.selectionRange()!!.start
        assertTrue("on the second visual line ($second)", l.getLineForOffset(second + 1) == 1)
        // right of the last line: the end of the paragraph, not the start of the next row
        tap(width() - 6f * density, midY(rowStart(0) + long.length))
        assertEquals(long.length, rule.selectionRange()!!.start)
        // left of the last line: the start of THAT line
        tap(1f, midY(rowStart(0) + long.length))
        assertEquals(l.getLineStart(lastLine) - 1, rule.selectionRange()!!.start)
    }

    // ---- E: checklists at a small and a large size -------------------------------------------------------------------

    private fun checklistWorksAt(size: Int) {
        val file = t.seed("- [ ] Milk\n- [ ] Bread\n").single()
        runBlocking { t.app.settings.setEditorFontSize(size) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(listOf("Milk", "Bread"))
        val field = rule.field().fetchSemanticsNode().boundsInRoot
        val boxes = rule.onAllNodes(hasTestTag("checkbox")).fetchSemanticsNodes()
        assertEquals(2, boxes.size)
        for ((i, b) in boxes.withIndex()) {
            val textLeft = field.left + layout().getCursorRect(rowStart(i)).left
            assertTrue("the checkbox column ends where the text starts (${b.boundsInRoot.right} vs $textLeft)", b.boundsInRoot.right <= textLeft + 1f)
            assertEquals("the target is one line tall", lineHeight(rowStart(i)), b.boundsInRoot.height, 2f)
            assertTrue("the target is at least 40 dp wide", b.boundsInRoot.width >= 40f * density)
        }
        // the rows are compact: one line apart
        val pitch = boxes[1].boundsInRoot.top - boxes[0].boundsInRoot.top
        assertEquals(sp(size.toFloat()) * 1.5f, pitch, 2f)
        // the text is right of the box, a tap right of it puts the caret at the end of the item, a tap on the box ticks it
        tapRightOf(rowStart(0))
        assertEquals(4, rule.selectionRange()!!.start)
        rule.onAllNodes(hasTestTag("checkbox"))[0].performTouchInput { click() }
        rule.waitFor("ticked", { file.readText() }) { file.readText().startsWith("- [x] Milk") }
        assertEquals(listOf("Milk", "Bread"), rule.rowTexts())
    }

    @Test fun checklistAt12sp() = checklistWorksAt(12)

    @Test fun checklistAt28sp() = checklistWorksAt(28)

    // ---- F: compact spacing scales with the size ----------------------------------------------------------------------

    @Test fun rowsStayCompactAtALargeSizeAndHeadingsKeepTheirHierarchy() {
        launch("# Title\n\nLine one\n\nLine two\n", listOf("Title", "Line one", "Line two"), 24)
        val l = layout()
        assertEquals("one line per row, no phantom lines", 3, l.lineCount)
        val body = lineHeight(rowStart(1))
        assertEquals(sp(24f) * 1.5f, body, 1.5f)
        assertEquals("rows follow each other directly", body, l.getLineTop(2) - l.getLineTop(1), 1.5f)
        assertTrue("the heading line is taller than a body line", lineHeight(rowStart(0)) > body * 1.5f)
    }
}
