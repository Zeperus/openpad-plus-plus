package io.github.zeperus.openpad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Several lines become list items: the format bar's Checklist, Bulleted list and Numbered list; the list markers of
 * the source text are cleaned.
 */
@RunWith(AndroidJUnit4::class)
class ListConversionUiTest {
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

    private fun focusField() {
        rule.field().performTouchInput { click(Offset(width / 2f, 4f * density)) }
        rule.waitForIdle()
    }

    private fun waitForCheckboxes(n: Int) = rule.waitFor("$n checkboxes", { "${rule.tagCount("checkbox")}" }) { rule.tagCount("checkbox") == n }

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
}
