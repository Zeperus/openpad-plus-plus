package io.github.zeperus.openpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class OpenDocumentsTest {
    private val a = NoteId("a")
    private val b = NoteId("b")
    private val c = NoteId("c")
    private val d = NoteId("d")
    private val draft = DocumentTab.Draft
    private fun saved(id: NoteId) = DocumentTab.Saved(id)

    /** `A | B | C` with [active] active and no draft. */
    private fun abc(active: NoteId = b) = OpenDocuments(listOf(a, b, c), draftOpen = false, activeNoteId = active)

    private fun info(id: NoteId, title: String) =
        NoteInfo(id, title, createdAt = 0, updatedAt = 0, trashedAt = null, autoTitle = false)

    // ---- Invariants ------------------------------------------------------------------------------------------

    @Test fun `a fresh session is one blank page`() {
        assertEquals(OpenDocuments.blank(), OpenDocuments())
        assertEquals(listOf(draft), OpenDocuments.blank().tabs)
        assertEquals(draft, OpenDocuments.blank().active)
        assertNull(OpenDocuments.blank().activeNoteId)
    }

    @Test fun `invalid states cannot be constructed`() {
        for (build in listOf<() -> OpenDocuments>(
            { OpenDocuments(listOf(a, a), true, a) }, // duplicate ids
            { OpenDocuments(listOf(a), true, b) }, // active note not open
            { OpenDocuments(listOf(a), false, null) }, // nothing active, no draft to show
            { OpenDocuments(emptyList(), false, null) }, // no tabs at all
        )) {
            try { build(); fail("expected the invariant to reject this state") } catch (_: IllegalArgumentException) {}
        }
    }

    // ---- Opening ---------------------------------------------------------------------------------------------

    @Test fun `opening the first note appends it and activates it`() {
        val s = OpenDocuments.blank().open(a)
        assertEquals(listOf(a), s.noteIds)
        assertEquals(saved(a), s.active)
        assertEquals(listOf(saved(a), draft), s.tabs) // the untouched blank page stays last
    }

    @Test fun `opening several notes keeps the order they were opened in`() {
        val s = OpenDocuments.blank().open(a).open(b).open(c)
        assertEquals(listOf(a, b, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `opening an already open note activates it without a duplicate or reordering`() {
        val s = abc(active = a).open(b)
        assertEquals(listOf(a, b, c), s.noteIds)
        assertEquals(b, s.activeNoteId)
        assertEquals(listOf(a, b, c), s.open(b).open(a).open(c).noteIds)
    }

    @Test fun `opening a note while the draft is active leaves the draft open`() {
        val s = OpenDocuments(listOf(a), draftOpen = true, activeNoteId = null).open(b)
        assertEquals(listOf(saved(a), saved(b), draft), s.tabs)
        assertEquals(saved(b), s.active)
    }

    // ---- Switching -------------------------------------------------------------------------------------------

    @Test fun `switching the active tab never changes the order`() {
        val s = abc(active = a)
        assertEquals(c, s.activate(saved(c)).activeNoteId)
        assertEquals(listOf(a, b, c), s.activate(saved(c)).activate(saved(b)).noteIds)
    }

    @Test fun `activating an unknown tab is ignored`() {
        val s = abc()
        assertSame(s, s.activate(saved(d)))
        assertSame(s, s.activate(draft)) // there is no draft in this session
    }

    @Test fun `the draft can be activated and left again`() {
        val s = OpenDocuments(listOf(a, b), true, a)
        assertEquals(draft, s.activate(draft).active)
        assertEquals(saved(b), s.activate(draft).activate(saved(b)).active)
        assertEquals(listOf(saved(a), saved(b), draft), s.activate(draft).tabs)
    }

    // ---- At most one draft -----------------------------------------------------------------------------------

    @Test fun `new draft opens a blank page at the end and activates it`() {
        val s = abc().newDraft()
        assertEquals(listOf(saved(a), saved(b), saved(c), draft), s.tabs)
        assertEquals(draft, s.active)
    }

    @Test fun `new draft never creates a second draft`() {
        val once = abc().newDraft()
        val thrice = once.newDraft().newDraft()
        assertEquals(once, thrice)
        assertEquals(1, thrice.tabs.count { it == draft })
        // also when the existing draft is merely inactive: it is activated, not duplicated
        val inactive = once.activate(saved(a)).newDraft()
        assertEquals(1, inactive.tabs.count { it == draft })
        assertEquals(draft, inactive.active)
    }

    // ---- Draft becomes a real note ---------------------------------------------------------------------------

    @Test fun `materializing the active draft turns it into the last saved tab and keeps it active`() {
        val s = OpenDocuments(listOf(a, b), true, null).materializeDraft(c)
        assertEquals(listOf(a, b, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
        assertFalse(s.draftOpen)
        assertEquals(listOf(saved(a), saved(b), saved(c)), s.tabs)
    }

    @Test fun `materializing a draft from the blank state enters the session`() {
        val s = OpenDocuments.blank().materializeDraft(a)
        assertEquals(listOf(a), s.noteIds)
        assertEquals(a, s.activeNoteId)
        assertEquals(listOf(a), s.toPersisted().noteIds.map { NoteId(it) })
    }

    @Test fun `materializing an inactive draft does not steal the focus`() {
        val s = OpenDocuments(listOf(a), true, a).materializeDraft(b)
        assertEquals(listOf(a, b), s.noteIds)
        assertEquals(a, s.activeNoteId)
    }

    @Test fun `materializing never duplicates an id that is already open`() {
        val s = OpenDocuments(listOf(a, b), true, null).materializeDraft(a)
        assertEquals(listOf(a, b), s.noteIds)
        assertEquals(a, s.activeNoteId)
        assertFalse(s.draftOpen)
    }

    @Test fun `materializing without a draft changes nothing`() {
        val s = abc()
        assertSame(s, s.materializeDraft(d))
    }

    @Test fun `after materializing the next new note is a fresh single draft after the saved note`() {
        val s = OpenDocuments.blank().materializeDraft(a).newDraft()
        assertEquals(listOf(saved(a), draft), s.tabs)
        assertEquals(draft, s.active)
    }

    // ---- Close -----------------------------------------------------------------------------------------------

    @Test fun `closing the active middle tab activates the next one`() {
        val s = abc(active = b).close(saved(b))
        assertEquals(listOf(a, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `closing the active last tab activates the previous one`() {
        val s = abc(active = c).close(saved(c))
        assertEquals(listOf(a, b), s.noteIds)
        assertEquals(b, s.activeNoteId)
    }

    @Test fun `closing the active first tab activates the new first tab`() {
        val s = abc(active = a).close(saved(a))
        assertEquals(listOf(b, c), s.noteIds)
        assertEquals(b, s.activeNoteId)
    }

    @Test fun `the draft counts as the next tab after the last saved note`() {
        val s = OpenDocuments(listOf(a, b), true, b).close(saved(b))
        assertEquals(listOf(a), s.noteIds)
        assertEquals(draft, s.active)
    }

    @Test fun `closing an inactive tab keeps the active one and the order`() {
        val s = abc(active = c).close(saved(a))
        assertEquals(listOf(b, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `closing the only tab leaves one fresh blank page`() {
        assertEquals(OpenDocuments.blank(), OpenDocuments(listOf(a), false, a).close(saved(a)))
    }

    @Test fun `closing the active draft activates the previous tab`() {
        val s = OpenDocuments(listOf(a, b), true, null).close(draft)
        assertEquals(listOf(a, b), s.noteIds)
        assertEquals(b, s.activeNoteId)
        assertFalse(s.draftOpen)
    }

    @Test fun `closing an inactive draft keeps the active note`() {
        val s = OpenDocuments(listOf(a, b), true, a).close(draft)
        assertEquals(a, s.activeNoteId)
        assertFalse(s.draftOpen)
    }

    @Test fun `closing the only tab when it is the draft leaves a single blank page`() {
        assertEquals(OpenDocuments.blank(), OpenDocuments.blank().close(draft))
    }

    @Test fun `closing an unknown tab changes nothing`() {
        val s = abc()
        assertSame(s, s.close(saved(d)))
        assertSame(s, s.close(draft))
    }

    @Test fun `close others keeps only the chosen tab and activates it`() {
        assertEquals(OpenDocuments(listOf(b), false, b), abc(active = a).closeOthers(saved(b)))
        assertEquals(OpenDocuments.blank(), OpenDocuments(listOf(a, b), true, a).closeOthers(draft))
        assertEquals(OpenDocuments(listOf(a), false, a), OpenDocuments(listOf(a, b), true, null).closeOthers(saved(a)))
    }

    @Test fun `close others with an unknown tab changes nothing`() {
        val s = abc()
        assertSame(s, s.closeOthers(saved(d)))
        assertSame(s, s.closeOthers(draft))
    }

    @Test fun `close all leaves one blank page`() {
        assertEquals(OpenDocuments.blank(), abc().closeAll())
        assertEquals(OpenDocuments.blank(), OpenDocuments(listOf(a, b), true, null).closeAll())
        assertEquals(listOf<String>(), abc().closeAll().toPersisted().noteIds)
    }

    // ---- Notes that are deleted or stale ---------------------------------------------------------------------

    @Test fun `a note moved to Trash leaves the session and a neighbour becomes active`() {
        val s = abc(active = b).remove(b)
        assertEquals(listOf(a, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `trashing an inactive note keeps the active tab`() {
        val s = abc(active = a).remove(c)
        assertEquals(listOf(a, b), s.noteIds)
        assertEquals(a, s.activeNoteId)
    }

    @Test fun `trashing the last open note leaves a blank page`() {
        assertEquals(OpenDocuments.blank(), OpenDocuments(listOf(a), false, a).remove(a))
    }

    @Test fun `removing a note that is not open changes nothing`() {
        val s = abc()
        assertSame(s, s.remove(d))
    }

    @Test fun `retainOnly drops stale ids and keeps order and the active tab`() {
        val s = abc(active = c).retainOnly(setOf(a, c))
        assertEquals(listOf(a, c), s.noteIds)
        assertEquals(c, s.activeNoteId)
    }

    @Test fun `retainOnly moves the active tab to a neighbour when the active note is stale`() {
        val s = OpenDocuments(listOf(a, b, c, d), false, b).retainOnly(setOf(a, d))
        assertEquals(listOf(a, d), s.noteIds)
        assertEquals(d, s.activeNoteId)
    }

    @Test fun `retainOnly with nothing left leaves a blank page and keeps an open draft`() {
        assertEquals(OpenDocuments.blank(), abc().retainOnly(emptySet()))
        val withDraft = OpenDocuments(listOf(a, b), true, a).retainOnly(setOf(a))
        assertEquals(listOf(saved(a), draft), withDraft.tabs)
    }

    @Test fun `retainOnly with everything present changes nothing`() {
        val s = abc()
        assertEquals(s, s.retainOnly(setOf(a, b, c, d)))
    }

    // ---- Titles / rename -------------------------------------------------------------------------------------

    @Test fun `tab titles come from the notes and reflect a rename without changing ids or order`() {
        val session = OpenDocuments(listOf(a, b, c), true, b)
        val before = listOf(info(a, "Shopping"), info(b, "Ideas"), info(c, "Work"))
        assertEquals(
            listOf("Shopping", "Ideas", "Work", "New note"),
            session.items(before, "New note").map { it.title },
        )

        val renamed = listOf(info(a, "Shopping"), info(b, "Better ideas"), info(c, "Work"))
        val after = session.items(renamed, "New note")
        assertEquals(listOf("Shopping", "Better ideas", "Work", "New note"), after.map { it.title })
        assertEquals(session.tabs, after.map { it.tab }) // same identities, same order, no duplicate
        assertEquals(listOf(false, true, false, false), after.map { it.isActive })
    }

    @Test fun `the draft tab is marked active when it is`() {
        val items = OpenDocuments(listOf(a), true, null).items(listOf(info(a, "A")), "New note")
        assertEquals(listOf(false, true), items.map { it.isActive })
    }

    @Test fun `tabs of unknown notes are left out of the display`() {
        val items = abc().items(listOf(info(a, "A"), info(c, "C")), "New note")
        assertEquals(listOf("A", "C"), items.map { it.title })
    }

    // ---- Persistence form ------------------------------------------------------------------------------------

    @Test fun `the draft is never persisted and the active id is null while it is active`() {
        val s = OpenDocuments(listOf(a, b), true, null).toPersisted()
        assertEquals(listOf("a", "b"), s.noteIds)
        assertNull(s.activeNoteId)
        assertEquals("b", OpenDocuments(listOf(a, b), true, b).toPersisted().activeNoteId)
    }

    // ---- Randomized safety net -------------------------------------------------------------------------------

    private fun isSubsequence(small: List<NoteId>, big: List<NoteId>): Boolean {
        val it = big.iterator()
        return small.all { x -> it.hasNext() && generateSequence { if (it.hasNext()) it.next() else null }.any { y -> y == x } }
    }

    @Test fun `any sequence of operations keeps the invariants, one draft at most and the tab order`() {
        val pool = listOf(a, b, c, d, NoteId("e"), NoteId("f"))
        repeat(300) { seed ->
            val random = Random(seed)
            var s = OpenDocuments.blank()
            repeat(60) {
                val before = s
                val tabs = s.tabs
                var orderPreserving = true
                s = when (random.nextInt(9)) {
                    0 -> s.open(pool.random(random)).also { orderPreserving = isSubsequence(before.noteIds, it.noteIds) }
                    1 -> s.activate(tabs.random(random))
                    2 -> s.newDraft()
                    3 -> s.materializeDraft(pool.random(random)).also { orderPreserving = isSubsequence(before.noteIds, it.noteIds) }
                    4 -> s.close(tabs.random(random)).also { orderPreserving = isSubsequence(it.noteIds, before.noteIds) }
                    5 -> s.closeOthers(tabs.random(random)).also { orderPreserving = isSubsequence(it.noteIds, before.noteIds) }
                    6 -> s.closeAll()
                    7 -> s.remove(pool.random(random)).also { orderPreserving = isSubsequence(it.noteIds, before.noteIds) }
                    else -> s.retainOnly(pool.filter { random.nextBoolean() }.toSet())
                        .also { orderPreserving = isSubsequence(it.noteIds, before.noteIds) }
                }
                assertTrue("seed $seed: at most one draft", s.tabs.count { it == draft } <= 1)
                assertTrue("seed $seed: draft is always the last tab", s.tabs.indexOf(draft).let { it < 0 || it == s.tabs.lastIndex })
                assertTrue("seed $seed: ids stay distinct", s.noteIds.size == s.noteIds.toSet().size)
                assertTrue("seed $seed: there is always a tab", s.tabs.isNotEmpty())
                assertTrue("seed $seed: active tab is one of the tabs", s.active in s.tabs)
                assertTrue("seed $seed: order preserved by this operation", orderPreserving)
            }
        }
    }
}
