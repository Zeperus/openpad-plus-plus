package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextInput

/** Helpers for the rich editor: one text field per row, tagged "row", whose text starts with an invisible marker. */
internal const val ZWSP = "​"

internal fun ComposeTestRule.editorRows(): SemanticsNodeInteractionCollection = onAllNodes(hasTestTag("row"))

internal fun ComposeTestRule.row(index: Int): SemanticsNodeInteraction = editorRows()[index]

/** The text of every row as the user sees it (without the invisible marker), top to bottom. */
internal fun ComposeTestRule.rowTexts(): List<String> = editorRows().fetchSemanticsNodes().map { node ->
    (node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "").removePrefix(ZWSP)
}

/** Types at the caret of the last row (an untouched row has its caret at the end). */
internal fun ComposeTestRule.typeInLastRow(text: String) {
    editorRows().onLast().performTextInput(text)
}

internal fun ComposeTestRule.formatButton(description: String): SemanticsNodeInteraction =
    onNode(androidx.compose.ui.test.hasContentDescription(description) and androidx.compose.ui.test.hasClickAction())
