package morefish.knowledge.trainer.presentation.richtext

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

/** 排版后的一笔绘制指令，坐标相对盒子基线与左边界（y 向下为正）。 */
internal sealed interface MathOp {
    data class Glyph(val layout: TextLayoutResult, val x: Float, val baseline: Float) : MathOp
    data class Rect(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val color: Color? = null,
    ) : MathOp
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : MathOp
}

/**
 * 排版结果：宽 + 基线以上（ascent）/ 以下（descent）的高度 + 绘制指令。
 *
 * 用"基线坐标系"而不是矩形坐标系，是因为公式里的每个原子都要对齐基线，
 * 上下标、分式的分子分母都只是基线的偏移。
 */
internal class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val ops: List<MathOp>,
) {
    val height: Float get() = ascent + descent

    fun moved(dx: Float, dy: Float): MathBox = MathBox(
        width,
        ascent,
        descent,
        ops.map {
            when (it) {
                is MathOp.Glyph -> it.copy(x = it.x + dx, baseline = it.baseline + dy)
                is MathOp.Rect -> it.copy(x = it.x + dx, y = it.y + dy)
                is MathOp.Line -> it.copy(x1 = it.x1 + dx, y1 = it.y1 + dy, x2 = it.x2 + dx, y2 = it.y2 + dy)
            }
        },
    )

    companion object {
        val Empty = MathBox(0f, 0f, 0f, emptyList())
    }
}

private data class MathStyle(
    val bold: Boolean = false,
    val upright: Boolean = false,
    val blackboard: Boolean = false,
)

/**
 * 极简 TeX 排版引擎。
 *
 * 没有字体度量文件、没有 LuaTeX：全部用 Compose 的 [TextMeasurer] 量字形，
 * 用固定的 em 比例（分式轴线 0.26em、根号 0.52em…）拼版。
 * 目标是"考试题干里的小公式看起来对"，不是印刷级数学排版。
 */
