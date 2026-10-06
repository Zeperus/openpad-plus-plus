package io.github.zeperus.openpad.markdown

import io.github.zeperus.openpad.markdown.Block.CodeBlock
import io.github.zeperus.openpad.markdown.Block.Heading
import io.github.zeperus.openpad.markdown.Block.ListBlock
import io.github.zeperus.openpad.markdown.Block.Paragraph
import io.github.zeperus.openpad.markdown.Block.Quote
import io.github.zeperus.openpad.markdown.Block.Raw
import io.github.zeperus.openpad.markdown.Inline.Code
import io.github.zeperus.openpad.markdown.Inline.Emphasis
import io.github.zeperus.openpad.markdown.Inline.Link
import io.github.zeperus.openpad.markdown.Inline.Strikethrough
import io.github.zeperus.openpad.markdown.Inline.Strong
import io.github.zeperus.openpad.markdown.Inline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSerializerTest {
    private fun t(s: String) = Text(s)
    private fun p(vararg i: Inline) = Paragraph(i.toList())
    private fun md(vararg blocks: Block) = MarkdownSerializer.serialize(OpenPadDocument(blocks.toList()))
    private fun item(vararg b: Block, checked: Boolean? = null) = ListItem(b.toList(), checked)
    private fun para(text: String) = Paragraph(listOf(t(text)))

    /** What a paragraph with only this text reads back as: proves the escaping really neutralizes the markup. */
    private fun assertTextSurvives(text: String) {
        val out = md(para(text))
        val back = MarkdownParser.parseDocument(out).blocks
        assertEquals("'$text' written as '${out.trim()}'", listOf<Block>(para(text)), back.mapNotNull { BlockNormalizer.compareForm(it) }.ifEmpty { back })
    }

    // ---- Plain structure -------------------------------------------------------------------------------------

    @Test fun `empty document`() {
        assertEquals("", md())
        assertEquals("", md(Paragraph(emptyList()), Paragraph(listOf(t("  ")))))
    }

    @Test fun `blocks are separated by one blank line and the file ends with a line break`() =
        assertEquals("# T\n\ntext\n\n---\n", md(Heading(1, listOf(t("T"))), para("text"), Block.Rule))

    @Test fun `inline formatting`() =
        assertEquals(
            "a **b** *c* ~~d~~ `e` [f](https://x.org \"T\")\n",
            md(p(t("a "), Strong(listOf(t("b"))), t(" "), Emphasis(listOf(t("c"))), t(" "), Strikethrough(listOf(t("d"))), t(" "), Code("e"), t(" "), Link(listOf(t("f")), "https://x.org", "T"))),
        )

    @Test fun `nested formatting`() {
        assertEquals("**a *b* c**\n", md(p(Strong(listOf(t("a "), Emphasis(listOf(t("b"))), t(" c"))))))
        assertEquals("***x***\n", md(p(Emphasis(listOf(Strong(listOf(t("x"))))))))
        assertEquals("[a **b**](u)\n", md(p(Link(listOf(t("a "), Strong(listOf(t("b")))), "u"))))
    }

    @Test fun `whitespace inside bold is moved outside the markers`() =
        assertEquals("x **a** y\n", md(p(t("x"), Strong(listOf(t(" a "))), t("y"))))

    @Test fun `headings`() {
        assertEquals("###### Six\n", md(Heading(6, listOf(t("Six")))))
        assertEquals("#\n", md(Heading(1, emptyList())))
        assertEquals("# Costs \\#\n", md(Heading(1, listOf(t("Costs #")))))
        assertEquals("# **Bold** title\n", md(Heading(1, listOf(Strong(listOf(t("Bold"))), t(" title")))))
    }

    @Test fun `hard and soft breaks`() =
        assertEquals("a\\\nb\nc\n", md(p(t("a"), Inline.HardBreak, t("b"), Inline.SoftBreak, t("c"))))

    // ---- Lists -----------------------------------------------------------------------------------------------

    @Test fun `bullet list keeps its marker`() {
        assertEquals("- a\n- b\n", md(ListBlock(ListKind.Bullet('-'), listOf(item(para("a")), item(para("b"))))))
        assertEquals("* a\n", md(ListBlock(ListKind.Bullet('*'), listOf(item(para("a"))))))
    }

    @Test fun `ordered lists are numbered consecutively from the start number`() =
        assertEquals("3. a\n4. b\n5. c\n", md(ListBlock(ListKind.Ordered(3), listOf(item(para("a")), item(para("b")), item(para("c"))))))

    @Test fun `ordered list with paren delimiter and wide numbers`() {
        val items = (1..11).map { item(para("i$it")) }
        val out = md(ListBlock(ListKind.Ordered(1, ')'), items))
        assertTrue(out.contains("9) i9\n10) i10\n11) i11\n"))
    }

    @Test fun `nested lists are indented by the width of the parent marker`() {
        val inner = ListBlock(ListKind.Bullet(), listOf(item(para("b"))))
        val ordered = ListBlock(ListKind.Ordered(), listOf(item(para("x"), inner)))
        assertEquals("1. x\n   - b\n", md(ordered))
        assertEquals("- a\n  - b\n", md(ListBlock(ListKind.Bullet(), listOf(item(para("a"), inner)))))
    }

    @Test fun `task items`() =
        assertEquals("- [ ] Milk\n- [x] Bread\n- plain\n", md(ListBlock(ListKind.Bullet(), listOf(item(para("Milk"), checked = false), item(para("Bread"), checked = true), item(para("plain"))))))

    @Test fun `loose lists have blank lines between items`() =
        assertEquals("- a\n\n- b\n", md(ListBlock(ListKind.Bullet(), listOf(item(para("a")), item(para("b"))), tight = false)))

    @Test fun `an item with two paragraphs makes the list loose`() {
        val out = md(ListBlock(ListKind.Bullet(), listOf(item(para("a"), para("a2")), item(para("b"))), tight = true))
        assertEquals("- a\n\n  a2\n\n- b\n", out)
    }

    @Test fun `empty list items`() {
        assertEquals("- a\n-\n- c\n", md(ListBlock(ListKind.Bullet(), listOf(item(para("a")), item(Paragraph(emptyList())), item(para("c"))))))
    }

    @Test fun `adjacent lists of the same kind get different markers so they stay separate`() {
        val a = ListBlock(ListKind.Bullet('-'), listOf(item(para("a"))))
        val b = ListBlock(ListKind.Bullet('-'), listOf(item(para("b"))))
        val out = md(a, b)
        assertEquals("- a\n\n* b\n", out)
        assertEquals(2, MarkdownParser.parseDocument(out).blocks.size)
        val oa = ListBlock(ListKind.Ordered(1, '.'), listOf(item(para("a"))))
        assertEquals("1. a\n\n1) a\n", md(oa, oa))
    }

    @Test fun `code inside list items`() {
        val out = md(ListBlock(ListKind.Bullet(), listOf(item(para("a"), CodeBlock("x\ny", "kt")))))
        assertEquals("- a\n  ```kt\n  x\n  y\n  ```\n", out)
        assertEquals(1, MarkdownParser.parseDocument(out).blocks.size)
    }

    // ---- Quotes, code, rules, raw ----------------------------------------------------------------------------

    @Test fun `quote prefixes every line including blank ones`() =
        assertEquals("> a\n>\n> b\n", md(Quote(listOf(para("a"), para("b")))))

    @Test fun `quote with a list`() =
        assertEquals("> - a\n> - b\n", md(Quote(listOf(ListBlock(ListKind.Bullet(), listOf(item(para("a")), item(para("b"))))))))

    @Test fun `empty quote`() = assertEquals(">\n", md(Quote(emptyList())))

    @Test fun `fenced code is written without interpreting its content`() =
        assertEquals("```kotlin\n# h\n**b**\n```\n", md(CodeBlock("# h\n**b**", "kotlin")))

    @Test fun `fence is longer than any fence inside the code`() {
        val out = md(CodeBlock("```\nx\n```", null))
        assertEquals("````\n```\nx\n```\n````\n", out)
        assertEquals(CodeBlock("```\nx\n```", null), MarkdownParser.parseDocument(out).blocks.single())
    }

    @Test fun `empty code block and an info string with backticks`() {
        assertEquals("```\n```\n", md(CodeBlock("", null)))
        assertEquals("~~~a`b\nx\n~~~\n", md(CodeBlock("x", "a`b")))
    }

    @Test fun `indented code stays indented, but not right after a list`() {
        assertEquals("    a\n    b\n", md(CodeBlock("a\nb", null, CodeStyle.Indented)))
        val list = ListBlock(ListKind.Bullet(), listOf(item(para("a"))))
        val out = md(list, CodeBlock("c", null, CodeStyle.Indented))
        assertEquals("- a\n\n```\nc\n```\n", out)
    }

    @Test fun `raw blocks are written verbatim`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals("$table\n\ntext\n", md(Raw(table), para("text")))
    }

    // ---- Escaping --------------------------------------------------------------------------------------------

    @Test fun `characters that would start markup are escaped`() {
        assertEquals("\\*star\\* \\_under\\_ \\`tick\\` \\[a\\](b) \\\\\n", md(para("*star* _under_ `tick` [a](b) \\")))
    }

    @Test fun `intraword underscores are left alone`() =
        assertEquals("snake_case_name\n", md(para("snake_case_name")))

    @Test fun `tildes entities and html are escaped`() {
        assertEquals("\\~\\~x\\~\\~ and ~y~\n", md(para("~~x~~ and ~y~")))
        assertEquals("a & b \\&amp; \\&#35;\n", md(para("a & b &amp; &#35;")))
        assertEquals("\\<b>x\\</b> 1 < 2\n", md(para("<b>x</b> 1 < 2")))
    }

    @Test fun `text that looks like a block start is neutralized`() {
        for (text in listOf("# h", "## h", "###### h", "#", "> q", ">q", "- a", "+ a", "-", "1. a", "12) a", "---", "- - -", "===", "=", "***", "___", "```", "~~~", "    code", "[ ] x", "[x] x", "<div>", "<!-- c -->", "| a | b |", "a  ", "\\")) {
            assertTextSurvives(text.trim().ifEmpty { text })
        }
    }

    @Test fun `block start lookalikes after a line break are neutralized too`() {
        val out = md(p(t("intro"), Inline.SoftBreak, t("- not a list"), Inline.SoftBreak, t("# nor a heading"), Inline.SoftBreak, t("1. nor this"), Inline.SoftBreak, t("---")))
        val back = MarkdownParser.parseDocument(out).blocks.single() as Paragraph
        assertEquals("intro\n- not a list\n# nor a heading\n1. nor this\n---", back.inlines.plainText())
    }

    @Test fun `trailing spaces before a line break cannot become a hard break`() {
        val out = md(p(t("a  "), Inline.SoftBreak, t("b")))
        assertEquals(listOf<Inline>(t("a"), Inline.SoftBreak, t("b")), (MarkdownParser.parseDocument(out).blocks.single() as Paragraph).inlines)
    }

    @Test fun `code spans with backticks and padding`() {
        assertEquals("``a`b``\n", md(p(Code("a`b"))))
        assertEquals("`` `x` ``\n", md(p(Code("`x`"))))
        assertEquals("`  a  `\n", md(p(Code(" a "))))
        assertEquals(p(Code(" a ")), MarkdownParser.parseDocument(md(p(Code(" a ")))).blocks.single())
    }

    @Test fun `link destinations and titles`() {
        assertEquals("[a](<my file.pdf>)\n", md(p(Link(listOf(t("a")), "my file.pdf"))))
        assertEquals("[a](<>)\n", md(p(Link(listOf(t("a")), ""))))
        assertEquals("[a](https://x.org/f_\\(1\\))\n", md(p(Link(listOf(t("a")), "https://x.org/f_(1)"))))
        assertEquals("[a](u \"say \\\"hi\\\"\")\n", md(p(Link(listOf(t("a")), "u", "say \"hi\""))))
        for (dest in listOf("a b", "", "x(1)", "a\\b", "&amp;", "a<b>c", "u#frag?q=1&r=2")) {
            val out = md(p(Link(listOf(t("l")), dest, "ti\"tle")))
            assertEquals(dest, p(Link(listOf(t("l")), dest, "ti\"tle")), MarkdownParser.parseDocument(out).blocks.single())
        }
    }

    // ---- Verification fallback -------------------------------------------------------------------------------

    @Test fun `delimiter edge cases still read back with the same text`() {
        // "**(foo)**bar" is not valid bold in CommonMark (closing ** after punctuation, before a letter)
        val doc = p(Strong(listOf(t("(foo)"))), t("bar"))
        val out = md(doc)
        val back = MarkdownParser.parseDocument(out).blocks.single() as Paragraph
        assertEquals("(foo)bar", back.inlines.plainText().replace(Regex("</?strong>"), ""))
        assertFalse(out.contains("**(foo)**bar"))
    }

    @Test fun `bold directly before italic stays two styles`() {
        val doc = p(Strong(listOf(t("a"))), Emphasis(listOf(t("b"))))
        assertEquals(listOf<Block>(doc), MarkdownParser.parseDocument(md(doc)).blocks)
    }

    // ---- Line endings ----------------------------------------------------------------------------------------

    @Test fun `crlf output`() =
        assertEquals("# T\r\n\r\ntext\r\n", MarkdownSerializer.serialize(OpenPadDocument(listOf(Heading(1, listOf(t("T"))), para("text"))), "\r\n"))

    // ---- Incremental serialization: untouched parts stay byte-identical --------------------------------------

    private fun inc(original: String, edit: (List<Block>) -> List<Block>, origins: (Int) -> List<Int?> = { n -> (0 until n).toList() }): String {
        val parsed = MarkdownParser.parse(original)
        val blocks = parsed.blocks.map { it.block }
        val edited = edit(blocks)
        return MarkdownSerializer.serializeIncremental(parsed, edited, origins(blocks.size).take(edited.size).let { o -> o + List(edited.size - o.size) { null } })
    }

    @Test fun `unchanged document is written back exactly`() {
        val original = "\n\n__Setext__\n=====\n\n* odd   bullets\n*   second\n\n   Indented *paragraph*  with   spaces  \n\n\n\n<div>raw</div>\n\n1. a\n1. b\n\n"
        assertEquals(original, inc(original, { it }))
    }

    @Test fun `only the edited block is regenerated`() {
        val original = "# Title\n\n* a\n* b\n\nsome   odd  *spacing*   here\n\n__keep__ me\n"
        val out = inc(original, { b ->
            val list = b[1] as ListBlock
            listOf(b[0], list.copy(items = list.items + item(para("c"))), b[2], b[3])
        })
        assertEquals("# Title\n\n* a\n* b\n* c\n\nsome   odd  *spacing*   here\n\n__keep__ me\n", out)
    }

    @Test fun `text typed into one paragraph does not reformat its neighbours`() {
        val original = "* x\n* y\n\nfirst *p*\n\nsecond __p__\n"
        val out = inc(original, { b -> listOf(b[0], Paragraph(listOf(t("first "), Emphasis(listOf(t("p"))), t(" typed"))), b[2]) })
        assertEquals("* x\n* y\n\nfirst *p* typed\n\nsecond __p__\n", out)
    }

    @Test fun `inserted and deleted blocks`() {
        val original = "a\n\nb\n\nc\n"
        assertEquals("a\n\nnew\n\nb\n\nc\n", inc(original, { b -> listOf(b[0], para("new"), b[1], b[2]) }, { listOf(0, null, 1, 2) }))
        assertEquals("a\n\nc\n", inc(original, { b -> listOf(b[0], b[2]) }, { listOf(0, 2) }))
        assertEquals("b\n", inc(original, { b -> listOf(b[1]) }, { listOf(1) }))
    }

    @Test fun `leading and trailing whitespace of an unchanged document is kept`() {
        val original = "\n\n  \ntext\n\n\n"
        assertEquals(original, inc(original, { it }))
    }

    @Test fun `appending a block to a document without a final newline`() {
        assertEquals("a\n\nb\n", inc("a", { b -> b + para("b") }))
    }

    @Test fun `crlf documents stay crlf`() {
        val original = "# T\r\n\r\ntext\r\n"
        assertEquals(original, inc(original, { it }))
        val out = inc(original, { b -> b + ListBlock(ListKind.Bullet(), listOf(item(para("a")), item(para("b")))) }, { listOf(0, 1, null) })
        assertEquals("# T\r\n\r\ntext\r\n\r\n- a\r\n- b\r\n", out)
        assertFalse(out.replace("\r\n", "").contains('\n'))
    }

    @Test fun `a changed block is not mistaken for its origin`() {
        val original = "a\n\nb\n"
        assertEquals("a\n\nB\n", inc(original, { b -> listOf(b[0], para("B")) }))
    }

    @Test fun `empty paragraphs the editor keeps in memory do not reach the file`() {
        val original = "a\n\nb\n"
        assertEquals("a\n\nb\n", inc(original, { b -> listOf(b[0], Paragraph(emptyList()), b[1]) }, { listOf(0, null, 1) }))
    }

    @Test fun `without a layout everything is regenerated`() =
        assertEquals("# T\n", MarkdownSerializer.serializeIncremental(null, listOf(Heading(1, listOf(t("T")))), listOf(null)))

    @Test fun `new list next to an unchanged list of the same marker stays separate`() {
        val original = "- a\n"
        val parsed = MarkdownParser.parse(original)
        val first = parsed.blocks[0].block
        val out = MarkdownSerializer.serializeIncremental(parsed, listOf(first, ListBlock(ListKind.Bullet('-'), listOf(item(para("b"))))), listOf(0, null))
        assertEquals(2, MarkdownParser.parseDocument(out).blocks.size)
    }
}
