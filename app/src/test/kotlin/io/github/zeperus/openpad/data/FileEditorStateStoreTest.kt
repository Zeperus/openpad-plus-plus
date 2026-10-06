package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.editor.PersistedCursor
import io.github.zeperus.openpad.editor.PersistedEditorState
import io.github.zeperus.openpad.editor.PersistedStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileEditorStateStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "sub/editor-state.json")
    private fun store() = FileEditorStateStore(file, Dispatchers.IO)

    private val state = PersistedEditorState("abc", PersistedCursor(1, 2, 3), listOf(PersistedStep("old", PersistedCursor(0, 0, 0))))

    @Test fun `states round-trip`() = runBlocking {
        store().save(mapOf("n1" to state, "n2" to state.copy(fingerprint = "def")))
        val loaded = store().load()
        assertEquals(setOf("n1", "n2"), loaded.keys)
        assertEquals(state, loaded["n1"])
    }

    @Test fun `a missing or damaged file means nothing is remembered`() = runBlocking {
        assertTrue(store().load().isEmpty())
        file.parentFile!!.mkdirs()
        file.writeText("{{{ garbage")
        assertTrue(store().load().isEmpty())
        file.writeBytes(byteArrayOf(0, -1, -2, 7))
        assertTrue(store().load().isEmpty())
    }

    @Test fun `saving nothing removes the file`() = runBlocking {
        store().save(mapOf("n1" to state))
        assertTrue(file.isFile)
        store().save(emptyMap())
        assertFalse(file.exists())
    }

    @Test fun `unknown fields are ignored`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("""{"n1":{"fingerprint":"abc","future":true}}""")
        assertEquals("abc", store().load()["n1"]!!.fingerprint)
    }
}
