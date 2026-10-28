package morefish.knowledge.trainer.domain.model

/**
 * 判定结论。PENDING 是一等公民：无可靠依据时必须停在"待确认"，
 * 而不是默认判错（默认判错会污染历史与错题本）。
 */
enum class Verdict(val label: String, val countsAsGraded: Boolean) {
    CORRECT("正确", true),
    PARTIALLY_CORRECT("部分正确", true),
    INCORRECT("错误", true),
    PENDING("待确认", false),
    ABANDONED("放弃", false),
}

enum class SegmentKind { SAME, INSERTED, DELETED }

data class DiffSegment(val text: String, val kind: SegmentKind)

sealed interface EvaluationDetail {
    data class Choice(
        val expected: Set<String>,
        val selected: Set<String>,
        val missing: Set<String>,
        val extra: Set<String>,
    ) : EvaluationDetail

    data class TextDiff(
        val expected: String,
        val actual: String,
        val segments: List<DiffSegment>,
        /** 人类可读的差异定位说明，例如"仅空白字符不同"。 */
        val notes: List<String>,
    ) : EvaluationDetail

    /** 默知识点关键词命中情况。present/missing/forbiddenHit 均为题目声明的关键词原文。 */
    data class Keywords(
        val present: List<String>,
        val missing: List<String>,
        val forbiddenHit: List<String>,
    ) : EvaluationDetail

    /** 语义重组的次序核对。expected/actual 为 optionId 次序。 */
    data class Ordering(
        val expected: List<String>,
        val actual: List<String>,
        val missing: List<String>,
        val extra: List<String>,
        /** 片段集合相同、仅次序不同。 */
        val sameSet: Boolean,
    ) : EvaluationDetail

    data class Pending(val reason: String) : EvaluationDetail
}

data class EvaluationResult(
    val verdict: Verdict,
    val strategyId: String,
    val detail: EvaluationDetail,
)
