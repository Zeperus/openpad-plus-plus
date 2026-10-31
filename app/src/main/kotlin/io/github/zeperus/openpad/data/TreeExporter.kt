package io.github.zeperus.openpad.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.zeperus.openpad.domain.ExportTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Writes exported notes as new documents into a folder the user picked (ACTION_OPEN_DOCUMENT_TREE). Never overwrites: providers
 * give a duplicate name a suffix.
 */
class TreeExporter(private val context: Context, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ExportTarget {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Creates [fileName] (MIME text/markdown) in the folder tree [folderUri] and writes [text] as UTF-8. Throws IOException on failure. */
    override suspend fun write(folderUri: String, fileName: String, text: String): Unit = withContext(dispatcher) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val document = createDocument(folderUri, fileName)
        try {
            openForWrite(document, fileName).use { it.write(bytes) }
        } catch (e: IOException) {
            // Do not leave an empty or half-written file behind: a retry would then get a "2" suffix.
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        }
    }

    /** Creates the empty document in the folder; the provider adds a suffix if [fileName] is already taken. */
    private fun createDocument(treeUri: String, fileName: String): Uri = try {
        val tree = Uri.parse(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, MIME_TYPE, fileName)
            ?: throw IOException("The provider could not create '$fileName'")
    } catch (e: SecurityException) {
        throw IOException("No permission to write into the chosen folder", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("The chosen folder is not usable", e)
    } catch (e: FileNotFoundException) {
        throw IOException("The chosen folder no longer exists", e)
    }

    private fun openForWrite(document: Uri, fileName: String): OutputStream = try {
        resolver.openOutputStream(document, "wt") ?: throw IOException("The provider cannot write '$fileName'")
    } catch (e: SecurityException) {
        throw IOException("No permission to write '$fileName'", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Could not write '$fileName'", e)
    } catch (e: FileNotFoundException) {
        throw IOException("Could not write '$fileName': ${e.message}", e)
    }

    private companion object {
        const val MIME_TYPE = "text/markdown"
    }
}
