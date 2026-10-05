package io.github.zeperus.openpad.domain

/** One entry in the open-document strip. */
sealed interface DocumentTab {
    /** A note that exists as a file, identified by its stable id (never by title or path). */
    data class Saved(val id: NoteId) : DocumentTab

    /** The transient blank page. It has no id and no file until it gets real content. */
    data object Draft : DocumentTab
}

/** A tab prepared for display; the title comes from the note's current metadata, so renames show up at once. */
data class TabItem(val tab: DocumentTab, val title: String, val isActive: Boolean)

/**
 * The set of open documents ("mobile tabs"): pure, immutable and independent of Compose, storage and Android.
 *
 * - [noteIds] are the open saved notes in tab order. Opening or activating a note never reorders them.
 * - [draftOpen] says whether the single transient blank page is in the strip. There is **at most one** draft and
 *   it is always the last tab, so repeated "new note" requests or restarts cannot pile up blank tabs.
 * - [activeNoteId] is the active saved note, or null when the draft is active.
 *
 * Invariants (checked on construction, preserved by every operation): ids are distinct, the active id is open,
 * and there is always at least one tab - if no note is open the draft is open and active.
 *
 * Closing a document only changes this model. It never deletes, trashes or modifies a note.
 */
data class OpenDocuments(
    val noteIds: List<NoteId> = emptyList(),
    val draftOpen: Boolean = true,
    val activeNoteId: NoteId? = null,
) {
    init {
        require(noteIds.size == noteIds.toSet().size) { "duplicate open note ids" }
        require(activeNoteId == null || activeNoteId in noteIds) { "active note is not open" }
        require(activeNoteId != null || draftOpen) { "no active tab: draft must be open when no note is active" }
    }

    /** All tabs in display order: saved notes, then the draft if there is one. */
    val tabs: List<DocumentTab>
        get() = noteIds.map { DocumentTab.Saved(it) } + if (draftOpen) listOf(DocumentTab.Draft) else emptyList()

    val active: DocumentTab
        get() = activeNoteId?.let { DocumentTab.Saved(it) } ?: DocumentTab.Draft

    fun isOpen(id: NoteId) = id in noteIds

    /**
     * Opens a note: an already open note is just activated (no duplicate tab, no reordering); a new one is
     * appended after the existing saved notes and activated.
     */
    fun open(id: NoteId): OpenDocuments =
        if (id in noteIds) copy(activeNoteId = id) else copy(noteIds = noteIds + id, activeNoteId = id)

    /** Switches to an open tab. Unknown tabs are ignored. Never changes the tab order. */
    fun activate(tab: DocumentTab): OpenDocuments = when (tab) {
        is DocumentTab.Saved -> if (tab.id in noteIds) copy(activeNoteId = tab.id) else this
        DocumentTab.Draft -> if (draftOpen) copy(activeNoteId = null) else this
    }

    /** "New note": activates the existing blank page or opens one. Never creates a second draft. */
    fun newDraft(): OpenDocuments = copy(draftOpen = true, activeNoteId = null)

    /**
     * The draft got its first meaningful content and was saved as note [id]: the draft tab becomes that note's
     * tab, in the same (last) position, and stays active if it was. The draft is gone afterwards; the next
     * "new note" opens a fresh one.
     */
    fun materializeDraft(id: NoteId): OpenDocuments {
        if (!draftOpen) return this
        val wasActive = activeNoteId == null
        return if (id in noteIds) {
            copy(draftOpen = false, activeNoteId = if (wasActive) id else activeNoteId) // already open: no duplicate
        } else {
            copy(noteIds = noteIds + id, draftOpen = false, activeNoteId = if (wasActive) id else activeNoteId)
        }
    }

    /**
     * Closes a tab. If it was the active one, the next tab becomes active, otherwise the previous one, otherwise a
     * fresh blank page. Closing the last remaining tab leaves one blank page, never zero tabs.
     */
    fun close(tab: DocumentTab): OpenDocuments {
        val before = tabs
        val index = before.indexOf(tab)
        if (index < 0) return this
        val after = before - tab
        val newNoteIds = after.filterIsInstance<DocumentTab.Saved>().map { it.id }
        val draft = DocumentTab.Draft in after
        if (tab != active) return OpenDocuments(newNoteIds, draft, activeNoteId)

        return when (val next = after.getOrNull(index) ?: after.getOrNull(index - 1)) {
            is DocumentTab.Saved -> OpenDocuments(newNoteIds, draft, next.id)
            DocumentTab.Draft -> OpenDocuments(newNoteIds, true, null)
            null -> blank() // that was the last tab
        }
    }

    /** Closes every tab except [keep], which becomes active. Unknown tabs are ignored. */
    fun closeOthers(keep: DocumentTab): OpenDocuments {
        if (keep !in tabs) return this
        return when (keep) {
            is DocumentTab.Saved -> OpenDocuments(listOf(keep.id), false, keep.id)
            DocumentTab.Draft -> OpenDocuments(emptyList(), true, null)
        }
    }

    /** Closes everything and leaves one fresh blank page. */
    fun closeAll(): OpenDocuments = blank()

    /** A note was moved to the Trash (or removed): drop its tab like [close], with the same neighbour rule. */
    fun remove(id: NoteId): OpenDocuments = close(DocumentTab.Saved(id))

    /** Drops tabs of notes that no longer exist (stale ids), keeping the order and a sensible active tab. */
    fun retainOnly(existing: Set<NoteId>): OpenDocuments =
        noteIds.filter { it !in existing }.fold(this) { session, stale -> session.remove(stale) }

    /** Tabs with display titles. Saved tabs whose note is unknown are left out (they are stale). */
    fun items(notes: Collection<NoteInfo>, draftTitle: String): List<TabItem> {
        val byId = notes.associateBy { it.id }
        return tabs.mapNotNull { tab ->
            when (tab) {
                is DocumentTab.Saved -> byId[tab.id]?.let { TabItem(tab, it.title, tab == active) }
                DocumentTab.Draft -> TabItem(tab, draftTitle, tab == active)
            }
        }
    }

    /** What survives a restart: only saved notes. The transient draft is never persisted. */
    fun toPersisted() = PersistedSession(noteIds.map { it.value }, activeNoteId?.value)

    companion object {
        /** One fresh blank page and nothing else. */
        fun blank() = OpenDocuments(emptyList(), true, null)
    }
}

/** The stored form of the session: raw ids, not yet validated against the existing notes. */
data class PersistedSession(
    val noteIds: List<String> = emptyList(),
    /** Null when the draft (or nothing) was active. */
    val activeNoteId: String? = null,
)
