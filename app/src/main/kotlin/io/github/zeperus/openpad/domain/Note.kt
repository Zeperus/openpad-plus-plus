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
    /** Favorite state is metadata only; it never touches the Markdown file. */
    val favorite: Boolean = false,
    /**
     * When the note last became the open note (or was created). Not updated by editing. Null for notes that were
     * never opened in this app, e.g. adopted files; such notes never show up in Recent.
     */
    val lastOpenedAt: Long? = null,
    /** Set for documents that live outside the app (a `content://` URI); the text is edited in place, never copied. */
    val externalUri: String? = null,
    /**
     * Smart checklist mode (metadata only, never written into the Markdown): checked task items sink below the unchecked
     * ones. Off for every note until the user switches it on; plain Markdown task lists keep their order.
     */
    val smartChecklist: Boolean = false,
    /** The folder the note is filed in (metadata only; the `.md` file does not move). Null = not in a folder. */
    val folderId: String? = null,
) {
    val isTrashed: Boolean get() = trashedAt != null

    val isExternal: Boolean get() = externalUri != null

    /** The name to use when the note leaves the app (Share/export); on disk the file is named after the id. */
    val exportFileName: String get() = title + NoteFileName.EXTENSION
}

/** [readOnly]: the provider does not allow writing (e.g. a file opened with a read-only grant). */
data class NoteContent(val info: NoteInfo, val text: String, val readOnly: Boolean = false)

/** A simple one-level folder, kept as metadata in the index. Notes in it are still plain `.md` files with their own stable ids. */
data class FolderInfo(val id: String, val name: String)
