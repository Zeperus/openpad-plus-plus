package io.github.zeperus.openpad

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A probe, not a regression test: how Compose lays out paragraph styles around line breaks. The result is read from the failure message. */
@RunWith(AndroidJUnit4::class)
class ParagraphProbeTest {
    @get:Rule val rule = createComposeRule()

    private val style = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None))
    private fun p(indent: Int = 0) = ParagraphStyle(lineHeight = 28.sp, textIndent = TextIndent(indent.sp, indent.sp))

    @Test fun probe() {
        var measurer: TextMeasurer? = null
        rule.setContent { measurer = rememberTextMeasurer() }
        rule.waitForIdle()
        fun describe(name: String, text: String, ranges: List<Pair<Int, Int>>, indents: List<Int> = ranges.map { 0 }): String {
            val annotated = buildAnnotatedString {
                append(text)
                for ((i, r) in ranges.withIndex()) addStyle(p(indents[i]), r.first, r.second)
            }
            val l = measurer!!.measure(annotated, style, constraints = Constraints(maxWidth = 1000))
            val firstLefts = listOf(0, 2, 4).filter { it <= text.length }.map { l.getCursorRect(it).left }
            return "$name: lines=${l.lineCount} tops=${(0 until l.lineCount).map { l.getLineTop(it) }} caretLefts@0,2,4=$firstLefts"
        }
        val t = "a\nb\nc"
        val out = listOf(
            describe("V1 covering the newline [0,2)[2,4)[4,5)", t, listOf(0 to 2, 2 to 4, 4 to 5)),
            describe("V2 without the newline [0,1)[2,3)[4,5)", t, listOf(0 to 1, 2 to 3, 4 to 5)),
            describe("V4 newline first [0,1)[1,3)[3,5)", t, listOf(0 to 1, 1 to 3, 3 to 5)),
            describe("V5 none", t, emptyList()),
            describe("V6 single [0,5)", t, listOf(0 to 5)),
            describe("V7 covering the newline, indents 0/30/0", t, listOf(0 to 2, 2 to 4, 4 to 5), listOf(0, 30, 0)),
            describe("V8 without the newline, indents 0/30/0", t, listOf(0 to 1, 2 to 3, 4 to 5), listOf(0, 30, 0)),
            describe("E1 empty last row: a\\n covering", "a\n", listOf(0 to 2)),
            describe("E2 empty last row: a\\n + nothing", "a\n", listOf(0 to 1)),
            describe("E3 empty last row with a ZWSP: a\\n​", "a\n​", listOf(0 to 2, 2 to 3), listOf(0, 30)),
        )
        throw AssertionError("PROBE\n" + out.joinToString("\n"))
    }
}