internal class MathTypesetter(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val color: Color,
    private val family: FontFamily = FontFamily.Default,
) {

    private val measureCache = mutableMapOf<String, TextLayoutResult>()
    private val parseCache = mutableMapOf<String, MathNode>()

    fun parse(tex: String): MathNode = parseCache.getOrPut(tex) { MathSourceParser(tex).parse() }

    fun layout(tex: String, sizePx: Float): MathBox = layoutNode(parse(tex), sizePx, MathStyle())

    // ------------------------------------------------------------ 原子

    private fun measure(text: String, sizePx: Float, italic: Boolean, bold: Boolean): TextLayoutResult {
        val key = "$text|${sizePx.toInt()}|$italic|$bold"
        measureCache[key]?.let { return it }
        val style = TextStyle(
            color = color,
            fontFamily = family,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = with(density) { sizePx.toSp() },
        )
        val result = measurer.measure(
            text = text,
            style = style,
            softWrap = false,
            maxLines = 1,
        )
        measureCache[key] = result
        return result
    }

    private fun glyph(text: String, sizePx: Float, italic: Boolean = false, bold: Boolean = false): MathBox {
        if (text.isEmpty()) return MathBox.Empty
        val layout = measure(text, sizePx, italic, bold)
        val ascent = layout.firstBaseline
        val descent = (layout.size.height - layout.firstBaseline).coerceAtLeast(0f)
        return MathBox(
            layout.size.width.toFloat(),
            ascent,
            descent,
            listOf(MathOp.Glyph(layout, 0f, 0f)),
        )
    }

    // ------------------------------------------------------------ 递归布局

    private fun layoutNode(node: MathNode, size: Float, style: MathStyle): MathBox = when (node) {
        is MathNode.Row -> row(node.items, size, style)
        is MathNode.Sym -> glyph(
            text = if (style.blackboard) blackboard(node.text) else node.text,
            sizePx = size,
            italic = node.italic && !style.upright,
            bold = style.bold,
        )
        is MathNode.Words -> glyph(node.text, size, italic = false, bold = style.bold)
        is MathNode.Frac -> fraction(node, size, style)
        is MathNode.Radical -> radical(node, size, style)
        is MathNode.Script -> scripts(node.base, node.sup, node.sub, size, style)
        is MathNode.Fenced -> fenced(node, size, style)
        is MathNode.BigOp -> bigOp(node, size, style)
        is MathNode.Styled -> layoutNode(
            node.body,
            size,
            style.copy(
                bold = style.bold || node.bold,
                upright = style.upright || node.upright,
                blackboard = style.blackboard || node.blackboard,
            ),
        )
        is MathNode.Overline -> overline(node.body, size, style)
        is MathNode.Accent -> accent(node, size, style)
        is MathNode.Space -> MathBox(node.em.coerceAtLeast(0f) * size, 0f, 0f, emptyList())
        is MathNode.Grid -> grid(node, size, style)
        is MathNode.Unsupported -> glyph(node.text, size * 0.92f, italic = false)
    }

    private fun row(items: List<MathNode>, size: Float, style: MathStyle): MathBox {
        if (items.isEmpty()) return MathBox.Empty
        var x = 0f
        var ascent = 0f
        var descent = 0f
        val ops = mutableListOf<MathOp>()
        var previous: AtomClass? = null
        for (item in items) {
            if (item is MathNode.Space) {
                x += item.em * size
                continue
            }
            val box = layoutNode(item, size, style)
            val cls = classOf(item)
            if (previous != null) x += spaceBetween(previous, cls, size)
            val placed = box.moved(x, 0f)
            ops += placed.ops
            if (box.ascent > ascent) ascent = box.ascent
            if (box.descent > descent) descent = box.descent
            x += box.width
            previous = cls
        }
        return MathBox(x, ascent, descent, ops)
    }

    private fun classOf(node: MathNode): AtomClass = when (node) {
        is MathNode.Sym -> node.cls
        is MathNode.BigOp -> AtomClass.Big
        is MathNode.Row -> AtomClass.Ord
        is MathNode.Space -> AtomClass.Ord
        else -> AtomClass.Ord
    }

    private fun spaceBetween(a: AtomClass, b: AtomClass, size: Float): Float {
        if (a == AtomClass.Func) return 0.18f * size
        if (a == AtomClass.Open || b == AtomClass.Close) return 0f
        if (a == AtomClass.Punct) return 0.16f * size
        if (a == AtomClass.Bin && b == AtomClass.Bin) return 0f
        if (a == AtomClass.Bin || b == AtomClass.Bin) return 0.22f * size
        if (a == AtomClass.Rel || b == AtomClass.Rel) return 0.28f * size
        if (a == AtomClass.Big || b == AtomClass.Big) return 0.12f * size
        return 0f
    }

    private fun fraction(node: MathNode.Frac, size: Float, style: MathStyle): MathBox {
        val num = layoutNode(node.num, size, style)
        val den = layoutNode(node.den, size, style)
        val axis = 0.26f * size
        val bar = maxOf(1f, 0.05f * size)
        val gap = 0.13f * size
        val pad = 0.15f * size
        val numBaseline = -(axis + bar / 2f + gap + num.descent)
        val denBaseline = -axis + bar / 2f + gap + den.ascent
        val width = maxOf(num.width, den.width) + pad * 2f
        val ops = buildList {
            addAll(num.moved((width - num.width) / 2f, numBaseline).ops)
            add(MathOp.Rect(0f, -axis - bar / 2f, width, bar))
            addAll(den.moved((width - den.width) / 2f, denBaseline).ops)
        }
        return MathBox(width, -numBaseline + num.ascent, denBaseline + den.descent, ops)
    }

    private fun radical(node: MathNode.Radical, size: Float, style: MathStyle): MathBox {
        val body = layoutNode(node.body, size, style)
        val bar = maxOf(1f, 0.045f * size)
        val gap = 0.14f * size
        val height = body.ascent + gap
        val radW = 0.5f * size
        val bodyX = radW
        val ops = mutableListOf<MathOp>()
        var ascent = height + bar
        node.index?.let { indexNode ->
            val index = layoutNode(indexNode, size * 0.55f, style)
            val indexBaseline = -(height + 0.12f * size)
            ops += index.moved(0f, indexBaseline).ops
            ascent = maxOf(ascent, -indexBaseline + index.ascent)
        }
        ops += body.moved(bodyX, 0f).ops
        val right = bodyX + body.width
        ops += MathOp.Line(0.02f * radW, -0.34f * height, 0.30f * radW, 0f, bar)
        ops += MathOp.Line(0.30f * radW, 0f, 0.58f * radW, -height, bar)
        ops += MathOp.Line(0.58f * radW, -height, right, -height, bar)
        return MathBox(right, ascent, body.descent + 0.04f * size, ops)
    }

    private fun scripts(baseNode: MathNode, supNode: MathNode?, subNode: MathNode?, size: Float, style: MathStyle): MathBox {
        val base = layoutNode(baseNode, size, style)
        val small = size * 0.72f
        val sup = supNode?.let { layoutNode(it, small, style) }
        val sub = subNode?.let { layoutNode(it, small, style) }
        val supBaseline = if (sup != null) -(base.ascent * 0.55f + 0.10f * size) else 0f
        val subBaseline = if (sub != null) maxOf(0.16f * size, base.descent * 0.2f) else 0f
        val kern = 0.03f * size
        val scriptWidth = maxOf(sup?.width ?: 0f, sub?.width ?: 0f)
        val width = base.width + if (scriptWidth > 0f) kern + scriptWidth else 0f
        val ops = mutableListOf<MathOp>()
        ops += base.ops
        sup?.let { ops += it.moved(base.width + kern, supBaseline).ops }
        sub?.let { ops += it.moved(base.width + kern, subBaseline).ops }
        val ascent = maxOf(base.ascent, sup?.let { -supBaseline + it.ascent } ?: 0f)
        val descent = maxOf(base.descent, sub?.let { subBaseline + it.descent } ?: 0f)
        return MathBox(width, ascent, descent, ops)
    }

    private fun fenced(node: MathNode.Fenced, size: Float, style: MathStyle): MathBox {
        val body = layoutNode(node.body, size, style)
        val target = maxOf(body.height + 0.22f * size, size * 0.95f)
        val open = stretchy(node.open, target, size)
        val close = stretchy(node.close, target, size)
        val bodyX = open?.width ?: 0f
        val center = (body.ascent - body.descent) / 2f
        val ops = mutableListOf<MathOp>()
        var ascent = body.ascent
        var descent = body.descent
        open?.let {
            val shift = -center + (it.ascent - it.descent) / 2f
            ops += it.moved(0f, shift).ops
            ascent = maxOf(ascent, -shift + it.ascent)
            descent = maxOf(descent, shift + it.descent)
        }
        ops += body.moved(bodyX, 0f).ops
        close?.let {
            val shift = -center + (it.ascent - it.descent) / 2f
            ops += it.moved(bodyX + body.width, shift).ops
            ascent = maxOf(ascent, -shift + it.ascent)
            descent = maxOf(descent, shift + it.descent)
        }
        return MathBox(bodyX + body.width + (close?.width ?: 0f), ascent, descent, ops)
    }

    private fun stretchy(delimiter: String, targetHeight: Float, size: Float): MathBox? {
        if (delimiter.isEmpty()) return null
        val unit = glyph(delimiter, size)
        val unitHeight = unit.height
        if (unitHeight <= 0f) return null
        val scale = (targetHeight / unitHeight).coerceIn(1f, 3.4f)
        return if (scale <= 1.05f) unit else glyph(delimiter, size * scale)
    }

    private fun bigOp(node: MathNode.BigOp, size: Float, style: MathStyle): MathBox {
        val op = glyph(node.text, size * 1.32f)
        val integral = node.text in INTEGRALS
        val small = size * 0.78f
        val sup = node.sup?.let { layoutNode(it, small, style) }
        val sub = node.sub?.let { layoutNode(it, small, style) }

        if (integral) {
            // 积分号的上限下标挂在右侧，不堆在上下——这是数学书写惯例
            val supBaseline = -0.55f * size
            val subBaseline = 0.28f * size
            val kern = 0.05f * size
            val width = op.width + maxOf(sup?.width ?: 0f, sub?.width ?: 0f) + kern
            val ops = mutableListOf<MathOp>()
            ops += op.ops
            sup?.let { ops += it.moved(op.width + kern, supBaseline).ops }
            sub?.let { ops += it.moved(op.width + kern, subBaseline).ops }
            val ascent = maxOf(op.ascent, sup?.let { -supBaseline + it.ascent } ?: 0f)
            val descent = maxOf(op.descent, sub?.let { subBaseline + it.descent } ?: 0f)
            return MathBox(width, ascent, descent, ops)
        }

        val gap = 0.07f * size
        val supBaseline = if (sup != null) -(op.ascent + gap + sup.descent) else 0f
        val subBaseline = if (sub != null) op.descent + gap + sub.ascent else 0f
        val width = maxOf(op.width, sup?.width ?: 0f, sub?.width ?: 0f)
        val ops = mutableListOf<MathOp>()
        ops += op.moved((width - op.width) / 2f, 0f).ops
        sup?.let { ops += it.moved((width - it.width) / 2f, supBaseline).ops }
        sub?.let { ops += it.moved((width - it.width) / 2f, subBaseline).ops }
        val ascent = maxOf(op.ascent, sup?.let { -supBaseline + it.ascent } ?: 0f)
        val descent = maxOf(op.descent, sub?.let { subBaseline + it.descent } ?: 0f)
        return MathBox(width, ascent, descent, ops)
    }

    private fun overline(bodyNode: MathNode, size: Float, style: MathStyle): MathBox {
        val body = layoutNode(bodyNode, size, style)
        val thickness = maxOf(1f, 0.045f * size)
        val gap = 0.1f * size
        val ops = body.ops + MathOp.Rect(0f, -(body.ascent + gap), body.width, thickness)
        return MathBox(body.width, body.ascent + gap + thickness, body.descent, ops)
    }

    private fun accent(node: MathNode.Accent, size: Float, style: MathStyle): MathBox {
        val body = layoutNode(node.body, size, style)
        val mark = glyph(node.mark, size * 0.75f)
        val lift = 0.07f * size
        val x = (body.width - mark.width) / 2f
        val baseline = -(body.ascent + lift + mark.descent)
        val ops = body.ops + mark.moved(x, baseline).ops
        return MathBox(body.width, -baseline + mark.ascent, body.descent, ops)
    }

    private fun grid(node: MathNode.Grid, size: Float, style: MathStyle): MathBox {
        if (node.rows.isEmpty()) return MathBox.Empty
        val cells = node.rows.map { row -> row.map { layoutNode(it, size, style) } }
        val columns = cells.maxOf { it.size }
        val columnWidth = FloatArray(columns)
        cells.forEach { row ->
            row.forEachIndexed { index, box -> if (box.width > columnWidth[index]) columnWidth[index] = box.width }
        }
        val columnGap = 0.85f * size
        val rowGap = 0.3f * size
        val rowAscent = cells.map { row -> row.maxOfOrNull { it.ascent } ?: 0f }
        val rowDescent = cells.map { row -> row.maxOfOrNull { it.descent } ?: 0f }
        val totalWidth = columnWidth.sum() + columnGap * (columns - 1).coerceAtLeast(0)
        val totalHeight = rowAscent.sum() + rowDescent.sum() + rowGap * (cells.size - 1).coerceAtLeast(0)

        // 基线偏移：整块垂直居中（相对于自身中心）
        var y = -totalHeight / 2f
        val ops = mutableListOf<MathOp>()
        cells.forEachIndexed { rowIndex, row ->
            val baseline = y + rowAscent[rowIndex]
            var x = 0f
            row.forEachIndexed { columnIndex, box ->
                val offset = (columnWidth[columnIndex] - box.width) / 2f
                ops += box.moved(x + offset, baseline).ops
                x += columnWidth[columnIndex] + columnGap
            }
            y += rowAscent[rowIndex] + rowDescent[rowIndex] + rowGap
        }
        val body = MathBox(totalWidth, totalHeight / 2f, totalHeight / 2f, ops)
        return when (node.env) {
            "pmatrix" -> fencedAround("(", ")", body, size)
            "bmatrix" -> fencedAround("[", "]", body, size)
            "vmatrix" -> fencedAround("|", "|", body, size)
            "Vmatrix" -> fencedAround("\u2016", "\u2016", body, size)
            "cases" -> casesAround("{", body, size)
            else -> body
        }
    }

    private fun fencedAround(open: String, close: String, body: MathBox, size: Float): MathBox {
        val target = body.height + 0.24f * size
        val openBox = stretchy(open, target, size)
        val closeBox = stretchy(close, target, size)
        val ops = mutableListOf<MathOp>()
        var ascent = body.ascent
        var descent = body.descent
        openBox?.let {
            val shift = -(body.ascent - body.descent) / 2f + (it.ascent - it.descent) / 2f
            ops += it.moved(0f, shift).ops
            ascent = maxOf(ascent, -shift + it.ascent)
            descent = maxOf(descent, shift + it.descent)
        }
        val bodyX = openBox?.width ?: 0f
        ops += body.moved(bodyX, 0f).ops
        closeBox?.let {
            val shift = -(body.ascent - body.descent) / 2f + (it.ascent - it.descent) / 2f
            ops += it.moved(bodyX + body.width, shift).ops
            ascent = maxOf(ascent, -shift + it.ascent)
            descent = maxOf(descent, shift + it.descent)
        }
        return MathBox(bodyX + body.width + (closeBox?.width ?: 0f), ascent, descent, ops)
    }

    private fun casesAround(open: String, body: MathBox, size: Float): MathBox {
        val target = body.height + 0.24f * size
        val openBox = stretchy(open, target, size)
        val ops = mutableListOf<MathOp>()
        var ascent = body.ascent
        var descent = body.descent
        openBox?.let {
            val shift = -(body.ascent - body.descent) / 2f + (it.ascent - it.descent) / 2f
            ops += it.moved(0f, shift).ops
            ascent = maxOf(ascent, -shift + it.ascent)
            descent = maxOf(descent, shift + it.descent)
        }
        val bodyX = (openBox?.width ?: 0f) + 0.08f * size
        ops += body.moved(bodyX, 0f).ops
        return MathBox(bodyX + body.width, ascent, descent, ops)
    }

    private fun blackboard(text: String): String = when (text) {
        "C" -> "\u2102"
        "H" -> "\u210D"
        "N" -> "\u2115"
        "P" -> "\u2119"
        "Q" -> "\u211A"
        "R" -> "\u211D"
        "Z" -> "\u2124"
        else -> text
    }

    private companion object {
        val INTEGRALS = setOf("\u222B", "\u222C", "\u222D", "\u222E")
    }
}
