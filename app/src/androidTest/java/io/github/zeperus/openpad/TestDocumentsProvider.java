package io.github.zeperus.openpad;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;

/**
 * A Storage Access Framework provider for tests. Its documents differ in what they say about writing:
 *  - writable.md  supports writing and says so (FLAG_SUPPORTS_WRITE)
 *  - readonly.md  does not support writing, says so, and refuses write modes
 *  - noflags.md   reports no capability flags at all (incomplete metadata) but accepts writes
 *  - liar.md      says it supports writing but refuses every write mode
 * Content lives in files of this (test) package's cache directory.
 *
 * Written in Java on purpose: the provider (and the helper activity) run in the test package's own process, which has no Kotlin
 * standard library (the tests share the app's); a Kotlin class here crashes that process on start and hangs every activity close.
 */
public class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.github.zeperus.openpad.test.documents";

    private static final String[] IDS = {"writable.md", "readonly.md", "noflags.md", "liar.md"};
    private static final String[] TEXTS = {"# Original\n", "locked\n", "text\n", "x\n"};
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    private static final String[] COLUMNS_WITHOUT_FLAGS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private File dir() {
        File d = new File(getContext().getCacheDir(), "test-documents");
        d.mkdirs();
        return d;
    }

    /** The documents exist with their initial text from the first access on (the test package's data is cleared between tests). */
    private void ensureFiles() {
        for (int i = 0; i < IDS.length; i++) {
            File f = new File(dir(), IDS[i]);
            if (f.exists()) continue;
            try (FileWriter w = new FileWriter(f)) {
                w.write(TEXTS[i]);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection
                : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Root.COLUMN_ROOT_ID: row.add(column, "root"); break;
                case Root.COLUMN_DOCUMENT_ID: row.add(column, "root"); break;
                case Root.COLUMN_TITLE: row.add(column, "openPad++ tests"); break;
                case Root.COLUMN_FLAGS: row.add(column, 0); break;
                default: row.add(column, null);
            }
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        ensureFiles();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection
                : (documentId.equals("noflags.md") ? COLUMNS_WITHOUT_FLAGS : COLUMNS));
        addRow(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        ensureFiles();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        for (String id : IDS) addRow(cursor, id);
        return cursor;
    }

    private void addRow(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        File file = new File(dir(), id);
        boolean root = id.equals("root");
        int flags = (id.equals("writable.md") || id.equals("liar.md")) ? Document.FLAG_SUPPORTS_WRITE : 0;
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(column, id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(column, id); break;
                case Document.COLUMN_MIME_TYPE: row.add(column, root ? Document.MIME_TYPE_DIR : "text/markdown"); break;
                case Document.COLUMN_SIZE: row.add(column, file.length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(column, file.lastModified()); break;
                case Document.COLUMN_FLAGS: row.add(column, root ? Document.FLAG_DIR_SUPPORTS_CREATE : flags); break;
                default: row.add(column, null);
            }
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        ensureFiles();
        File file = new File(dir(), documentId);
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        boolean writing = mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0;
        if (writing && (documentId.equals("readonly.md") || documentId.equals("liar.md"))) {
            throw new FileNotFoundException(documentId + " cannot be written");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return true;
    }
}
