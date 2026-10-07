package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTypographyTest {
    private val sizes = listOf(12, 16, 20, 24, 28)

    @Test fun `body, lists, checklists and quotes use the base size`() {
        for (b in sizes) assertEquals(b.toFloat(), EditorTypography(b.toFloat()).body, 0f)
    }

    @Test fun `headings scale from the base with fixed ratios`() {
        val t = EditorTypography(20f)
        assertEquals(36f, t.headingSize(1), 0.001f)
        assertEquals(31f, t.headingSize(2), 0.001f)
        assertEquals(27f, t.headingSize(3), 0.001f)
        assertEquals(24f, t.headingSize(4), 0.001f)
        assertEquals(22f, t.headingSize(5), 0.001f)
        assertEquals(20f, t.headingSize(6), 0.001f)
        // doubling the base doubles every heading
        val d = EditorTypography(40f)
        for (level in 1..6) assertEquals(t.headingSize(level) * 2, d.headingSize(level), 0.001f)
    }

    @Test fun `the hierarchy is kept at every size`() {
        for (b in sizes) {
            val t = EditorTypography(b.toFloat())
            val heading = (1..6).map { t.headingSize(it) }
            assertEquals(heading.sortedDescending(), heading)
            assertTrue(heading[0] > t.body)
            assertEquals(t.body, heading[5], 0f)
        }
    }

    @Test fun `body line height is 1_5 x the size and the default stays 24 sp`() {
        for (b in sizes) assertEquals(b * 1.5f, EditorTypography(b.toFloat()).bodyLineHeight, 0.001f)
        assertEquals(24f, EditorTypography(16f).bodyLineHeight, 0f)
    }

    @Test fun `no line is ever shorter than its text and headings never get less than a body line`() {
        for (b in sizes) {
            val t = EditorTypography(b.toFloat())
            for (level in 1..6) {
                assertTrue(t.headingLineHeight(level) >= t.headingSize(level) * 1.25f - 0.001f) // never clipped
                assertTrue(t.headingLineHeight(level) >= t.bodyLineHeight)
            }
            assertTrue(t.codeLineHeight >= t.code * 1.2f)
            // never double spaced either: a body line is at most 1.5 x the text
            assertTrue(t.bodyLineHeight <= t.body * 1.5f + 0.001f)
        }
    }

    @Test fun `line heights scale linearly with the base`() {
        val a = EditorTypography(12f)
        val b = EditorTypography(24f)
        assertEquals(a.bodyLineHeight * 2, b.bodyLineHeight, 0.001f)
        assertEquals(a.headingLineHeight(1) * 2, b.headingLineHeight(1), 0.001f)
        assertEquals(a.codeLineHeight * 2, b.codeLineHeight, 0.001f)
    }

    @Test fun `code and tables follow the base too`() {
        assertEquals(14f, EditorTypography(16f).code, 0f)
        assertEquals(14f, EditorTypography(16f).table, 0f)
        assertEquals(24.5f, EditorTypography(28f).code, 0f)
    }

    @Test fun `the marker column grows with the text and the checkbox stays within sensible limits`() {
        assertEquals(32f, EditorTypography(16f).markerColumn, 0f)
        assertEquals(56f, EditorTypography(28f).markerColumn, 0f)
        for (b in sizes) {
            val s = EditorTypography(b.toFloat()).checkboxScale
            assertTrue(s in 0.75f..1.75f)
        }
        assertEquals(1f, EditorTypography(16f).checkboxScale, 0f)
    }
}
