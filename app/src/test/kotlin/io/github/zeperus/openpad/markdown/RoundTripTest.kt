package io.github.zeperus.openpad.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown -> parse -> model -> serialize -> parse again: the meaning must survive. Checked for a corpus of
 * realistic and nasty documents. For every document:
 *  1. the parser's layout reproduces the original text exactly,
 *  2. serializing the model and parsing it again gives the same model (semantic fixpoint),
 *  3. the serialized text is stable (serializing that again changes nothing),
 *  4. writing an *unchanged* document back incrementally gives back the original bytes.
 */
class RoundTripTest {
    companion object {
        private val long = "word ".repeat(5000).trim()

        val corpus: List<Pair<String, String>> = listOf(
            "empty" to "",
            "only newline" to "\n",
            "only blank lines" to "\n\n   \n\t\n",
            "single word" to "hello",
            "single word with newline" to "hello\n",
            "two paragraphs" to "one\n\ntwo\n",
            "many blank lines between" to "one\n\n\n\n\ntwo\n\n\n",
            "leading blank lines" to "\n\n\none\n",
            "heading and text" to "# Shopping\n\nBuy **these things** today.\n",
            "all heading levels" to (1..6).joinToString("\n\n") { "#".repeat(it) + " H$it" } + "\n",
            "setext headings" to "Title\n=====\n\nSub\n---\n\ntext\n",
            "atx with closing hashes" to "## Closed ##\n",
            "shopping list" to "# Shopping\n\n- Milk\n- Bread\n- [ ] Cheese\n",
            "task list" to "- [ ] Milk\n- [x] Bread\n- [X] Eggs\n",
            "task list star marker" to "* [ ] a\n* [x] b\n",
            "task with formatting" to "- [x] ~~done~~ **thing** with `code` and [link](http://x.org)\n",
            "nested tasks" to "- [ ] parent\n  - [x] child\n  - [ ] child 2\n",
            "bullets star" to "* a\n* b\n* c\n",
            "bullets plus" to "+ a\n+ b\n",
            "bullets mixed markers become separate lists" to "- a\n- b\n\n* c\n* d\n",
            "ordered" to "1. one\n2. two\n3. three\n",
            "ordered from five" to "5. five\n6. six\n",
            "ordered all ones" to "1. a\n1. b\n1. c\n",
            "ordered paren" to "1) a\n2) b\n",
            "nested lists three deep" to "- a\n  - b\n    - c\n      - d\n- e\n",
            "ordered inside bullets" to "- a\n  1. x\n  2. y\n- b\n",
            "bullets inside ordered" to "1. a\n   - x\n   - y\n2. b\n",
            "loose list" to "- a\n\n- b\n\n- c\n",
            "list item with two paragraphs" to "- a\n\n  more of a\n- b\n",
            "list item with code" to "- a\n\n  ```\n  code\n  ```\n- b\n",
            "list item with quote" to "- a\n\n  > quoted\n",
            "list followed by paragraph" to "- a\n- b\n\nafter\n",
            "paragraph then list directly" to "intro\n- a\n- b\n",
            "empty list items" to "- a\n-\n- c\n",
            "blockquote" to "> quote\n> continues\n",
            "quote with blank line" to "> a\n>\n> b\n",
            "nested quote" to "> a\n>> b\n> > c\n",
            "quote with list and code" to "> - a\n> - b\n>\n> ```\n> code\n> ```\n",
            "lazy quote continuation" to "> a\nb\n",
            "fenced code" to "```kotlin\nfun main() {}\n```\n",
            "fenced with markdown inside" to "```\n# not heading\n**not bold**\n- not list\n> not quote\n```\n",
            "tilde fence" to "~~~\ncode\n~~~\n",
            "fence with longer inner fence" to "````\n```\ninner\n```\n````\n",
            "unterminated fence" to "```\nrunning to the end",
            "empty fence" to "```\n```\n",
            "indented code" to "    code line\n      indented more\n\n    second block line\n",
            "code with trailing blank line inside fence" to "```\na\n\n```\n",
            "horizontal rules" to "a\n\n---\n\nb\n\n***\n\n___\n",
            "rule between lists" to "- a\n\n---\n\n- b\n",
            "emphasis variants" to "*a* _b_ **c** __d__ ***e*** ___f___\n",
            "strike" to "~~gone~~ and ~single~ stays\n",
            "inline code variants" to "`a` ``b`c`` `` `d` `` ` e `\n",
            "bold inside paragraph" to "Some text with **bold words** inside it.\n",
            "italic inside bold" to "**bold with *italic* inside**\n",
            "bold inside italic" to "*italic with **bold** inside*\n",
            "link with formatted text" to "[**bold** and *italic* link](https://example.org/a?b=1&c=2)\n",
            "link with title and angle dest" to "[t](<a b.html> \"A title\") [u](x 'single')\n",
            "autolink" to "see <https://example.org> now\n",
            "bare url" to "visit https://example.org/path_(x) for more\n",
            "reference link" to "[text][ref]\n\n[ref]: https://example.org \"Title\"\n",
            "image" to "![alt text](pic.png \"Caption\")\n",
            "image in link" to "[![alt](pic.png)](https://example.org)\n",
            "inline html" to "text <span class=\"x\">html</span> and <br> more\n",
            "html block" to "<div align=\"center\">\n  <b>hi</b>\n</div>\n\ntext\n",
            "html comment" to "<!-- a comment -->\n\ntext\n",
            "table" to "| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |\n\nafter\n",
            "table in the middle" to "before\n\n| h |\n|---|\n| c |\n\nafter\n",
            "footnote-like syntax" to "text[^1]\n\n[^1]: the note\n",
            "escapes" to "\\*not italic\\* \\_x\\_ \\# \\> \\- \\[a\\]\n",
            "entities" to "&amp; &lt; &gt; &copy; &#35; &#x41;\n",
            "special characters as text" to "a * b _ c ` d [ e ] f < g > h & i ~ j | k\n",
            "text that looks like markup after escaping" to "\\- not a list\n\n\\# not a heading\n\n1\\. not numbered\n",
            "hard break backslash" to "line one\\\nline two\n",
            "hard break spaces" to "line one  \nline two\n",
            "soft breaks" to "line one\nline two\nline three\n",
            "german" to "Größe: Äpfel, Öl, Übung — für Straße & Mäuse.\n",
            "umlauts in lists and headings" to "# Überschrift\n\n- Äpfel\n- Öl\n- [x] Müsli\n",
            "emoji" to "Hello 😀 world 👍🏽 family 👨‍👩‍👧‍👦 flag 🇩🇪\n",
            "cjk and rtl" to "日本語のテキスト\n\nمرحبا بالعالم\n",
            "combining characters" to "é ä ñ\n",
            "non-breaking and zero-width spaces" to "a b​c d\n",
            "crlf document" to "# Title\r\n\r\ntext line\r\nnext line\r\n\r\n- a\r\n- b\r\n\r\n```\r\ncode\r\n```\r\n",
            "crlf blank lines only" to "\r\n\r\n",
            "lone cr line endings" to "a\rb\r\rc\r",
            "mixed line endings" to "a\r\nb\n\nc\r\n",
            "no final newline" to "# T\n\ntext without final newline",
            "trailing spaces" to "text   \n\nmore  \n",
            "tabs" to "a\tb\n\n\tindented by tab\n\n- a\n\t- b\n",
            "very long line" to "$long\n",
            "very long list" to (1..300).joinToString("\n") { "- item $it" } + "\n",
            "very long document" to (1..400).joinToString("\n\n") { "## Section $it\n\nParagraph $it with **bold**, *italic* and `code`.\n\n- a\n- b" } + "\n",
            "heading directly followed by list" to "# H\n- a\n- b\n",
            "code directly after paragraph" to "text\n```\ncode\n```\nmore\n",
            "link reference at the end" to "text\n\n[a]: /u\n",
            "weird but valid" to "* * *\n\n- - -\n\n+ + +\n",
            "list marker only" to "-\n",
            "angle quotes" to "> > > deep\n",
            "stray emphasis markers" to "** not closed and * alone and _ one\n",
            "unbalanced brackets" to "a [b ] c ] [ d\n",
            "unbalanced backticks" to "a ` b `` c ``` d\n",
            "markdown in link text and code" to "[`code` *em*](u)\n",
            "link with parens in url" to "[a](https://en.wikipedia.org/wiki/Foo_(bar))\n",
            "line starting with number and dot in paragraph" to "text\n2. not interrupting? yes it can\n",
        )

        /** What the round trip must preserve: meaning, not spelling. */
        fun semantic(md: String): List<Block> = MarkdownParser.parseDocument(md).blocks.mapNotNull { BlockNormalizer.compareForm(it) }
    }

