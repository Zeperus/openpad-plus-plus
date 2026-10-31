package io.github.zeperus.openpad.domain

/** The order of the drawer's FILES section. */
enum class FilesSort {
    /** Alphabetical by title, ignoring case. */
    Name,

    /** The last change first. */
    Modified,
    ;

    companion object {
        val Default = Name
    }
}

/** The drawer's note sections, as pure functions of the full note list. */
object NoteLists {
    const val RECENT_LIMIT = 3

    private val byTitle = compareBy<NoteInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id.value }

    /** Active favorites, alphabetical. The Favorites section is shown only when this is not empty. */
    fun favorites(notes: List<NoteInfo>): List<NoteInfo> =
        notes.filter { !it.isTrashed && it.favorite }.sortedWith(byTitle)

    /**
     * [notes] in the order chosen for the FILES section. [FilesSort.Name]: title ignoring case, then id (the order the
     * repository has always listed notes in, with a stable tie-break). [FilesSort.Modified]: `updatedAt` newest first,
     * then title, then id. Never depends on the input order.
     */
    fun sorted(notes: List<NoteInfo>, sort: FilesSort): List<NoteInfo> = when (sort) {
        FilesSort.Name -> notes.sortedWith(byTitle)
        FilesSort.Modified -> notes.sortedWith(compareByDescending<NoteInfo> { it.updatedAt }.then(byTitle))
    }

    /**
     * The last [limit] *used* notes that are not favorites and not in the Trash, most recently used first.
     *
     * "Used" means opened (or created) - see [NoteInfo.lastOpenedAt] - not edited, so typing never reorders the
     * list. Notes that were never opened have no timestamp and are not recent. Ties are broken by `updatedAt`,
     * then title (case-insensitive), then id, so the result never depends on input order.
     */
    fun recent(notes: List<NoteInfo>, limit: Int = RECENT_LIMIT): List<NoteInfo> =
        notes.filter { !it.isTrashed && !it.favorite && it.lastOpenedAt != null }
            .sortedWith(
                compareByDescending<NoteInfo> { it.lastOpenedAt }
                    .thenByDescending { it.updatedAt }
                    .then(byTitle),
            )
            .take(limit)
}
