package io.github.zeperus.openpad;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/**
 * Acts as another app's "Open with": opens the test provider's document EXTRA_ID in openPad++ with a read grant, plus a write
 * grant if EXTRA_WRITE. It lives in the test package (the owner of the provider), so the system really hands the grants over.
 * Java, like the provider: this process has no Kotlin standard library.
 */
public class OpenWithActivity extends Activity {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_WRITE = "write";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null) id = "writable.md";
        boolean write = getIntent().getBooleanExtra(EXTRA_WRITE, false);
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setClassName("io.github.zeperus.openpad", "io.github.zeperus.openpad.MainActivity")
                .setDataAndType(DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, id), "text/markdown")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | (write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0) | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
