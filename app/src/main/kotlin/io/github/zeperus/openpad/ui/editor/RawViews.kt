package io.github.zeperus.openpad.ui.editor

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.editor.HtmlRun
import io.github.zeperus.openpad.editor.RawBlock
import io.github.zeperus.openpad.editor.RichText
import io.github.zeperus.openpad.markdown.Block
import io.github.zeperus.openpad.markdown.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** True for the raw blocks that are drawn as formatted content (the rest is shown as its Markdown source). */
internal fun RawBlock.isRendered(): Boolean = this is RawBlock.Table || this is RawBlock.Image || (this is RawBlock.Html && simple != null)

/**
 * A raw block drawn for reading: a table, an image (or its placeholder), simple HTML. The Markdown behind it is not touched;
 * "Edit source" opens it as text in the same row.
 */
@Composable
internal fun RenderedRawBlock(block: RawBlock, readOnly: Boolean, selected: Boolean, onEditSource: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (selected) Modifier.background(colors.tertiaryContainer, RoundedCornerShape(6.dp)) else Modifier),
    ) {
        when (block) {
            is RawBlock.Table -> TableBlock(block)
            is RawBlock.Image -> ImageBlock(block)
            is RawBlock.Html -> HtmlBlock(block.simple.orEmpty())
            RawBlock.Other -> Unit
        }
        if (!readOnly) {
            TextButton(onClick = onEditSource, modifier = Modifier.testTag("edit-source")) { Text(stringResource(R.string.raw_edit_source)) }
        }
    }
}

// ---- Tables ------------------------------------------------------------------------------------------------------

@Composable
private fun TableBlock(table: RawBlock.Table) {
    val colors = MaterialTheme.colorScheme
    val spanColors = remember(colors) { SpanColors(link = colors.primary, codeBackground = colors.surfaceVariant, muted = colors.onSurfaceVariant) }
    val description = stringResource(R.string.table_description, table.header.size, table.rows.size + 1)
    Box(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = description }.testTag("table")) {
        TableLayout(columns = table.header.size) {
            for ((c, cell) in table.header.withIndex()) TableCell(inline(cell, spanColors), table.aligns[c], header = true)
            for (row in table.rows) for ((c, cell) in row.withIndex()) TableCell(inline(cell, spanColors), table.aligns[c], header = false)
        }
    }
}

@Composable
private fun TableCell(text: AnnotatedString, align: RawBlock.Align, header: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(if (header) colors.surfaceVariant else colors.background)
            .border(androidx.compose.ui.unit.Dp.Hairline, colors.outline)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = when (align) {
            RawBlock.Align.Start -> Alignment.TopStart
            RawBlock.Align.Center -> Alignment.TopCenter
            RawBlock.Align.End -> Alignment.TopEnd
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.Bold else FontWeight.Normal),
            textAlign = when (align) { RawBlock.Align.Start -> TextAlign.Start; RawBlock.Align.Center -> TextAlign.Center; RawBlock.Align.End -> TextAlign.End },
        )
    }
}

/** Cells in reading order; every column as wide as its widest cell (within limits), every row as high as its highest cell. */
@Composable
private fun TableLayout(columns: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val minWidth = 72.dp.roundToPx()
        val maxWidth = 260.dp.roundToPx()
        val rowCount = (measurables.size + columns - 1) / columns
        val widths = IntArray(columns) { minWidth }
        measurables.forEachIndexed { i, m -> widths[i % columns] = maxOf(widths[i % columns], m.maxIntrinsicWidth(0).coerceAtMost(maxWidth)) }
        val heights = IntArray(rowCount)
        measurables.forEachIndexed { i, m -> heights[i / columns] = maxOf(heights[i / columns], m.minIntrinsicHeight(widths[i % columns])) }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rowCount) {
                var x = 0
                for (c in 0 until columns) {
                    val i = r * columns + c
                    if (i < placeables.size) placeables[i].place(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

/** Inline Markdown in a table cell (bold, italic, code, links, strike) as formatted text. */
private fun inline(cell: String, colors: SpanColors): AnnotatedString {
    val inlines = (MarkdownParser.parseDocument(cell).blocks.firstOrNull() as? Block.Paragraph)?.inlines
    return (inlines?.let { RichText.fromInlines(it) } ?: RichText(cell)).toAnnotated(colors)
}

// ---- Images ------------------------------------------------------------------------------------------------------

/**
 * An image: shown if it is a `content://` document the app may read, otherwise a quiet placeholder with the alt text and the
 * address. Nothing is ever downloaded (the app has no INTERNET permission) and nothing but `content://` is opened.
 */
@Composable
private fun ImageBlock(image: RawBlock.Image) {
    val context = LocalContext.current
    val uri = remember(image.source) { runCatching { Uri.parse(image.source) }.getOrNull() }
    val local = uri?.scheme == "content"
    val bitmap by produceState<ImageBitmap?>(null, image.source) {
        value = if (local && uri != null) withContext(Dispatchers.IO) { loadBitmap(context, uri) } else null
    }
    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = image.alt.ifBlank { stringResource(R.string.image_generic) },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("image"),
        )
    } else {
        ImagePlaceholder(image, remote = uri?.scheme == "http" || uri?.scheme == "https", loading = local)
    }
}

@Composable
private fun ImagePlaceholder(image: RawBlock.Image, remote: Boolean, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val name = image.alt.ifBlank { stringResource(R.string.image_generic) }
    val where = when {
        remote -> runCatching { URI(image.source).host }.getOrNull() ?: image.source
        loading -> stringResource(R.string.image_loading)
        else -> stringResource(R.string.image_unavailable)
    }
    val description = stringResource(R.string.image_placeholder_description, name, where)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant)
            .padding(12.dp)
            .semantics { contentDescription = description }
            .testTag("image-placeholder"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🖼", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(where, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (remote) {
            TextButton(onClick = {
                val uri = runCatching { Uri.parse(image.source) }.getOrNull() ?: return@TextButton
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.link_open)) }
        }
    }
}

/** Decodes a content image at a sensible size. Any failure (no permission, not an image, too large) is just "no image". */
private fun loadBitmap(context: android.content.Context, uri: Uri): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

// ---- HTML --------------------------------------------------------------------------------------------------------

@Composable
private fun HtmlBlock(runs: List<HtmlRun>) {
    val colors = MaterialTheme.colorScheme
    val text = remember(runs) {
        buildAnnotatedString {
            for (r in runs) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (r.bold) FontWeight.Bold else null,
                        fontStyle = if (r.italic) FontStyle.Italic else null,
                        fontFamily = if (r.code) FontFamily.Monospace else null,
                    ),
                ) { append(r.text) }
            }
        }
    }
    Column(Modifier.testTag("html-block")) {
        Text(stringResource(R.string.html_label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
