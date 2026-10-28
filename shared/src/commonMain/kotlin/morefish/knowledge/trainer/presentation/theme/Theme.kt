package morefish.knowledge.trainer.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import morefish.knowledge.trainer.application.port.ThemeMode

/**
 * 中文优先的字阶。
 *
 * Material 默认字阶是给拉丁文设计的：中文在 14sp / 1.5 行高下比拉丁文更挤，
 * 所以正文统一抬行高，标题收紧字距，数字用衬线体做出"成绩单"的观感。
 */
private val KtTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val KtShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * 唯一主题入口。所有平台共用，保证桌面 / Android / Web 的观感一致。
 *
 * 两套配色（暖纸 / 墨夜）都是令牌，见 [LightScheme] / [DarkScheme]。
 *
 * [dark] 是**已经解算过**的结论，不是用户的选择：用户选择三种（跟随系统 / 浅色 / 深色），
 * 由 [ThemeMode.isDark] 在组合期问一次系统，再传到这里。这样主题这一层不需要知道
 * "系统现在是什么"，测试里也能直接给一个确定的布尔值。
 *
 * **副作用**：进入时会把 [Palette.current] 切到对应方案。这是整棵子树的颜色来源，
 * 所以调用方必须在切换时让子树重建（见 `AppContent` 的 `key(dark)`）。
 */
@Composable
fun KtTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (dark) DarkScheme else LightScheme
    Palette.use(scheme)
    MaterialTheme(
        colorScheme = if (dark) darkColorSchemeFrom(scheme) else lightColorSchemeFrom(scheme),
        typography = KtTypography,
        shapes = KtShapes,
        content = content,
    )
}

/** 用户的选择（跟随系统 / 浅色 / 深色）在组合期解算成"现在到底是不是深色"。 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun lightColorSchemeFrom(p: PaletteScheme) = lightColorScheme(
    primary = p.accent,
    onPrimary = p.onAccent,
    primaryContainer = p.accentSoft,
    onPrimaryContainer = p.accentDeep,
    secondary = p.ink,
    onSecondary = p.onAccent,
    secondaryContainer = p.neutralSoft,
    onSecondaryContainer = p.ink,
    background = p.canvas,
    onBackground = p.ink,
    surface = p.panel,
    onSurface = p.ink,
    surfaceVariant = p.panelMuted,
    onSurfaceVariant = p.muted,
    outline = p.panelBorderStrong,
    outlineVariant = p.panelBorder,
    error = p.wrong,
    onError = p.onAccent,
    errorContainer = p.wrongSoft,
    onErrorContainer = p.wrong,
)

/**
 * 深色方案的 Material 映射与浅色逐项对应，唯一的差别是生成的 `ColorScheme` 类型：
 * 它决定 Material 组件内部"按明暗分支"的那几处默认行为（涟漪、描边、滚动条等）。
 */
private fun darkColorSchemeFrom(p: PaletteScheme) = darkColorScheme(
    primary = p.accent,
    onPrimary = p.onAccent,
    primaryContainer = p.accentSoft,
    onPrimaryContainer = p.accentDeep,
    secondary = p.ink,
    onSecondary = p.onAccent,
    secondaryContainer = p.neutralSoft,
    onSecondaryContainer = p.ink,
    background = p.canvas,
    onBackground = p.ink,
    surface = p.panel,
    onSurface = p.ink,
    surfaceVariant = p.panelMuted,
    onSurfaceVariant = p.muted,
    outline = p.panelBorderStrong,
    outlineVariant = p.panelBorder,
    error = p.wrong,
    onError = p.onAccent,
    errorContainer = p.wrongSoft,
    onErrorContainer = p.wrong,
)
