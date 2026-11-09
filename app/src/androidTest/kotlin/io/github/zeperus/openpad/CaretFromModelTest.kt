package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A caret that the *model* moves while the document stays the same must still reach the text field. Since Alpha 10 a field no
 * longer recomposes on every published editor state (that made each caret move as expensive as typing); it learns of the model's
 * caret through `cursorEpoch` alone, and this is the path no other test walks.
 */
@RunWith(AndroidJUnit4::class)
class CaretFromModelTest {
    private val t = TestApp(ApplicationProvider.getApplicationContext())

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    private val belowTheText = SemanticsMatcher("the area below the last row") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == "Continue writing at the end"
    }

    @Test fun aTapBelowTheTextPutsTheCaretAtTheEnd() {
        t.seed("First line\n\nSecond line")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForRowTexts(listOf("First line", "Second line"))

        rule.field().performClick() // the field has the focus
        rule.placeCaret(row = 0, offset = 2)
        rule.waitFor("the caret in the first line", { "${rule.selectionRange()}" }) { rule.selectionRange() == TextRange(2) }

        rule.onNode(belowTheText).performClick() // the model moves the caret; the document is the same one

        val end = "First line\nSecond line".length
        rule.waitFor("the caret at the end of the note", { "${rule.selectionRange()}" }) { rule.selectionRange() == TextRange(end) }
    }
}
