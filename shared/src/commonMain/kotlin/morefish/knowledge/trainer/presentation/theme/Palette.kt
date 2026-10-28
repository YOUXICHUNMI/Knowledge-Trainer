package morefish.knowledge.trainer.presentation.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌：颜色只在这里定义一次。
 *
 * 之前每屏各写 `Color(0xFF...)`，改一处配色要翻十个文件，也容易让"正确/错误"的语义色
 * 在不同页面出现色差 —— 判题结论的颜色必须全应用一致，否则用户会读错。
 */
object Palette {
    // ---- 底层：暖纸色画布 + 纯白纸面 ----
    val Canvas = Color(0xFFF3F1EA)
    val CanvasEdge = Color(0xFFE8E5DA)
    val Panel = Color(0xFFFFFFFF)
    val PanelMuted = Color(0xFFFAF9F4)
    val PanelBorder = Color(0xFFE5E1D5)
    val PanelBorderStrong = Color(0xFFCFCABA)

    // ---- 文字：墨色三级 ----
    val Ink = Color(0xFF1A1915)
    val Muted = Color(0xFF6C695E)
    val Faint = Color(0xFF9B9689)

    // ---- 主色：靛蓝 ----
    val Accent = Color(0xFF4A3ECB)
    val AccentDeep = Color(0xFF322796)
    val AccentSoft = Color(0xFFEFEDFC)
    val AccentLine = Color(0xFFD9D4F7)
    val OnAccent = Color(0xFFFFFEFB)

    // ---- 语义色：判定结论 ----
    val Correct = Color(0xFF0B7B58)
    val CorrectSoft = Color(0xFFE0F4EC)
    val Wrong = Color(0xFFBB441F)
    val WrongSoft = Color(0xFFFBE9E2)
    val Pending = Color(0xFF94610E)
    val PendingSoft = Color(0xFFFAEFD7)
    val Neutral = Color(0xFF6C695E)
    val NeutralSoft = Color(0xFFEFEDE4)

    // ---- 组件细节 ----
    val CodeBg = Color(0xFFF2F0E6)
    val ShadowSoft = Color(0x12000000)
    val ShadowLifted = Color(0x1E000000)
    val HeroStart = Color(0xFF3A2FB4)
    val HeroEnd = Color(0xFF6E5AE6)
}

/** 主按钮 / 进度条的渐变，避免大面积纯色显得单调。 */
val AccentGradient = Brush.linearGradient(
    listOf(Palette.Accent, Color(0xFF6F5BE8)),
)

/** 首页 Hero 的深色渐变。深底上放白字，和整页纸感形成对比。 */
val HeroGradient = Brush.linearGradient(
    listOf(Palette.HeroStart, Palette.HeroEnd),
)
