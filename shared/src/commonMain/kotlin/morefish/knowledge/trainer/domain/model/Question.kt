package morefish.knowledge.trainer.domain.model

import morefish.knowledge.trainer.domain.i18n.LocaleTag

/**
 * 原生题型。只描述"这道题本质上是什么"，不描述用户如何作答，
 * 也不描述如何判题 —— 后两者是 [AnswerInput] 与 [GradingSpec] 的职责。
 */
enum class QuestionType(val wireName: String, val label: String) {
    SINGLE_CHOICE("single_choice", "单选"),
    MULTIPLE_CHOICE("multiple_choice", "多选"),
    TRUE_FALSE("true_false", "判断"),
    SHORT_ANSWER("short_answer", "简答"),
    ORDERING("ordering", "重组");

    val isChoice: Boolean get() = this != SHORT_ANSWER

    companion object {
        fun fromWire(value: String): QuestionType? =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}

/** 一个可被引用的选项。optionId 是稳定身份，A/B/C/D 只是当前显示顺序。 */
data class QuestionOption(
    val optionId: String,
    val contentMarkdown: String,
)

/** 判题策略声明。作者可以显式声明，也可以在导入时按题型推导默认值。 */
sealed interface GradingSpec {
    val strategyId: String
    val wireName: String

    /** 集合完全匹配（单选恰好一个、多选顺序无关）。 */
    data object ExactSet : GradingSpec {
        override val strategyId: String = "exact-set"
        override val wireName: String = "exact-set"
    }

    /** 死记硬背：与 answerText 原始字符完全一致。 */
    data object StrictText : GradingSpec {
        override val strategyId: String = "strict-text"
        override val wireName: String = "strict-text"
    }

    /**
     * 默知识点：开放作答按关键词规则判题。
     * required 全命中 → CORRECT；部分命中 → PARTIALLY_CORRECT；
     * 命中任一 forbidden → INCORRECT；规则为空 → PENDING（无可靠依据不默认判错）。
     */
    data class Keywords(
        val required: List<String> = emptyList(),
        val forbidden: List<String> = emptyList(),
    ) : GradingSpec {
        override val strategyId: String = "keywords"
        override val wireName: String = "keywords"
        val hasRules: Boolean get() = required.isNotEmpty() || forbidden.isNotEmpty()
    }

    /**
     * 语义重组：把正确片段按认可次序排列（片段 = 选项，干扰项 = 不在正确集合里的选项）。
     * [canonical] 为标准次序；为空时按选项声明顺序推导。
     * [alternatives] 是作者声明的逻辑等价次序，判对时一并认可。
     */
    data class Ordering(
        val canonical: List<String> = emptyList(),
        val alternatives: List<List<String>> = emptyList(),
    ) : GradingSpec {
        override val strategyId: String = "ordering"
        override val wireName: String = "ordering"
    }

