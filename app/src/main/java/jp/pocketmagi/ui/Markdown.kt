package jp.pocketmagi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Small models sometimes answer in Markdown even when told not to.
 * This turns the common pieces (headings, bold, lists, code, rules, tables) into styled
 * text so no raw "###" or "**" reaches the screen. Works on partial (streaming) text.
 */
object Markdown {
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+・]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")

    fun render(text: String, accent: Color, dim: Color): AnnotatedString = buildAnnotatedString {
        val lines = text.replace("\r\n", "\n").split('\n')
        var inCode = false
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            val nl = i < lines.lastIndex
            if (inCode) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = dim)) { append(raw) }
                if (nl) append('\n'); return@forEachIndexed
            }
            when {
                RULE.matches(raw) -> withStyle(SpanStyle(color = dim)) { append("────────") }
                TABLE_SEP.matches(raw) -> return@forEachIndexed
                HEADING.matches(raw) -> {
                    val t = HEADING.find(raw)!!.groupValues[1]
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent, fontSize = 1.05.em)) { inline(t) }
                }
                BULLET.matches(raw) -> {
                    val m = BULLET.find(raw)!!
                    append("  ".repeat(m.groupValues[1].length / 2)); append("・"); inline(m.groupValues[2])
                }
                ORDERED.matches(raw) -> {
                    val m = ORDERED.find(raw)!!
                    append("  ".repeat(m.groupValues[1].length / 2)); append(m.groupValues[2]); append(". "); inline(m.groupValues[3])
                }
                QUOTE.matches(raw) -> withStyle(SpanStyle(color = dim)) { append("｜"); inline(QUOTE.find(raw)!!.groupValues[1]) }
                raw.trim().startsWith("|") && raw.trim().endsWith("|") ->
                    inline(raw.trim().trim('|').split('|').joinToString("　") { it.trim() })
                else -> inline(raw)
            }
            if (nl) append('\n')
        }
    }

    // **bold**, __bold__, *em*/_em_ (markers dropped), `code`
    private fun AnnotatedString.Builder.inline(s: String) {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = s.indexOf(mark, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(s.substring(i + 2, end)) }
                        i = end + 2
                    } else { i += 2 }   // unmatched (e.g. still streaming): drop the marker
                }
                s[i] == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                        i = end + 1
                    } else i++
                }
                (s[i] == '*' || s[i] == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
                    (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = s.indexOf(s[i], i + 1)
                    if (end > i + 1) { inline(s.substring(i + 1, end)); i = end + 1 } else { append(s[i]); i++ }
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

@Composable
fun MdText(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val accent = MagiColors.Amber
    val dim = MagiColors.TextDim
    val rendered = remember(text, accent, dim) { Markdown.render(text, accent, dim) }
    Text(rendered, style = style, modifier = modifier, color = color)
}
