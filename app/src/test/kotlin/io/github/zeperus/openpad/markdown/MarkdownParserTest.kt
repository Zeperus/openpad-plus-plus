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

class MarkdownParserTest {
    private fun blocks(md: String) = MarkdownParser.parseDocument(md).blocks
    private fun single(md: String) = blocks(md).single()
    private fun t(s: String) = Text(s)

    // ---- Blocks ----------------------------------------------------------------------------------------------

    @Test fun `empty and blank documents have no blocks`() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("\n\n  \n").isEmpty())
    }

    @Test fun `paragraphs are separated by blank lines`() {
        assertEquals(listOf(Paragraph(listOf(t("one"))), Paragraph(listOf(t("two")))), blocks("one\n\ntwo\n"))
    }

    @Test fun `headings of every level and setext headings`() {
        for (level in 1..6) assertEquals(Heading(level, listOf(t("Title"))), single("#".repeat(level) + " Title"))
        assertEquals(Heading(1, listOf(t("Setext"))), single("Setext\n======"))
        assertEquals(Heading(2, listOf(t("Setext"))), single("Setext\n------"))
        assertEquals(Heading(1, listOf(t("Closed"))), single("# Closed ##"))
    }

    @Test fun `seven hashes are just text`() =
        assertEquals(Paragraph(listOf(t("####### x"))), single("####### x"))

    @Test fun `thematic break`() {
        for (md in listOf("---", "***", "___", "- - -")) assertEquals(md, Block.Rule, single(md))
    }

    @Test fun `fenced code keeps its content verbatim and the info string`() {
        assertEquals(CodeBlock("# not a heading\n**not bold**", "kotlin"), single("```kotlin\n# not a heading\n**not bold**\n```"))
        assertEquals(CodeBlock("a\n\nb", null), single("~~~\na\n\nb\n~~~"))
        assertEquals(CodeBlock("", null), single("```\n```"))
    }

    @Test fun `unterminated fence runs to the end of the document`() =
        assertEquals(CodeBlock("still code", null), single("```\nstill code"))

    @Test fun `fence can contain a shorter fence`() =
        assertEquals(CodeBlock("```\ninner\n```", null), single("````\n```\ninner\n```\n````"))

    @Test fun `indented code`() =
        assertEquals(CodeBlock("a\n  b", null, CodeStyle.Indented), single("    a\n      b"))

    @Test fun `block quote with several paragraphs`() =
        assertEquals(Quote(listOf(Paragraph(listOf(t("a"))), Paragraph(listOf(t("b"))))), single("> a\n>\n> b"))

    @Test fun `lazy continuation stays inside the quote paragraph`() =
        assertEquals(Quote(listOf(Paragraph(listOf(t("a"), Inline.SoftBreak, t("b"))))), single("> a\nb"))

    // ---- Lists -----------------------------------------------------------------------------------------------

    @Test fun `bullet lists keep their marker and tightness`() {
        val list = single("* a\n* b") as ListBlock
        assertEquals(ListKind.Bullet('*'), list.kind)
        assertTrue(list.tight)
        assertEquals(listOf(ListItem(listOf(Paragraph(listOf(t("a"))))), ListItem(listOf(Paragraph(listOf(t("b")))))), list.items)
        assertFalse((single("- a\n\n- b") as ListBlock).tight)
    }

    @Test fun `ordered lists keep start and delimiter`() {
        val list = single("3) a\n4) b") as ListBlock
        assertEquals(ListKind.Ordered(3, ')'), list.kind)
        assertEquals(2, list.items.size)
    }

    @Test fun `nested lists`() {
        val list = single("- a\n  - b\n    - c\n- d") as ListBlock
        assertEquals(2, list.items.size)
        val inner = list.items[0].blocks[1] as ListBlock
        assertEquals(listOf(Paragraph(listOf(t("b")))), inner.items[0].blocks.take(1))
        assertTrue(inner.items[0].blocks[1] is ListBlock)
    }

    @Test fun `task list items have an explicit checked state`() {
        val list = single("- [ ] Milk\n- [x] Bread\n- [X] Eggs\n- plain") as ListBlock
        assertEquals(listOf(false, true, true, null), list.items.map { it.checked })
        assertEquals(listOf(Paragraph(listOf(t("Milk")))), list.items[0].blocks)
        assertEquals(listOf(Paragraph(listOf(t("Bread")))), list.items[1].blocks)
        assertFalse(list.isTaskList) // mixed with a plain item
        assertTrue((single("- [ ] a\n- [x] b") as ListBlock).isTaskList)
    }

    @Test fun `task text can be formatted`() {
        val list = single("- [x] **done** thing") as ListBlock
        assertEquals(listOf(Paragraph(listOf(Strong(listOf(t("done"))), t(" thing")))), list.items[0].blocks)
        assertEquals(true, list.items[0].checked)
    }

    @Test fun `brackets that are not a task marker stay text`() {
        val list = single("- [] a\n- [y] b\n- [ ]c") as ListBlock
        assertEquals(listOf<Boolean?>(null, null, null), list.items.map { it.checked })
    }

    @Test fun `list item containing code and quote`() {
        val list = single("- a\n\n  ```\n  code\n  ```\n\n  > q") as ListBlock
        val blocks = list.items.single().blocks
        assertEquals(CodeBlock("code", null), blocks[1])
        assertTrue(blocks[2] is Quote)
        assertFalse(list.tight)
    }

    // ---- Inlines ---------------------------------------------------------------------------------------------

    @Test fun `bold italic strikethrough and code`() {
        assertEquals(
            Paragraph(listOf(t("a "), Strong(listOf(t("b"))), t(" "), Emphasis(listOf(t("c"))), t(" "), Strikethrough(listOf(t("d"))), t(" "), Code("e"))),
            single("a **b** *c* ~~d~~ `e`"),
        )
    }

    @Test fun `underscores and asterisks give the same meaning`() =
        assertEquals(single("__b__ _i_"), single("**b** *i*"))

    @Test fun `single tildes are not strikethrough`() =
        assertEquals(Paragraph(listOf(t("~a~ and ~~b~~").let { Text("~a~ and ") }, Strikethrough(listOf(t("b"))))), single("~a~ and ~~b~~"))

    @Test fun `italic inside bold and bold inside italic`() {
        assertEquals(Paragraph(listOf(Strong(listOf(t("a "), Emphasis(listOf(t("b"))), t(" c"))))), single("**a *b* c**"))
        assertEquals(Paragraph(listOf(Emphasis(listOf(t("a "), Strong(listOf(t("b"))), t(" c"))))), single("*a **b** c*"))
    }

    @Test fun `triple asterisks are italic around bold`() =
        assertEquals(Paragraph(listOf(Emphasis(listOf(Strong(listOf(t("x"))))))), single("***x***"))

    @Test fun `links with formatted text title and angle brackets`() {
        assertEquals(Paragraph(listOf(Link(listOf(t("a "), Strong(listOf(t("b")))), "https://x.org", null))), single("[a **b**](https://x.org)"))
        assertEquals(Paragraph(listOf(Link(listOf(t("t")), "u", "Title"))), single("[t](u \"Title\")"))
        assertEquals(Paragraph(listOf(Link(listOf(t("t")), "a b", null))), single("[t](<a b>)"))
    }

    @Test fun `autolinks become links and bare urls stay text`() {
        assertEquals(Paragraph(listOf(Link(listOf(t("https://a.org")), "https://a.org", null))), single("<https://a.org>"))
        assertEquals(Paragraph(listOf(t("see https://a.org now"))), single("see https://a.org now"))
    }

    @Test fun `escapes and entities are decoded to plain text`() {
        assertEquals(Paragraph(listOf(t("*not italic* & <b>"))), single("\\*not italic\\* &amp; &lt;b>"))
    }

    @Test fun `soft and hard line breaks`() {
        assertEquals(Paragraph(listOf(t("a"), Inline.SoftBreak, t("b"))), single("a\nb"))
        assertEquals(Paragraph(listOf(t("a"), Inline.HardBreak, t("b"))), single("a\\\nb"))
        assertEquals(Paragraph(listOf(t("a"), Inline.HardBreak, t("b"))), single("a  \nb"))
    }

    @Test fun `code span edge cases`() {
        assertEquals(Paragraph(listOf(Code("a`b"))), single("``a`b``"))
        assertEquals(Paragraph(listOf(Code("`x`"))), single("`` `x` ``"))
    }

    @Test fun `unicode german umlauts and emoji`() =
        assertEquals(Paragraph(listOf(t("Größe: Äpfel, Öl, Übung — 😀 👨‍👩‍👧 日本語"))), single("Größe: Äpfel, Öl, Übung — 😀 👨‍👩‍👧 日本語"))

    // ---- Unsupported constructs are kept, not dropped --------------------------------------------------------

    @Test fun `html blocks are kept raw`() =
        assertEquals(Raw("<div class=\"x\">\n  hi\n</div>"), single("<div class=\"x\">\n  hi\n</div>"))

    @Test fun `tables are kept raw and untouched`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(Raw(table), single(table))
    }

    @Test fun `link reference definitions are kept raw`() {
        val doc = blocks("[foo]: https://example.org \"T\"\n\ntext [foo]")
        assertEquals(Raw("[foo]: https://example.org \"T\""), doc[0])
        assertTrue(doc[1] is Paragraph)
    }

    @Test fun `images and inline html are kept as raw inlines`() {
        assertEquals(Paragraph(listOf(t("a "), Inline.Raw("![alt text](pic.png)"), t(" b"))), single("a ![alt text](pic.png) b"))
        assertEquals(Paragraph(listOf(t("x "), Inline.Raw("<br>"), t(" y"))), single("x <br> y"))
    }

    @Test fun `image with title and spaces in the destination survives`() {
        val p = single("![a](<my pic.png> \"cap\")") as Paragraph
        assertEquals(listOf<Inline>(Inline.Raw("![a](<my pic.png> \"cap\")")), p.inlines)
    }

    @Test fun `nested html block inside a list is kept raw without the list indentation`() {
        val list = single("- item\n\n  <div>\n  x\n  </div>") as ListBlock
        assertEquals(Raw("<div>\nx\n</div>"), list.items.single().blocks[1])
    }

    // ---- Layout ----------------------------------------------------------------------------------------------

    @Test fun `layout reproduces the original text exactly`() {
        val inputs = listOf(
            "", "\n", "a", "a\n", "\n\n# T\n\n\n\ntext\n\n\n", "- a\n- b\n\ntext", "> q\n\n```\ncode\n```\n---\n",
            "a\r\nb\r\n\r\n# T\r\n", "  indented para\n\n   - list\n", "<div>\nx\n</div>\n\n| a |\n|---|\n", "[r]: /u\n\n[r]\n",
        )
        for (md in inputs) {
            val parsed = MarkdownParser.parse(md)
            assertTrue(md, parsed.layoutValid)
            assertEquals(md, parsed.original())
        }
    }

    @Test fun `line ending is detected`() {
        assertEquals("\r\n", MarkdownParser.parse("a\r\n\r\nb\r\n").eol)
        assertEquals("\n", MarkdownParser.parse("a\n\nb\n").eol)
        assertEquals("\n", MarkdownParser.parse("a").eol)
        assertEquals("\n", MarkdownParser.parse("a\n\nb\r\nc\n").eol) // mixed: LF wins when it is more common
    }

    @Test fun `block sources are whole lines`() {
        val parsed = MarkdownParser.parse("# T\n\n- a\n- b\n\ntext\n")
        assertEquals(listOf("# T", "- a\n- b", "text"), parsed.blocks.map { it.source })
        assertEquals(listOf("\n\n", "\n\n", "\n"), parsed.blocks.map { it.gapAfter })
    }

    // ---- Rules found by the property tests -------------------------------------------------------------------

    @Test fun `html tags for bold italic and strike are read as those styles`() {
        assertEquals(Paragraph(listOf(t("a"), Strong(listOf(t("b"))), Emphasis(listOf(t("c"))), Strikethrough(listOf(t("d"))))), single("a<strong>b</strong><em>c</em><del>d</del>"))
        assertEquals(Paragraph(listOf(Link(listOf(t("l")), "u&v", "T"))), single("<a href=\"u&amp;v\" title=\"T\">l</a>"))
    }

    @Test fun `unclosed or other html stays raw`() {
        assertEquals(Paragraph(listOf(Inline.Raw("<strong>"), t("x"))), single("<strong>x"))
        assertEquals(Paragraph(listOf(Inline.Raw("<b>"), t("x"), Inline.Raw("</b>"))), single("<b>x</b>"))
    }

    @Test fun `the style of the delimiter does not matter`() {
        assertEquals(single("**a** *b*"), single("<strong>a</strong> <em>b</em>"))
    }

    @Test fun `text that looks like a task marker at the start of a list item is a task`() {
        assertEquals(true, (single("- \\[x\\] literal") as ListBlock).items[0].checked)
        assertEquals(Paragraph(listOf(t("literal"))), (single("- \\[x\\] literal") as ListBlock).items[0].blocks[0].let { BlockNormalizer.taskForm(ListItem(listOf(it), null)).blocks[0] })
    }
}
