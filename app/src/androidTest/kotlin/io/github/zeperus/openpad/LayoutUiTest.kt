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
 * Window width: a wide window shows the sidebar next to the editor, a narrow one keeps the slide-in drawer.
 */
@RunWith(AndroidJUnit4::class)
class LayoutUiTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val t = TestApp(app)

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    @After fun cleanUp() {
        scenario?.close()
        runCatching { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
    }

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
