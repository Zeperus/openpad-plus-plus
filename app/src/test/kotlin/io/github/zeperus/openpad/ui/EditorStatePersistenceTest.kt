package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.domain.EditorStateStore
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.EditorSession
import io.github.zeperus.openpad.editor.EditorStateCodec
import io.github.zeperus.openpad.editor.PersistedEditorState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorStatePersistenceTest {
    private val a = NoteId("a")
    private val b = NoteId("b")

    /** Keeps the saved states in memory; the first [failures] saves throw. Records the virtual time of each successful save. */
    private class MemoryStore(private val now: () -> Long) : EditorStateStore {
        var stored: Map<String, PersistedEditorState> = emptyMap()
        var failures = 0
        val saveTimes = ArrayList<Long>()

        override suspend fun load(): Map<String, PersistedEditorState> = stored

        override suspend fun save(states: Map<String, PersistedEditorState>) {
            if (failures > 0) {
                failures--
                throw IOException("disk full")
            }
            stored = states
            saveTimes += now()
        }
    }

    private fun TestScope.persistence(store: EditorStateStore, live: Map<NoteId, EditorSession>): EditorStatePersistence =
        EditorStatePersistence(store, backgroundScope, StandardTestDispatcher(testScheduler), clock = { currentTime })
            .also { it.liveSessions = { id -> live[id] } }

    private fun session(markdown: String): EditorSession = EditorSession(EditorDocument.fromMarkdown(markdown))

    @Test fun `repeated marks within the idle time are written once, after the idle time`() = runTest {
        val store = MemoryStore { currentTime }
        val p = persistence(store, mapOf(a to session("alpha")))
        repeat(8) { p.markDirty(a); advanceTimeBy(100); runCurrent() } // marks at 0 .. 700 ms
        assertTrue(store.saveTimes.isEmpty())
        advanceTimeBy(600); runCurrent() // 1400 ms: still within the idle time after the last mark
        assertTrue(store.saveTimes.isEmpty())
        advanceTimeBy(200); runCurrent() // 1600 ms
        assertEquals(listOf(1_500L), store.saveTimes)
    }

    @Test fun `continuous marks are written at the latest after the max wait`() = runTest {
        val store = MemoryStore { currentTime }
        val p = persistence(store, mapOf(a to session("alpha")))
        repeat(60) { p.markDirty(a); advanceTimeBy(200); runCurrent() } // 12 s with a mark every 200 ms
        assertTrue(store.saveTimes.isNotEmpty())
        assertTrue(store.saveTimes.first() in 5_000L..5_200L)
        assertTrue(store.saveTimes.size >= 2)
    }

    @Test fun `persist writes at once and nothing is written again when nothing changed`() = runTest {
        val store = MemoryStore { currentTime }
        val p = persistence(store, mapOf(a to session("alpha")))
        p.markDirty(a)
        p.persist()
        assertEquals(listOf(0L), store.saveTimes)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, store.saveTimes.size)
    }

    @Test fun `closed notes are left out of the next write and do not come back when marked`() = runTest {
        val store = MemoryStore { currentTime }
        val live = mapOf(a to session("alpha"), b to session("beta"))
        val p = persistence(store, live)
        p.markDirty(a); p.markDirty(b); p.persist()
        assertEquals(setOf(a.value, b.value), store.stored.keys)

        p.retainOnly(setOf(a))
        p.persist()
        assertEquals(setOf(a.value), store.stored.keys)

        p.markDirty(b) // its session still exists, but the tab is closed
        p.persist()
        assertEquals(setOf(a.value), store.stored.keys)
    }

    @Test fun `a remembered session comes back only for exactly the same text`() = runTest {
        val store = MemoryStore { currentTime }
        val s = session("alpha\n\nbeta\n")
        s.onText(s.doc.rows[0].id, "alpha!", 6)
        val p = persistence(store, mapOf(a to s))
        p.markDirty(a)
        p.persist()

        val restored = requireNotNull(p.restore(a, s.markdown()))
        assertTrue(restored.undo())
        assertEquals("alpha\n\nbeta\n", restored.markdown())
        assertNull(p.restore(a, "alpha\n\nbeta\n!")) // the text changed meanwhile
        assertNull(p.restore(b, s.markdown())) // another note
    }

    @Test fun `load brings back the stored states, and an unreadable store leaves nothing remembered`() = runTest {
        val s = session("alpha")
        val store = MemoryStore { currentTime }.apply { stored = mapOf(a.value to EditorStateCodec.capture(s)) }
        val p = persistence(store, emptyMap())
        p.load()
        assertNotNull(p.restore(a, "alpha"))

        val unreadable = object : EditorStateStore {
            override suspend fun load(): Map<String, PersistedEditorState> = throw IOException("unreadable")
            override suspend fun save(states: Map<String, PersistedEditorState>) = Unit
        }
        val q = persistence(unreadable, emptyMap())
        q.load()
        assertNull(q.restore(a, "alpha"))
    }

    @Test fun `a failed write is ignored and the next one is written`() = runTest {
        val store = MemoryStore { currentTime }.apply { failures = 1 }
        val p = persistence(store, mapOf(a to session("alpha")))
        p.markDirty(a)
        p.persist()
        assertTrue(store.saveTimes.isEmpty())
        p.markDirty(a)
        p.persist()
        assertEquals(listOf(0L), store.saveTimes)
        assertEquals(setOf(a.value), store.stored.keys)
    }
}
