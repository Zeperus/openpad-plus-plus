package io.github.zeperus.openpad

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.Key
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The formatted editor on a real Android runtime: Markdown is shown formatted (no markup characters), every edit ends up as
 * Markdown on disk, tabs keep their caret and undo history, and a blank page never becomes a file by itself.
 */
@RunWith(AndroidJUnit4::class)
class RichEditorTest {
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

    /** Notes from an earlier session; the first one (or [active]) is open when the app starts. */
    private fun seed(vararg markdown: String, active: Int = 0): List<File> = runBlocking {
        val ids = markdown.map { app.repository.createNote(it).id }
        app.sessionStore.save(PersistedSession(ids.map { it.value }, ids[active].value))
        app.settings.setStartupMode(StartupMode.ResumeSession)
        ids.map { File(notesDir, it.value + ".md") }
    }

    /** Waits until [condition] holds; the failure says what was there instead of just timing out. */
    private fun waitFor(what: String, actual: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; found: ${actual()}")
            Thread.sleep(100)
            rule.waitForIdle()
        }
    }

    private fun waitForRows(expected: List<String>) =
        waitFor("rows $expected", { rule.rowTexts().toString() }) { rule.rowTexts() == expected }

    private fun waitForFile(file: File, text: String) =
        waitFor("file ${file.name} = ${text.replace("\n", "⏎")}", { if (file.exists()) file.readText().replace("\n", "⏎") else "<missing>" }) {
            file.exists() && file.readText() == text
        }

    /** The only note file, once it exists, has this content. */
    private fun waitForOnlyFile(text: String) {
        waitFor("a note file", { mdFiles().toString() }) { mdFiles().size == 1 }
        waitForFile(mdFiles().single(), text)
    }

    private fun mdFiles() = notesDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().toList()

    private fun select(row: Int, from: Int, to: Int) {
        rule.row(row).performTextInputSelection(TextRange(from + 1, to + 1)) // +1: the invisible marker in front of the text
    }

    private fun buttonEnabled(description: String) =
        rule.formatButton(description).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not()

    private fun tabNodes() = rule.onAllNodes(hasTestTag("tab")).fetchSemanticsNodes()
    private fun tabTitles() = tabNodes().map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "" }

    // ---- A. Markdown appears formatted ----------------------------------------------------------------------

    @Test fun markdownAppearsFormattedWithoutMarkupCharacters() {
        seed("# Shopping\n\nBuy **these things** today.\n\n- Milk\n- Bread\n- [ ] Cheese\n\n> quoted\n\n---\n\n```\ncode here\n```\n")
        launch()
        waitForRows(listOf("Shopping", "Buy these things today.", "Milk", "Bread", "Cheese", "quoted", "code here", ""))
        val text = rule.rowTexts().joinToString("\n")
        for (markup in listOf("#", "**", "- [", "```", "> ")) assertFalse("'$markup' must not be visible", markup in text)
        assertEquals(1, rule.onAllNodes(hasTestTag("checkbox")).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size) // two bullets
        assertEquals(1, rule.onAllNodes(hasTestTag("rule")).fetchSemanticsNodes().size)
    }

    // ---- B. Editing and persistence --------------------------------------------------------------------------

    @Test fun editingAnExistingNoteWritesMarkdownAndSurvivesSwitchingTabs() {
        val files = seed("Hello", "Other")
        launch()
        waitForRows(listOf("Hello"))
        rule.typeInLastRow(" world")
        waitForFile(files[0], "Hello world\n")
        rule.onAllNodes(hasTestTag("tab") and hasText("Other")).onFirst().performClick()
        waitForRows(listOf("Other"))
        rule.onAllNodes(hasTestTag("tab") and hasText("Hello world")).onFirst().performClick()
        waitForRows(listOf("Hello world"))
        assertEquals("Hello world\n", files[0].readText())
    }

    @Test fun anUntouchedNoteIsNeverRewritten() {
        val odd = "* one\n*   two\n\n\n\nText   with  spaces\n"
        val files = seed(odd, "Other")
        launch()
        waitForRows(listOf("one", "two", "Text   with  spaces"))
        rule.onAllNodes(hasTestTag("tab") and hasText("Other")).onFirst().performClick()
        waitForRows(listOf("Other"))
        Thread.sleep(1_500)
        assertEquals(odd, files[0].readText())
    }

    // ---- C. Formatting ---------------------------------------------------------------------------------------

    private fun typeWordAndSelectIt(): File {
        launch()
        rule.typeInLastRow("word")
        rule.waitUntil(timeoutMillis = 8_000) { mdFiles().isNotEmpty() }
        select(0, 0, 4)
        rule.waitForIdle()
        return mdFiles().single()
    }

    @Test fun boldItalicStrikeAndCodeOnASelection() {
        val file = typeWordAndSelectIt()
        rule.formatButton("Bold").performClick()
        waitForFile(file, "**word**\n")
        rule.formatButton("Bold").performClick()
        rule.formatButton("Italic").performClick()
        waitForFile(file, "*word*\n")
        rule.formatButton("Italic").performClick()
        rule.formatButton("Strikethrough").performClick()
        waitForFile(file, "~~word~~\n")
        rule.formatButton("Strikethrough").performClick()
        rule.formatButton("Inline code").performClick()
        waitForFile(file, "`word`\n")
        assertEquals(listOf("word"), rule.rowTexts()) // never any markup in the editable text
    }

    @Test fun paragraphStyleMakesAHeadingWithoutTypingHashes() {
        val file = typeWordAndSelectIt()
        rule.formatButton("Paragraph style").performClick()
        rule.onNodeWithText("Heading 2").performClick()
        waitForFile(file, "## word\n")
        assertEquals(listOf("word"), rule.rowTexts())
        rule.formatButton("Paragraph style").performClick()
        rule.onNodeWithText("Text").performClick()
        waitForFile(file, "word\n")
    }

    @Test fun aLinkCanBeAddedToASelection() {
        val file = typeWordAndSelectIt()
        rule.formatButton("Link").performClick()
        rule.onNode(hasTestTag("link-address")).performTextInput("https://example.org")
        rule.onNodeWithText("Apply").performClick()
        waitForFile(file, "[word](https://example.org)\n")
        assertEquals(listOf("word"), rule.rowTexts())
    }

    // ---- D. Lists --------------------------------------------------------------------------------------------

    @Test fun bulletListEnterContinuesAndEnterOnAnEmptyItemLeaves() {
        launch()
        rule.typeInLastRow("milk")
        rule.formatButton("Bulleted list").performClick()
        rule.typeInLastRow("\n")
        rule.typeInLastRow("bread")
        rule.typeInLastRow("\n")
        waitForRows(listOf("milk", "bread", ""))
        rule.typeInLastRow("\n") // Enter on the empty item leaves the list
        waitFor("only two bullets and three rows after leaving the list", { "rows=${rule.rowTexts()} markers=${rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size}" }) {
            rule.rowTexts().size == 3 && rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size == 2
        }
        rule.typeInLastRow("after")
        waitForRows(listOf("milk", "bread", "after"))
        waitForOnlyFile("- milk\n- bread\n\nafter\n")
    }

    @Test fun enterKeyOnAnEmptyItemLeavesTheListToo() {
        launch()
        rule.typeInLastRow("milk")
        rule.formatButton("Bulleted list").performClick()
        rule.row(0).performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForRows(listOf("milk", ""))
        rule.editorRows().onLast().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitFor("one bullet and two rows after leaving the list", { "rows=${rule.rowTexts()} markers=${rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size}" }) {
            rule.rowTexts().size == 2 && rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().size == 1
        }
    }

    @Test fun numberedListIsNumbered() {
        launch()
        rule.typeInLastRow("one")
        rule.formatButton("Numbered list").performClick()
        rule.typeInLastRow("\n")
        rule.typeInLastRow("two")
        waitForRows(listOf("one", "two"))
        waitForOnlyFile("1. one\n2. two\n")
        assertEquals(listOf("1.", "2."), rule.onAllNodes(hasTestTag("marker")).fetchSemanticsNodes().map {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text } ?: ""
        })
    }

    @Test fun backspaceAtTheStartOfAListItemLeavesTheList() {
        launch()
        rule.typeInLastRow("item")
        rule.formatButton("Bulleted list").performClick()
        waitForOnlyFile("- item\n")
        rule.row(0).performTextInputSelection(TextRange(1))
        rule.row(0).performKeyInput { keyDown(Key.Backspace); keyUp(Key.Backspace) }
        waitForOnlyFile("item\n")
    }

    // ---- E. Checkboxes ---------------------------------------------------------------------------------------

    @Test fun tickingACheckboxChangesOnlyThatItemAndNothingMoves() {
        val files = seed("- [ ] milk\n- [ ] bread\n- [ ] cheese\n")
        launch()
        waitForRows(listOf("milk", "bread", "cheese"))
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForFile(files[0], "- [ ] milk\n- [x] bread\n- [ ] cheese\n")
        assertEquals(listOf("milk", "bread", "cheese"), rule.rowTexts()) // not reordered
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick()
        waitForFile(files[0], "- [ ] milk\n- [ ] bread\n- [ ] cheese\n")
    }

    // ---- F. Tabs ---------------------------------------------------------------------------------------------

    @Test fun eachTabKeepsItsUndoHistory() {
        val files = seed("One", "Two")
        launch()
        waitForRows(listOf("One"))
        rule.typeInLastRow("!")
        waitForFile(files[0], "One!\n")
        rule.onAllNodes(hasTestTag("tab") and hasText("Two")).onFirst().performClick()
        waitForRows(listOf("Two"))
        rule.onAllNodes(hasTestTag("tab") and hasText("One!")).onFirst().performClick()
        waitForRows(listOf("One!"))
        rule.formatButton("Undo").assertIsEnabled()
        rule.formatButton("Undo").performClick()
        waitFor("rows [One] after Undo", { "rows=${rule.rowTexts()} undo=${buttonEnabled("Undo")} redo=${buttonEnabled("Redo")}" }) { rule.rowTexts() == listOf("One") }
        rule.waitUntil(timeoutMillis = 8_000) { files[0].readText().trimEnd() == "One" }
        assertEquals(listOf("One", "Two"), tabTitles())
    }

    // ---- G. Draft --------------------------------------------------------------------------------------------

    @Test fun aBlankPageIsNotAFileUntilThereIsRealContent() {
        launch()
        waitForRows(listOf(""))
        rule.row(0).performClick() // the caret is placed in the empty paragraph
        rule.typeInLastRow("   ")
        Thread.sleep(1_500) // longer than the autosave delay
        assertTrue(mdFiles().isEmpty())
        rule.typeInLastRow("x")
        rule.waitUntil(timeoutMillis = 8_000) { mdFiles().size == 1 }
        assertEquals("x\n", mdFiles().single().readText().trimStart())
    }

    // ---- H. External Markdown --------------------------------------------------------------------------------

    @Test fun anExternalMarkdownFileIsShownFormattedAndEditedInPlace() {
        val dir = File(app.cacheDir, "share/rich-test").apply { mkdirs() }
        val file = File(dir, "Plan.md").apply { writeText("# Plan\n\n- [ ] one\n") }
        val uri: Uri = FileProvider.getUriForFile(app, ShareHelper.authority(app), file)
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent) // not closed: with singleTask, close() waits 45 s (see ExternalFlowTest)
        waitForRows(listOf("Plan", "one"))
        rule.onAllNodes(hasTestTag("checkbox")).onFirst().performClick()
        rule.waitUntil(timeoutMillis = 8_000) { file.readText() == "# Plan\n\n- [x] one\n" }
    }
}
