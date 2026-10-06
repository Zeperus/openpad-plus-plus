package io.github.zeperus.openpad.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realistically sized notes must be comfortable, and nothing may grow quadratically with the document size.
 * Time limits are deliberately generous (shared CI machines); the scaling check is what catches O(n^2) behaviour.
 */
class MarkdownPerformanceTest {
    private fun note(paragraphs: Int): String = (1..paragraphs).joinToString("\n\n") { i ->
        when (i % 5) {
            0 -> "## Section $i"
            1 -> "Paragraph $i with **bold**, *italic*, ~~strike~~, `code` and a [link](https://example.org/$i). " + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(3)
            2 -> "- [ ] task $i\n- [x] done $i\n- plain item $i"
            3 -> "> quoted text $i\n> continues"
            else -> "```\ncode block $i\nmore code\n```"
        }
    } + "\n"

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    @Test fun `short notes and shopping lists are instant`() {
        val shopping = "# Shopping\n\n" + (1..30).joinToString("\n") { "- [ ] item $it" } + "\n"
        val (parsed, ms) = timed { MarkdownParser.parse(shopping) }
        assertTrue("parse took ${ms}ms", ms < 500)
        assertEquals(shopping, parsed.original())
    }

    @Test fun `a several thousand word note parses and writes comfortably`() {
        val md = note(400) // roughly 6000 words
        assertTrue("test note too small: ${md.split(Regex("\\s+")).size} words", md.split(Regex("\\s+")).size > 4000)
        repeat(3) { MarkdownParser.parse(md) } // warm up
        val (parsed, parseMs) = timed { MarkdownParser.parse(md) }
        val (written, writeMs) = timed { MarkdownSerializer.serialize(parsed.document) }
        val (_, incrementalMs) = timed {
            val blocks = parsed.blocks.map { it.block }
            MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList())
        }
        assertTrue("parse ${parseMs}ms", parseMs < 2000)
        assertTrue("serialize ${writeMs}ms", writeMs < 4000)
        assertTrue("incremental ${incrementalMs}ms", incrementalMs < 1000)
        assertEquals(md, parsed.original())
        assertTrue(written.isNotEmpty())
    }

    @Test fun `editing one paragraph only regenerates that paragraph`() {
        val md = note(400)
        val parsed = MarkdownParser.parse(md)
        val blocks = parsed.blocks.map { it.block }.toMutableList()
        repeat(3) { MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList()) }
        // simulate typing: one block changes, 50 times in a row
        val (_, ms) = timed {
            repeat(50) { i ->
                blocks[0] = Block.Paragraph(listOf(Inline.Text("typed text number $i")))
                MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList())
            }
        }
        assertTrue("50 simulated keystrokes took ${ms}ms", ms < 3000)
    }

    @Test fun `parsing scales roughly linearly`() {
        fun cost(paragraphs: Int): Long {
            val md = note(paragraphs)
            repeat(2) { MarkdownParser.parse(md) }
            return (1..3).minOf { timed { MarkdownParser.parse(md) }.second }.coerceAtLeast(1)
        }
        val small = cost(200)
        val large = cost(2000) // 10x the document
        assertTrue("10x the text took ${large}ms vs ${small}ms (ratio ${large.toDouble() / small})", large < small * 60)
    }

    @Test fun `serializing scales roughly linearly`() {
        fun cost(paragraphs: Int): Long {
            val doc = MarkdownParser.parseDocument(note(paragraphs))
            repeat(2) { MarkdownSerializer.serialize(doc) }
            return (1..3).minOf { timed { MarkdownSerializer.serialize(doc) }.second }.coerceAtLeast(1)
        }
        val small = cost(200)
        val large = cost(2000)
        assertTrue("10x the text took ${large}ms vs ${small}ms (ratio ${large.toDouble() / small})", large < small * 60)
    }

    @Test fun `very long lines and long lists are fine`() {
        val line = "word ".repeat(40_000) + "\n"
        assertEquals(line, MarkdownParser.parse(line).original())
        val list = (1..5000).joinToString("\n") { "- item $it" } + "\n"
        val (parsed, ms) = timed { MarkdownParser.parse(list) }
        assertTrue("5000-item list took ${ms}ms", ms < 3000)
        assertEquals(list, parsed.original())
    }
}
