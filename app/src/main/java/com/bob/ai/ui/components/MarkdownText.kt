package com.bob.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer (no library). Supports headings (#/##/###),
 * **bold**, *italic*, `inline code`, fenced code blocks, bullet and numbered
 * lists, [links](url), > quotes, --- dividers and paragraphs.
 *
 * @param highlight when non-blank, every case-insensitive occurrence is drawn
 * with a background colour (used by in-conversation search).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    highlight: String = "",
    color: Color = Color.Unspecified
) {
    val colorScheme = MaterialTheme.colorScheme
    val blocks = remember(text) { parseBlocks(text) }
    val highlightColor = colorScheme.tertiaryContainer
    val linkColor = colorScheme.primary
    val codeBackground = colorScheme.surfaceVariant.copy(alpha = 0.7f)

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> {
                    Text(
                        text = withHighlight(
                            parseInline(block.text, linkColor, codeBackground),
                            highlight, highlightColor
                        ),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                            2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        color = color,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is Block.Paragraph -> {
                    Text(
                        text = withHighlight(
                            parseInline(block.text, linkColor, codeBackground),
                            highlight, highlightColor
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                is Block.Code -> {
                    Text(
                        text = withHighlight(
                            AnnotatedString(block.code),
                            highlight, highlightColor
                        ),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBackground)
                            .padding(10.dp)
                    )
                }
                is Block.BulletList -> {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        block.items.forEach { item ->
                            Text(
                                text = withHighlight(
                                    parseInline("•  $item", linkColor, codeBackground),
                                    highlight, highlightColor
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            )
                        }
                    }
                }
                is Block.NumberedList -> {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Text(
                                text = withHighlight(
                                    parseInline("${index + 1}.  $item", linkColor, codeBackground),
                                    highlight, highlightColor
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            )
                        }
                    }
                }
                is Block.Quote -> {
                    Text(
                        text = withHighlight(
                            parseInline(block.text, linkColor, codeBackground),
                            highlight, highlightColor
                        ),
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(
                                colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                is Block.Divider -> {
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = colorScheme.outlineVariant
                    )
                }
            }
        }
        if (blocks.isEmpty()) {
            Spacer(Modifier.height(0.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Block parsing (line based)
// ---------------------------------------------------------------------------

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val code: String) : Block
    data class BulletList(val items: List<String>) : Block
    data class NumberedList(val items: List<String>) : Block
    data class Quote(val text: String) : Block
    object Divider : Block
}

private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.lines()
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(Block.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks.add(Block.Code(code.toString().trimEnd('\n')))
            }
            trimmed.matches(Regex("^#{1,3}\\s+.*")) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(Block.Heading(level, trimmed.drop(level).trim()))
            }
            trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                flushParagraph()
                blocks.add(Block.Divider)
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks.add(Block.Quote(trimmed.removePrefix(">").trim()))
            }
            trimmed.matches(Regex("^[-*]\\s+.*")) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^[-*]\\s+.*"))) {
                    items.add(lines[i].trim().removePrefix("-").removePrefix("*").trim())
                    i++
                }
                i--
                blocks.add(Block.BulletList(items))
            }
            trimmed.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^\\d+[.)]\\s+.*"))) {
                    items.add(lines[i].trim().replaceFirst(Regex("^\\d+[.)]\\s+"), ""))
                    i++
                }
                i--
                blocks.add(Block.NumberedList(items))
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

// ---------------------------------------------------------------------------
// Inline parsing: **bold**, *italic*, `code`, [text](url)
// ---------------------------------------------------------------------------

private fun parseInline(
    text: String,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val linkStyle = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        )
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end == -1) { append(text[i]); i++ } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(parseInline(text.substring(i + 2, end), linkColor, codeBackground))
                        }
                        i = end + 2
                    }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end == -1) { append(text[i]); i++ } else {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                fontSize = 13.sp
                            )
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                text.startsWith("[", i) -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1) closeBracket + 1 else -1
                    val closeParen = if (openParen != -1 && openParen < text.length && text[openParen] == '(') {
                        text.indexOf(')', openParen + 1)
                    } else -1
                    if (closeBracket == -1 || closeParen == -1) { append(text[i]); i++ } else {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(openParen + 1, closeParen)
                        // withLink() only exists in Compose UI 1.8+, but the
                        // 2024.09 BOM pins UI 1.7, so use the 1.7 addLink API.
                        val linkStart = length
                        append(label)
                        addLink(LinkAnnotation.Url(url, linkStyle), linkStart, length)
                        i = closeParen + 1
                    }
                }
                text.startsWith("*", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end == -1) { append(text[i]); i++ } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(parseInline(text.substring(i + 1, end), linkColor, codeBackground))
                        }
                        i = end + 1
                    }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

private fun withHighlight(
    annotated: AnnotatedString,
    highlight: String,
    highlightColor: Color
): AnnotatedString {
    if (highlight.isBlank()) return annotated
    val query = highlight.trim()
    val plain = annotated.text
    var start = plain.indexOf(query, ignoreCase = true)
    if (start == -1) return annotated
    return buildAnnotatedString {
        append(annotated)
        while (start != -1) {
            addStyle(
                SpanStyle(background = highlightColor),
                start,
                (start + query.length).coerceAtMost(plain.length)
            )
            start = plain.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}
