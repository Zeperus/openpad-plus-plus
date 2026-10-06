package io.github.zeperus.openpad.markdown

import kotlin.random.Random

/** Random documents built from every construct, with text from an adversarial alphabet. */
internal class Gen(seed: Int) {
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
