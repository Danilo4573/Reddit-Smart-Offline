package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders the subset of Reddit markdown that matters for reading: headings, quotes, lists, emphasis, code and links. */
@Composable
fun Markdown(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface) {
    val link = MaterialTheme.colorScheme.primary
    val blocks = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#x200B;", "").trim().split(Regex("\n\\s*\n"))
    Column {
        blocks.forEachIndexed { i, raw ->
            val block = raw.trimEnd()
            if (block.isBlank()) return@forEachIndexed
            if (i > 0) Spacer(Modifier.height(10.dp))
            when {
                block.startsWith("#") -> {
                    val level = block.takeWhile { it == '#' }.length
                    Text(inline(block.trimStart('#').trim(), link), style = if (level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = color)
                }
                block.startsWith(">") -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Spacer(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(block.lines().joinToString("\n") { it.removePrefix(">").trimStart() }, link), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                block.startsWith("    ") || block.startsWith("```") -> Text(
                    block.removePrefix("```").removeSuffix("```").trimIndent(), style = style.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(10.dp),
                )
                else -> Text(inline(block.lines().joinToString("\n") { line ->
                    val t = line.trimStart()
                    when {
                        t.startsWith("* ") || t.startsWith("- ") || t.startsWith("+ ") -> "  •  " + t.drop(2)
                        else -> line
                    }
                }, link), style = style, color = color)
            }
        }
    }
}

private val tokens = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*([^*\s][^*]*?)\*|(https?://[^\s)]+)""")

private fun inline(s: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    for (m in tokens.findAll(s)) {
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withLink(LinkAnnotation.Url(g[2], linkStyle)) { append(g[1]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7]) }
            g[8].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], linkStyle)) { append(g[8]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
