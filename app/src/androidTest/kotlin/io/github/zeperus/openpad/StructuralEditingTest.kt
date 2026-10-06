package io.github.zeperus.openpad

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for the real-device bug: a structural edit (paragraph <-> list item, leaving a list, Enter, Backspace,
 * ticking a checkbox) used to replace the text field the user was typing in, so the keyboard closed and reopened and a held
 * Backspace stopped. The editor must *change* the row instead. Observable here: no text field is disposed by such an
 * edit ([EditorDiagnostics]), and the field that had the focus still has it. (The emulator has no soft keyboard to watch; a field that
 * keeps its focus and is never disposed keeps its input connection, which is what the keyboard follows.)
 */
@RunWith(AndroidJUnit4::class)
class StructuralEditingTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val notesDir = File(app.filesDir, "openpad/notes")

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun seed(markdown: String): File = runBlocking {
        val id = app.repository.createNote(markdown).id
        app.sessionStore.save(PersistedSession(listOf(id.value), id.value))
        app.settings.setStartupMode(StartupMode.ResumeSession)
        File(notesDir, id.value + ".md")
    }

    private fun waitFor(what: String, actual: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; found: ${actual()}")
            Thread.sleep(100)
            rule.waitForIdle()
        }
    }

    private fun waitForRows(expected: List<String>) = waitFor("rows $expected", { rule.rowTexts().toString() }) { rule.rowTexts() == expected }

    private fun waitForFile(file: File, text: String) = waitFor(
        "file = ${text.replace("\n", "⏎")}", { if (file.exists()) file.readText().replace("\n", "⏎") else "<missing>" },
    ) { file.exists() && file.readText() == text }

    private fun markers() = rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size
    private fun checkboxes() = rule.onAllNodes(hasTestTag("checkbox")).fetchSemanticsNodes().size

    private fun backspace(row: Int = -1) {
        val node = if (row < 0) rule.editorRows().onLast() else rule.row(row)
        node.performKeyInput { keyDown(Key.Backspace); keyUp(Key.Backspace) }
        rule.waitForIdle()
    }

    private fun overflow(item: String) {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(item).performScrollTo().performClick()
    }

    private val disposed get() = EditorDiagnostics.fieldsDisposed

    // ---- A, B, C: leaving a list keeps the field and its focus ----------------------------------------------------

    private fun leavingByTheBarKeepsTheField(button: String, rowIsList: () -> Boolean) {
        launch()
        rule.typeInLastRow("hello")
        rule.formatButton(button).performClick()
        waitFor("a list row", { "markers=${markers()} boxes=${checkboxes()}" }) { rowIsList() }
        rule.row(0).assertIsFocused()
        val before = disposed
        rule.formatButton(button).performClick() // back to a paragraph
        waitFor("a paragraph again", { "markers=${markers()} boxes=${checkboxes()}" }) { !rowIsList() }
        assertEquals("no text field may be replaced by the conversion", before, disposed)
        rule.row(0).assertIsFocused()
        assertEquals(listOf("hello"), rule.rowTexts())
    }

    @Test fun bulletToParagraphKeepsTheFocusedField() = leavingByTheBarKeepsTheField("Bulleted list") { markers() == 1 }

    @Test fun numberedToParagraphKeepsTheFocusedField() = leavingByTheBarKeepsTheField("Numbered list") { markers() == 1 }

    @Test fun checklistToParagraphKeepsTheFocusedField() = leavingByTheBarKeepsTheField("Checklist") { checkboxes() == 1 }

    private fun leavingByBackspaceKeepsTheField(button: String, rowIsList: () -> Boolean) {
        launch()
        rule.typeInLastRow("hello")
        rule.formatButton(button).performClick()
        waitFor("a list row", { "markers=${markers()} boxes=${checkboxes()}" }) { rowIsList() }
        rule.placeCaret(0, 0) // the caret at the very start of the text
        val before = disposed
        backspace(0)
        waitFor("a paragraph", { "markers=${markers()} boxes=${checkboxes()}" }) { !rowIsList() }
        assertEquals(before, disposed)
        rule.row(0).assertIsFocused()
        assertEquals(listOf("hello"), rule.rowTexts())
    }

    @Test fun backspaceOutOfABulletKeepsTheFocusedField() = leavingByBackspaceKeepsTheField("Bulleted list") { markers() == 1 }

    @Test fun backspaceOutOfANumberedItemKeepsTheFocusedField() = leavingByBackspaceKeepsTheField("Numbered list") { markers() == 1 }

    @Test fun backspaceOutOfAChecklistItemKeepsTheFocusedField() = leavingByBackspaceKeepsTheField("Checklist") { checkboxes() == 1 }

    // ---- D: repeated Backspace over a list boundary -------------------------------------------------------------

    @Test fun repeatedBackspaceOverAListBoundaryNeverLeavesTheField() {
        seed("- a\n- b\n\npara\n")
        launch()
        waitForRows(listOf("a", "b", "para"))
        rule.placeCaret(2, 0)
        rule.waitForIdle()
        val before = disposed
        // a=list item, b=list item, then the paragraph; the simulated key repeat: every press must leave the same field focused
        val expected = listOf(
            listOf("a", "bpara"), // joined into the item above (it is now that item)
            listOf("a", "para"), // deleted "b"
            listOf("a", "para"), // Backspace at the start of an item: it becomes a paragraph
            listOf("apara"), // joined into "a"
            listOf("para"), // deleted "a"
            listOf("para"), // Backspace at the start of an item: it becomes a paragraph
        )
        for ((n, rows) in expected.withIndex()) {
            backspace()
            waitFor("rows $rows after press ${n + 1}", { rule.rowTexts().toString() }) { rule.rowTexts() == rows }
            rule.editorRows().onLast().assertIsFocused()
        }
        assertEquals("one text field holds all the rows: no field is ever replaced, whatever the rows do", before, disposed)
        assertEquals(0, markers())
    }

    // ---- E: Enter on empty items --------------------------------------------------------------------------------

    private fun enterOnEmptyItemLeavesCleanly(button: String, rowIsList: (Int) -> Boolean) {
        launch()
        rule.typeInLastRow("milk")
        rule.formatButton(button).performClick()
        rule.typeInLastRow("\n")
        waitFor("two list rows", { "rows=${rule.rowTexts()} markers=${markers()} boxes=${checkboxes()}" }) { rule.rowTexts() == listOf("milk", "") && rowIsList(2) }
        val before = disposed
        rule.typeInLastRow("\n") // Enter on the empty item
        waitFor("one list row and an empty paragraph", { "rows=${rule.rowTexts()} markers=${markers()} boxes=${checkboxes()}" }) { rowIsList(1) }
        Thread.sleep(500) // a ghost row would show up late
        rule.waitForIdle()
        assertEquals(listOf("milk", ""), rule.rowTexts())
        assertEquals("the field in use is not replaced", before, disposed)
        rule.editorRows().onLast().assertIsFocused()
    }

    @Test fun enterOnAnEmptyBulletLeavesTheListWithoutAGhostRow() = enterOnEmptyItemLeavesCleanly("Bulleted list") { n -> markers() == n }

    @Test fun enterOnAnEmptyNumberedItemLeavesTheList() = enterOnEmptyItemLeavesCleanly("Numbered list") { n -> markers() == n }

    @Test fun enterOnAnEmptyChecklistItemLeavesTheList() = enterOnEmptyItemLeavesCleanly("Checklist") { n -> checkboxes() == n }

    // ---- F, G, H: smart checklist ---------------------------------------------------------------------------------

    private fun smartChecklist(): File {
        val file = seed("- [ ] A\n- [ ] B\n- [ ] C\n")
        launch()
        waitForRows(listOf("A", "B", "C"))
        overflow("Smart checklist: off (turn on)")
        rule.waitForIdle()
        return file
    }

    @Test fun checkingAnItemMovesItBelowTheUncheckedOnes() {
        val file = smartChecklist()
        val before = disposed
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForRows(listOf("A", "C", "B"))
        waitForFile(file, "- [ ] A\n- [ ] C\n- [x] B\n")
        assertEquals("the moved items keep their fields", before, disposed)
    }

    @Test fun uncheckingReturnsTheItemToTheEndOfTheUncheckedGroup() {
        val file = smartChecklist()
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForRows(listOf("A", "C", "B"))
        rule.onAllNodes(hasTestTag("checkbox"))[2].performClick() // B, now last
        waitFor("B unchecked at the end", { rule.rowTexts().toString() }) { rule.rowTexts() == listOf("A", "C", "B") }
        waitForFile(file, "- [ ] A\n- [ ] C\n- [ ] B\n")
    }

    @Test fun undoRestoresTheCheckboxAndTheOrderInOneStep() {
        val file = smartChecklist()
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForRows(listOf("A", "C", "B"))
        rule.formatButton("Undo").performClick()
        waitForRows(listOf("A", "B", "C"))
        waitFor("original file", { file.readText().replace("\n", "⏎") }) { file.readText() == "- [ ] A\n- [ ] B\n- [ ] C\n" }
        rule.formatButton("Redo").performClick()
        waitForRows(listOf("A", "C", "B"))
    }

    @Test fun anOrdinaryTaskListKeepsItsOrder() {
        val file = seed("- [ ] A\n- [ ] B\n- [ ] C\n")
        launch()
        waitForRows(listOf("A", "B", "C"))
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForFile(file, "- [ ] A\n- [x] B\n- [ ] C\n")
        assertEquals(listOf("A", "B", "C"), rule.rowTexts())
    }

    @Test fun tickingACheckboxDoesNotMoveTheTextCaret() {
        seed("- [ ] A\n- [ ] B\n- [ ] C\n")
        launch()
        waitForRows(listOf("A", "B", "C"))
        overflow("Smart checklist: off (turn on)")
        rule.placeCaret(0, 1) // caret in "A"; the field is the one that must keep it
        rule.field().requestFocus()
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick() // B moves down
        waitForRows(listOf("A", "C", "B"))
        rule.row(0).assertIsFocused() // still in A
    }
}
