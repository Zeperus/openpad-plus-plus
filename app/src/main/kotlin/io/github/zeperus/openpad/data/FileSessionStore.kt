package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.PersistedSession
import io.github.zeperus.openpad.domain.SessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * The open-document session in its own small file (`session.json`), deliberately separate from `index.json` and
 * from the notes: session data is disposable, so a damaged file means "no open notes", never lost text.
 * Writes are atomic (temp file + fsync + rename), reads accept anything and never throw.
 */
class FileSessionStore(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionStore {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }

    override suspend fun load(): PersistedSession = withContext(dispatcher) {
        mutex.withLock {
            try {
                parse(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                PersistedSession() // missing, unreadable or damaged: start without a session
            }
        }
    }

    override suspend fun save(session: PersistedSession) = withContext(dispatcher) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put("version", VERSION)
                putJsonArray("noteIds") { session.noteIds.forEach { add(JsonPrimitive(it)) } }
                session.activeNoteId?.let { put("activeNoteId", it) }
            }
            AtomicFiles.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }

    /** Lenient on purpose: wrong types or unknown fields are skipped instead of invalidating the whole file. */
    private fun parse(text: String): PersistedSession {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return PersistedSession()
        val ids = (root["noteIds"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val active = (root["activeNoteId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return PersistedSession(ids, active)
    }

    private companion object {
        const val VERSION = 1
    }
}
