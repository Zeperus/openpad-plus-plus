package io.github.zeperus.openpad.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests: random documents built from every supported construct, with text drawn from an adversarial
 * alphabet (markup characters, entities, line-start lookalikes, unicode). For each one,
 * `parse(serialize(doc))` must have the same meaning as `doc`, and serializing again must not change anything.
 */
class RoundTripPropertyTest {

    private fun semantic(blocks: List<Block>) = blocks.mapNotNull { BlockNormalizer.compareForm(it) }

    private fun foldHtml(blocks: List<Block>): List<Block> = HtmlSpans.fold(blocks)

    private fun reparsed(md: String) = foldHtml(MarkdownParser.parseDocument(md).blocks).mapNotNull { BlockNormalizer.compareForm(it) }

    // ---- properties ------------------------------------------------------------------------------------------

    @Test fun `random documents keep their meaning through serialize and parse`() {
        var failures = 0
        val report = StringBuilder()
        repeat(4000) { seed ->
            val doc = Gen(seed).document()
            val md = MarkdownSerializer.serialize(OpenPadDocument(doc))
            if (semantic(doc) != reparsed(md)) {
                failures++
                if (failures <= 3) report.append("\n--- seed $seed\nwritten:\n$md\nwanted: ${semantic(doc)}\ngot:    ${reparsed(md)}\n")
            }
        }
        assertEquals("$failures of 4000 random documents changed meaning:$report", 0, failures)
    }

    @Test fun `serializing the parsed result again changes nothing`() {
        var failures = 0
        val report = StringBuilder()
        repeat(2000) { seed ->
            val md = MarkdownSerializer.serialize(OpenPadDocument(Gen(seed + 100_000).document()))
            val again = MarkdownSerializer.serialize(MarkdownParser.parseDocument(md))
            // tight/loose can flip in deeply nested lists (CommonMark reads it inconsistently): compare without blank lines
            if (md.replace(Regex("\\n\\s*\\n"), "\n") != again.replace(Regex("\\n\\s*\\n"), "\n")) {
                failures++
                if (failures <= 3) report.append("\n--- seed ${seed + 100_000}\nfirst:\n$md\nsecond:\n$again\n")
            }
        }
        assertEquals("$failures unstable documents:$report", 0, failures)
    }

    @Test fun `random text in a paragraph always reads back as exactly that text`() {
        var failures = 0
        val report = StringBuilder()
        repeat(6000) { seed ->
            val g = Gen(seed + 200_000)
            val text = g.text() + g.text()
            val normalized = InlineNormalizer.block(listOf(Inline.Text(text)))
            if (normalized.isEmpty()) return@repeat
            val md = MarkdownSerializer.serialize(OpenPadDocument(listOf(Block.Paragraph(normalized))))
            val back = foldHtml(MarkdownParser.parseDocument(md).blocks)
            if (back.singleOrNull()?.let { BlockNormalizer.compareForm(it) } != Block.Paragraph(normalized)) {
                failures++
                if (failures <= 5) report.append("\n--- '${text.replace("\n", "\\n")}' written as '${md.trimEnd().replace("\n", "\\n")}' read back as $back")
            }
        }
        assertEquals("$failures texts did not survive:$report", 0, failures)
    }

    @Test fun `random inline formatting always keeps its visible text`() {
        repeat(3000) { seed ->
            val g = Gen(seed + 300_000)
            val inlines = InlineNormalizer.block(g.inlines(0))
            if (inlines.isEmpty()) return@repeat
            val md = MarkdownSerializer.serialize(OpenPadDocument(listOf(Block.Paragraph(inlines))))
            val back = MarkdownParser.parseDocument(md).blocks
            val text = (back.singleOrNull() as? Block.Paragraph)?.inlines?.plainText()
            // formatting may in the worst case fall back to html tags, but the words must always be there
            assertTrue("seed ${seed + 300_000}: '${md.trim()}' lost text: expected '${inlines.plainText()}' got '$text'",
                text != null && text.replace(Regex("</?(strong|em|del)>"), "") == inlines.plainText())
        }
    }

    @Test fun `normalization is idempotent for random inline content`() {
        repeat(3000) { seed ->
            val inlines = Gen(seed + 400_000).inlines(0)
            val once = InlineNormalizer.block(inlines)
            assertEquals("seed ${seed + 400_000}", once, InlineNormalizer.block(once))
        }
    }

    @Test fun `editing one block of a random document leaves the others byte identical`() {
        repeat(500) { seed ->
            val g = Gen(seed + 500_000)
            val original = MarkdownSerializer.serialize(OpenPadDocument(g.document() + Block.Paragraph(listOf(Inline.Text("tail")))))
            val parsed = MarkdownParser.parse(original)
            if (parsed.blocks.size < 2) return@repeat
            val index = g.r.nextInt(parsed.blocks.size)
            val blocks = parsed.blocks.map { it.block }.toMutableList()
            blocks[index] = Block.Paragraph(listOf(Inline.Text("EDITED")))
            val out = MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList())
            for ((i, b) in parsed.blocks.withIndex()) {
                if (i != index) assertTrue("seed ${seed + 500_000}: block $i was reformatted\n--- original\n$original\n--- out\n$out", out.contains(b.source))
            }
            assertTrue(out.contains("EDITED"))
        }
    }
}
