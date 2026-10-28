package morefish.knowledge.trainer.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * 只有浅色：产品语境是"纸面 + 墨"，深色模式需要另一套语义色，
 * 没验证过就不假装支持 —— 但所有颜色都已令牌化，后续加深色只改本文件。
 */
@Composable
fun KtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            primaryContainer = Palette.AccentSoft,
            onPrimaryContainer = Palette.AccentDeep,
            secondary = Palette.Ink,
            onSecondary = Palette.OnAccent,
            secondaryContainer = Palette.NeutralSoft,
            onSecondaryContainer = Palette.Ink,
            background = Palette.Canvas,
            onBackground = Palette.Ink,
            surface = Palette.Panel,
            onSurface = Palette.Ink,
            surfaceVariant = Palette.PanelMuted,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.PanelBorderStrong,
            outlineVariant = Palette.PanelBorder,
            error = Palette.Wrong,
            onError = Palette.OnAccent,
            errorContainer = Palette.WrongSoft,
            onErrorContainer = Palette.Wrong,
        ),
        typography = KtTypography,
        shapes = KtShapes,
        content = content,
    )
}
