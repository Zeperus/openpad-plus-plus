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
 * Folders: filing a note into a new folder; the folder and the filing survive a restart.
 */
@RunWith(AndroidJUnit4::class)
class FoldersUiTest {
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

    @Test fun aNoteCanBeFiledInANewFolderAndStaysThereAfterARestart() {
        val file = t.seed("Plan\n\ntext\n")[0]
        launch()
        rule.waitForRowTexts(listOf("Plan", "text"))
        rule.overflow("Move to folder…")
        rule.onNodeWithTag("move-new-folder").performClick()
        rule.onNodeWithTag("name-field").performTextInput("Work")
        rule.onNodeWithText("Create").performClick()
        rule.waitFor("the note filed in the folder", { "" }) { runBlocking { app.repository.listNotes().single().folderId } != null }
        assertEquals(listOf("Work"), runBlocking { app.repository.listFolders().map { it.name } })
        assertEquals("Plan\n\ntext\n", file.readText()) // the file itself is untouched
        restart()
        rule.waitForRowTexts(listOf("Plan", "text"))
        openDrawer()
        rule.waitFor("the folder in the drawer", { "" }) { rule.tagCount("folder") == 1 }
        rule.onNodeWithText("Work").assertExists()
        rule.onAllNodes(hasText("Plan") and androidx.compose.ui.test.hasClickAction() and !hasTestTag("tab") and !hasTestTag("title")).onFirst().assertExists()
        assertEquals(runBlocking { app.repository.listFolders().single().id }, runBlocking { app.repository.listNotes().single().folderId })
    }
}
