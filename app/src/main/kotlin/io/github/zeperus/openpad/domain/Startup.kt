package io.github.zeperus.openpad.domain

/** What the app shows on launch. */
enum class StartupMode {
    /** Restore the previous open notes and the previously active one. */
    ResumeSession,

    /** Restore the previous open notes, plus one fresh blank note which is active. */
    ResumeAndBlank,

    /** One fresh blank note; previous open notes are not restored. */
    BlankNote,
    ;

    companion object {
        val Default = ResumeAndBlank
    }
}

object StartupPlanner {
    /**
     * Decides the open documents at launch from the stored session and the ids of the notes that still exist.
     * Stale ids (deleted, trashed or garbage) are ignored; duplicates collapse to their first occurrence.
     *
     * At most one blank page is created per launch and it is never persisted, so blank tabs cannot accumulate
     * across launches.
     */
    fun initial(mode: StartupMode, persisted: PersistedSession, existing: Set<NoteId>): OpenDocuments {
        val restored = persisted.noteIds.map { NoteId(it) }.distinct().filter { it in existing }
        return when (mode) {
            StartupMode.BlankNote -> OpenDocuments.blank()
            StartupMode.ResumeAndBlank -> OpenDocuments(restored, draftOpen = true, activeNoteId = null)
            StartupMode.ResumeSession -> {
                if (restored.isEmpty()) {
                    OpenDocuments.blank()
                } else {
                    // If the draft was active when the app closed there is nothing to reactivate: use the last note.
                    val active = persisted.activeNoteId?.let { NoteId(it) }?.takeIf { it in restored } ?: restored.last()
                    OpenDocuments(restored, draftOpen = false, activeNoteId = active)
                }
            }
        }
    }
}

/** Persistence of the open-document session, separate from note metadata and note files. */
interface SessionStore {
    /** Never throws for missing or damaged data: that simply means "no session". */
    suspend fun load(): PersistedSession

    suspend fun save(session: PersistedSession)
}

/** Persistence of user settings. */
interface SettingsStore {
    /** The chosen mode, or [StartupMode.Default] if none was chosen or the stored value is unusable. */
    suspend fun startupMode(): StartupMode

    suspend fun setStartupMode(mode: StartupMode)
}
