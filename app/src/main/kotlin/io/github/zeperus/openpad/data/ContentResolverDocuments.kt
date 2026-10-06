package io.github.zeperus.openpad.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.zeperus.openpad.domain.ExternalDocuments
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
import io.github.zeperus.openpad.domain.ReadOnlyReason
import io.github.zeperus.openpad.domain.WriteAccess
import io.github.zeperus.openpad.domain.WriteAccessPolicy
import io.github.zeperus.openpad.domain.WriteProbe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Reads and writes documents of any provider (Storage Access Framework) in place, through [ContentResolver]. */
class ContentResolverDocuments(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExternalDocuments {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun read(uri: String, maxBytes: Int): ByteArray = withContext(dispatcher) {
        unavailable("read") {
            val input = resolver.openInputStream(Uri.parse(uri)) ?: throw NoteSourceUnavailableException("The provider returned no data")
            input.use {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw NoteSourceUnavailableException("The document is larger than ${maxBytes / (1024 * 1024)} MB")
                }
                out.toByteArray()
            }
        }
    }

    override suspend fun write(uri: String, bytes: ByteArray) = withContext(dispatcher) {
        unavailable("write") {
            // "wt" truncates then writes; providers cannot replace a file atomically, which is why the repository
            // keeps a recovery copy and reads the content back afterwards
            val out = resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw NoteSourceUnavailableException("The provider cannot write this document")
            out.use {
                it.write(bytes)
                it.flush()
            }
        }
    }

    override suspend fun displayName(uri: String): String? = withContext(dispatcher) {
        val parsed = Uri.parse(uri)
        try {
            if (parsed.scheme == "file") return@withContext parsed.lastPathSegment
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            } ?: parsed.lastPathSegment
        } catch (e: Exception) {
            parsed.lastPathSegment
        }
    }

    /**
     * Whether the document can be written, and why not - from several signals together (see [WriteAccessPolicy]): the granted or
     * persisted write permission, the provider's `FLAG_SUPPORTS_WRITE`, and what opening the document for writing in append mode
     * ("wa": changes nothing) really does. When in doubt this says "read-only": showing a document read-only is safe, writing into
     * one that refuses is not.
     */
    override suspend fun writeAccess(uri: String): WriteAccess = withContext(dispatcher) {
        val parsed = Uri.parse(uri)
        try {
            if (parsed.scheme == "file") {
                val writable = parsed.path?.let { File(it).canWrite() } == true
                return@withContext if (writable) WriteAccess.Writable else WriteAccess.ReadOnly(ReadOnlyReason.ProviderRefuses)
            }
            val isDocument = DocumentsContract.isDocumentUri(context, parsed)
            val granted = context.checkUriPermission(parsed, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val persisted = resolver.persistedUriPermissions.any { it.uri == parsed && it.isWritePermission }
            WriteAccessPolicy.evaluate(
                requiresGrant = isDocument,
                hasWriteGrant = granted || persisted,
                supportsWriteFlag = if (isDocument) supportsWriteFlag(parsed) else null,
                probe = { probeWrite(parsed) },
            )
        } catch (e: Exception) {
            WriteAccess.ReadOnly(ReadOnlyReason.Unavailable)
        }
    }

    /** The provider's capability flag; null if it does not report flags at all (incomplete metadata). */
    private fun supportsWriteFlag(uri: Uri): Boolean? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 else null
        }
    } catch (e: Exception) {
        null
    }

    /** Opens the document for appending and closes it again: tells whether writing is possible without writing anything. */
    private fun probeWrite(uri: Uri): WriteProbe = try {
        val fd = resolver.openFileDescriptor(uri, "wa")
        if (fd == null) WriteProbe.Refused else { fd.close(); WriteProbe.Opened }
    } catch (e: SecurityException) {
        WriteProbe.Denied
    } catch (e: java.io.FileNotFoundException) {
        WriteProbe.Refused
    } catch (e: UnsupportedOperationException) {
        WriteProbe.Refused
    } catch (e: IOException) {
        WriteProbe.Refused
    } catch (e: Exception) {
        WriteProbe.Unknown
    }

    private inline fun <T> unavailable(what: String, block: () -> T): T = try {
        block()
    } catch (e: NoteSourceUnavailableException) {
        throw e
    } catch (e: SecurityException) {
        throw NoteSourceUnavailableException("No permission to $what this document", e)
    } catch (e: IOException) {
        throw NoteSourceUnavailableException("Could not $what this document: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw NoteSourceUnavailableException("Could not $what this document", e)
    } catch (e: UnsupportedOperationException) {
        throw NoteSourceUnavailableException("The provider does not support this", e)
    }
}

/** Taking over long-lived access to a document the user picked or opened with openPad++. */
object ExternalAccess {
    /** True if access to [uri] now survives a restart. Picker results can be persisted; many "Open with" grants cannot. */
    fun takePersistable(resolver: ContentResolver, uri: Uri): Boolean {
        for (flags in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                resolver.takePersistableUriPermission(uri, flags)
                return true
            } catch (_: SecurityException) {
                // try the next, weaker grant
            } catch (_: UnsupportedOperationException) {
                return false
            }
        }
        return false
    }
}

/**
 * The document picker, asking for what editing needs. `ActivityResultContracts.OpenDocument` asks for *read* access only, and the
 * system file picker then grants nothing more - which made every file picked in Alpha 3 read-only. Editing in place needs
 * `FLAG_GRANT_WRITE_URI_PERMISSION` in the request (and `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` to keep the access after a restart).
 * A provider that cannot write simply does not grant it. [initial] is where the picker opens (e.g. next to a file that arrived
 * read-only), when the system supports a hint.
 */
class OpenWritableDocument(private val initial: Uri? = null) : androidx.activity.result.contract.ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (initial != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
}
