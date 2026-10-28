package morefish.knowledge.trainer.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 配色的对比度验收：**两套方案、逐条阈值**。
 *
 * 深色模式最容易出的事故不是"不好看"，而是"某行字在某块底上读不出来" ——
 * 那种问题在一台亮着顶灯的显示器上、在截图缩略图里，几乎看不出来，
 * 只有在夜里把亮度调低时才会被人发现，而那时已经发出去了。
 *
 * 所以这里不靠眼睛：按 WCAG 的相对亮度公式逐对算，每对写清它为什么是这个门槛。
 * 阈值取自 WCAG 2.1：正文 4.5:1（AA）、大字号 3:1、装饰与提示可更低。
 * 本应用正文是 14–15sp 的常规字重，按正文算。
 */
class PaletteContrastTest {

    /** 一对"字压在某块底上"的关系，以及它必须达到的对比度门槛。 */
    private data class Reading(val pair: String, val fg: Color, val bg: Color, val min: Float)

    /**
     * 两套方案都要过的"读文字"清单。
     *
     * 门槛取自 WCAG 2.1：正文 4.5:1（AA）、大字号 3:1、装饰性提示可更低。
     * 本应用正文是 14–15sp 的常规字重，一律按正文算；主阅读路径（墨色压纸面）
     * 直接按 AAA 的 7:1 要求，留出余量。
     */
    private fun readings(s: PaletteScheme): List<Reading> = listOf(
        // 正文墨色压在画布 / 纸面上：主阅读路径
        Reading("ink/canvas", s.ink, s.canvas, 7.0f),
        Reading("ink/panel", s.ink, s.panel, 7.0f),
        Reading("ink/panelMuted", s.ink, s.panelMuted, 7.0f),
        // 次级文字（说明、副标题）
        Reading("muted/panel", s.muted, s.panel, 4.5f),
        Reading("muted/canvas", s.muted, s.canvas, 4.5f),
        // 三级提示（计数、脚注）：装饰性，但也不许低到看不清
        Reading("faint/panel", s.faint, s.panel, 2.5f),
        // 代码块是另一块底，正文与次级各自再过一遍
        Reading("ink/codeBg", s.ink, s.codeBg, 7.0f),
        Reading("muted/codeBg", s.muted, s.codeBg, 4.5f),
        // 主色当字用（链接、图标、胶囊文字）—— 深色下这里是 AccentInk 而不是 Accent
        Reading("accentInk/panel", s.accentInk, s.panel, 4.5f),
        Reading("accentInk/canvas", s.accentInk, s.canvas, 4.5f),
        Reading("accentInk/accentSoft", s.accentInk, s.accentSoft, 4.5f),
        // 主色当底用（主按钮、选中的胶囊）：上面压的是 OnAccent
        Reading("onAccent/accent", s.onAccent, s.accent, 4.5f),
        // 软底上的深色字
        Reading("accentDeep/accentSoft", s.accentDeep, s.accentSoft, 4.5f),
        // 判题结论：四色都要在自己那块软底上读得出来
        Reading("correct/correctSoft", s.correct, s.correctSoft, 4.5f),
        Reading("wrong/wrongSoft", s.wrong, s.wrongSoft, 4.5f),
        Reading("pending/pendingSoft", s.pending, s.pendingSoft, 4.5f),
        Reading("neutral/neutralSoft", s.neutral, s.neutralSoft, 4.5f),
        // 结论也要能直接压在纸面上（列表里的「正确 / 错误」标签）
        Reading("correct/panel", s.correct, s.panel, 4.5f),
        Reading("wrong/panel", s.wrong, s.panel, 4.5f),
        Reading("pending/panel", s.pending, s.panel, 4.5f),
        // 首页 Hero：整块渐变上压白字，两端都要够
        Reading("onAccent/heroStart", s.onAccent, s.heroStart, 4.5f),
        Reading("onAccent/heroEnd", s.onAccent, s.heroEnd, 4.5f),
        // Hero 上的那颗主按钮是**白底**（不随主题变），字必须一直是深色
        Reading("heroActionInk/white", s.heroActionInk, Color.White, 4.5f),
    )

