package morefish.knowledge.trainer.domain.model

import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.i18n.LocaleTag

/**
 * 一个题库。
 *
 * 多语言是"一层覆盖物"：结构（题型/正确选项/判题策略）属于题目本身，
 * 文案按语言各有一份。源语言（[sourceLocale]）必须完整，其他语言允许部分覆盖 ——
 * 但覆盖到什么程度必须写在 [localeCoverage] 里，让人一眼看到"英文只翻了 6 题里的 2 题"，
 * 而不是抽题时才发现。
 */
data class QuestionBank(
    val bankId: String,
    val title: String,
    val sourceLocale: String,
    val questions: List<Question>,
    val importedAtMillis: Long,
    val sourceLabel: String,
    val contentRevision: Int = 1,
    val fingerprint: String = Fingerprint.ofQuestions(questions.map { it.contentFingerprint() }),
    /**
     * 从目录里读到的题型分布。为 null 表示"还没算过"，此时 [typeBreakdown] 现算。
     *
     * 有了它，首页画题库卡不必把十万道题从磁盘解析一遍 —— 分布是入库时算好写进目录的。
     */
    val typeCounts: Map<QuestionType, Int>? = null,
    /**
     * 各语言的覆盖度。为 null 表示旧数据（只有源语言），此时回落到 [sourceLocale] 一种。
     * 与 [typeCounts] 同样来自目录，所以读它是 O(语言数)，不碰题目文件。
     */
    val localeCoverage: List<LocaleCoverage>? = null,
    /**
     * 这个包自己声明的出处与整包摘要。
     *
     * 它是"关于这份题库的说明"，不是任何一道题的内容 —— 所以放在题库上而不是题目里，
     * 也因此不会因为改题而失效。[BankProvenance.digestVerified] 为 false 表示
     * 包没声明摘要（老包），界面必须写"未提供"而不是打勾。
     */
    val provenance: BankProvenance = BankProvenance(),
) {
    val questionCount: Int get() = questions.size

    /** 这个库能练哪几种语言。源语言永远在第一位：它不是"另一种可选语言"，而是原文。 */
    val availableLocales: List<String>
        get() {
            val declared = localeCoverage?.map { it.locale }.orEmpty()
            return (listOf(sourceLocale) + declared).distinct()
        }

    fun coverageOf(locale: String): LocaleCoverage {
        val want = LocaleTag.parse(locale)?.value ?: locale
        val total = questionCount
        return localeCoverage?.firstOrNull { LocaleTag.parse(it.locale)?.value == want }
            ?: LocaleCoverage(want, if (want == normalSource) total else 0, total)
    }

    private val normalSource: String get() = LocaleTag.parse(sourceLocale)?.value ?: sourceLocale

    /**
     * 按语言取题。缺译文的题按 [fallback] 处理：
     * 跳过（默认）或退回原文并标注原语言。
     *
     * 返回的题目内联文案已经被换成目标语言，判题会自然使用该语言的答案 ——
     * 这正是"不同语言的标准答案可以不同"能成立的原因。
     */
    fun questionsIn(locale: String, fallback: LocaleFallback = LocaleFallback.SKIP): List<Question> {
        val want = LocaleTag.parse(locale)?.value ?: normalSource
        if (want == normalSource) {
            return questions.map { it.localized(normalSource, normalSource) ?: it }
        }
        return questions.mapNotNull { question ->
            question.localized(want, sourceLocale)
                ?: if (fallback == LocaleFallback.ORIGINAL) {
                    question.localized(normalSource, normalSource)
                } else {
                    null
                }
        }
    }

    fun practicableQuestions(): List<Question> = questions.filter { it.isPracticable() }

    fun typeBreakdown(): Map<QuestionType, Int> =
        typeCounts ?: questions.groupingBy { it.type }.eachCount().toList().sortedBy { it.first.ordinal }.toMap()
}
