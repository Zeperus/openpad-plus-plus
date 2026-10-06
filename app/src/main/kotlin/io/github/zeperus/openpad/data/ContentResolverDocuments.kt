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
     * Whether the document can be written. Document providers (file picker, cloud storage) say so themselves
     * (`FLAG_SUPPORTS_WRITE`) and need a write grant; for every other kind of `content://` URI the only reliable test is
     * to try opening it for writing in append mode, which changes nothing. When in doubt this says "read-only": showing
     * a document read-only is safe, writing into one that refuses is not.
     */
    override suspend fun isWritable(uri: String): Boolean = withContext(dispatcher) {
        val parsed = Uri.parse(uri)
        try {
            if (parsed.scheme == "file") return@withContext parsed.path?.let { File(it).canWrite() } == true
            if (DocumentsContract.isDocumentUri(context, parsed)) {
                val granted = context.checkUriPermission(parsed, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val persisted = resolver.persistedUriPermissions.any { it.uri == parsed && it.isWritePermission }
                val supports = resolver.query(parsed, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                } ?: false
                (granted || persisted) && supports
            } else {
                resolver.openFileDescriptor(parsed, "wa")?.use { true } ?: false
            }
        } catch (e: Exception) {
            false
        }
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
