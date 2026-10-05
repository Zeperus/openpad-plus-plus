package io.github.zeperus.openpad.domain

/** Stable identity of a note (a UUID). Never derived from the title, so renaming does not change it. */
@JvmInline
value class NoteId(val value: String)

/** Metadata about a note. The text itself lives in the `.md` file, see [NoteContent]. */
data class NoteInfo(
    val id: NoteId,
    /** User-visible title; always a legal file name stem (see [NoteFileName.sanitizeOrNull]). */
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Non-null while the note is in the Trash. */
    val trashedAt: Long?,
    /** True until the user renames the note; while true the title follows the first line of text. */
    val autoTitle: Boolean,
) {
    val isTrashed: Boolean get() = trashedAt != null

    /** The name to use when the note leaves the app (Share/export); on disk the file is named after the id. */
    val exportFileName: String get() = title + NoteFileName.EXTENSION
}

data class NoteContent(val info: NoteInfo, val text: String)
