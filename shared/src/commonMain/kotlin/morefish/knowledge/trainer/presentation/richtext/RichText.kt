package morefish.knowledge.trainer.presentation.richtext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import morefish.knowledge.trainer.presentation.theme.Palette

/**
 * 题库正文渲染入口。
 *
 * - 没有 Markdown 标记的普通题干：直接走 `Text`，排版与旧版完全一致（极速通道）；
 * - 有标记：按块渲染，行内 `$...$` 交给自绘公式排版，可用宽度不够时整块公式换行；
 * - 解析不了的写法原样显示，绝不吞内容。
 */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    val ink = if (color == Color.Unspecified) Palette.Ink else color
    val blocks = remember(text) { if (Markdown.isPlain(text)) null else Markdown.parse(text) }
    if (blocks == null) {
        Text(text = text, modifier = modifier, style = style, color = ink)
        return
    }
    val gap = with(style) {
        if (lineHeight == TextUnit.Unspecified) 10.dp else (lineHeight.value * 0.42f).dp
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        blocks.forEach { block -> BlockView(block, style, ink) }
    }
}

@Composable
private fun BlockView(block: MdBlock, style: TextStyle, ink: Color) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inlines, style, ink)

        is MdBlock.Heading -> {
            val scale = when (block.level) {
                1 -> 1.36f
                2 -> 1.22f
                3 -> 1.1f
                else -> 1f
            }
            val headingStyle = style.copy(
                fontSize = if (style.fontSize == TextUnit.Unspecified) 16.sp else (style.fontSize.value * scale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            InlineText(block.inlines, headingStyle, ink)
        }

        is MdBlock.Code -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.PanelMuted, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = block.text,
                    style = style.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (style.fontSize == TextUnit.Unspecified) 13.sp else (style.fontSize.value * 0.88f).sp,
                    ),
                    color = ink,
                )
            }
        }

        is MdBlock.MathBlock -> MathView(block.tex, style, ink, Modifier.fillMaxWidth())

        is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                // 居中对齐：条目里可能有分式把行盒顶高，顶端对齐会让圆点飘到上一行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("•", style = style, color = Palette.Muted, modifier = Modifier.width(18.dp))
                    Box(Modifier.weight(1f)) { InlineText(item, style, ink) }
                }
            }
        }

        is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${block.start + index}.",
                        style = style,
                        color = Palette.Muted,
                        modifier = Modifier.width(26.dp),
                    )
                    Box(Modifier.weight(1f)) { InlineText(item, style, ink) }
                }
            }
        }

        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Palette.AccentSoft),
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.blocks.forEach { inner -> BlockView(inner, style, Palette.Muted) }
            }
        }

        is MdBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.PanelBorder),
        )
    }
}

/** 没有行内公式时走 `Text`（排版最好）；有公式才进自绘混排。 */
@Composable
private fun InlineText(inlines: List<MdInline>, style: TextStyle, ink: Color) {
    if (!inlines.hasMath()) {
        Text(text = annotate(inlines, ink), style = style, color = ink)
        return
    }
    InlineFlow(inlines, style, ink, Modifier.fillMaxWidth())
}

private fun annotate(inlines: List<MdInline>, ink: Color): AnnotatedString = buildAnnotatedString {
    inlines.forEach { inline ->
        when (inline) {
            is MdInline.Text -> withStyle(
                SpanStyle(
                    fontWeight = if (inline.bold) FontWeight.Bold else null,
                    fontStyle = if (inline.italic) FontStyle.Italic else null,
                    textDecoration = if (inline.strike) TextDecoration.LineThrough else null,
                ),
            ) { append(inline.text) }

            is MdInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Palette.PanelMuted,
                ),
            ) { append(inline.text) }

            is MdInline.Link -> withStyle(
                SpanStyle(color = Palette.Accent, textDecoration = TextDecoration.Underline),
            ) { append(inline.text) }

            is MdInline.Math -> append("\$" + inline.tex + "\$")
        }
    }
}

// ------------------------------------------------------------------ 行内混排

private sealed interface InlineToken {
    class Box(
        val box: MathBox,
        val space: Boolean = false,
        val noLineStart: Boolean = false,
        val noLineEnd: Boolean = false,
    ) : InlineToken

    data object HardBreak : InlineToken
}

internal class InlineLayout(
    val width: Float,
    val height: Float,
    val ops: List<MathOp>,
)

/**
 * 文本 + 公式混排。
 *
 * 段落折行是自己算的：Compose 的 Text 无法把公式当作"字"参与断行。
 * 断行规则按中文习惯——汉字逐字可断、拉丁词整体不断、标点不出现在行首/行尾。
 */