    @Test
    fun bothSchemesAreReadableWhereItMatters() {
        for ((name, scheme) in listOf("浅色" to LightScheme, "深色" to DarkScheme)) {
            for (r in readings(scheme)) {
                val ratio = contrast(r.fg, r.bg)
                assertTrue(
                    ratio >= r.min,
                    "${name}方案 ${r.pair} 的对比度只有 ${round2(ratio)}:1，低于 ${r.min}:1",
                )
            }
        }
    }

    /**
     * 深色不是"把浅色调暗"，而是把关系倒过来。
     *
     * 这条断言盯的正是那种偷懒实现：直接把整套颜色乘个系数，
     * 结果纸比字还亮，或者浅色面在深色页里突兀地亮成一块白。
     */
    @Test
    fun theDarkSchemeInvertsThePaperAndInkRelationship() {
        assertTrue(
            DarkScheme.canvas.luminance() < LightScheme.canvas.luminance(),
            "深色的画布必须比浅色暗",
        )
        assertTrue(
            DarkScheme.ink.luminance() > LightScheme.ink.luminance(),
            "深色的墨色必须比浅色亮",
        )
        assertTrue(
            DarkScheme.panel.luminance() < DarkScheme.ink.luminance(),
            "深色里面纸仍然要比字暗 —— 只是两者都换了个方向",
        )
        assertTrue(
            DarkScheme.panel.luminance() > DarkScheme.canvas.luminance(),
            "纸面要浮在画布之上，否则所有卡片会糊成一片",
        )
    }

    /**
     * 浅色下 AccentInk 与 Accent 是同一个值，深色下必须分开。
     *
     * 分开的理由是数学上的：白字压在主色上要求主色足够暗，主色自己当字又要求它足够亮，
     * 深色方案里这两个区间不相交。若哪天有人把它们改回同一个值，这条断言会拦住。
     */
    @Test
    fun theInkFlavouredAccentIsOnlySplitWhenItHasToBe() {
        assertTrue(LightScheme.accentInk == LightScheme.accent, "浅色下两者本来就该是同一个颜色")
        assertTrue(
            DarkScheme.accentInk.luminance() > DarkScheme.accent.luminance(),
            "深色下当字用的主色必须比当底用的更亮",
        )
        assertTrue(
            contrast(DarkScheme.onAccent, DarkScheme.accent) >= 4.5f,
            "主按钮（深色）上的白字仍要读得出来",
        )
        assertTrue(
            contrast(DarkScheme.accent, DarkScheme.panel) < 4.5f,
            "若当底用的主色本身就能当字用，说明主色又合并回一个令牌了",
        )
    }

    /** 两套方案不能是同一份：至少表面与文字这几项都得不一样。 */
    @Test
    fun theTwoSchemesAreNotTheSamePalette() {
        val changed = listOf(
            LightScheme.canvas to DarkScheme.canvas,
            LightScheme.panel to DarkScheme.panel,
            LightScheme.panelMuted to DarkScheme.panelMuted,
            LightScheme.panelBorder to DarkScheme.panelBorder,
            LightScheme.ink to DarkScheme.ink,
            LightScheme.muted to DarkScheme.muted,
            LightScheme.correct to DarkScheme.correct,
        ).count { (a, b) -> a != b }
        assertTrue(changed == 7, "深色方案有 ${7 - changed} 项与浅色完全一样，像是没改")
    }

    // WCAG 2.1：相对亮度比 (L1 + 0.05) / (L2 + 0.05)，L1 是较亮的一方。
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun round2(v: Float): String = ((v * 100).toInt() / 100.0).toString()
}
