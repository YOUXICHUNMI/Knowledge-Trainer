package morefish.knowledge.trainer.presentation.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import morefish.knowledge.trainer.application.usecase.PlanPracticeUseCase
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.i18n.pickLocale
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.isPracticable
import morefish.knowledge.trainer.domain.practice.PracticePlan
import morefish.knowledge.trainer.domain.practice.PracticeScope
import morefish.knowledge.trainer.domain.practice.QuestionKey
import morefish.knowledge.trainer.domain.practice.StudyStats
import morefish.knowledge.trainer.presentation.UI_LOCALE

/** 设置页按下"开始练习"的结果。抽不出题不是异常，是一个需要解释清楚的状态。 */
sealed interface SetupStart {

    /** 抽题成功。会话由组合根接过去保存与导航 —— 这里不碰磁盘、不改路由。 */
    data class Ready(val session: PracticeSession) : SetupStart

    /** 当前条件下没有可练的题。[reason] 是给用户看的原因，不是给日志看的错误码。 */
    data class Empty(val reason: String) : SetupStart
}

/**
 * 练习设置页的全部状态：抽题范围、题型、标签、题量、语言与缺译文策略。
 *
 * 为什么值得单独成类：这一页的判断全是**同一个问题的不同侧面** ——
 * "照现在这样点下去，会抽到哪些题"。所以它必须有一份**按语言算出来的**历史统计
 * （一道题在英文里答对过，不代表中文里也会），而这份统计的失效时机
 * 只有组合根知道（历史变了、题库换了）。把它留在一个三千行的类里，
 * "统计什么时候该重算"就会被埋在别处的两行 `setupStatsLocale = ""` 里。
 *
 * 三条边界：
 * - **不导航、不写盘、不弹横幅。** [start] 只交出会话或一句原因，
 *   谁保存会话、跳哪个页面由组合根决定；
 * - 只认 domain 与 application：抽题规则在 [PlanPracticeUseCase] 里，
 *   这里只是把用户的选择攒成一个 [PracticePlan]；
 * - 计数**不是每帧算的**：题型分布与各范围的候选数要遍历题目，只在
 *   [open] / [selectLocale] / [toggleFallback] 之后重算，其余交互只改选择。
 */
