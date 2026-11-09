package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.domain.Autosaver
import io.github.zeperus.openpad.domain.EditorStateStore
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.editor.EditorSession
import io.github.zeperus.openpad.editor.EditorStateCodec
import io.github.zeperus.openpad.editor.EditorStateSnapshot
import io.github.zeperus.openpad.editor.PersistedEditorState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remembers caret and recent undo steps of the open notes in editor-state.json (disposable, never inside the notes).
 *
 * Everything that touches a live session or the state map runs on the caller's (main) thread. A write takes an immutable
 * snapshot of each dirty session there and encodes it on [encodeDispatcher]; writes are strictly ordered by [writes].
 */
class EditorStatePersistence(
    private val store: EditorStateStore,
    scope: CoroutineScope,
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    idleMillis: Long = 800,
    maxWaitMillis: Long = 5_000,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The live session of an open note. Read on the main thread only; the view model sets it once. */
    var liveSessions: (NoteId) -> EditorSession? = { null }

    /** Note id value -> remembered state. Only touched on the main thread. */
    private val states = HashMap<String, PersistedEditorState>()
    private val dirty = HashSet<NoteId>()

    /** The open notes as last reported by [retainOnly]; null until then, when every note counts as open. */
    private var open: Set<NoteId>? = null

    /** True when [states] changed since the last write. */
    private var unsaved = false

    private val writes = Mutex()

    /** Debounces the writes: idle time after the last change, at the latest [maxWaitMillis] after the first one. */
    private val autosaver = Autosaver(scope, idleMillis, maxWaitMillis, clock = clock, onError = {}) { persist() }

    /** Reads the stored states. A failed or damaged file means nothing is remembered. */
    suspend fun load() {
        try {
            states.putAll(store.load())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // nothing remembered
        }
    }

    /** The remembered session for exactly this [text] (same fingerprint), or null: nothing remembered, or the note changed since. */
    fun restore(id: NoteId, text: String): EditorSession? = states[id.value]?.let { EditorStateCodec.restore(text, it) }

    /**
     * The remembered state of a note as a value (null: nothing remembered). For callers that apply it themselves, e.g. off the
     * main thread; like [restore] it must be called on the main thread, and the state is only valid for the text it was made for.
     */
    fun stateOf(id: NoteId): PersistedEditorState? = states[id.value]

    /** The caret or undo history of an open note changed: it is written after an idle moment (see [Autosaver]). */
    fun markDirty(id: NoteId) {
        dirty += id
        autosaver.notifyChanged()
    }

    /** Forgets the notes that are no longer open (closed tabs); the next write leaves them out. */
    fun retainOnly(openIds: Set<NoteId>) {
        open = openIds
        if (states.keys.retainAll(openIds.mapTo(HashSet()) { it.value })) {
            unsaved = true
            autosaver.notifyChanged()
        }
    }

    /** Writes the dirty notes now, e.g. when the app goes to the background. Call it after the text was saved. */
    suspend fun persist(live: (NoteId) -> EditorSession? = liveSessions) {
        writes.withLock { writeLocked(live) }
    }

    /** Stops the background writes. Call [persist] first if something is still pending. */
    fun close() {
        autosaver.close()
    }

    private suspend fun writeLocked(live: (NoteId) -> EditorSession?) {
        val ids = dirty.toList()
        dirty.clear()
        val jobs = ids.mapNotNull { snapshotOf(it, live) } // main thread: immutable values only
        val encoded: List<Pair<NoteId, PersistedEditorState>> =
            if (jobs.isEmpty()) emptyList() else withContext(encodeDispatcher) { jobs.mapNotNull { encodeOf(it) } }
        for ((id, state) in encoded) {
            if (isOpen(id)) { // a tab closed while its state was being encoded must not come back
                states[id.value] = state
                unsaved = true
            }
        }
        if (!unsaved) return
        unsaved = false
        try {
            store.save(states.toMap())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // disposable: the next change writes it again
        }
    }

    private fun snapshotOf(id: NoteId, live: (NoteId) -> EditorSession?): Pair<NoteId, EditorStateSnapshot>? = try {
        live(id)?.let { id to EditorStateCodec.snapshot(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // as before: a note that cannot be captured keeps its old state
    }

    private fun encodeOf(job: Pair<NoteId, EditorStateSnapshot>): Pair<NoteId, PersistedEditorState>? = try {
        job.first to EditorStateCodec.encode(job.second)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Until [retainOnly] has been called every note counts as open. */
    private fun isOpen(id: NoteId): Boolean = open?.contains(id) ?: true
}
