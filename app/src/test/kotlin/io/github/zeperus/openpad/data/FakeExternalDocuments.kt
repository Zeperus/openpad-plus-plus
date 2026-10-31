package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.ExternalDocuments
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.ReadOnlyReason
import io.github.zeperus.openpad.domain.WriteAccess
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** An in-memory document provider for tests. Thread-safe, because blocking tests call it from real I/O threads. */
class FakeExternalDocuments : ExternalDocuments {
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val names: MutableMap<String, String> = ConcurrentHashMap()
    /** No write grant (the sender gave read access only). */
    val readOnly = HashSet<String>()

    /** The provider itself refuses writes. */
    val providerReadOnly = HashSet<String>()

    /** Write access cannot be determined. */
    val undetermined = HashSet<String>()
    /** The uri of every write call that reached the provider, in order. */
    val writes: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** While set, writing stores only this many bytes (a crash/partial write). */
    var truncateWritesTo: Int? = null
    /** While set, writing stores only this many bytes and then fails (a write that was interrupted). */
    @Volatile var truncateThenFail: Int? = null
    var failWrites = false
    var failReads = false
    var hang = false

    /** While set, reads and writes block the calling thread (real blocking I/O, unlike [hang]) until it is counted down. */
    @Volatile var blockThread: java.util.concurrent.CountDownLatch? = null
    /** When set, [blockThread] only blocks calls for this uri; other documents answer normally. */
    @Volatile var blockOnly: String? = null
    /** Invoked at the start of every read, with its uri (tests use it to simulate the user typing while a read is in progress). */
    @Volatile var onRead: ((String) -> Unit)? = null

    fun put(uri: String, text: String, name: String? = null, bytes: ByteArray? = null) {
        files[uri] = bytes ?: text.toByteArray(Charsets.UTF_8)
        if (name != null) names[uri] = name
    }

    fun text(uri: String) = String(files.getValue(uri), Charsets.UTF_8)

    private suspend fun gate(uri: String) {
        if (hang) delay(Long.MAX_VALUE)
        val latch = blockThread
        if (latch != null && (blockOnly == null || blockOnly == uri)) latch.await()
    }

    override suspend fun read(uri: String, maxBytes: Int): ByteArray {
        onRead?.invoke(uri)
        gate(uri)
        if (failReads) throw NoteSourceUnavailableException("no access")
        val bytes = files[uri] ?: throw NoteSourceUnavailableException("missing")
        if (bytes.size > maxBytes) throw NoteSourceUnavailableException("too large")
        return bytes
    }

    override suspend fun write(uri: String, bytes: ByteArray) {
        gate(uri)
        if (failWrites || uri in readOnly || uri in providerReadOnly) throw NoteSourceUnavailableException("cannot write")
        writes += uri
        truncateThenFail?.let { stored ->
            files[uri] = bytes.copyOf(minOf(stored, bytes.size))
            throw NoteSourceUnavailableException("write interrupted")
        }
        files[uri] = truncateWritesTo?.let { bytes.copyOf(minOf(it, bytes.size)) } ?: bytes
    }

    override suspend fun displayName(uri: String): String? = names[uri]

    override suspend fun writeAccess(uri: String): WriteAccess = when {
        uri in readOnly -> WriteAccess.ReadOnly(ReadOnlyReason.NoWriteGrant)
        uri in providerReadOnly -> WriteAccess.ReadOnly(ReadOnlyReason.ProviderRefuses)
        uri in undetermined -> WriteAccess.ReadOnly(ReadOnlyReason.Unavailable)
        else -> WriteAccess.Writable
    }
}
