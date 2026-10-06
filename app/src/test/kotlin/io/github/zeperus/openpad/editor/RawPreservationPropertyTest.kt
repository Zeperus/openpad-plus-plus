package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Markdown the editor does not edit (tables, HTML, images, reference definitions, odd code fences) must survive any amount of
 * editing around it, byte for byte, and come back exactly with Undo.
 */
class RawPreservationPropertyTest {
    private var now = 0L

    private val raws = listOf(
        "| a | b |\n|:--|--:|\n| 1 | **2** |",
        "<div class=\"x\">\n<script>alert(1)</script>\n</div>",
        "<p>simple <strong>html</strong></p>",
        "![a cat](https://example.org/cat.png)",
        "[ref]: https://example.org \"Title\"",
        "<!-- a comment -->",
    )
    private val texts = listOf("Alpha", "Bravo **bold**", "Charlie `code`", "- one\n- two", "1. x\n2. y", "- [ ] t1\n- [x] t2", "> quote", "# Heading", "plain text")

    private fun doc(r: Random): Pair<String, List<String>> {
        val pieces = ArrayList<String>()
        val used = ArrayList<String>()
        repeat(r.nextInt(4, 9)) {
            if (r.nextInt(3) == 0) { val raw = raws[r.nextInt(raws.size)]; pieces += raw; used += raw } else pieces += texts[r.nextInt(texts.size)]
        }
        // blank lines of varying count between blocks
        val md = pieces.joinToString("") { it + "\n".repeat(r.nextInt(2, 4)) }
        return md to used
    }

    @Test fun `raw blocks survive random editing around them and Undo restores everything`() {
        repeat(400) { seed ->
            val r = Random(seed)
            val (md, used) = doc(r)
            val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
            s.smartChecklist = seed % 3 == 0
            val rawIds = s.doc.rows.filter { it.kind == RowKind.Raw }.map { it.id }.toSet()
            repeat(25) {
                val rows = s.doc.rows.filter { it.kind != RowKind.Raw && it.kind != RowKind.Rule }
                if (rows.isEmpty()) return@repeat
                val row = rows[r.nextInt(rows.size)]
                val len = row.text.length
                when (r.nextInt(9)) {
                    0, 1 -> { val at = r.nextInt(len + 1); val t = row.text.text; s.moveCursor(Cursor(row.id, at)); s.onText(row.id, t.substring(0, at) + "x y" + t.substring(at), at + 3) }
                    2 -> s.onText(row.id, row.text.text + "\n", len + 1) // Enter
                    3 -> s.backspaceAtStart(row.id)
                    4 -> { s.moveCursor(Cursor(row.id, 0)); s.toggleList(r.nextBoolean()) }
                    5 -> { s.moveCursor(Cursor(row.id, 0)); s.toggleTask() }
                    6 -> if (len > 1) { s.moveCursor(Cursor(row.id, 0, len)); s.toggleStyle(SpanKind.Bold) }
                    7 -> s.setChecked(row.id, r.nextBoolean())
                    else -> if (s.history.canUndo && r.nextBoolean()) s.undo() else s.redo()
                }
                now += 400
                s.doc.rows.filter { it.kind == RowKind.Raw }.forEach { assertTrue("a raw block was edited (seed $seed)", it.id in rawIds || it.text.text.isNotEmpty()) }
            }
            val out = s.markdown()
            for (raw in used) assertTrue("seed $seed lost a raw block:\n$raw\n--- in ---\n$out", out.contains(raw))
            while (s.undo()) { /* all the way back */ }
            assertEquals("seed $seed", md, s.markdown())
        }
    }

    @Test fun `malformed markdown never crashes the editor and is not lost`() {
        val bad = listOf(
            "**unclosed bold and `code",
            "[link](unclosed",
            "| broken | table\n|--\n| x",
            "<div><span>unclosed html",
            "```\nunterminated fence",
            "- [ ]\n- [x\n- [ ] ok",
            "\u0000 control \uD800 lone surrogate",
            "> > > > nested\n> > quote",
            "1) a\n3) b\n2) c",
            "***\n---\n___",
        )
        for (md in bad) {
            val s = EditorSession(EditorDocument.fromMarkdown(md), clock = { now })
            assertEquals("untouched text must come back as it was: <$md>", md, s.markdown())
            val row = s.doc.rows.first { it.kind != RowKind.Raw && it.kind != RowKind.Rule && it.kind !is RowKind.Code }
            s.onText(row.id, row.text.text + "!", row.text.length + 1)
            val reloaded = EditorDocument.fromMarkdown(s.markdown())
            assertTrue(reloaded.rows.isNotEmpty())
        }
    }
}
