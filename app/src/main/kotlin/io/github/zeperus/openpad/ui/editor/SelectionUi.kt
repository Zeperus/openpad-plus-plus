package io.github.zeperus.openpad.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Clipboard access in one place. Copy uses the plain-text clip type; the label says what it is. */
internal class AppClipboard(private val context: Context) {
    private val manager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copy(label: String, text: String) = manager.setPrimaryClip(ClipData.newPlainText(label, text))

    fun text(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
}

@Composable
internal fun rememberAppClipboard(): AppClipboard {
    val context = LocalContext.current
    return remember(context) { AppClipboard(context) }
}
