package io.github.zeperus.openpad.domain

/** Stable identity of a note. Never derived from the file name, so renaming does not change it. */
@JvmInline
value class NoteId(val value: String)

/** Metadata about a note. The text itself lives in the `.md` file, see [NoteContent]. */
data class NoteInfo(
    val id: NoteId,
    /** File name including the `.md` extension. */
    val fileName: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Non-null while the note is in the Trash. */
    val trashedAt: Long?,
    /** True until the user renames the note; while true the title follows the first line of text. */
    val autoTitle: Boolean,
) {
    val title: String get() = NoteFileName.titleOf(fileName)
    val isTrashed: Boolean get() = trashedAt != null
}

data class NoteContent(val info: NoteInfo, val text: String)
