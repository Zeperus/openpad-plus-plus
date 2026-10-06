package io.github.zeperus.openpad.domain

/**
 * Access to documents outside the app (Storage Access Framework). The repository only knows this interface; the
 * Android implementation is `ContentResolverDocuments`, tests use an in-memory fake.
 */
interface ExternalDocuments {
    /** The complete content. Throws [NoteSourceUnavailableException] if it cannot be read or is larger than [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Int): ByteArray

    /** Replaces the content. Throws [NoteSourceUnavailableException] if the document cannot be written. */
    suspend fun write(uri: String, bytes: ByteArray)

    /** The file name as the provider shows it (e.g. `Shopping.md`), if known. */
    suspend fun displayName(uri: String): String?

    /** True if the document can be written (false for read-only grants or providers). */
    suspend fun isWritable(uri: String): Boolean = writeAccess(uri) is WriteAccess.Writable

    /** Whether the document can be written and, if not, why - from every signal available, never by changing the file. */
    suspend fun writeAccess(uri: String): WriteAccess
}

/** Used where external documents are not available. */
object NoExternalDocuments : ExternalDocuments {
    override suspend fun read(uri: String, maxBytes: Int): ByteArray = throw NoteSourceUnavailableException("External documents are not available")
    override suspend fun write(uri: String, bytes: ByteArray) = throw NoteSourceUnavailableException("External documents are not available")
    override suspend fun displayName(uri: String): String? = null
    override suspend fun writeAccess(uri: String): WriteAccess = WriteAccess.ReadOnly(ReadOnlyReason.Unavailable)
}
