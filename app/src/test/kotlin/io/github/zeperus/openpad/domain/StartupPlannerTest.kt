package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPlannerTest {
    private val a = NoteId("a")
    private val b = NoteId("b")
    private val c = NoteId("c")
    private val all = setOf(a, b, c)

    /** Previous run: `A | B | C`, with [active] active. */
    private fun previous(active: String? = "b") = PersistedSession(listOf("a", "b", "c"), active)

    private fun titles(s: OpenDocuments) = s.tabs.map {
        when (it) {
            is DocumentTab.Saved -> it.id.value
            DocumentTab.Draft -> "blank"
        }
    }

    // ---- The three modes -------------------------------------------------------------------------------------

    @Test fun `the default mode is resume plus blank note`() = assertEquals(StartupMode.ResumeAndBlank, StartupMode.Default)

    @Test fun `resume session restores the open notes and the active one`() {
        val s = StartupPlanner.initial(StartupMode.ResumeSession, previous("b"), all)
        assertEquals(listOf("a", "b", "c"), titles(s))
        assertEquals(b, s.activeNoteId)
        assertFalse(s.draftOpen)
    }

    @Test fun `resume plus blank restores the notes and adds one active blank note`() {
        val s = StartupPlanner.initial(StartupMode.ResumeAndBlank, previous("b"), all)
        assertEquals(listOf("a", "b", "c", "blank"), titles(s))
        assertEquals(DocumentTab.Draft, s.active)
    }

    @Test fun `blank note starts with a single blank page and does not restore`() {
        val s = StartupPlanner.initial(StartupMode.BlankNote, previous(), all)
        assertEquals(listOf("blank"), titles(s))
        assertEquals(OpenDocuments.blank(), s)
    }

    // ---- Nothing to restore ----------------------------------------------------------------------------------

    @Test fun `with no previous session every mode shows exactly one blank note`() {
        for (mode in StartupMode.entries) {
            assertEquals(mode.name, listOf("blank"), titles(StartupPlanner.initial(mode, PersistedSession(), all)))
        }
    }

    @Test fun `resume session with only stale entries falls back to one blank note`() {
        val s = StartupPlanner.initial(StartupMode.ResumeSession, previous(), emptySet())
        assertEquals(OpenDocuments.blank(), s)
    }

    // ---- Stale and damaged entries ---------------------------------------------------------------------------

    @Test fun `deleted or trashed notes are ignored when restoring`() {
        val onlyAandC = setOf(a, c) // b was deleted meanwhile
        val resume = StartupPlanner.initial(StartupMode.ResumeSession, previous("c"), onlyAandC)
        assertEquals(listOf("a", "c"), titles(resume))
        assertEquals(c, resume.activeNoteId)
        val plusBlank = StartupPlanner.initial(StartupMode.ResumeAndBlank, previous("c"), onlyAandC)
        assertEquals(listOf("a", "c", "blank"), titles(plusBlank))
    }

    @Test fun `a stale active note falls back to the last restored note`() {
        val s = StartupPlanner.initial(StartupMode.ResumeSession, previous("b"), setOf(a, c))
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `if the blank page was active when the app closed the last note is activated`() {
        val s = StartupPlanner.initial(StartupMode.ResumeSession, previous(active = null), all)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `an active id that was never in the list is ignored`() {
        val s = StartupPlanner.initial(StartupMode.ResumeSession, previous(active = "zzz"), all)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `duplicates and garbage ids in the stored session are harmless`() {
        val messy = PersistedSession(listOf("a", "a", "", "../../etc", "b", "a"), "a")
        val s = StartupPlanner.initial(StartupMode.ResumeSession, messy, all)
        assertEquals(listOf("a", "b"), titles(s))
        assertEquals(a, s.activeNoteId)
    }

    // ---- No blank accumulation / session tracking ------------------------------------------------------------

    @Test fun `repeated launches never accumulate blank tabs`() {
        var persisted = previous("b")
        repeat(10) {
            val s = StartupPlanner.initial(StartupMode.ResumeAndBlank, persisted, all)
            assertEquals(listOf("a", "b", "c", "blank"), titles(s))
            persisted = s.toPersisted() // what the app writes back; the draft is not part of it
        }
    }

    @Test fun `closing everything and relaunching gives one blank note in every mode`() {
        val persisted = OpenDocuments.blank().toPersisted() // after Close all and quitting without typing
        for (mode in StartupMode.entries) {
            assertEquals(mode.name, listOf("blank"), titles(StartupPlanner.initial(mode, persisted, all)))
        }
    }

    @Test fun `blank note mode tracks the new session so a later switch to resume does not resurrect the old one`() {
        // Launch with "Blank note": the old A|B|C is not shown, and the new (empty) session replaces it on disk.
        val blank = StartupPlanner.initial(StartupMode.BlankNote, previous(), all)
        var persisted = blank.toPersisted()
        // Work in this run: open A.
        persisted = blank.open(a).toPersisted()
        // Next launch with "Resume session" restores only what happened in the last run.
        val resumed = StartupPlanner.initial(StartupMode.ResumeSession, persisted, all)
        assertEquals(listOf("a"), titles(resumed))
    }

    @Test fun `blank note mode with an untouched blank page leaves nothing to resume`() {
        val persisted = StartupPlanner.initial(StartupMode.BlankNote, previous(), all).toPersisted()
        assertTrue(persisted.noteIds.isEmpty())
        assertEquals(OpenDocuments.blank(), StartupPlanner.initial(StartupMode.ResumeSession, persisted, all))
    }
}
