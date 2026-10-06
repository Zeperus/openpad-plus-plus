package io.github.zeperus.openpad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the caret goes when the user taps a row: the whole row takes part, not just the glyphs. Real touch coordinates (no internal
 * caret API): a tap is computed from the text layout - the end of a row's last line, the middle of a word, the start of a line - and
 * then "!" / "X" is typed with the keyboard path, so the result shows where the caret really was.
 */
@RunWith(AndroidJUnit4::class)
class CaretHitTestTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch(markdown: String, rows: List<String>) {
        t.seed(markdown)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(rows)
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.field().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun width() = rule.field().fetchSemanticsNode().size.width.toFloat()

    /** The field offset (with the invisible marker in front) where row [row] starts. */
    private fun rowStart(row: Int): Int = 1 + rule.fieldTexts()[0].split("\n").take(row).sumOf { it.length + 1 }

    private fun rowLength(row: Int): Int = rule.fieldTexts()[0].split("\n")[row].length

    private fun midY(offset: Int): Float = layout().let { l -> l.getLineForOffset(offset).let { (l.getLineTop(it) + l.getLineBottom(it)) / 2f } }

    private fun tap(x: Float, y: Float) {
        rule.field().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    /** Far to the right of the text, inside the row. */
    private fun tapRightOf(offset: Int) = tap(width() - 6f * density, midY(offset))

    private fun typeAndRows(text: String): List<String> {
        rule.field().performTextInput(text)
        rule.waitForIdle()
        Thread.sleep(200)
        return rule.rowTexts()
    }

    // ---- single line -----------------------------------------------------------------------------------------------

    @Test fun tappingFarToTheRightOfASingleRowPutsTheCaretAtItsEnd() {
        launch("Hello\n", listOf("Hello"))
        tapRightOf(rowStart(0))
        assertEquals(listOf("Hello!"), typeAndRows("!"))
    }

    @Test fun tappingFarToTheRightOfARowThatIsFollowedByAnotherPutsTheCaretAtItsEnd() {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"))
        tapRightOf(rowStart(0))
        val sel = rule.selectionRange()!!
        assertEquals("the caret is at the end of the first row ($sel)", 5, sel.start)
        assertEquals(listOf("Hello!", "World"), typeAndRows("!"))
    }

    @Test fun tappingInsideTheTextStillPlacesTheCaretBetweenTheGlyphs() {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"))
        val l = layout()
        val between = l.getBoundingBox(rowStart(0) + 2).left // between "e" and "l"... the left edge of the second "l"
        tap(between, midY(rowStart(0)))
        assertEquals(listOf("HelXlo", "World"), typeAndRows("X"))
    }

    @Test fun tappingTheStartOfTheTextAndLeftOfIt() {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"))
        tap(2f, midY(rowStart(1)))
        assertEquals(listOf("Hello", "XWorld"), typeAndRows("X"))
    }

    @Test fun aSecondTapToTheRightInAnAlreadyFocusedRowMovesTheCaret() {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"))
        val l = layout()
        tap(l.getBoundingBox(rowStart(0) + 1).center.x, midY(rowStart(0))) // in the text: focus, caret after "H"
        assertEquals(1, rule.selectionRange()!!.start)
        tapRightOf(rowStart(0)) // already focused: the caret still has to move
        assertEquals(5, rule.selectionRange()!!.start)
        assertEquals(listOf("HelloX", "World"), typeAndRows("X"))
    }

    // ---- wrapped rows ------------------------------------------------------------------------------------------------

    private val longText = "This is a long paragraph that wraps onto another visual line and keeps going for a third line too"

    @Test fun inAWrappedRowTheLineOfTheTapDecides() {
        launch("$longText\n\nNext\n", listOf(longText, "Next"))
        val l = layout()
        assertTrue("the paragraph must wrap (${l.lineCount} lines)", l.lineCount >= 3)
        val lastLine = l.getLineForOffset(rowStart(0) + longText.length)
        // right of the first visual line: the end of THAT line, not of the paragraph
        tapRightOf(rowStart(0))
        val first = rule.selectionRange()!!
        val firstLineEnd = l.getLineEnd(0) - 1 // display offset; the field's marker shifts the text by one
        assertTrue("the caret is on the first visual line ($first, line ends at $firstLineEnd)", first.start in (firstLineEnd - 1)..(firstLineEnd + 1) && first.start < longText.length)
        // right of the last visual line: the end of the row, not the start of the next one
        tap(width() - 6f * density, midY(rowStart(0) + longText.length))
        assertEquals(longText.length, rule.selectionRange()!!.start)
        // left of the last visual line: the start of THAT line
        tap(1f, midY(rowStart(0) + longText.length))
        val start = l.getLineStart(lastLine) - 1
        assertEquals(start, rule.selectionRange()!!.start)
    }

    // ---- lists and headings -----------------------------------------------------------------------------------------

    private fun endOfFirstRow(markdown: String, rows: List<String>, expectedAfter: List<String>) {
        launch(markdown, rows)
        tapRightOf(rowStart(0))
        assertEquals(rows[0].length, rule.selectionRange()!!.start)
        assertEquals(expectedAfter, typeAndRows("!"))
    }

    @Test fun bulletItem() = endOfFirstRow("- Shopping\n- Milk\n", listOf("Shopping", "Milk"), listOf("Shopping!", "Milk"))

    @Test fun numberedItem() = endOfFirstRow("1. Something\n2. Other\n", listOf("Something", "Other"), listOf("Something!", "Other"))

    @Test fun checklistItem() = endOfFirstRow("- [ ] Milk\n- [ ] Bread\n", listOf("Milk", "Bread"), listOf("Milk!", "Bread"))

    @Test fun heading() = endOfFirstRow("# Title\n\nBody\n", listOf("Title", "Body"), listOf("Title!", "Body"))

    @Test fun quote() = endOfFirstRow("> Said\n\nBody\n", listOf("Said", "Body"), listOf("Said!", "Body"))

    @Test fun tappingTheCheckboxTogglesItAndDoesNotPlaceTheCaret() {
        val file = t.seed("- [ ] Milk\n- [ ] Bread\n").single()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(listOf("Milk", "Bread"))
        rule.onAllNodes(hasTestTag("checkbox"))[0].performTouchInput { click() }
        rule.waitFor("the task is ticked", { file.readText() }) { file.readText().startsWith("- [x] Milk") }
    }

    @Test fun tappingTheMarginLeftOfTheTextPlacesTheCaretAtTheStartOfThatRow() {
        launch("Hello\n\nWorld\n", listOf("Hello", "World"))
        val field = rule.field().fetchSemanticsNode().boundsInRoot
        val y = midY(rowStart(1))
        rule.onNodeWithTag("editor").performTouchInput { click(Offset(4f * density, field.top + y)) }
        rule.waitForIdle()
        assertEquals(listOf("Hello", "XWorld"), typeAndRows("X"))
    }
}