internal class InlineTypesetter(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val style: TextStyle,
    private val ink: Color,
) {

    private val math = MathTypesetter(measurer, density, ink)
    private val cache = mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>()

    private val baseSizePx: Float
    private val lineHeightPx: Float
    private val codeSizePx: Float

    init {
        baseSizePx = with(density) {
            if (style.fontSize == TextUnit.Unspecified) 15.sp.toPx() else style.fontSize.toPx()
        }
        lineHeightPx = with(density) {
            if (style.lineHeight == TextUnit.Unspecified) baseSizePx * 1.6f else style.lineHeight.toPx()
        }
        codeSizePx = baseSizePx * 0.92f
    }

    fun layout(inlines: List<MdInline>, maxWidth: Float): InlineLayout {
        val tokens = mutableListOf<InlineToken>()
        inlines.forEach { tokens += tokensOf(it) }
        val lines = breakLines(tokens, maxWidth)

        var y = 0f
        var maxUsed = 0f
        val ops = mutableListOf<MathOp>()
        lines.forEach { line ->
            var ascent = 0f
            var descent = 0f
            line.forEach {
                if (it.box.ascent > ascent) ascent = it.box.ascent
                if (it.box.descent > descent) descent = it.box.descent
            }
            val natural = ascent + descent
            val lineHeight = maxOf(lineHeightPx, natural)
            // 行盒内的基线位置：把自然高度在行高里居中
            val baseline = y + (lineHeight - natural) / 2f + ascent
            var x = 0f
            line.forEach {
                ops += it.box.moved(x, baseline).ops
                x += it.box.width
            }
            if (x > maxUsed) maxUsed = x
            y += lineHeight
        }
        return InlineLayout(maxUsed, y, ops)
    }

    private fun tokensOf(inline: MdInline): List<InlineToken> = when (inline) {
        is MdInline.Math -> listOf(InlineToken.Box(math.layout(inline.tex, baseSizePx)))
        is MdInline.Code -> listOf(InlineToken.Box(codeBox(inline.text)))
        is MdInline.Link -> chunksOf(inline.text, style.copy(textDecoration = TextDecoration.Underline), Palette.Accent)
        is MdInline.Text -> chunksOf(inline.text, styleFor(inline), ink)
    }

    private fun styleFor(inline: MdInline.Text): TextStyle = style.copy(
        fontWeight = if (inline.bold) FontWeight.Bold else style.fontWeight,
        fontStyle = if (inline.italic) FontStyle.Italic else style.fontStyle,
        textDecoration = if (inline.strike) TextDecoration.LineThrough else style.textDecoration,
    )

    private fun codeBox(text: String): MathBox {
        val chunk = measure(text, style.copy(fontFamily = FontFamily.Monospace), codeSizePx, Palette.Accent)
        val pad = baseSizePx * 0.16f
        val box = MathBox(
            chunk.size.width + pad * 2,
            chunk.firstBaseline + pad * 0.4f,
            (chunk.size.height - chunk.firstBaseline) + pad * 0.4f,
            listOf(
                MathOp.Rect(0f, -chunk.firstBaseline - pad * 0.4f, chunk.size.width + pad * 2, chunk.size.height + pad * 0.8f, Palette.PanelMuted),
                MathOp.Glyph(chunk, pad, 0f),
            ),
        )
        return box
    }

    private fun chunksOf(text: String, textStyle: TextStyle, color: Color): List<InlineToken> {
        val out = mutableListOf<InlineToken>()
        val word = StringBuilder()

        fun flushWord() {
            if (word.isEmpty()) return
            out += InlineToken.Box(textBox(word.toString(), textStyle, color))
            word.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    flushWord()
                    out += InlineToken.HardBreak
                }
                c == ' ' || c == '\t' -> {
                    flushWord()
                    out += InlineToken.Box(textBox(" ", textStyle, color), space = true)
                }
                isCjk(c) -> {
                    flushWord()
                    out += InlineToken.Box(
                        textBox(c.toString(), textStyle, color),
                        noLineStart = c in NO_LINE_START,
                        noLineEnd = c in NO_LINE_END,
                    )
                }
                c in NO_LINE_START -> {
                    flushWord()
                    out += InlineToken.Box(textBox(c.toString(), textStyle, color), noLineStart = true)
                }
                else -> word.append(c)
            }
            i++
        }
        flushWord()
        return out
    }

    private fun textBox(text: String, textStyle: TextStyle, color: Color): MathBox {
        val layout = measure(text, textStyle, baseSizePx, color)
        return MathBox(
            layout.size.width.toFloat(),
            layout.firstBaseline,
            (layout.size.height - layout.firstBaseline).coerceAtLeast(0f),
            listOf(MathOp.Glyph(layout, 0f, 0f)),
        )
    }

    private fun measure(text: String, textStyle: TextStyle, sizePx: Float, color: Color): androidx.compose.ui.text.TextLayoutResult {
        val key = "$text|${sizePx.toInt()}|${textStyle.fontWeight}|${textStyle.fontStyle}|$color|${textStyle.fontFamily}"
        cache[key]?.let { return it }
        val result = measurer.measure(
            text = text,
            style = textStyle.copy(fontSize = with(density) { sizePx.toSp() }, color = color),
            softWrap = false,
            maxLines = 1,
        )
        cache[key] = result
        return result
    }

    private fun breakLines(tokens: List<InlineToken>, maxWidth: Float): List<List<InlineToken.Box>> {
        if (maxWidth <= 0f) return listOf(tokens.filterIsInstance<InlineToken.Box>())
        val lines = mutableListOf<List<InlineToken.Box>>()
        var current = mutableListOf<InlineToken.Box>()
        var width = 0f

        fun flush(carry: InlineToken.Box? = null) {
            if (current.isNotEmpty()) {
                while (current.isNotEmpty() && current.last().space) current.removeAt(current.size - 1)
                lines += current
            }
            current = mutableListOf()
            if (carry != null) {
                current += carry
                width = carry.box.width
            } else {
                width = 0f
            }
        }

        for (token in tokens) {
            if (token is InlineToken.HardBreak) {
                flush()
                continue
            }
            token as InlineToken.Box
            if (current.isEmpty()) {
                if (token.space) continue
                current += token
                width = token.box.width
                continue
            }
            if (width + token.box.width > maxWidth && !token.noLineStart) {
                var carry: InlineToken.Box? = null
                if (current.last().noLineEnd) {
                    carry = current.removeAt(current.size - 1)
                }
                if (carry != null) width -= carry.box.width
                flush(carry)
                if (token.space) continue
                current += token
                width += token.box.width
            } else {
                current += token
                width += token.box.width
            }
        }
        if (current.isNotEmpty()) {
            while (current.isNotEmpty() && current.last().space) current.removeAt(current.size - 1)
            lines += current
        }
        return lines
    }

    private companion object {
        val NO_LINE_START = "，。、；：！？）】》」』”’%,.;:!?)]}>"
        val NO_LINE_END = "（【《「『“‘([{<"

        fun isCjk(c: Char): Boolean {
            val code = c.code
            return code in 0x2E80..0x9FFF ||
                code in 0xF900..0xFAFF ||
                code in 0xFE30..0xFE4F ||
                code in 0xFF00..0xFFEF ||
                code in 0x3000..0x303F
        }
    }
}

