package io.github.zeperus.openpad

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A Storage Access Framework provider for tests. Its documents differ in what they say about writing:
 *  - `writable.md`  supports writing and says so (FLAG_SUPPORTS_WRITE)
 *  - `readonly.md`  does not support writing, says so, and refuses write modes
 *  - `noflags.md`   reports no capability flags at all (incomplete metadata) but accepts writes
 *  - `liar.md`      says it supports writing but refuses every write mode
 * Content lives in files of this (test) package's cache directory.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private val dir get() = File(context!!.cacheDir, "test-documents").apply { mkdirs() }

    private val initialTexts = mapOf(
        "writable.md" to "# Original\n",
        "readonly.md" to "locked\n",
        "noflags.md" to "text\n",
        "liar.md" to "x\n",
    )

    /** The documents exist with their initial text from the first access on (the test package's data is cleared between tests). */
    private fun ensureFiles() {
        for ((id, text) in initialTexts) File(dir, id).let { if (!it.exists()) it.writeText(text) }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS))
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root").add(Root.COLUMN_TITLE, "openPad++ tests").add(Root.COLUMN_FLAGS, 0)
        return cursor
    }

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ensureFiles()
        val cursor = MatrixCursor(projection ?: if (documentId == "noflags.md") columns.dropLast(1).toTypedArray() else columns)
        addRow(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        ensureFiles()
        val cursor = MatrixCursor(projection ?: columns)
        for (id in listOf("writable.md", "readonly.md", "noflags.md", "liar.md")) addRow(cursor, id)
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, id: String) {
        val row = cursor.newRow()
        val file = File(dir, id)
        val flags = when (id) {
            "writable.md", "liar.md" -> Document.FLAG_SUPPORTS_WRITE
            else -> 0
        }
        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to id,
            Document.COLUMN_MIME_TYPE to (if (id == "root") Document.MIME_TYPE_DIR else "text/markdown"),
            Document.COLUMN_SIZE to file.length(),
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to (if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else flags),
        )
        for (column in cursor.columnNames) row.add(column, values[column])
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        ensureFiles()
        val file = File(dir, documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        val writing = mode.contains('w') || mode.contains('a')
        if (writing && (documentId == "readonly.md" || documentId == "liar.md")) throw FileNotFoundException("$documentId cannot be written")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = true

    companion object {
        const val AUTHORITY = "io.github.zeperus.openpad.test.documents"
    }
}
