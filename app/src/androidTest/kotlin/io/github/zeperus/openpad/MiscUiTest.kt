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
 * Remembered editor state across a restart (caret position, undo history) and what happens to that history when the
 * file was changed outside openPad++.
 */
@RunWith(AndroidJUnit4::class)
class MiscUiTest {
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

    private fun editorStateFile() = File(t.root, "editor-state.json")

    private fun selection(): TextRange? = rule.selectionRange(0)

    @Test fun theCaretComesBackAfterARestart() {
        t.seed("Hello world\n")
        launch()
        rule.waitForRowTexts(listOf("Hello world"))
        // a caret inside "world" (Android drops a range when the field loses focus)
        rule.placeCaret(0, 7)
        rule.waitFor("the caret", { "${selection()}" }) { selection() == TextRange(7, 7) }
        rule.waitFor("the remembered state", { "" }) { editorStateFile().isFile && editorStateFile().readText().contains("\"start\":7") }
        restart()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.waitFor("the caret", { "${selection()}" }) { selection() == TextRange(7, 7) } // not the end of the text (11)
    }

    @Test fun undoStillWorksAfterARestart() {
        val file = t.seed("Hello world\n")[0]
        launch()
        rule.waitForRowTexts(listOf("Hello world"))
        rule.typeInLastRow("!")
        rule.waitFor("the file", { file.readText() }) { file.readText() == "Hello world!\n" }
        rule.waitFor("the remembered history", { "" }) { editorStateFile().isFile && editorStateFile().readText().contains("Hello world\\n") }
        restart()
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
}
