package morefish.knowledge.trainer.presentation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.presentation.Banner
import morefish.knowledge.trainer.presentation.theme.AccentGradient
import morefish.knowledge.trainer.presentation.theme.Palette

fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.CORRECT -> Palette.Correct
    Verdict.PARTIALLY_CORRECT -> Palette.Pending
    Verdict.INCORRECT -> Palette.Wrong
    Verdict.PENDING -> Palette.Pending
    Verdict.ABANDONED -> Palette.Muted
}

fun verdictSoft(verdict: Verdict): Color = when (verdict) {
    Verdict.CORRECT -> Palette.CorrectSoft
    Verdict.PARTIALLY_CORRECT -> Palette.PendingSoft
    Verdict.INCORRECT -> Palette.WrongSoft
    Verdict.PENDING -> Palette.PendingSoft
    Verdict.ABANDONED -> Palette.NeutralSoft
}

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 1 -> "<1 秒"
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}

fun formatRelative(nowMillis: Long, thenMillis: Long): String {
    val diff = nowMillis - thenMillis
    if (diff < 0) return "刚刚"
    val minutes = diff / 60000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

// ------------------------------------------------------------------ 页面外壳

/**
 * 所有页面共用的外壳：固定页头 + 可滚动内容 + 可选进度线。
 *
 * 页头随滚动轻微"抬起"（阴影渐显），是唯一一处在滚动时变化的装饰 ——
 * 它让人知道内容在下面，而不是页面卡住了。
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    overline: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 3 } }
    val lift by animateDpAsState(
        targetValue = if (scrolled) 10.dp else 0.dp,
        animationSpec = tween(Motion.StandardMillis),
        label = "header-lift",
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(Motion.StandardMillis),
        label = "header-divider",
    )

    Box(modifier = Modifier.fillMaxSize().background(Palette.Canvas)) {
        // 顶部一层极淡的靛蓝晕染，避免整页是死板的灰白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Palette.GlowTop, Palette.Canvas.copy(alpha = 0f)),
                    ),
                ),
        )

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(lift)
                    .background(Palette.Panel),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 980.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        BackChip(onBack)
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (overline != null) {
                            Text(
                                overline,
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Faint,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Palette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(12.dp))
                        trailing()
                    }
                }

                if (progress != null) {
                    ProgressLine(progress = progress, height = 3.dp, rounded = false)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Palette.PanelBorder.copy(alpha = dividerAlpha)),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 980.dp)
                    .weight(1f)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(scroll)
                    .padding(top = 20.dp, bottom = 40.dp),
                content = content,
            )
        }
    }
}

/** 返回按钮做成胶囊形，比默认 TextButton 更像一个"可点的东西"。 */
@Composable
private fun BackChip(onBack: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Palette.PanelMuted)
            .border(1.dp, Palette.PanelBorder, CircleShape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onBack)
            .pressScale(source, pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Text("←", style = MaterialTheme.typography.titleSmall, color = Palette.Muted)
    }
}

// ------------------------------------------------------------------ 卡片

/**
 * 内容卡片。整套界面只有这一种"面"：白纸 + 1dp 描边 + 极轻阴影。
 * 通过 [accent] 顶端色条和 [onClick] 区分强调层级，而不是靠更多边框颜色。
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    background: Color = Palette.Panel,
    borderColor: Color = Palette.PanelBorder,
    padding: Dp = 18.dp,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    var base = modifier
        .fillMaxWidth()
        .shadow(2.dp, shape)
        .clip(shape)
        .background(background)
        .border(1.dp, borderColor, shape)

    if (onClick != null) {
        base = base
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pressScale(source)
    }

    Column(modifier = base) {
        if (accent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.15f)))),
            )
        }
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** 空状态：与其显示一行灰字，不如把"接下来能做什么"说清楚。 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    glyph: String = "○",
    action: (@Composable () -> Unit)? = null,
) {
    Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorder) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, style = MaterialTheme.typography.titleMedium, color = Palette.AccentInk)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
                Spacer(Modifier.height(3.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

// ------------------------------------------------------------------ 标题与小件

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(15.dp)
                .background(Palette.Accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Palette.Ink) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

@Composable
fun Mono(text: String, color: Color = Palette.Ink) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.CodeBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

/** 语义色小标签。判定结论、题型、审核状态都用它，保证同一含义同一形状。 */
@Composable
fun Pill(
    text: String,
    color: Color,
    background: Color = color.copy(alpha = 0.1f),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier.fillMaxWidth(), color: Color = Palette.PanelBorder) {
    Box(modifier.height(1.dp).background(color))
}

