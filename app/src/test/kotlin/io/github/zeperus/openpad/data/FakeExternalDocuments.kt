package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.ExternalDocuments
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import kotlinx.coroutines.delay

/** An in-memory document provider for tests. */
class FakeExternalDocuments : ExternalDocuments {
    val files = HashMap<String, ByteArray>()
    val names = HashMap<String, String>()
    val readOnly = HashSet<String>()
    val writes = ArrayList<String>()

    /** While set, writing stores only this many bytes (a crash/partial write). */
    var truncateWritesTo: Int? = null
    var failWrites = false
    var failReads = false
    var hang = false

    fun put(uri: String, text: String, name: String? = null, bytes: ByteArray? = null) {
        files[uri] = bytes ?: text.toByteArray(Charsets.UTF_8)
        if (name != null) names[uri] = name
    }

    fun text(uri: String) = String(files.getValue(uri), Charsets.UTF_8)

    private suspend fun gate() {
        if (hang) delay(Long.MAX_VALUE)
    }

    override suspend fun read(uri: String, maxBytes: Int): ByteArray {
        gate()
        if (failReads) throw NoteSourceUnavailableException("no access")
        val bytes = files[uri] ?: throw NoteSourceUnavailableException("missing")
        if (bytes.size > maxBytes) throw NoteSourceUnavailableException("too large")
        return bytes
    }

    override suspend fun write(uri: String, bytes: ByteArray) {
        gate()
        if (failWrites || uri in readOnly) throw NoteSourceUnavailableException("cannot write")
        writes += uri
        files[uri] = truncateWritesTo?.let { bytes.copyOf(minOf(it, bytes.size)) } ?: bytes
    }

    override suspend fun displayName(uri: String): String? = names[uri]

    override suspend fun isWritable(uri: String): Boolean = uri !in readOnly
}
