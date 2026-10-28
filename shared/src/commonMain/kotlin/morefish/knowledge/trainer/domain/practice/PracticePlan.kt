package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.model.QuestionType

/**
 * 题目身份。题库 ID + 题目 ID 才是全局唯一身份：
 * 不同题库里出现同名字符串的 questionId 并不代表同一道题。
 */
data class QuestionKey(
    val bankId: String,
    val questionId: String,
) {
    /** 落盘与收藏使用的稳定写法。bankId 为 UUID，不会包含竖线。 */
    val wire: String get() = "$bankId|$questionId"

    companion object {
        fun parse(wire: String): QuestionKey? {
            val index = wire.indexOf('|')
            if (index <= 0 || index == wire.lastIndex) return null
            return QuestionKey(wire.substring(0, index), wire.substring(index + 1))
        }
    }
}

/**
 * 练习范围。范围只决定"从题库里挑哪些题"，
 * 不参与判题，也不会改变历史记录的含义。
 */
enum class PracticeScope(val wireName: String, val label: String, val hint: String) {
    ALL("all", "全部", "题库内所有可判题的题目"),
    UNPRACTICED("unpracticed", "未练习", "没有任何有效作答记录（放弃不算练过）"),
    CURRENT_WRONG("current_wrong", "当前错题", "最近一次判定为错误或部分正确"),
    HISTORY_WRONG("history_wrong", "历史错题", "曾经答错，包含后来已答对的题"),
    CONSOLIDATED("consolidated", "已巩固", "曾答错，且最近一次已答对"),
    FAVORITES("favorites", "收藏", "你手动标记为需要复习的题目"),
    ;

    companion object {
        fun fromWire(value: String): PracticeScope =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) } ?: ALL
    }
}

/**
 * 一次正式练习的抽取条件。
 *
 * [mixedTypes] 为 true 时按题型分层轮转抽取，避免"10 道题里 9 道单选"；
 * 为 false 时在候选集内均匀随机。
 */
data class PracticePlan(
    val scope: PracticeScope = PracticeScope.ALL,
    val types: Set<QuestionType> = emptySet(),
    val tags: Set<String> = emptySet(),
    val questionCount: Int? = null,
    val shuffleQuestions: Boolean = true,
    val shuffleOptions: Boolean = true,
    val mixedTypes: Boolean = false,
) {
    fun matchesType(type: QuestionType): Boolean = types.isEmpty() || type in types

    fun matchesTags(questionTags: List<String>): Boolean =
        tags.isEmpty() || questionTags.any { it in tags }

    /** 会话里保存的人类可读描述，恢复时直接展示，不重新推导条件。 */
    fun describe(): String = buildString {
        append(scope.label)
        if (types.isNotEmpty()) {
            append(" · ")
            append(QuestionType.entries.filter { it in types }.joinToString("/") { it.label })
        }
        if (tags.isNotEmpty()) {
            append(" · 标签 ").append(tags.joinToString("/"))
        }
        append(" · ")
        append(questionCount?.let { "$it 题" } ?: "全部")
        if (mixedTypes) append(" · 混合题型")
        if (!shuffleOptions) append(" · 选项固定顺序")
    }
}
