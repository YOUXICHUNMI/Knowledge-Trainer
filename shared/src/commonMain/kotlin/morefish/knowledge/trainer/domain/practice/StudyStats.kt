package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 单题的训练状态，完全由历史记录派生 —— 不额外维护一份会与历史失配的"题目状态"。
 */
data class QuestionStudyState(
    val key: QuestionKey,
    val attemptCount: Int,
    val gradedCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val lastGradedVerdict: Verdict?,
    val lastAttemptAtMillis: Long,
    val lastWrongAtMillis: Long?,
) {
    /** 有效作答：放弃不算练过，因为它既没产生判定，也不该把题从"未练习"里移走。 */
    val isPracticed: Boolean get() = gradedCount > 0

    val everWrong: Boolean get() = wrongCount > 0

    val isCurrentWrong: Boolean
        get() = lastGradedVerdict == Verdict.INCORRECT || lastGradedVerdict == Verdict.PARTIALLY_CORRECT

    val isConsolidated: Boolean
        get() = everWrong && lastGradedVerdict == Verdict.CORRECT
}

/**
 * 历史记录的聚合视图。一次遍历构建，之后 O(1) 查询，
 * 避免在抽题时为每道题重新扫描全部历史。
 */
class StudyStats private constructor(
    private val states: Map<QuestionKey, QuestionStudyState>,
) {
    fun of(bankId: String, questionId: String): QuestionStudyState? = states[QuestionKey(bankId, questionId)]

    fun of(key: QuestionKey): QuestionStudyState? = states[key]

    companion object {
        val Empty: StudyStats = StudyStats(emptyMap())

        fun derive(attempts: List<AttemptRecord>): StudyStats {
            if (attempts.isEmpty()) return Empty
            val grouped = attempts.groupBy { QuestionKey(it.bankId, it.questionId) }
            val states = grouped.mapValues { (key, records) ->
                val ordered = records.sortedBy { it.submittedAtMillis }
                val graded = ordered.filter { it.verdict != Verdict.ABANDONED }
                val wrong = graded.filter {
                    it.verdict == Verdict.INCORRECT || it.verdict == Verdict.PARTIALLY_CORRECT
                }
                QuestionStudyState(
                    key = key,
                    attemptCount = ordered.size,
                    gradedCount = graded.size,
                    correctCount = graded.count { it.verdict == Verdict.CORRECT },
                    wrongCount = wrong.size,
                    lastGradedVerdict = graded.lastOrNull()?.verdict,
                    lastAttemptAtMillis = ordered.last().submittedAtMillis,
                    lastWrongAtMillis = wrong.maxOfOrNull { it.submittedAtMillis },
                )
            }
            return StudyStats(states)
        }
    }
}
