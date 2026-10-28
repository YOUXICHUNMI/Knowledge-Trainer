package morefish.knowledge.trainer.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 一套完整的颜色令牌。浅色与深色各一份，除此之外没有第三份。
 *
 * 为什么是数据类而不是在 [Palette] 里写两套 if：令牌必须**成套**存在。
 * 少一个字段编译就失败，而"深色模式下忘了定义某个颜色、结果它悄悄还是浅色的"
 * 是这类改动最容易留下的暗伤 —— 它只在某个二级页面才看得见。
 */
@Immutable
data class PaletteScheme(
    val canvas: Color,
    val canvasEdge: Color,
    val panel: Color,
    val panelMuted: Color,
    val panelBorder: Color,
    val panelBorderStrong: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentBright: Color,
    val accentInk: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val onAccent: Color,
    val correct: Color,
    val correctSoft: Color,
    val wrong: Color,
    val wrongSoft: Color,
    val pending: Color,
    val pendingSoft: Color,
    val neutral: Color,
    val neutralSoft: Color,
    val codeBg: Color,
    val shadowSoft: Color,
    val shadowLifted: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val heroInk: Color,
    /** 压在 Hero 那颗**白底**主按钮上的字色：白色按钮不随主题变，字也就不能跟着变。 */
    val heroActionInk: Color,
    val glowTop: Color,
    /** 学习日历四档热度（0 题 / 少量 / 中 / 多）。 */
    val heat: List<Color>,
    /** 题库卡片头像渐变，按 bankId 哈希取一组。 */
    val bankSeeds: List<Pair<Color, Color>>,
)

/**
 * 浅色："暖纸 + 墨"。
 *
 * 之前每屏各写 `Color(0xFF...)`，改一处配色要翻十个文件，也容易让"正确/错误"的语义色
 * 在不同页面出现色差 —— 判题结论的颜色必须全应用一致，否则用户会读错。
 */
val LightScheme = PaletteScheme(
    canvas = Color(0xFFF3F1EA),
    canvasEdge = Color(0xFFE8E5DA),
    panel = Color(0xFFFFFFFF),
    panelMuted = Color(0xFFFAF9F4),
    panelBorder = Color(0xFFE5E1D5),
    panelBorderStrong = Color(0xFFCFCABA),
    ink = Color(0xFF1A1915),
    muted = Color(0xFF6C695E),
    faint = Color(0xFF9B9689),
    accent = Color(0xFF4A3ECB),
    accentBright = Color(0xFF6F5BE8),
    // 浅色下"当字用的主色"就是主色本身：纸够亮，7.4:1 完全够读。
    accentInk = Color(0xFF4A3ECB),
    accentDeep = Color(0xFF322796),
    accentSoft = Color(0xFFEFEDFC),
    accentLine = Color(0xFFD9D4F7),
    onAccent = Color(0xFFFFFEFB),
    correct = Color(0xFF0B7B58),
    correctSoft = Color(0xFFE0F4EC),
    wrong = Color(0xFFBB441F),
    wrongSoft = Color(0xFFFBE9E2),
    pending = Color(0xFF94610E),
    pendingSoft = Color(0xFFFAEFD7),
    neutral = Color(0xFF6C695E),
    neutralSoft = Color(0xFFEFEDE4),
    codeBg = Color(0xFFF2F0E6),
    shadowSoft = Color(0x12000000),
    shadowLifted = Color(0x1E000000),
    heroStart = Color(0xFF3A2FB4),
    heroEnd = Color(0xFF6E5AE6),
    heroInk = Color(0xFFCFC6FF),
    heroActionInk = Color(0xFF322796),
    glowTop = Color(0xFFEDEAFB),
    heat = listOf(
        Color(0xFFEDEBE1),
        Color(0xFFDCD6F8),
        Color(0xFFBAB0EF),
        Color(0xFF8B7BE4),
    ),
    bankSeeds = listOf(
        Color(0xFF4A3ECB) to Color(0xFF8B7BE4),
        Color(0xFF0B7B58) to Color(0xFF4CB79A),
        Color(0xFFB4581F) to Color(0xFFE0A063),
        Color(0xFF8B2C6B) to Color(0xFFC973AC),
    ),
)

/**
 * 深色："墨夜 + 纸字" —— 把同一套纸墨关系倒过来，不是把浅色整体调暗。
 *
 * 两处必须与浅色反着来的地方：
 *
 * 1. **主色分成两个令牌**。`Accent` 是"当底色用"的主色（按钮、进度条），
 *    上面压白字；`AccentInk` 是"当字用"的主色（链接、图标、胶囊文字）。
 *    深色底上一个颜色不可能同时满足这两件事：白字要求它足够暗（相对亮度 ≤ 0.18），
 *    而它自己当文字时又要求足够亮（≥ 0.24），两个区间不相交。浅色下两者恰好同一个值，
 *    所以过去只有一个令牌。
 * 2. **语义色反过来变亮**。`Correct` 在浅色里是深绿，深色底上必须换成亮青绿，
 *    否则"答对了"那行字在黑底上读不出来；`CorrectSoft` 随之换成暗青绿，
 *    两者仍是"深底浅字"的同一种关系。
 *
 * 所有对比度都有测试钉着（`PaletteContrastTest`），不是凭眼睛调出来的。
 */
