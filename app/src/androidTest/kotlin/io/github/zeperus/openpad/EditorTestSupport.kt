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
import androidx.compose.ui.test.performTextInputSelection

/**
 * Helpers for the rich editor. Consecutive paragraphs, headings and list items share **one** text field ("segment"); a rule or a
 * table splits the note into several. [rowTexts] lists the lines of all fields (a soft line break inside a paragraph counts as a
 * line, as it does on screen).
 */
internal fun ComposeTestRule.fields(): SemanticsNodeInteractionCollection = onAllNodes(hasTestTag("segment"))

/** The text field that holds the rows (the first one: most notes are a single segment). */
internal fun ComposeTestRule.field(index: Int = 0): SemanticsNodeInteraction = fields()[index]

/** Legacy name: the field that holds the row. There is no longer one field per row. */
internal fun ComposeTestRule.row(@Suppress("UNUSED_PARAMETER") index: Int = 0): SemanticsNodeInteraction = field(0)

internal fun ComposeTestRule.editorRows(): SemanticsNodeInteractionCollection = fields()

internal fun ComposeTestRule.fieldTexts(): List<String> = fields().fetchSemanticsNodes().map { node ->
    (node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "").removePrefix(FIELD_MARKER)
}

/** Every field starts with an invisible marker (see FIELD_PREFIX in SegmentField.kt); the tests talk in the visible text. */
internal const val FIELD_MARKER = "\u200B"

/** The text of every line, top to bottom. */
internal fun ComposeTestRule.rowTexts(): List<String> = fieldTexts().flatMap { it.split("\n") }

/** The caret to [offset] characters into the line [row] (of the first field). The field keeps its focus if it has it. */
internal fun ComposeTestRule.placeCaret(row: Int, offset: Int, fieldIndex: Int = 0) {
    val lines = fieldTexts()[fieldIndex].split("\n")
    val at = lines.take(row).sumOf { it.length + 1 } + offset + 1
    field(fieldIndex).performTextInputSelection(androidx.compose.ui.text.TextRange(at))
}

internal fun ComposeTestRule.selectionRange(fieldIndex: Int = 0): androidx.compose.ui.text.TextRange? =
    field(fieldIndex).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)
        ?.let { androidx.compose.ui.text.TextRange(maxOf(0, it.start - 1), maxOf(0, it.end - 1)) }

internal fun ComposeTestRule.typeInLastRow(text: String) {
    fields().onLast().performTextInput(text)
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

    fun setClipboard(text: String) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("test", text))
        }
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
