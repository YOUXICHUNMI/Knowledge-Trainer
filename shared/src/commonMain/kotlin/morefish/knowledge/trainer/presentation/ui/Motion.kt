package morefish.knowledge.trainer.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

/**
 * 动效统一在这里定义。
 *
 * 三条自制规则：
 * 1. 交互反馈 120–260ms，状态切换 260–400ms；超过 400ms 会被感知成"卡"。
 * 2. 只动 transform / alpha / color，不动布局尺寸（除刻意展开的面板），避免重排抖动。
 * 3. 尊重"减少动态"的直觉：没有任何循环动画，页面静止时画面必须完全静止。
 */
object Motion {
    /** 轻量：hover、按压、颜色过渡。 */
    val Quick: Easing = CubicBezierEasing(0.3f, 0f, 0.4f, 1f)

    /** 主转场：进出屏幕、题目切换。 */
    val Emphasis: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val QuickMillis = 140
    const val StandardMillis = 260
    const val EmphasisMillis = 380

    /** 列表逐项入场时每项的间隔，超过 6 项后不再累加，避免最后一项等太久。 */
    const val StaggerStepMillis = 45
    const val StaggerMaxSteps = 6

    val PressSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 900f)
    val PopSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f)
}

/**
 * 按压缩放。Material 的水波纹在桌面端几乎看不见，缩放是唯一能让人确认"点到了"的反馈。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.985f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.PressSpring,
        label = "press-scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 悬停微抬。桌面端把鼠标放在卡片上会有一点点回应，
 * 幅度刻意压到 1.004 —— 再大就会显得整页在晃。
 */
@Composable
fun Modifier.hoverLift(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered && enabled) 1.004f else 1f,
        animationSpec = tween(Motion.QuickMillis, easing = Motion.Quick),
        label = "hover-lift",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 颜色过渡糖：所有"选中 / 正确 / 错误"的底色变化都走这里，避免硬切。 */
@Composable
fun animatedTint(target: Color): Color = animateColorAsState(
    targetValue = target,
    animationSpec = tween(Motion.StandardMillis, easing = Motion.Quick),
    label = "tint",
).value

/**
 * 列表逐项淡入上浮。只在首次组合时触发一次；
 * 滚动、筛选导致的重新组合不会重播，否则每动一下就全屏闪。
 */
@Composable
fun StaggeredAppear(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, Motion.StaggerMaxSteps) * Motion.StaggerStepMillis).toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.StandardMillis, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(Motion.EmphasisMillis, easing = Motion.Emphasis)) { it / 8 },
        exit = fadeOut(tween(Motion.QuickMillis)),
    ) {
        content()
    }
}

/**
 * 数字滚动。正确率、题数这类指标如果直接跳变，用户会怀疑"是不是刷新了"；
 * 从旧值滑到新值能明确表达"这是同一次统计的更新"。
 */
@Composable
fun AnimatedCount(
    value: Int,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = Color.Unspecified,
    suffix: String = "",
    prefix: String = "",
    fontWeight: FontWeight? = null,
) {
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "count",
    )
    Text(
        text = "$prefix$animated$suffix",
        style = style,
        color = color,
        fontWeight = fontWeight,
    )
}

/**
 * 从 0 生长到目标值的浮点动画，用于进度条 / 环形正确率。
 * 用 [Animatable] 而不是 animateFloatAsState：后者首帧直接就位，看不到"长出来"的过程。
 */
@Composable
fun rememberGrowth(target: Float, durationMillis: Int = 700): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) {
        anim.animateTo(target, tween(durationMillis, easing = Motion.Emphasis))
    }
    return anim.value
}