    companion object {
        fun fromWire(value: String?): GradingSpec? = when (value?.trim()?.lowercase()) {
            null, "" -> null
            "exact-set", "exact_set" -> ExactSet
            "strict-text", "strict_text" -> StrictText
            "keywords" -> Keywords()
            "ordering" -> Ordering()
            else -> null
        }
    }
}

/**
 * 一道题在**某一种语言**下的文案。
 *
 * 结构（题型、正确选项、判题策略）不属于任何语言，只属于题目本身；
 * 这里只装"会被翻译的东西"。选项文案按 `optionId` 索引，因为显示顺序是会话的事，
 * 身份不随语言变 —— 翻译不能让 A/B 顺序漂移。
 */
data class QuestionText(
    val locale: String,
    val promptMarkdown: String,
    val optionTexts: Map<String, String> = emptyMap(),
    val answerText: String? = null,
    val explanationMarkdown: String? = null,
    /** 该语言自己的内容版本。翻译改过就是新版本，判题硬依赖要据此复核。 */
    val contentRevision: Int = 1,
)

data class Question(
    val questionId: String,
    val type: QuestionType,
    val promptMarkdown: String,
    val options: List<QuestionOption> = emptyList(),
    val correctOptionIds: Set<String> = emptySet(),
    val answerText: String? = null,
    val explanationMarkdown: String? = null,
    /** 包内媒体资源的引用（KT-QBF assetId）。字节不在题目里，由 [morefish.knowledge.trainer.application.port.MediaStore] 按 bankId + assetId 提供。 */
    val assetIds: List<String> = emptyList(),
    val shuffleAllowed: Boolean = true,
    val grading: GradingSpec = Question.defaultGradingFor(type),
    val tags: List<String> = emptyList(),
    val contentRevision: Int = 1,
    /**
     * 上面这些内联文案是哪种语言。null 表示未声明（旧数据 / 手工新建）。
     * 只有在"这道题已经被翻译成某个语言"之后才会被填上，不是必填。
     */
    val locale: String? = null,
    /**
     * 除当前内联文案之外的译文本，键是语言标签。
     *
     * 内联字段永远保存**源语言**原文：这样所有既有代码（判题、编辑器、快照）
     * 不需要理解多语言也能正确工作，翻译只是"多出来的一份替换文本"。
     */
    val translations: Map<String, QuestionText> = emptyMap(),
    /**
     * 这道题怎么来的。**不参与 [contentFingerprint]**：出处是"关于这道题的说明"，
     * 不是"这道题是什么"。作者补上一个漏写的出处不该让补丁的逐题锚点失效。
     */
    val origin: ProvenanceOrigin = ProvenanceOrigin.UNSPECIFIED,
    /** 引用题库 `sources` 里的哪一条。null = 没声明出处。 */
    val sourceId: String? = null,
    /** 在来源里的位置（章节 / 页码 / 题号）。有 sourceId 却没写它，读者没法核对。 */
    val sourceLocator: String? = null,
) {
    fun option(optionId: String): QuestionOption? = options.firstOrNull { it.optionId == optionId }

    /** 有没有这种语言的题面。判断只看语言，不猜"差不多算有"。 */
    fun hasTextIn(locale: String): Boolean {
        val want = LocaleTag.parse(locale) ?: return false
        if (this.locale != null &&
            LocaleTag.parse(this.locale) == want
        ) {
            return true
        }
        return translations.keys.any {
            LocaleTag.parse(it) == want
        }
    }

    /**
     * 换成本语言的题面。没有译文返回 null —— 跳过还是回退原文由调用方决定。
     *
     * 译文缺任何一处（题干、某个选项、简答的标准答案）都算"没有这一份"，
     * 不做逐字段回退：一半中文一半英文的题面，读起来比整题原文更糟。
     */
    fun localized(locale: String, sourceLocale: String): Question? {
        val want = LocaleTag.parse(locale) ?: return null
        // 已经是某语言的副本（会话里存的就是），只能同语言取用
        if (this.locale != null) {
            val current = LocaleTag.parse(this.locale) ?: return null
            return if (current == want) copy(translations = emptyMap()) else null
        }

        val source = LocaleTag.parse(sourceLocale)
        if (source != null && source == want) {
            return copy(locale = want.value, translations = emptyMap())
        }

        val text = translations.entries
            .firstOrNull { LocaleTag.parse(it.key) == want }
            ?.value
            ?: return null

        if (type == QuestionType.SHORT_ANSWER && text.answerText.isNullOrEmpty()) return null
        val translatedOptions = options.map { option ->
            val translated = text.optionTexts[option.optionId] ?: return null
            option.copy(contentMarkdown = translated)
        }
        return copy(
            promptMarkdown = text.promptMarkdown,
            options = translatedOptions,
            answerText = text.answerText ?: answerText,
            explanationMarkdown = text.explanationMarkdown,
            contentRevision = text.contentRevision,
            locale = want.value,
            translations = emptyMap(),
        )
    }

    /** 稳定内容指纹：题干 + 正确答案 + 选项内容，不含显示顺序。 */
    fun contentFingerprint(): String = Fingerprint.of(
        buildString {
            append(type.wireName).append('\u0001').append(promptMarkdown)
            append('\u0001').append(correctOptionIds.sorted().joinToString(","))
            append('\u0001').append(answerText ?: "")
            options.sortedBy { it.optionId }.forEach {
                append('\u0001').append(it.optionId).append('=').append(it.contentMarkdown)
            }
        }
    )

    companion object {
        fun defaultGradingFor(type: QuestionType): GradingSpec = when (type) {
            QuestionType.SHORT_ANSWER -> GradingSpec.StrictText
            QuestionType.ORDERING -> GradingSpec.Ordering()
            else -> GradingSpec.ExactSet
        }
    }
}

enum class Severity { ERROR, WARNING }

/** 结构化校验问题。导入阶段必须把它们展示给用户，而不是静默丢弃题目。 */
data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val questionId: String? = null,
)