val DarkScheme = PaletteScheme(
    canvas = Color(0xFF17150F),
    canvasEdge = Color(0xFF201D16),
    panel = Color(0xFF1F1C16),
    panelMuted = Color(0xFF262218),
    panelBorder = Color(0xFF332E24),
    panelBorderStrong = Color(0xFF4A4436),
    ink = Color(0xFFF2EDE0),
    muted = Color(0xFFB3AB98),
    faint = Color(0xFF8A8271),
    accent = Color(0xFF5B4BD6),
    accentBright = Color(0xFF7A67F0),
    accentInk = Color(0xFFB3A6FF),
    accentDeep = Color(0xFFCFC6FF),
    accentSoft = Color(0xFF2A2447),
    accentLine = Color(0xFF3F3770),
    onAccent = Color(0xFFFFFDF7),
    correct = Color(0xFF52D2A4),
    correctSoft = Color(0xFF17332A),
    wrong = Color(0xFFFF9A78),
    wrongSoft = Color(0xFF3A211A),
    pending = Color(0xFFEFC070),
    pendingSoft = Color(0xFF35291A),
    neutral = Color(0xFFB3AB98),
    neutralSoft = Color(0xFF2C281F),
    codeBg = Color(0xFF2A2419),
    // 深色底上阴影几乎看不见，只能加深；它只在面与面之间做层次，不承担任何语义。
    shadowSoft = Color(0x66000000),
    shadowLifted = Color(0x8C000000),
    heroStart = Color(0xFF2E248F),
    heroEnd = Color(0xFF5748C8),
    heroInk = Color(0xFFCFC6FF),
    // 深色下这里**故意**还是深靛蓝：按钮底仍是白色，字跟着爱怎么变就怎么变会读不出来
    heroActionInk = Color(0xFF322796),
    glowTop = Color(0xFF1E1A38),
    heat = listOf(
        Color(0xFF2A2721),
        Color(0xFF3A3468),
        Color(0xFF524CA8),
        Color(0xFF7A6FE0),
    ),
    bankSeeds = listOf(
        Color(0xFF6A5CF0) to Color(0xFF9A8CF8),
        Color(0xFF2E9E7E) to Color(0xFF5FD3B2),
        Color(0xFFB4652F) to Color(0xFFE5A16A),
        Color(0xFF8E3A72) to Color(0xFFC97FB2),
    ),
)

/**
 * 设计令牌：颜色只在这里定义一次，界面一律读这里。
 *
 * [current] 是当前生效的那一套，由 [KtTheme] 在组合开始时写入。写全局而不是走
 * CompositionLocal，是因为有 800 多处读取点，其中不少落在 `Canvas` / `drawBehind`
 * 这类非 @Composable 的绘制闭包里 —— 换成组合期读取会把它们全部打碎。
 *
 * 代价是：**换主题时必须让整棵树重建**（`AppContent` 用 `key(dark)` 保证），
 * 否则没有订阅这个全局的子树不会重组，屏幕上会留下一半旧色。
 * 整树重建只在切主题的那一次发生，代价换来的是一处定义、处处自动跟随。
 */
object Palette {

    var current: PaletteScheme = LightScheme
        private set

    fun use(scheme: PaletteScheme) {
        current = scheme
    }

    // ---- 底层：画布 + 纸面 ----
    val Canvas: Color get() = current.canvas
    val CanvasEdge: Color get() = current.canvasEdge
    val Panel: Color get() = current.panel
    val PanelMuted: Color get() = current.panelMuted
    val PanelBorder: Color get() = current.panelBorder
    val PanelBorderStrong: Color get() = current.panelBorderStrong

    // ---- 文字：墨色三级 ----
    val Ink: Color get() = current.ink
    val Muted: Color get() = current.muted
    val Faint: Color get() = current.faint

    // ---- 主色 ----
    val Accent: Color get() = current.accent
    val AccentBright: Color get() = current.accentBright

    /** 当文字 / 图标用的主色。深色下比 [Accent] 亮，浅色下两者相同。 */
    val AccentInk: Color get() = current.accentInk
    val AccentDeep: Color get() = current.accentDeep
    val AccentSoft: Color get() = current.accentSoft
    val AccentLine: Color get() = current.accentLine
    val OnAccent: Color get() = current.onAccent

    // ---- 语义色：判定结论 ----
    val Correct: Color get() = current.correct
    val CorrectSoft: Color get() = current.correctSoft
    val Wrong: Color get() = current.wrong
    val WrongSoft: Color get() = current.wrongSoft
    val Pending: Color get() = current.pending
    val PendingSoft: Color get() = current.pendingSoft
    val Neutral: Color get() = current.neutral
    val NeutralSoft: Color get() = current.neutralSoft

    // ---- 组件细节 ----
    val CodeBg: Color get() = current.codeBg
    val ShadowSoft: Color get() = current.shadowSoft
    val ShadowLifted: Color get() = current.shadowLifted
    val HeroStart: Color get() = current.heroStart
    val HeroEnd: Color get() = current.heroEnd
    val HeroInk: Color get() = current.heroInk
    val HeroActionInk: Color get() = current.heroActionInk
    val GlowTop: Color get() = current.glowTop
    val Heat: List<Color> get() = current.heat
    val BankSeeds: List<Pair<Color, Color>> get() = current.bankSeeds
}

/** 主按钮 / 进度条的渐变。每次取用时按当前方案现算，不能做成只算一次的顶层 val。 */
val AccentGradient: Brush
    get() = Brush.linearGradient(listOf(Palette.Accent, Palette.AccentBright))

/** 首页 Hero 的深色渐变。深底上放白字，和整页纸感形成对比。 */
val HeroGradient: Brush
    get() = Brush.linearGradient(listOf(Palette.HeroStart, Palette.HeroEnd))
