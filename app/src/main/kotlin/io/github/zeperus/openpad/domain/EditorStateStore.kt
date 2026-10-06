package io.github.zeperus.openpad.domain

import io.github.zeperus.openpad.editor.PersistedEditorState

/** Persistence of per-note editor state (caret, bounded undo history), apart from the notes and never inside their Markdown. */
interface EditorStateStore {
    /** Note id -> state. Never throws: missing or damaged data means "nothing remembered". */
    suspend fun load(): Map<String, PersistedEditorState>

    suspend fun save(states: Map<String, PersistedEditorState>)

    /** Remembers nothing (tests, previews). */
    object None : EditorStateStore {
        override suspend fun load(): Map<String, PersistedEditorState> = emptyMap()
        override suspend fun save(states: Map<String, PersistedEditorState>) = Unit
    }
}
