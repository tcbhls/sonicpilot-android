package com.sonicpilot.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.ui.theme.SonicColors

/**
 * 极简杂志级 Markdown 富文本排版器（专治 AI 聊天裸露 `**` / `###` / `>` 符号）：
 * 零第三方依赖，支持标题、加粗、斜体、行内标签、彩色圆点列表、编号列表与引用块。
 */
@Composable
fun RichMarkdownText(
    markdown: String,
    textColor: Color = SonicColors.TextPrimary,
    secondaryColor: Color = SonicColors.TextSecondary,
    accentColor: Color = SonicColors.Accent,
    codeBgColor: Color = SonicColors.SurfaceElevated,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = parseInlineMarkdown(block.text, accentColor, codeBgColor),
                        style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 23.sp
                    )
                }

                is MdBlock.Bullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 9.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, accentColor, codeBgColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            lineHeight = 24.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Ordered -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp).width(22.dp)
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, accentColor, codeBgColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            lineHeight = 24.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(codeBgColor.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accentColor)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, accentColor, codeBgColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryColor,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, accentColor, codeBgColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        lineHeight = 24.sp
                    )
                }
            }
        }
    }
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val headingRegex = Regex("""^(#{1,4})\s+(.+)$""")
private val bulletRegex = Regex("""^(?:[-*•])\s+(.+)$""")
private val orderedRegex = Regex("""^(\d+)[.)、]\s*(.+)$""")
private val inlineTokenRegex = Regex("""(\*\*[^*]+\*\*|`[^`]+`|\*[^*\n]+\*)""")

internal fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").lines()
    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isBlank() || line == "---" || line == "***") continue

        val hm = headingRegex.matchEntire(line)
        if (hm != null) {
            out += MdBlock.Heading(hm.groupValues[1].length, hm.groupValues[2].trim())
            continue
        }
        if (line.startsWith(">")) {
            val content = line.removePrefix(">").trim()
            if (content.isNotBlank()) out += MdBlock.Quote(content)
            continue
        }
        val bm = bulletRegex.matchEntire(line)
        if (bm != null) {
            out += MdBlock.Bullet(bm.groupValues[1].trim())
            continue
        }
        val om = orderedRegex.matchEntire(line)
        if (om != null) {
            out += MdBlock.Ordered(om.groupValues[1], om.groupValues[2].trim())
            continue
        }
        out += MdBlock.Paragraph(line)
    }
    return out
}

internal fun parseInlineMarkdown(
    text: String,
    accentColor: Color,
    codeBgColor: Color
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlineTokenRegex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = accentColor,
                        background = codeBgColor
                    )
                ) {
                    append(" ${token.substring(1, token.length - 1)} ")
                }
            }
            token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