/** 行内混排的绘制。 */
internal fun DrawScope.drawInlineLayout(layout: InlineLayout, ink: Color) {
    drawOps(layout.ops, ink)
}

internal fun DrawScope.drawOps(ops: List<MathOp>, ink: Color) {
    ops.forEach { op ->
        when (op) {
            // drawText 的 topLeft 是文本盒左上角，不是基线：要减掉首行基线高度
            is MathOp.Glyph -> drawText(
                textLayoutResult = op.layout,
                topLeft = Offset(op.x, op.baseline - op.layout.firstBaseline),
            )
            is MathOp.Rect -> drawRect(
                color = op.color ?: ink,
                topLeft = Offset(op.x, op.y),
                size = Size(op.w, op.h),
            )
            is MathOp.Line -> drawLine(
                color = ink,
                start = Offset(op.x1, op.y1),
                end = Offset(op.x2, op.y2),
                strokeWidth = op.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun InlineFlow(inlines: List<MdInline>, style: TextStyle, ink: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 10_000f
        val layout = remember(inlines, maxWidthPx, style, ink, density.density, density.fontScale) {
            InlineTypesetter(measurer, density, style, ink).layout(inlines, maxWidthPx)
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { layout.height.toDp() })) {
            drawInlineLayout(layout, ink)
        }
    }
}

/** 独立公式（`$$...$$`）：居中显示，字号比正文大一档。 */
@Composable
internal fun MathView(tex: String, style: TextStyle, ink: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sizePx = with(density) {
        val base = if (style.fontSize == TextUnit.Unspecified) 15f else style.fontSize.toPx()
        base * 1.45f
    }
    val box = remember(tex, sizePx, ink) {
        MathTypesetter(measurer, density, ink).layout(tex, sizePx)
    }
    Canvas(modifier.height(with(density) { (box.height + 6f).toDp() })) {
        val x = (size.width - box.width) / 2f
        val y = 3f + box.ascent
        for (op in box.moved(x, y).ops) {
            when (op) {
                // drawText 的 topLeft 是文本盒左上角，不是基线：要减掉首行基线高度
            is MathOp.Glyph -> drawText(
                textLayoutResult = op.layout,
                topLeft = Offset(op.x, op.baseline - op.layout.firstBaseline),
            )
                is MathOp.Rect -> drawRect(op.color ?: ink, Offset(op.x, op.y), Size(op.w, op.h))
                is MathOp.Line -> drawLine(ink, Offset(op.x1, op.y1), Offset(op.x2, op.y2), op.width, cap = StrokeCap.Round)
            }
        }
    }
}
