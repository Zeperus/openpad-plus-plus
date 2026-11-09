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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search notes by title and content, and find in note: counting the matches and stepping through them.
 */
@RunWith(AndroidJUnit4::class)
class SearchUiTest {
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
}
