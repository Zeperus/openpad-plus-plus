package io.github.zeperus.openpad

import android.util.TypedValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the editor looks like in numbers: ordinary lines and list items sit under each other like in a notepad (no gaps that
 * look like blank paragraphs), and Enter in a list leaves the caret in the text of the new item - to the right of its
 * marker - at once. Not pixel-exact: generous limits, with the measured values in every failure message.
 */
@RunWith(AndroidJUnit4::class)
class EditorDensityTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())
    private val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.field().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    /** The top of the line each row starts on, and how many lines the field has. */
    private fun report(rows: List<String>): String {
        val l = layout()
        var offset = 1 // the invisible marker
        val tops = rows.map { row -> l.getLineTop(l.getLineForOffset(offset)).also { offset += row.length + 1 } }
        return "lineCount=${l.lineCount} tops=$tops (16sp = ${sp(16f)} px)"
    }

    /** Consecutive rows are one line apart, at most a little more than a text line (1.6 x the font size), and the field has no extra lines. */
    private fun assertCompact(rows: List<String>) {
        rule.waitForRowTexts(rows)
        val l = layout()
        val message = report(rows)
        assertEquals("one line per row, no phantom lines; $message", rows.size, l.lineCount)
        var offset = 1
        val tops = rows.map { row -> l.getLineTop(l.getLineForOffset(offset)).also { offset += row.length + 1 } }
        for (i in 1 until tops.size) {
            val pitch = tops[i] - tops[i - 1]
            assertTrue("rows $i and ${i - 1} are $pitch px apart; $message", pitch <= sp(16f) * 1.6f)
            assertTrue("rows must not overlap; $message", pitch >= sp(16f))
        }
    }

    // ---- A / B: density ---------------------------------------------------------------------------------------

    @Test fun paragraphsSitUnderEachOtherLikeInANotepad() {
        t.seed("Line one\n\nLine two\n\nLine three\n")
        launch()
        assertCompact(listOf("Line one", "Line two", "Line three"))
    }

    @Test fun aChecklistIsOneCompactList() {
        t.seed("- [ ] Einkauf\n- [ ] Brot\n- [ ] Wasser\n- [ ] Schwein\n")
        launch()
        assertCompact(listOf("Einkauf", "Brot", "Wasser", "Schwein"))
        // the checkboxes can be hit comfortably although the lines are close together
        val boxes = rule.onAllNodes(hasTestTag("checkbox")).fetchSemanticsNodes()
        assertEquals(4, boxes.size)
        for (b in boxes) {
            val w = b.boundsInWindow.width
            assertTrue("a checkbox is ${w}px wide, needs 48dp = ${dp(48f)}", w >= dp(47f))
            assertTrue("a checkbox is ${b.boundsInWindow.height}px tall", b.boundsInWindow.height >= dp(24f))
        }
    }

    @Test fun bulletsAndNumbersAreCompactToo() {
        t.seed("- one\n- two\n- three\n")
        launch()
        assertCompact(listOf("one", "two", "three"))
    }

    // ---- C / D: Enter ----------------------------------------------------------------------------------------

    /** Enter at the end of [first] in a list: the new item exists, has the focus, the caret is in its text, typing goes there. */
    private fun enterThenType(markdown: String, first: String, next: String) {
        t.seed(markdown)
        launch()
        rule.waitForRowTexts(listOf(first))
        rule.placeCaret(0, first.length)
        rule.field().assertIsFocused() // placing the caret focuses the field (or it already had it)
        val disposedBefore = EditorDiagnostics.fieldsDisposed
        val createdBefore = EditorDiagnostics.fieldsCreated
        rule.typeInLastRow("\n")
        rule.waitForRowTexts(listOf(first, ""))
        rule.field().assertIsFocused()
        assertEquals("the caret is at the start of the new item's text", TextRange(first.length + 1), rule.selectionRange())
        val before = layout().getCursorRect(first.length + 1 + 1 /* marker */ + 1 /* line break */)
        rule.typeInLastRow(next)
        rule.waitForRowTexts(listOf(first, next))
        // typing did not move the start of the text: the caret was already where the text begins
        val after = layout().getCursorRect(first.length + 1 + 1 + 1)
        assertEquals("the caret sat ${before.left}px from the left, the text starts at ${after.left}px", after.left, before.left, 2f)
        assertTrue("the caret is right of the marker column (${before.left}px)", before.left >= dp(24f))
        assertEquals("no field was disposed or created", disposedBefore, EditorDiagnostics.fieldsDisposed)
        assertEquals(createdBefore, EditorDiagnostics.fieldsCreated)
    }

    @Test fun enterInAChecklistPutsTheCaretInTheNewItem() = enterThenType("- [ ] Schwein\n", "Schwein", "Salz")

    @Test fun enterInABulletListPutsTheCaretInTheNewItem() = enterThenType("- Item\n", "Item", "Two")

    @Test fun enterInANumberedListPutsTheCaretInTheNewItem() = enterThenType("1. Item\n", "Item", "Two")
}