/**
 * 题目自检。返回的 ERROR 意味着这道题无法进入可判题状态，
 * WARNING 意味着可以练但存在疑问（例如没有解析、答案可能不完整）。
 */
fun Question.validate(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    fun error(code: String, msg: String) = issues.add(ValidationIssue(Severity.ERROR, code, msg, questionId))
    fun warn(code: String, msg: String) = issues.add(ValidationIssue(Severity.WARNING, code, msg, questionId))

    if (promptMarkdown.isBlank()) error("prompt-blank", "题干为空")
    if (contentRevision < 1) error("revision-invalid", "contentRevision 必须 >= 1")
    if (options.map { it.optionId }.toSet().size != options.size) error("option-id-duplicate", "选项 ID 重复")
    options.filter { it.contentMarkdown.isBlank() }.forEach {
        error("option-blank", "选项 ${it.optionId} 内容为空")
    }

    when (type) {
        QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE -> {
            if (options.size < 2) error("options-too-few", "${type.label} 至少需要 2 个选项")
            if (correctOptionIds.size != 1) error("correct-count", "${type.label} 必须恰好 1 个正确选项")
        }
        QuestionType.MULTIPLE_CHOICE -> {
            if (options.size < 2) error("options-too-few", "多选至少需要 2 个选项")
            if (correctOptionIds.isEmpty()) error("correct-missing", "多选缺少正确选项")
        }
        QuestionType.SHORT_ANSWER -> {
            if (answerText.isNullOrBlank()) error("answer-missing", "简答题缺少标准答案")
        }
        QuestionType.ORDERING -> {
            if (options.size < 2) error("options-too-few", "重组题至少需要 2 个片段")
            if (correctOptionIds.isEmpty()) error("correct-missing", "重组题缺少正确片段")
            val grading = grading as? GradingSpec.Ordering
            if (grading != null && grading.canonical.isNotEmpty()) {
                if (grading.canonical.toSet() != correctOptionIds) {
                    error("canonical-mismatch", "标准次序与正确片段集合不一致")
                }
                grading.alternatives.forEachIndexed { i, alt ->
                    if (alt.toSet() != correctOptionIds) {
                        error("alternative-mismatch", "等价次序 ${i + 1} 与正确片段集合不一致")
                    }
                }
            }
        }
    }

    val optionIds = options.map { it.optionId }.toSet()
    val unknown = correctOptionIds - optionIds
    if (unknown.isNotEmpty()) error("correct-unknown", "正确答案引用了不存在的选项：${unknown.joinToString()}")

    if (type != QuestionType.SHORT_ANSWER && correctOptionIds.size == options.size && options.size > 2) {
        warn("all-options-correct", "所有选项都是正确答案，可能是导入映射出错")
    }
    if (explanationMarkdown.isNullOrBlank()) warn("no-explanation", "缺少解析")
    return issues
}

/** 题目是否达到"可离线判题"标准。 */
fun Question.isPracticable(): Boolean = validate().none { it.severity == Severity.ERROR }
