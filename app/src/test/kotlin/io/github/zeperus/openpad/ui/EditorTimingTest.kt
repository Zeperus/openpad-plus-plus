package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import io.github.zeperus.openpad.ui.editor.EditorTiming
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EditorTimingTest {

    @Before
    fun reset() {
        EditorDiagnostics.resetTiming()
    }

    @Test
    fun recordsEditsAndOpens() {
        EditorDiagnostics.recordEdit(1_000_000L)
        EditorDiagnostics.recordEdit(3_000_000L)
        EditorDiagnostics.recordEdit(2_000_000L)
        EditorDiagnostics.recordOpen(5_000_000L)

        val timing = EditorDiagnostics.timing
        assertEquals(3, timing.edits)
        assertEquals(2.0, timing.lastEditMs, 0.0001)
        assertEquals(3.0, timing.maxEditMs, 0.0001)
        assertEquals(2.0, timing.avgEditMs, 0.0001)
        assertEquals(1, timing.opens)
        assertEquals(5.0, timing.lastOpenMs, 0.0001)
        assertEquals(5.0, timing.maxOpenMs, 0.0001)
    }

    @Test
    fun resetGivesZeros() {
        EditorDiagnostics.recordEdit(4_000_000L)
        EditorDiagnostics.recordOpen(7_000_000L)

        EditorDiagnostics.resetTiming()

        assertEquals(EditorTiming(0, 0.0, 0.0, 0.0, 0, 0.0, 0.0), EditorDiagnostics.timing)
    }

    @Test
    fun averageIsZeroWithoutEdits() {
        EditorDiagnostics.recordOpen(2_000_000L)

        assertEquals(0.0, EditorDiagnostics.timing.avgEditMs, 0.0)
    }
}