class PracticeSetupController(
    private val planPractice: PlanPracticeUseCase,
    private val banks: () -> List<QuestionBank>,
    private val history: () -> List<AttemptRecord>,
    private val favorites: () -> Set<QuestionKey>,
) {

    var bankId by mutableStateOf<String?>(null)
        private set

    var scope by mutableStateOf(PracticeScope.ALL)
        private set

    /** 空集 = 全部题型。选一个就等于"排除掉其他"，比再点一次"全部"直观。 */
    var types by mutableStateOf<Set<QuestionType>>(emptySet())
        private set

    var tags by mutableStateOf<Set<String>>(emptySet())
        private set

    /** null = 不限题量。 */
    var count by mutableStateOf<Int?>(10)
        private set

    var mixedTypes by mutableStateOf(false)
        private set

    var shuffleOptions by mutableStateOf(true)
        private set

    /**
     * 练习语言。空串表示"还没选"，打开设置页时会落到默认值
     * （界面语言与题库语言的交集，取不到就是题库的源语言）。
     */
    var locale by mutableStateOf("")
        private set

    /** 译文缺失时的处理方式。默认跳过 —— 把另一种语言当译文塞给用户比少练几题糟得多。 */
    var fallback by mutableStateOf(LocaleFallback.SKIP)
        private set

    /** 该语言下没有译文的题数。设置页必须说出来，否则"只能抽 2 题"看起来像坏了。 */
    var untranslated by mutableStateOf(0)
        private set

    /**
     * 按当前语言过滤后的历史统计。
     *
     * 为什么不用全局统计：一道题在英文里答对过，不代表中文里也会。
     * 抽题范围（错题 / 未练 / 已巩固）必须以"这门语言的作答记录"为准。
     */
    private var stats by mutableStateOf(StudyStats.derive(emptyList()))
    private var statsLocale by mutableStateOf("")

    /** 打开设置页时算一次：遍历题目是 O(题目数)，不能每一帧重算。 */
    private var typeCounts by mutableStateOf<Map<QuestionType, Int>>(emptyMap())
    private var scopeCounts by mutableStateOf<Map<PracticeScope, Int>>(emptyMap())

    val bank: QuestionBank? get() = banks().firstOrNull { it.bankId == bankId }

    /** 设置页/练习页展示用：这个题库有哪几种语言可选。 */
    val locales: List<String> get() = bank?.availableLocales.orEmpty()

    val coverage: LocaleCoverage?
        get() = bank?.let { it.coverageOf(locale.ifBlank { it.sourceLocale }) }

    val plan: PracticePlan
        get() = PracticePlan(
            scope = scope,
            types = types,
            tags = tags,
            questionCount = count,
            mixedTypes = mixedTypes,
            shuffleOptions = shuffleOptions,
        )

    /** 设置页实时显示"会抽到多少题"，与真正抽题共用同一套筛选逻辑。 */
    val candidateCount: Int
        get() = bank?.let { bank ->
            planPractice.candidateCount(
                bank = bank,
                plan = plan,
                stats = stats,
                favorites = favorites(),
                locale = locale.ifBlank { bank.sourceLocale },
                fallback = fallback,
            )
        } ?: 0

    val tagPool: List<String>
        get() = bank?.questions.orEmpty().flatMap { it.tags }.distinct().sorted()

    fun typeCount(type: QuestionType): Int = typeCounts[type] ?: 0

    fun scopeCount(scope: PracticeScope): Int = scopeCounts[scope] ?: 0

    /**
     * 默认练习语言：界面语言能对上就用它，对不上用源语言。
     *
     * 界面上写的是中文，却默认用英文抽题，是最容易让人以为"题库坏了"的做法。
     */
    fun defaultLocale(bank: QuestionBank): String =
        pickLocale(UI_LOCALE, bank.availableLocales)?.tag?.value ?: bank.sourceLocale

    // ---------------------------------------------------------------- 交互

    /** 打开某个题库的设置页：所有选择回到默认值，然后按新语言重算一遍计数。 */
    fun open(bankId: String) {
        this.bankId = bankId
        scope = PracticeScope.ALL
        types = emptySet()
        tags = emptySet()
        count = 10
        mixedTypes = false
        shuffleOptions = true
        val target = banks().firstOrNull { it.bankId == bankId }
        locale = target?.let { defaultLocale(it) }.orEmpty()
        fallback = LocaleFallback.SKIP
        recompute()
    }

    fun selectLocale(locale: String) {
        if (locale == this.locale) return
        this.locale = locale
        // 换语言等于换一套作答历史：统计与候选必须一起重算
        types = emptySet()
        tags = emptySet()
        recompute()
    }

    fun toggleFallback() {
        fallback = if (fallback == LocaleFallback.SKIP) LocaleFallback.ORIGINAL else LocaleFallback.SKIP
        recompute()
    }

    fun selectScope(scope: PracticeScope) {
        this.scope = scope
    }

    fun toggleType(type: QuestionType) {
        types = if (type in types) types - type else types + type
    }

    fun toggleTag(tag: String) {
        tags = if (tag in tags) tags - tag else tags + tag
    }

    fun selectCount(count: Int?) {
        this.count = count
    }

    fun toggleMixedTypes() {
        mixedTypes = !mixedTypes
    }

    fun toggleShuffleOptions() {
        shuffleOptions = !shuffleOptions
    }

    // ---------------------------------------------------------------- 开工

    /**
     * 按当前选择抽题。
     *
     * 题量会按候选数**收敛**：不然会话上会写着"20 题"却只有 6 题，
     * 事后看历史对不上号。收敛只影响这一次抽题，不改用户选的值。
     */
    fun start(): SetupStart {
        val bank = bank ?: return SetupStart.Empty("题库不见了，请回首页刷新后再试")
        val plan = plan
        val target = locale.ifBlank { bank.sourceLocale }
        val candidates = planPractice.candidateCount(bank, plan, stats, favorites(), target, fallback)
        if (candidates == 0) {
            return SetupStart.Empty(if (untranslated > 0 && fallback == LocaleFallback.SKIP) {
                // 说清真正的原因：不是没题，是这门语言没译文
                "该语言下有 $untranslated 题没有译文，当前范围里已无题可练；可切换语言或改用「缺译文时看原文」"
            } else {
                "当前范围内没有可练习的题目，请放宽范围、题型或标签"
            })
        }
        val effective = plan.questionCount?.takeIf { it > candidates }
            ?.let { plan.copy(questionCount = candidates) }
            ?: plan
        val created = planPractice.build(bank, effective, stats, favorites(), locale = target, fallback = fallback)
            ?: return SetupStart.Empty("当前范围内没有可练习的题目，请放宽范围、题型或标签")
        return SetupStart.Ready(created)
    }

    /**
     * 题库卡片上的快捷入口：全部范围、固定题量、默认语言。
     *
     * 走自己的 [defaultLocale] 而不是借用设置页当前的选择：用户可能刚在另一个题库里
     * 选了英文，快捷开始不该把那个选择带过来。
     */
    fun quickSession(bank: QuestionBank, questionCount: Int?): PracticeSession? {
        val target = defaultLocale(bank)
        val plan = PracticePlan(scope = PracticeScope.ALL, questionCount = questionCount)
        return planPractice.build(
            bank = bank,
            plan = plan,
            stats = StudyStats.derive(attemptsInLocale(bank, target)),
            favorites = favorites(),
            locale = target,
            fallback = LocaleFallback.SKIP,
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 历史变了（新作答、复核改判、恢复备份）之后调：下一次 [recompute] 才会重算统计。 */
    fun invalidateStats() {
        statsLocale = ""
    }

    /**
     * 重算设置页的计数：题型分布、范围候选、缺译文题数。
     *
     * 全部按当前语言重来，而不是拿全库数糊上去 —— 否则会写着"单选 6 题"，
     * 真抽的时候因为没译文只剩 2 题。
     */
    private fun recompute() {
        val bank = bank ?: return
        val locale = locale.ifBlank { bank.sourceLocale }
        if (statsLocale != locale) {
            stats = StudyStats.derive(attemptsInLocale(bank, locale))
            statsLocale = locale
        }
        val inLocale = bank.questionsIn(locale, LocaleFallback.SKIP)
        untranslated = (bank.questionCount - inLocale.size).coerceAtLeast(0)
        val practicable = bank.questionsIn(locale, fallback).filter { it.isPracticable() }
        typeCounts = QuestionType.entries.associateWith { type -> practicable.count { it.type == type } }
        scopeCounts = PracticeScope.entries.associateWith { scope ->
            planPractice.candidateCount(
                bank = bank,
                plan = PracticePlan(scope = scope),
                stats = stats,
                favorites = favorites(),
                locale = locale,
                fallback = fallback,
            )
        }
    }

    /** 某题库在某语言下的作答历史。旧记录（无语言）算进源语言。 */
    private fun attemptsInLocale(bank: QuestionBank, locale: String): List<AttemptRecord> {
        val want = LocaleTag.parse(locale)?.value ?: locale
        val source = LocaleTag.parse(bank.sourceLocale)?.value ?: bank.sourceLocale
        return history().filter { record ->
            record.bankId == bank.bankId &&
                (LocaleTag.parse(record.locale)?.value ?: source) == want
        }
    }
}
