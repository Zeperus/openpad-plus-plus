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
 * Blocks that are not plain text: tables (shown as tables, editable as source and back) and images (placeholders; the
 * app asks for no internet permission).
 */
@RunWith(AndroidJUnit4::class)
class RawBlocksUiTest {
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
}
