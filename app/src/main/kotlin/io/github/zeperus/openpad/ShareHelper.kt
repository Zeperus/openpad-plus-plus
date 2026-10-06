package io.github.zeperus.openpad

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.zeperus.openpad.domain.NoteFileName
import java.io.File

/**
 * Shares a note as what it is: a `.md` file (`Shopping.md`), not text pasted into a message. The content is written to
 * a private cache copy first because the note itself may live in app-private storage or in another provider.
 */
object ShareHelper {
    const val AUTHORITY_SUFFIX = ".share"
    private const val DIR = "share"

    fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX

    /** Writes the cache copy and returns the file to share. Old copies are removed first. */
    fun prepareFile(context: Context, title: String, text: String): File {
        val root = File(context.cacheDir, DIR)
        root.deleteRecursively()
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        return File(dir, NoteFileName.toFileName(title)).apply { writeText(text, Charsets.UTF_8) }
    }

    /** The chooser intent for sharing [text] as `<title>.md`. */
    fun createIntent(context: Context, title: String, text: String): Intent {
        val file = prepareFile(context, title, text)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
