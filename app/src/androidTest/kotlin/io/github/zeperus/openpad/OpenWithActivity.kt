package io.github.zeperus.openpad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * Acts as another app's "Open with": opens the test provider's document [EXTRA_ID] in openPad++ with a read grant, plus a write
 * grant if [EXTRA_WRITE]. It lives in the test package (the owner of the provider), so the system really hands the grants over.
 */
class OpenWithActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: "writable.md"
        val write = intent.getBooleanExtra(EXTRA_WRITE, false)
        val uri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, id)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setClassName("io.github.zeperus.openpad", "io.github.zeperus.openpad.MainActivity")
                .setDataAndType(uri, "text/markdown")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_WRITE = "write"
    }
}
