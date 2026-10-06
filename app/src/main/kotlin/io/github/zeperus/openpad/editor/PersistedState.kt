package io.github.zeperus.openpad.editor

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A caret/selection as it can be stored: the index of the row (ids are not stable across loads) and the offsets in it. */
@Serializable
data class PersistedCursor(val row: Int, val start: Int, val end: Int)

/** One undo/redo step: the Markdown of the document at that point (not the whole editor state - small and robust) and the caret. */
@Serializable
data class PersistedStep(val markdown: String, val cursor: PersistedCursor? = null)

/**
 * What is remembered about an open note between runs, kept apart from the note and never inside its Markdown. It is only valid
 * for exactly the text it was made for: [fingerprint] is that text's hash, so a note that changed in the meantime (edited
 * elsewhere, restored from a backup) silently gets no caret and no history instead of a wrong one.
 */
@Serializable
data class PersistedEditorState(
    val fingerprint: String,
    val cursor: PersistedCursor? = null,
    /** Oldest first. The state *before* each of the last edits. */
    val undo: List<PersistedStep> = emptyList(),
    val redo: List<PersistedStep> = emptyList(),
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Turning an [EditorSession] into a [PersistedEditorState] and back. Bounded in steps and size; every failure means "start fresh". */
object EditorStateCodec {
    const val MAX_STEPS = 25
    /** Total characters of Markdown kept in history steps of one note. */
    const val MAX_HISTORY_CHARS = 400_000
    /** A document bigger than this is remembered with its caret only (no history). */
    const val MAX_DOCUMENT_CHARS = 150_000

    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    fun capture(session: EditorSession): PersistedEditorState {
        val markdown = session.markdown()
        val withHistory = markdown.length <= MAX_DOCUMENT_CHARS
        val budget = intArrayOf(MAX_HISTORY_CHARS)
        fun steps(snapshots: List<Snapshot>): List<PersistedStep> {
            val out = ArrayList<PersistedStep>()
            for (s in snapshots.asReversed().take(MAX_STEPS)) { // newest first: the oldest are dropped when the budget runs out
                val text = s.doc.toMarkdown()
                if (text.length > budget[0]) break
                budget[0] -= text.length
                out += PersistedStep(text, cursorOf(s.doc, s.cursor))
            }
            return out.asReversed()
        }
        return PersistedEditorState(
            fingerprint = fingerprint(markdown),
            cursor = cursorOf(session.doc, session.cursor),
            undo = if (withHistory) steps(session.history.undoSnapshots()) else emptyList(),
            redo = if (withHistory) steps(session.history.redoSnapshots()) else emptyList(),
        )
    }

    /**
     * A session for [markdown] with the remembered caret and history - or null if [state] is for different text, a different
     * format version, or cannot be applied. Never throws.
     */
    fun restore(markdown: String, state: PersistedEditorState, clock: () -> Long = System::currentTimeMillis): EditorSession? = try {
        if (state.version != PersistedEditorState.VERSION || state.fingerprint != fingerprint(markdown)) null else {
            val session = EditorSession(EditorDocument.fromMarkdown(markdown), clock)
            fun snapshots(steps: List<PersistedStep>) = steps.takeLast(MAX_STEPS).map { s ->
                val doc = EditorDocument.fromMarkdown(s.markdown)
                Snapshot(doc, s.cursor?.let { cursorIn(doc, it) })
            }
            session.history.restore(snapshots(state.undo), snapshots(state.redo))
            state.cursor?.let { session.moveCursor(cursorIn(session.doc, it)) }
            session
        }
    } catch (e: Exception) {
        null
    }

    private fun cursorOf(doc: EditorDocument, cursor: Cursor?): PersistedCursor? {
        cursor ?: return null
        val index = doc.indexOf(cursor.rowId)
        return if (index < 0) null else PersistedCursor(index, cursor.start, cursor.end)
    }

    private fun cursorIn(doc: EditorDocument, c: PersistedCursor): Cursor {
        val row = doc.rows[c.row.coerceIn(0, doc.rows.lastIndex)]
        val len = row.text.length
        return Cursor(row.id, c.start.coerceIn(0, len), c.end.coerceIn(0, len))
    }
}
