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
 * Alpha 8 on a real runtime: the clipboard keeps the logical lines, several lines become several list items (typed in one go, which
 * is what a paste is for the field), "Paste as Checklist" cleans messenger lists, and all of it at several text sizes.
 * Pasting here means: the clipboard is set and the menu item is used, or the text is committed to the field in one edit (exactly what
 * the system's Paste does; the CI emulator has no keyboard).
 */
@RunWith(AndroidJUnit4::class)
class Alpha8Test {
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

    private fun checkboxStates(): List<Boolean> = rule.onAllNodesWithTag("checkbox").fetchSemanticsNodes().map {
        it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    private fun waitForCheckboxes(n: Int) = rule.waitFor("$n checkboxes", { "${rule.tagCount("checkbox")}" }) { rule.tagCount("checkbox") == n }

    private fun pasteAsChecklist(clip: String) {
        t.setClipboard(clip)
        rule.overflowEdit("Paste as Checklist")
        rule.waitForIdle()
    }

    private val fixture = """
[10.10., 12:27] ❤ Mäuschen ❤: - Shampoo
- Duschgel
- Desinfektionsmittel
- Lenor
- Spaghetti
- Borritos
- Tacco-Sauce
- Tacco-Gewürz
- Hackfleisch 2x
- Hähnchen
- Lyoner
- Rahmfleisch
- Cola?
- Karotten
- Salat
- Tomaten
- Brokkoli
- Knoblauch
- Gouda
- Ofenkäse
- Schmelzkäse
- Ketchup
- Dosentomaten
- Blätterteig
- Eier
- Ciabatta-Brot
- Brot
- Burger-Brötchen
- Pommes
[10.10., 12:42] ❤ Mäuschen ❤: - Reis
[10.10., 12:44] ❤ Mäuschen ❤: - Hühnerbrühe
[10.10., 12:51] Patrick Wilkens: - Nürnberger
- Bacon
- Toastbrot
""".trim()

    private val fixtureItems = listOf(
        "Shampoo", "Duschgel", "Desinfektionsmittel", "Lenor", "Spaghetti", "Borritos", "Tacco-Sauce", "Tacco-Gewürz", "Hackfleisch 2x", "Hähnchen",
        "Lyoner", "Rahmfleisch", "Cola?", "Karotten", "Salat", "Tomaten", "Brokkoli", "Knoblauch", "Gouda", "Ofenkäse", "Schmelzkäse", "Ketchup",
        "Dosentomaten", "Blätterteig", "Eier", "Ciabatta-Brot", "Brot", "Burger-Brötchen", "Pommes", "Reis", "Hühnerbrühe", "Nürnberger", "Bacon", "Toastbrot",
    )

    // ---- C / D / E: the clipboard keeps the logical lines ------------------------------------------------------------

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

    // ---- F / H / I: several lines -> list items ----------------------------------------------------------------------

    private fun threeLinesThenConvert(button: String, markerTag: String? = null) {
        launchBlank()
        focusField()
        rule.field().performTextInput("Milk\nBread\nWater")
        rule.waitFor("the text", { rule.rowTexts().toString() }) { rule.rowTexts() == listOf("Milk", "Bread", "Water") }
        rule.formatButton(button).performClick()
        rule.waitForIdle()
        if (markerTag == null) waitForCheckboxes(3) else rule.waitFor("3 markers", { "${rule.tagCount(markerTag)}" }) { rule.tagCount(markerTag) == 3 }
        assertEquals(listOf("Milk", "Bread", "Water"), rule.rowTexts())
    }

    @Test fun threeLinesBecomeThreeCheckboxes() = threeLinesThenConvert("Checklist")

    @Test fun threeLinesBecomeThreeBullets() {
        threeLinesThenConvert("Bulleted list", "marker")
        assertEquals(0, rule.tagCount("checkbox"))
    }

    @Test fun threeLinesBecomeThreeNumberedItems() {
        threeLinesThenConvert("Numbered list", "marker")
        val numbers = rule.onAllNodesWithTag("marker").fetchSemanticsNodes().map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        assertEquals(listOf("1.", "2.", "3."), numbers)
    }

    @Test fun sourceMarkersAreCleanedWhenConverting() {
        launchBlank()
        focusField()
        rule.field().performTextInput("- Milk\n* Bread\n• Water")
        rule.formatButton("Checklist").performClick()
        waitForCheckboxes(3)
        assertEquals(listOf("Milk", "Bread", "Water"), rule.rowTexts())
    }

    // ---- G: several lines pasted into an empty checklist item --------------------------------------------------------

    @Test fun threeLinesPastedIntoAnEmptyChecklistItemAreThreeItems() {
        launchBlank()
        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ New Checklist").performClick()
        waitForCheckboxes(1)
        rule.waitForRowTexts(listOf(""))
        rule.field().performTextInput("Milk\nBread\nWater")
        waitForCheckboxes(3)
        assertEquals(listOf("Milk", "Bread", "Water"), rule.rowTexts())
        assertEquals(listOf(false, false, false), checkboxStates())
    }

    @Test fun pastingInTheMiddleOfAnItemKeepsTheTextAfterTheCaret() {
        launchWith("- [ ] Milk\n", listOf("Milk"))
        rule.placeCaret(0, 2)
        rule.field().performTextInput("AAA\nBBB")
        rule.waitForRowTexts(listOf("MiAAA", "BBBlk"))
        waitForCheckboxes(2)
    }

    // ---- J: smart checklist ------------------------------------------------------------------------------------------

    @Test fun linesPastedIntoASmartChecklistLandAboveTheCompletedItems() {
        launchWith("- [ ] Milk\n- [x] Existing completed\n", listOf("Milk", "Existing completed"))
        runBlocking { t.app.repository.setSmartChecklist(t.app.repository.listNotes().single().id, true) }
        scenario?.close()
        launch()
        rule.waitForRowTexts(listOf("Milk", "Existing completed"))
        rule.placeCaret(0, 4)
        rule.field().performTextInput("\nWater\nCheese")
        rule.waitForRowTexts(listOf("Milk", "Water", "Cheese", "Existing completed"))
        assertEquals(listOf(false, false, false, true), checkboxStates())
    }

    // ---- K / L / M: Paste as Checklist ---------------------------------------------------------------------------------

    @Test fun pasteAsChecklistCleansTheRealWhatsAppShoppingList() {
        launchBlank()
        pasteAsChecklist(fixture)
        waitForCheckboxes(34)
        val rows = rule.rowTexts()
        assertEquals(34, rows.size)
        assertEquals("Shampoo", rows.first())
        assertEquals("Toastbrot", rows.last())
        assertEquals(fixtureItems, rows)
        for (row in rows) {
            for (bad in listOf("[10.10.", "12:27", "12:42", "12:44", "12:51", "Mäuschen", "Patrick Wilkens")) assertFalse("'$row' contains $bad", bad in row)
            for (bad in listOf("-", "*", "+", "•", "– ", "—")) assertFalse("'$row' starts with $bad", row.startsWith(bad))
        }
        // the note became a smart checklist (a blank note, explicit import) and the file holds the cleaned Markdown
        rule.waitFor("the note", { "${t.mdFiles()}" }) { t.mdFiles().size == 1 && t.mdFiles().single().readText().lines().count { it.startsWith("- [ ] ") } == 34 }
        assertTrue(runBlocking { t.app.repository.listNotes().single().smartChecklist })
        assertFalse("Mäuschen" in t.mdFiles().single().readText())
    }

    @Test fun pasteAsChecklistWorksWithoutABulletAfterTheSender() {
        launchBlank()
        pasteAsChecklist("[12:30] Sybille: Bier\n[12:31] Patrick: Bacon")
        waitForCheckboxes(2)
        assertEquals(listOf("Bier", "Bacon"), rule.rowTexts())
    }

    @Test fun pasteAsChecklistLeavesOrdinaryColonTextAlone() {
        launchBlank()
        pasteAsChecklist("Note: buy milk")
        waitForCheckboxes(1)
        assertEquals(listOf("Note: buy milk"), rule.rowTexts())
    }

    @Test fun pasteAsChecklistKeepsDoneMarkersAndSmartChecklistPutsThemBelow() {
        launchBlank()
        pasteAsChecklist("- [x] Bread\n- [ ] Milk\n- [ ] Water")
        waitForCheckboxes(3)
        assertEquals(listOf("Milk", "Water", "Bread"), rule.rowTexts())
        assertEquals(listOf(false, false, true), checkboxStates())
    }

    @Test fun pasteAsChecklistIsInTheTextMenu() {
        launchWith("keep\n", listOf("keep"))
        t.setClipboard("[12:30] Sybille: Bier\n- Cola")
        longPressWord("keep")
        val item = toolbar("Paste as Checklist") ?: throw AssertionError("no Paste as Checklist in the text menu")
        item.click()
        waitForCheckboxes(2)
        assertEquals(listOf("Bier", "Cola"), rule.rowTexts()) // the selected word is replaced by the cleaned list
    }

    // ---- N: Undo -------------------------------------------------------------------------------------------------------

    @Test fun oneUndoTakesBackThePastedChecklistAndRedoBringsItBack() {
        launchWith("Intro\n", listOf("Intro"))
        rule.placeCaret(0, 5)
        pasteAsChecklist(fixture)
        waitForCheckboxes(34)
        assertEquals(35, rule.rowTexts().size)
        rule.formatButton("Undo").performClick()
        rule.waitForRowTexts(listOf("Intro"))
        assertEquals(0, rule.tagCount("checkbox"))
        rule.formatButton("Redo").performClick()
        waitForCheckboxes(34)
    }

    // ---- Several text sizes ------------------------------------------------------------------------------------------

    private fun tapRightOfLastLineAndType(size: Int) {
        // right of the text of the last row
        val lines = rule.rowTexts()
        val offset = 1 + text().length - lines.last().length // the start of the last row in the field
        val l = layout()
        val y = l.getLineForOffset(offset).let { (l.getLineTop(it) + l.getLineBottom(it)) / 2f }
        rule.field().performTouchInput { click(Offset(width - 6f * density, y)) }
        rule.waitForIdle()
        rule.field().performTextInput("X")
        rule.waitFor("X at the end of the last item at ${size} sp", { rule.rowTexts().toString() }) { rule.rowTexts().last().endsWith("X") }
    }

    private fun listAtSize(size: Int) {
        val long = "Frisches Bauernbrot vom Markt, in Scheiben geschnitten und danach luftdicht verpackt, am besten das große Vollkornbrot mit Körnern"
        launchBlank(size)
        pasteAsChecklist("[12:30] Sybille: Hackfleisch 2x\n- Milch\n- $long\n• Eier")
        waitForCheckboxes(4)
        assertEquals(listOf("Hackfleisch 2x", "Milch", long, "Eier"), rule.rowTexts())
        assertTrue("the long item wraps at $size sp without becoming two items", layout().lineCount > 4)
        assertEquals(4, rule.tagCount("checkbox"))
        tapRightOfLastLineAndType(size)
        // the checkbox target works: ticking the first item moves it below the others (smart checklist)
        rule.onAllNodesWithTag("checkbox")[0].performScrollTo().performClick()
        rule.waitFor("Hackfleisch moved down at $size sp", { rule.rowTexts().toString() }) { rule.rowTexts().last() == "Hackfleisch 2x" }
        assertEquals(listOf(false, false, false, true), checkboxStates())
    }

    @Test fun listsWork12sp() = listAtSize(12)

    @Test fun listsWork16sp() = listAtSize(16)

    @Test fun listsWork20sp() = listAtSize(20)

    @Test fun listsWork24sp() = listAtSize(24)

    @Test fun listsWork28sp() = listAtSize(28)
}