    @Test fun `layout reproduces every document exactly`() {
        for ((name, md) in corpus) {
            val parsed = MarkdownParser.parse(md)
            assertTrue("$name: layout valid", parsed.layoutValid)
            assertEquals("$name: original text", md, parsed.original())
        }
    }

    @Test fun `serialize and parse again keeps the meaning`() {
        for ((name, md) in corpus) {
            val doc = MarkdownParser.parseDocument(md)
            val written = MarkdownSerializer.serialize(doc)
            assertEquals("$name: meaning changed.\nwritten:\n$written", semantic(md), semantic(written))
        }
    }

    @Test fun `serialized text is stable`() {
        for ((name, md) in corpus) {
            val once = MarkdownSerializer.serialize(MarkdownParser.parseDocument(md))
            val twice = MarkdownSerializer.serialize(MarkdownParser.parseDocument(once))
            assertEquals("$name: second pass changed the text", once, twice)
        }
    }

    @Test fun `an unchanged document is written back byte for byte`() {
        for ((name, md) in corpus) {
            val parsed = MarkdownParser.parse(md)
            val blocks = parsed.blocks.map { it.block }
            assertEquals(name, md, MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList()))
        }
    }

    @Test fun `plain text is never lost`() {
        for ((name, md) in corpus) {
            val before = visibleText(MarkdownParser.parseDocument(md).blocks)
            val after = visibleText(MarkdownParser.parseDocument(MarkdownSerializer.serialize(MarkdownParser.parseDocument(md))).blocks)
            assertEquals("$name: visible text", before, after)
        }
    }

    @Test fun `crlf documents keep crlf when regenerated`() {
        val md = "# Title\r\n\r\ntext\r\n\r\n- a\r\n- b\r\n"
        val parsed = MarkdownParser.parse(md)
        val edited = MarkdownSerializer.serialize(parsed.document, parsed.eol)
        assertTrue(edited.contains("\r\n"))
        assertEquals(edited.replace("\r\n", ""), edited.replace("\r\n", "").replace("\n", "")) // no lone LF
        assertEquals(semantic(md), semantic(edited))
    }

    @Test fun `unsupported constructs survive verbatim`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        val html = "<div>\n  raw <b>html</b>\n</div>"
        val ref = "[foo]: https://example.org \"T\""
        val md = "# T\n\n$table\n\n$html\n\n$ref\n\ntext ![img](a.png) <br> end\n"
        val out = MarkdownSerializer.serialize(MarkdownParser.parseDocument(md))
        for (piece in listOf(table, html, ref, "![img](a.png)", "<br>")) assertTrue("$piece lost in:\n$out", out.contains(piece))
    }

    private fun visibleText(blocks: List<Block>): String = buildString {
        fun walk(b: Block) {
            when (b) {
                is Block.Paragraph -> appendLine(b.inlines.plainText())
                is Block.Heading -> appendLine(b.inlines.plainText())
                is Block.CodeBlock -> appendLine(b.code)
                is Block.Raw -> appendLine(b.markdown)
                is Block.Quote -> b.blocks.forEach(::walk)
                is Block.ListBlock -> b.items.forEach { it.blocks.forEach(::walk) }
                Block.Rule -> appendLine("---")
            }
        }
        blocks.forEach(::walk)
    }
}
