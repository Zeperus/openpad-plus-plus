package io.github.zeperus.openpad.domain

/** One file to write: the file name (with .md) and the note it comes from. */
data class ExportItem(val id: NoteId, val fileName: String)

/** What an export did: how many files were written, which notes failed (title), how many external documents were skipped (they already are files the user owns). */
data class ExportResult(val written: Int, val failed: List<String>, val skippedExternal: Int)

object NoteExport {
    /**
     * File names for exporting [notes] into one folder: active internal notes only (external documents are skipped; trashed notes are
     * not in the list). The name is the note's title made safe with NoteFileName (fallback NoteFileName.DEFAULT_TITLE), unique
     * case-insensitively within the export (`Shopping`, `Shopping 2`, ... via NoteFileName.uniqueTitle), plus the .md extension.
     * Order: by title, case-insensitive, then by id for stability.
     */
    fun plan(notes: List<NoteInfo>): List<ExportItem> {
        val exportable = notes.filter { !it.isExternal && !it.isTrashed }
        val ordered = exportable.sortedWith(
            compareBy<NoteInfo> { NoteFileName.sanitize(it.title).lowercase() }.thenBy { it.id.value },
        )
        // uniqueTitle compares case-insensitively, so names chosen so far are passed in as they were picked.
        val taken = mutableListOf<String>()
        return ordered.map { note ->
            val title = NoteFileName.uniqueTitle(note.title, taken)
            taken += title
            ExportItem(note.id, title + NoteFileName.EXTENSION)
        }
    }
}