/**
 * 筛选 / 选择用的小胶囊。
 *
 * 不用 Material 的 FilterChip：它在选中时只是加一层 8% 透明度的底色，
 * 在投影仪或低亮度屏上几乎看不出选中与否。这里选中就是实心主色。
 */
@Composable
fun KtChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val background = animatedTint(
        when {
            !enabled -> Palette.PanelMuted
            // 胶囊选中是"整块上色 + 压白字"，所以用当底色用的 Accent，不是 AccentInk
            selected -> Palette.Accent
            else -> Palette.Panel
        },
    )
    val foreground = animatedTint(
        when {
            !enabled -> Palette.Faint
            selected -> Palette.OnAccent
            else -> Palette.Muted
        },
    )
    val borderColor = animatedTint(
        when {
            !enabled -> Palette.PanelBorder
            selected -> Palette.Accent
            else -> Palette.PanelBorderStrong
        },
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .hoverable(source, enabled = enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .pressScale(source, enabled, pressedScale = 0.95f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

// ------------------------------------------------------------------ 进度与指标

/**
 * 进度条。从当前值平滑长到目标值 —— 答题时看到它一格一格推进，
 * 比数字"3/10"更能感知还剩多少。
 */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    rounded: Boolean = true,
    track: Color = Palette.NeutralSoft,
    brush: Brush = AccentGradient,
) {
    val grown = rememberGrowth(progress.coerceIn(0f, 1f))
    val shape = if (rounded) RoundedCornerShape(height) else RoundedCornerShape(0.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(grown)
                .clip(shape)
                .background(brush),
        )
    }
}

/** 指标块：一格一个数字，横向排成"仪表盘"。 */
@Composable
fun StatTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
    accent: Color = Palette.Ink,
    hint: String? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.PanelMuted)
            .border(1.dp, Palette.PanelBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Spacer(Modifier.height(4.dp))
        AnimatedCount(
            value = value,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            suffix = suffix,
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ 按钮

/**
 * 按钮三件套。刻意只保留三种：
 * 主（深靛蓝实心，一屏最多一个）、次（描边）、弱（纯文字）。
 * 层级超过三种，用户就要开始猜"哪个才是下一步"。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .hoverable(source)
            .pressScale(source, enabled),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.NeutralSoft,
            disabledContentColor = Palette.Faint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        interactionSource = source,
    ) {
        if (leading != null) {
            Text(leading, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .hoverable(source)
            .pressScale(source, enabled),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Palette.Ink,
            disabledContentColor = Palette.Faint,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) Palette.PanelBorderStrong else Palette.PanelBorder,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        interactionSource = source,
    ) {
        if (leading != null) {
            Text(leading, style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Muted,
) {
    val source = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(source, enabled),
        enabled = enabled,
        interactionSource = source,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

// ------------------------------------------------------------------ 提示条

/**
 * 提示条带进出动画。首页、导入页、练习页都会用到，
 * 所以做成"传 null 就自己淡出"的 Host，避免各屏各写一遍。
 */
@Composable
fun BannerHost(banner: Banner?, onDismiss: () -> Unit) {
    AnimatedContent(
        targetState = banner,
        transitionSpec = {
            (fadeIn(tween(Motion.StandardMillis)) + slideInVertically(tween(Motion.EmphasisMillis)) { -it / 2 })
                .togetherWith(fadeOut(tween(Motion.QuickMillis)) + slideOutVertically(tween(Motion.StandardMillis)) { -it / 2 })
        },
        label = "banner",
    ) { current ->
        if (current != null) {
            BannerBar(current, onDismiss)
        } else {
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
fun BannerBar(banner: Banner, onDismiss: () -> Unit) {
    val fg = if (banner.isError) Palette.Wrong else Palette.Correct
    val bg = if (banner.isError) Palette.WrongSoft else Palette.CorrectSoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(fg, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            banner.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
        )
        TextButton(onClick = onDismiss) {
            Text("关闭", style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

