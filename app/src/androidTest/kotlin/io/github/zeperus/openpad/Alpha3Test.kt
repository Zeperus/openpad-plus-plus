package io.github.zeperus.openpad

import android.app.LocaleManager
import android.content.pm.PackageManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zeperus.openpad.domain.StartupMode
import io.github.zeperus.openpad.ui.NotesScreen
import io.github.zeperus.openpad.ui.NotesViewModel
import io.github.zeperus.openpad.ui.theme.OpenPadTheme
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
 * Alpha 3 on a real runtime: New checklist, tables, images, remembered caret and undo, search, folders, German, wide screens.
 * "Restart" means: the screen is closed and a new one opened over the same files (a new process cannot be started inside a test;
 * the view model, which is what reads the files and the remembered state, is created anew).
 */
@RunWith(AndroidJUnit4::class)
class Alpha3Test {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val t = TestApp(app)

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    @After fun cleanUp() {
        scenario?.close()
        runCatching { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun restart() {
        scenario?.close()
        launch()
    }

    private fun openDrawer() {
        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
    }

    // ---- E. New checklist ------------------------------------------------------------------------------------

    @Test fun aNewChecklistIsASmartChecklistThatKeepsItsOrderAcrossARestart() {
        runBlocking { app.settings.setStartupMode(StartupMode.ResumeSession) }
        launch()
        openDrawer()
        rule.onNodeWithText("+ New Checklist").performClick()
        rule.waitFor("one empty task", { "boxes=${rule.tagCount("checkbox")}" }) { rule.tagCount("checkbox") == 1 }
        rule.typeInLastRow("A")
        rule.typeInLastRow("\n")
        rule.typeInLastRow("B")
        rule.typeInLastRow("\n")
        rule.typeInLastRow("C")
        rule.waitForRowTexts(listOf("A", "B", "C"))
        rule.waitFor("the note file", { t.mdFiles().toString() }) { t.mdFiles().size == 1 && t.mdFiles()[0].readText() == "- [ ] A\n- [ ] B\n- [ ] C\n" }
        rule.onAllNodes(hasTestTag("checkbox"))[1].performClick() // B is done: it goes to the end
        rule.waitForRowTexts(listOf("A", "C", "B"))
        rule.waitFor("sorted file", { t.mdFiles()[0].readText() }) { t.mdFiles()[0].readText() == "- [ ] A\n- [ ] C\n- [x] B\n" }
        rule.onAllNodes(hasTestTag("checkbox"))[2].performClick() // and back: the end of the unchecked group
        rule.waitFor("unchecked again", { t.mdFiles()[0].readText() }) { t.mdFiles()[0].readText() == "- [ ] A\n- [ ] C\n- [ ] B\n" }
        rule.onAllNodes(hasTestTag("checkbox"))[0].performClick() // A done
        rule.waitFor("A at the end", { t.mdFiles()[0].readText() }) { t.mdFiles()[0].readText() == "- [ ] C\n- [ ] B\n- [x] A\n" }
        restart()
        rule.waitForRowTexts(listOf("C", "B", "A"))
        assertEquals(3, rule.tagCount("checkbox"))
        assertTrue(runBlocking { app.repository.listNotes().single().smartChecklist })
    }

    // ---- F. tables -------------------------------------------------------------------------------------------

    @Test fun aTableIsShownAsATableNotAsSource() {
        t.seed("Intro\n\n| Name | Qty |\n|:-----|----:|\n| Milk | 2 |\n| Bread | 1 |\n\nEnd\n")
        launch()
        rule.waitFor("the table", { "rows=${rule.rowTexts()}" }) { rule.tagCount("table") == 1 }
        assertEquals(listOf("Intro", "End"), rule.rowTexts()) // the table has no text field of its own
        rule.onNodeWithText("Milk").assertExists()
        rule.onNodeWithText("Qty").assertExists()
        assertEquals(0, rule.onAllNodes(hasText("|---", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun editSourceOpensTheTableAsTextAndDoneShowsItAgain() {
        val file = t.seed("| a | b |\n|---|---|\n| 1 | 2 |\n")[0]
        launch()
        rule.waitFor("the table", { "" }) { rule.tagCount("table") == 1 }
        rule.onNodeWithTag("edit-source").performClick()
        rule.waitFor("the source", { rule.rowTexts().toString() }) { rule.rowTexts().any { it.contains("|---|---|") } }
        rule.onNodeWithText("Done").performClick()
        rule.waitFor("the table again", { "" }) { rule.tagCount("table") == 1 }
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |\n", file.readText()) // looking at it did not change a byte
    }

    // ---- G. images -------------------------------------------------------------------------------------------

    @Test fun aRemoteImageIsAPlaceholderAndTheAppHasNoInternetPermission() {
        t.seed("![a cat](https://example.org/cat.png)\n")
        launch()
        rule.waitFor("the placeholder", { "" }) { rule.tagCount("image-placeholder") == 1 }
        assertEquals(0, rule.tagCount("image"))
        rule.onNodeWithText("a cat").assertExists()
        rule.onNodeWithText("example.org").assertExists()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
    }

    @Test fun anImageThatCannotBeLoadedIsAStablePlaceholder() {
        t.seed("![gone](content://no.such.provider/image/1)\n\ntext\n")
        launch()
        rule.waitFor("the placeholder", { "" }) { rule.tagCount("image-placeholder") == 1 }
        rule.waitForIdle()
        assertEquals(1, rule.tagCount("image-placeholder"))
        assertEquals(listOf("text"), rule.rowTexts())
    }

    // ---- H, I. remembered caret and undo ------------------------------------------------------------------------

    private fun editorStateFile() = File(t.root, "editor-state.json")

    @Test fun theCaretComesBackAfterARestart() {
        t.seed("Hello world\n")
        launch()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.row(0).performTextInputSelection(TextRange(7, 9)) // "wo" (the field text starts with an invisible marker)
        rule.waitFor("the remembered state", { "" }) { rule.waitForIdle(); true }
        restart()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.waitFor("the caret", { "${rule.row(0).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)}" }) {
            rule.row(0).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange) == TextRange(7, 9)
        }
    }

    @Test fun undoStillWorksAfterARestart() {
        val file = t.seed("Hello world\n")[0]
        launch()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.typeInLastRow("!")
        rule.waitFor("the file", { file.readText() }) { file.readText() == "Hello world!\n" }
        restart() // the screen is closed first: pending state is written when it stops
        rule.waitForRowTexts(listOf("Hello world!"))
        rule.waitFor("undo available", { "" }) { rule.formatButton("Undo").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
        rule.formatButton("Undo").performClick()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.waitFor("the original file", { file.readText() }) { file.readText() == "Hello world\n" }
    }

    @Test fun anEditedOutsideNoteForgetsItsOldHistory() {
        val file = t.seed("Hello world\n")[0]
        launch()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.typeInLastRow("!")
        rule.waitFor("the file", { file.readText() }) { file.readText() == "Hello world!\n" }
        scenario?.close()
        file.writeText("Changed outside\n")
        launch()
        rule.waitForRowTexts(listOf("Changed outside"))
        rule.waitFor("undo not available", { "" }) { rule.formatButton("Undo").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null }
    }

    // ---- K. search --------------------------------------------------------------------------------------------

    @Test fun searchFindsNotesByTitleAndContentAndOpensThemWithFind() {
        t.seed("Gartenarbeit\n\nÄpfel pflücken\n", "Einkauf\n\nMilch und Brot\n")
        launch()
        rule.waitFor("tabs", { "" }) { rule.tagCount("tab") >= 2 }
        rule.onNodeWithContentDescription("Search notes").performClick()
        rule.onNodeWithTag("search-field").performTextInput("milch")
        rule.waitFor("one result", { "results=${rule.tagCount("search-result")}" }) { rule.tagCount("search-result") == 1 }
        rule.onNodeWithText("Einkauf").assertExists()
        rule.onAllNodes(hasTestTag("search-result")).onFirst().performClick()
        rule.waitFor("the find bar", { "" }) { rule.tagCount("find-bar") == 1 }
        rule.waitFor("one match", { "" }) { rule.onNodeWithTag("find-count").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == "1 of 1" }
    }

    @Test fun findInNoteCountsAndSteps() {
        t.seed("one two\n\nTWO three\n\ntwo\n")
        launch()
        rule.waitForRowTexts(listOf("one two", "TWO three", "two"))
        rule.overflow("Find in note")
        rule.onNodeWithTag("find-field").performTextInput("two")
        rule.waitFor("3 matches", { "" }) { rule.onNodeWithTag("find-count").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == "1 of 3" }
        rule.onNodeWithContentDescription("Next result").performClick()
        rule.waitFor("2 of 3", { "" }) { rule.onNodeWithTag("find-count").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == "2 of 3" }
    }

    // ---- L. folders -------------------------------------------------------------------------------------------

    @Test fun aNoteCanBeFiledInANewFolderAndStaysThereAfterARestart() {
        val file = t.seed("Plan\n\ntext\n")[0]
        launch()
        rule.waitForRowTexts(listOf("Plan", "text"))
        rule.overflow("Move to folder…")
        rule.onNodeWithTag("move-new-folder").performClick()
        rule.onNodeWithTag("name-field").performTextInput("Work")
        rule.onNodeWithText("Create").performClick()
        rule.waitFor("the folder", { "" }) { runBlocking { app.repository.listFolders().map { it.name } } == listOf("Work") }
        assertEquals("Plan\n\ntext\n", file.readText()) // the file itself is untouched
        restart()
        rule.waitForRowTexts(listOf("Plan", "text"))
        openDrawer()
        rule.waitFor("the folder in the drawer", { "" }) { rule.tagCount("folder") == 1 }
        rule.onNodeWithText("Work").assertExists()
        rule.onAllNodes(hasText("Plan") and androidx.compose.ui.test.hasClickAction() and !hasTestTag("tab")).onFirst().assertExists()
        assertEquals(runBlocking { app.repository.listFolders().single().id }, runBlocking { app.repository.listNotes().single().folderId })
    }

    // ---- J. German ---------------------------------------------------------------------------------------------

    @Test fun theUiIsGermanUnderAGermanLocale() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("de")
        launch()
        openDrawer()
        rule.onNodeWithText("+ Neue Notiz").assertExists()
        rule.onNodeWithText("+ Neue Checkliste").assertExists()
        rule.onNodeWithText("DATEIEN").assertExists()
        rule.onNodeWithText("PAPIERKORB").assertExists()
        rule.onNodeWithText("Datei öffnen …").assertExists()
    }

    // ---- M. wide screens ---------------------------------------------------------------------------------------

    private fun hostWide(width: Int, height: Int): NotesViewModel {
        val vm = NotesViewModel(app.repository, app.sessionStore, app.settings, app.appScope, app.editorStates)
        val s = ActivityScenario.launch(ComponentActivity::class.java)
        scenario = s
        s.onActivity { activity ->
            activity.setContent { OpenPadTheme { Box(Modifier.requiredSize(width.dp, height.dp)) { NotesScreen(vm) } } }
        }
        return vm
    }

    @Test fun aWideWindowShowsTheSidebarNextToTheEditor() {
        t.seed("Wide note\n")
        hostWide(1000, 700)
        rule.waitFor("the sidebar", { "" }) { rule.tagCount("sidebar") == 1 }
        rule.waitFor("the editor", { "" }) { rule.tagCount("editor") == 1 }
        val sidebar = rule.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
        val editor = rule.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot
        assertTrue("sidebar ${sidebar} must not overlap the editor ${editor}", sidebar.right <= editor.left + 1f)
        assertTrue(editor.width > 300f)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open navigation")).fetchSemanticsNodes().size)
    }

    @Test fun aNarrowWindowKeepsTheSlideInDrawer() {
        t.seed("Narrow note\n")
        hostWide(400, 700)
        rule.waitFor("the editor", { "" }) { rule.tagCount("editor") == 1 }
        assertEquals(0, rule.tagCount("sidebar"))
        rule.onNodeWithContentDescription("Open navigation").assertExists()
    }
}
