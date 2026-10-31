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
 * Checklists: a new checklist is a smart checklist (checked items move to the end), and its order and checkboxes
 * survive a restart.
 */
@RunWith(AndroidJUnit4::class)
class ChecklistUiTest {
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
}
