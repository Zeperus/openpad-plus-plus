package io.github.zeperus.openpad.markdown

import io.github.zeperus.openpad.markdown.Inline.Code
import io.github.zeperus.openpad.markdown.Inline.Emphasis
import io.github.zeperus.openpad.markdown.Inline.HardBreak
import io.github.zeperus.openpad.markdown.Inline.Link
import io.github.zeperus.openpad.markdown.Inline.SoftBreak
import io.github.zeperus.openpad.markdown.Inline.Strikethrough
import io.github.zeperus.openpad.markdown.Inline.Strong
import io.github.zeperus.openpad.markdown.Inline.Text
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineNormalizerTest {
    private fun t(s: String) = Text(s)
    private fun norm(vararg i: Inline) = InlineNormalizer.block(i.toList())

    @Test fun `adjacent text merges and empty text disappears`() =
        assertEquals(listOf(t("abc")), norm(t("a"), t(""), t("b"), t("c")))

    @Test fun `newlines in text become soft breaks`() =
        assertEquals(listOf(t("a"), SoftBreak, t("b")), norm(t("a\nb")))

    @Test fun `carriage returns count as line breaks and nul becomes the replacement character`() {
        assertEquals(listOf(t("a"), SoftBreak, t("b"), SoftBreak, t("c")), norm(t("a\r\nb\rc")))
        assertEquals(listOf(t("a�b")), norm(t("a\u0000b")))
    }

    @Test fun `bold around only whitespace disappears`() =
        assertEquals(listOf(t("a b")), norm(t("a"), Strong(listOf(t(" "))), t("b")))

    @Test fun `bold edge whitespace keeps its text`() =
        assertEquals(listOf(t("a "), Strong(listOf(t("b"))), t(" c")), norm(t("a"), Strong(listOf(t(" b "))), t("c")))

    @Test fun `a style is not applied inside itself`() {
        assertEquals(listOf(Strong(listOf(t("a b c")))), norm(Strong(listOf(t("a "), Strong(listOf(t("b"))), t(" c")))))
        assertEquals(
            listOf(Emphasis(listOf(Strong(listOf(t("x")))))),
            norm(Emphasis(listOf(Emphasis(listOf(Strong(listOf(t("x")))))))),
        )
    }

    @Test fun `links do not nest`() =
        assertEquals(listOf(Link(listOf(t("ab")), "u")), norm(Link(listOf(t("a"), Link(listOf(t("b")), "v")), "u")))

    @Test fun `bold around italic and italic around bold have one canonical form`() {
        val canonical = listOf(Emphasis(listOf(Strong(listOf(t("x"))))))
        assertEquals(canonical, norm(Strong(listOf(Emphasis(listOf(t("x")))))))
        assertEquals(canonical, norm(Emphasis(listOf(Strong(listOf(t("x")))))))
    }

    @Test fun `adjacent identical styles merge`() =
        assertEquals(listOf(Strong(listOf(t("ab")))), norm(Strong(listOf(t("a"))), Strong(listOf(t("b")))))

    @Test fun `spaces at line edges are dropped`() =
        assertEquals(listOf(t("a"), SoftBreak, t("b")), norm(t("  a   "), SoftBreak, t("   b  ")))

    @Test fun `breaks at the paragraph edges are dropped and repeated breaks collapse`() {
        assertEquals(listOf(t("a")), norm(SoftBreak, t("a"), HardBreak))
        assertEquals(listOf(t("a"), HardBreak, t("b")), norm(t("a"), SoftBreak, HardBreak, t("b")))
    }

    @Test fun `empty formatting and empty code disappear`() =
        assertEquals(listOf(t("a")), norm(t("a"), Strong(emptyList()), Emphasis(listOf(t(""))), Strikethrough(emptyList()), Code("")))

    @Test fun `normalizing twice changes nothing`() {
        val messy = listOf(
            t(" a\n"),
            Strong(listOf(t(" b "), Emphasis(listOf(Strong(listOf(t(" c"))), Code(" d "))))),
            Link(listOf(t(" l "), Link(listOf(t("n")), "x")), "u", "t"), SoftBreak, SoftBreak, t(" e "),
        )
        val once = InlineNormalizer.block(messy)
        assertEquals(once, InlineNormalizer.block(once))
    }
}
