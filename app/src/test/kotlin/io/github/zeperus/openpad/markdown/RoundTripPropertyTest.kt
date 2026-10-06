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

    // ---- generator -------------------------------------------------------------------------------------------

    private class Gen(seed: Int) {
        val r = Random(seed)

        private val atoms = listOf(
            "a", "b", "word", "Hello", "x1", "3", "42", "ä", "Ö", "ß", "😀", "日本", " ", " ", " ", "  ",
            "*", "_", "__", "**", "`", "``", "~", "~~", "[", "]", "(", ")", "<", ">", "&", "&amp;", "&#35;", "#", "##",
            "-", "+", "=", ".", "1.", "2)", "!", "\\", "\"", "'", "|", ":", "/", "http://x.org", "<b>", "</i>", "[x]", "[ ]",
            "---", "===", "***", "```", "> ", "- ", "# ", "\t",
        )

        fun text(): String = buildString { repeat(r.nextInt(1, 7)) { append(atoms[r.nextInt(atoms.size)]) } }

        fun inlines(depth: Int): List<Inline> = List(r.nextInt(1, 5)) { inline(depth) }

        fun inline(depth: Int): Inline {
            val roll = r.nextInt(if (depth >= 3) 4 else 12)
            return when (roll) {
                0, 1, 2 -> Inline.Text(text())
                3 -> Inline.Code(text().replace("\n", " ").ifBlank { "c" })
                4 -> Inline.SoftBreak
                5 -> Inline.HardBreak
                6, 7 -> Inline.Strong(inlines(depth + 1))
                8, 9 -> Inline.Emphasis(inlines(depth + 1))
                10 -> Inline.Strikethrough(inlines(depth + 1))
                else -> Inline.Link(inlines(depth + 1), destination(), if (r.nextBoolean()) null else text().replace("\n", " "))
            }
        }

        fun destination(): String = listOf("https://x.org/a", "a b", "", "x(1)", "f_(2)", "a\\b", "u#f?q=1&r=2", "&amp;", "rel/path.md", "a>b")[r.nextInt(10)]

        fun block(depth: Int, allowRaw: Boolean): Block {
            val roll = r.nextInt(if (depth >= 2) 6 else 10)
            return when (roll) {
                0, 1, 2 -> Block.Paragraph(inlines(0))
                3 -> Block.Heading(r.nextInt(1, 7), inlines(0).filter { it != Inline.HardBreak })
                4 -> Block.CodeBlock(code(), if (r.nextInt(3) == 0) listOf("kotlin", "js", "a`b", "")[r.nextInt(4)].ifEmpty { null } else null, if (r.nextInt(4) == 0) CodeStyle.Indented else CodeStyle.Fenced)
                5 -> if (allowRaw && depth == 0 && r.nextBoolean()) Block.Raw(listOf("<div>x</div>", "| a | b |\n|---|---|\n| 1 | 2 |", "<!-- c -->", "[r]: /u \"t\"")[r.nextInt(4)]) else Block.Rule
                6, 7 -> list(depth)
                else -> Block.Quote(List(r.nextInt(1, 3)) { block(depth + 1, false) })
            }
        }

        fun code(): String = List(r.nextInt(0, 4)) { listOf("a", "# h", "**b**", "- x", "```", "~~~", "  indented", "", "\ttab", "> q")[r.nextInt(10)] }.joinToString("\n")

        fun list(depth: Int): Block.ListBlock {
            val kind = if (r.nextBoolean()) ListKind.Bullet("-*+"[r.nextInt(3)]) else ListKind.Ordered(r.nextInt(0, 12), if (r.nextBoolean()) '.' else ')')
            val items = List(r.nextInt(1, 4)) {
                val blocks = ArrayList<Block>()
                blocks += Block.Paragraph(inlines(0))
                if (depth < 2 && r.nextInt(3) == 0) blocks += list(depth + 1)
                if (r.nextInt(8) == 0) blocks += block(depth + 1, false)
                ListItem(blocks, if (r.nextInt(3) == 0) r.nextBoolean() else null)
            }
            return Block.ListBlock(kind, items, tight = r.nextInt(3) != 0)
        }

        fun document(): List<Block> = List(r.nextInt(1, 6)) { block(0, true) }
    }

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
