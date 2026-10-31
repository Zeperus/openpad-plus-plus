package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberingTest {
    @Test fun `the one-pass numbering agrees with numberOf`() {
        val md = "1. a\n2. b\n   1. x\n   2. y\n3. c\n\ntext\n\n5. e\n6. f\n\n- bullet\n\n1) p\n2) q\n"
        val doc = EditorDocument.fromMarkdown(md)
        val numbers = Numbering.of(doc.rows)
        for ((i, row) in doc.rows.withIndex()) assertEquals("row $i", doc.numberOf(i), numbers[row.id])
    }
}
