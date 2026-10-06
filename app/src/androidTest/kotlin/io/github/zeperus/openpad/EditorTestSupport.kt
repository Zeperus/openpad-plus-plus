package io.github.zeperus.openpad

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        .also { runCatching { it.performScrollTo() } } // the bar scrolls sideways: bring the button into view first

// ---- Shared helpers for the Alpha 3 tests ------------------------------------------------------------------------------

internal class TestApp(val app: OpenPadApplication) {
    val root = java.io.File(app.filesDir, "openpad")
    val notesDir = java.io.File(root, "notes")

    /** Notes from an earlier run; [active] is open at start (session mode "resume"). Returns the note files. */
    fun seed(vararg markdown: String, active: Int = 0): List<java.io.File> = kotlinx.coroutines.runBlocking {
        val ids = markdown.map { app.repository.createNote(it).id }
        app.sessionStore.save(io.github.zeperus.openpad.domain.PersistedSession(ids.map { it.value }, ids[active].value))
        app.settings.setStartupMode(io.github.zeperus.openpad.domain.StartupMode.ResumeSession)
        ids.map { java.io.File(notesDir, it.value + ".md") }
    }

    fun mdFiles() = notesDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().toList()

    fun clipboardText(): String? {
        var text: String? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            text = manager.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        }
        return text
    }

    fun clearClipboard() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }
}

internal fun ComposeTestRule.waitFor(what: String, actual: () -> String = { "" }, timeoutMs: Long = 8_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("$what; found: ${actual()}")
        Thread.sleep(100)
        waitForIdle()
    }
}

internal fun ComposeTestRule.waitForRowTexts(expected: List<String>) =
    waitFor("rows $expected", { rowTexts().toString() }) { rowTexts() == expected }

internal fun ComposeTestRule.tagCount(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

internal fun ComposeTestRule.overflow(item: String) {
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText(item).performScrollTo().performClick()
}

/** An item of the overflow menu's second page (select / copy / paste). */
internal fun ComposeTestRule.overflowEdit(item: String) {
    onNodeWithContentDescription("More options").performClick()
    onNodeWithText("Select, copy, paste…").performScrollTo().performClick()
    onNodeWithText(item).performScrollTo().performClick()
}

internal fun ComposeTestRule.barButton(description: String): SemanticsNodeInteraction =
    onNode(androidx.compose.ui.test.hasContentDescription(description) and androidx.compose.ui.test.hasClickAction())
        .also { runCatching { it.performScrollTo() } }
