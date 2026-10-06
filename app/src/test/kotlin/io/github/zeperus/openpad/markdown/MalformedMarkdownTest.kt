package io.github.zeperus.openpad.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Opening a strange `.md` file must never crash the app or lose its content. */
class MalformedMarkdownTest {
    private fun parseAndWrite(md: String): Pair<ParsedDocument, String> {
        val parsed = MarkdownParser.parse(md)
        // regenerating the whole document must work for anything the parser accepted
        val written = MarkdownSerializer.serialize(parsed.document, parsed.eol)
        // and an unchanged document must come back byte for byte
        val blocks = parsed.blocks.map { it.block }
        assertEquals("unchanged document", md, MarkdownSerializer.serializeIncremental(parsed, blocks, blocks.indices.toList()))
        return parsed to written
    }

    @Test fun `random bytes as text never crash and are written back unchanged`() {
        repeat(1500) { seed ->
            val r = Random(seed)
            val md = String(CharArray(r.nextInt(0, 200)) { r.nextInt(0, 0x2FF).toChar() })
            parseAndWrite(md)
        }
    }

    @Test fun `random markup soup never crashes`() {
        val atoms = listOf("*", "**", "_", "__", "`", "```", "~~~", "~~", "[", "]", "(", ")", "<", ">", "&", "#", "-", "+", "1.", "\n", "\n\n", " ", "  ", "\t", "|", ":", "!", "\\", "\r\n", "\r", "<div>", "</div>", "- [ ] ", "- [x] ", "> ", "---", "===", "http://x", "😀", "ä", "\u0000", "﻿", " ")
        repeat(3000) { seed ->
            val r = Random(seed)
            val md = buildString { repeat(r.nextInt(1, 60)) { append(atoms[r.nextInt(atoms.size)]) } }
            parseAndWrite(md)
        }
    }

    @Test fun `unpaired surrogates and control characters are tolerated`() {
        for (md in listOf("\uD800", "a\uDC00b", "\uD83D", "x\u0000y", "\u0001\u0002\u0003", "￿", "‎‏")) {
            val (parsed, _) = parseAndWrite(md)
            assertTrue(parsed.layoutValid)
        }
    }

    @Test fun `deeply nested quotes lists and emphasis are survived`() {
        val deep = listOf(
            ">".repeat(5000) + " x",
            "- ".repeat(3000) + "x",
            "*".repeat(5000) + "x" + "*".repeat(5000),
            "[".repeat(5000) + "x" + "]".repeat(5000),
            "(".repeat(5000) + "x",
            "`".repeat(5000),
            "<".repeat(3000) + "a",
            "![".repeat(2000) + "](" + "x".repeat(2000),
            "1. ".repeat(2000) + "x",
            "# ".repeat(2000) + "x",
            "**a *b ".repeat(2000),
        )
        for (md in deep) {
            val parsed = MarkdownParser.parse(md)
            // whatever structure it ended up with, the text is still there
            assertEquals(md, parsed.original())
            MarkdownSerializer.serialize(parsed.document)
        }
    }

    @Test fun `unbalanced and unterminated constructs`() {
        for (md in listOf("**bold", "*it", "_x", "`code", "```\nfence", "[link](", "[link](url", "![img](", "<div>\nunclosed", "<!-- never closed", "> quote\n>> deeper\n> back", "- [ ", "- [x", "1. \n2.", "|a|b|\n|---", "~~strike", "[a]: ", "[a]:\n", "\\")) {
            val (parsed, written) = parseAndWrite(md)
            // the written version must parse again without trouble
            MarkdownParser.parse(written)
            assertTrue(parsed.layoutValid)
        }
    }

    @Test fun `a document that is only whitespace`() {
        for (md in listOf("", " ", "\n", "\t\n\t", "\r\n\r\n", "   \n   \n", " ")) parseAndWrite(md)
    }

    @Test fun `byte order mark and exotic line separators`() {
        for (md in listOf("﻿# Title\n\ntext\n", "a b c", "a\u0085b", "line\u000Bvertical", "\u000Cform feed")) {
            val (parsed, _) = parseAndWrite(md)
            assertEquals(md, parsed.original())
        }
    }

    @Test fun `huge single block`() {
        val md = "- " + "item ".repeat(100_000)
        val parsed = MarkdownParser.parse(md)
        assertEquals(md, parsed.original())
    }

    @Test fun `parse never throws even when the library does`() {
        // many nested containers in a row exceed the parser's nesting limits: the result is still a valid document
        val md = (1..200).joinToString("") { "> ".repeat(it % 30) + "- ".repeat(it % 7) + "[x](" + "(".repeat(it % 9) + "\n" }
        val parsed = MarkdownParser.parse(md)
        assertEquals(md, parsed.original())
        MarkdownSerializer.serialize(parsed.document)
    }
}
