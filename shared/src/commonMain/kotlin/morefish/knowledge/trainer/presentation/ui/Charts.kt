package morefish.knowledge.trainer.presentation.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.presentation.theme.Palette
import kotlin.math.roundToInt

/**
 * 环形正确率。
 *
 * [progress] 为 null 时画一条完整的浅色轨 + 一个「—」：
 * 没有任何已判定题时不该显示 0%，那是"全错"的意思。
 */
@Composable
fun RingMeter(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    stroke: Dp = 9.dp,
    color: Color = Palette.Accent,
    track: Color = Palette.NeutralSoft,
    contentColor: Color = Palette.Ink,
    caption: String? = null,
) {
    val grown = rememberGrowth(progress ?: 0f, durationMillis = 900)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (progress != null && grown > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * grown,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (progress == null) "—" else "${(grown * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

/** 分段条的一段。weight 为 0 的段不占位置，但会保留在数据里，方便图例始终齐全。 */
data class BarSegment(val weight: Float, val color: Color)

/**
 * 判定分布条。比并排的五个数字更快看懂"这次练得怎么样"，
 * 但数字仍然原样保留在下方 —— 图形只是索引，不是替代。
 */
@Composable
fun SegmentedBar(
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    val total = segments.sumOf { it.weight.toDouble() }.toFloat()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Palette.NeutralSoft),
    ) {
        if (total <= 0f) return@Row
        segments.forEach { segment ->
            val target = (segment.weight / total).coerceIn(0f, 1f)
            val animated by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(520, easing = Motion.Emphasis),
                label = "segment",
            )
            if (animated > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(animated)
                        .background(segment.color),
                )
            }
        }
    }
}
