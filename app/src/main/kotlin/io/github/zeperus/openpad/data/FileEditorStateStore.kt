package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.EditorStateStore
import io.github.zeperus.openpad.editor.PersistedEditorState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `editor-state.json`: caret and recent undo history per open note. Disposable like the session: a damaged or unreadable file
 * means "nothing remembered", an entry that cannot be read is skipped, and writes are atomic.
 */
class FileEditorStateStore(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EditorStateStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PersistedEditorState.serializer())

    override suspend fun load(): Map<String, PersistedEditorState> = withContext(dispatcher) {
        mutex.withLock {
            try {
                json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    override suspend fun save(states: Map<String, PersistedEditorState>) = withContext(dispatcher) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            if (states.isEmpty()) {
                file.delete()
                Unit
            } else {
                AtomicFiles.writeText(file, json.encodeToString(serializer, states))
            }
        }
    }
}
