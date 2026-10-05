package io.github.zeperus.openpad.domain

/** The drawer's note sections, as pure functions of the full note list. */
object NoteLists {
    const val RECENT_LIMIT = 3

    private val byTitle = compareBy<NoteInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id.value }

    /** Active favorites, alphabetical. The Favorites section is shown only when this is not empty. */
    fun favorites(notes: List<NoteInfo>): List<NoteInfo> =
        notes.filter { !it.isTrashed && it.favorite }.sortedWith(byTitle)

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
