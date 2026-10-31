package io.github.zeperus.openpad.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBlocksTest {
    // ---- Tables ------------------------------------------------------------------------------------------------

    @Test fun `a GFM table is recognized with header alignment and rows`() {
        val t = RawBlocks.classify("| Name | Qty | Note |\n|:-----|----:|:----:|\n| Milk | 2 | **cold** |\n| Bread | 1 | |\n") as RawBlock.Table
        assertEquals(listOf("Name", "Qty", "Note"), t.header)
        assertEquals(listOf(RawBlock.Align.Start, RawBlock.Align.End, RawBlock.Align.Center), t.aligns)
        assertEquals(listOf(listOf("Milk", "2", "**cold**"), listOf("Bread", "1", "")), t.rows)
    }

    @Test fun `tables without outer pipes and with short or long rows`() {
        val t = RawBlocks.classify("a | b\n--|--\n1\n1 | 2 | 3") as RawBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), t.rows)
    }

    @Test fun `escaped pipes and code spans stay in their cell`() {
        val t = RawBlocks.classify("| a | b |\n|---|---|\n| x \\| y | `p|q` |") as RawBlock.Table
        assertEquals(listOf(listOf("x | y", "`p|q`")), t.rows)
    }

    @Test fun `text that only looks a bit like a table is not one`() {
        assertEquals(RawBlock.Other, RawBlocks.classify("| a | b |\n| c | d |"))
        assertEquals(RawBlock.Other, RawBlocks.classify("just text"))
    }

    // ---- Images ------------------------------------------------------------------------------------------------

    @Test fun `an image paragraph is recognized`() {
        assertEquals(RawBlock.Image("a cat", "https://x.org/cat.png", null), RawBlocks.classify("![a cat](https://x.org/cat.png)"))
        assertEquals(RawBlock.Image("", "img/a.png", "Title"), RawBlocks.classify("![](img/a.png \"Title\")"))
        assertEquals(RawBlock.Image("x", "content://p/1", null), RawBlocks.classify("![x](<content://p/1>)"))
    }

    @Test fun `text around an image is not an image block`() {
        assertEquals(RawBlock.Other, RawBlocks.classify("see ![a](b) here"))
    }

    // ---- HTML --------------------------------------------------------------------------------------------------

    @Test fun `simple html becomes styled runs`() {
        val runs = HtmlSimple.parse("<p>Hello <strong>bold</strong> and <em>it</em><br>next <code>x</code></p>")!!
        assertEquals("Hello bold and it\nnext x", runs.joinToString("") { it.text }.trim())
        assertTrue(runs.any { it.bold && it.text == "bold" })
        assertTrue(runs.any { it.italic && it.text == "it" })
        assertTrue(runs.any { it.code && it.text == "x" })
    }

    @Test fun `pre keeps its whitespace`() {
        val runs = HtmlSimple.parse("<pre>a  b\n  c</pre>")!!
        assertEquals("a  b\n  c", runs.single().text)
        assertTrue(runs.single().code)
    }

    @Test fun `entities are decoded to text and never to markup`() {
        val runs = HtmlSimple.parse("<p>1 &lt; 2 &amp; &lt;script&gt;</p>")!!
        assertEquals("1 < 2 & <script>", runs.joinToString("") { it.text }.trim())
    }

    @Test fun `anything outside the allowlist is shown as source`() {
        for (html in listOf(
            "<script>alert(1)</script>",
            "<style>p{}</style>",
            "<a href=\"https://x.org\">x</a>",
            "<img src=\"x.png\">",
            "<div>block</div>",
            "<p onclick=\"evil()\">x</p>",
            "<iframe src=\"https://x.org\"></iframe>",
            "<!-- hidden -->",
            "<p>unclosed <strong>bold</p>",
            "<table><tr><td>x</td></tr></table>",
        )) {
            assertNull("must not be rendered: $html", HtmlSimple.parse(html))
            assertEquals(RawBlock.Html(null), RawBlocks.classify(html))
        }
    }

    @Test fun `html without tags in the allowlist never produces links or resources`() {
        val block = RawBlocks.classify("<details><summary>x</summary>y</details>")
        assertEquals(RawBlock.Html(null), block)
    }
}
