package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 语义重组判题：片段集合完全一致，且次序等于标准次序或任一作者声明的等价次序。
 *
 * 标准次序的推导：优先用作者显式声明的 canonical；
 * 未声明时按选项在题目里的声明顺序中"正确片段"的相对顺序推导 —— 这是确定性的，
 * 不依赖选项显示时的随机排列。
 *
 * 片段缺失/多选 → INCORRECT（说明漏了什么、多了什么）；
 * 片段一致但次序不符 → INCORRECT（注明"片段正确，次序不符"）。
 */
class OrderingEvaluator : EvaluationStrategy {

    // 与 GradingSpec.Ordering.wireName 保持一致（Ordering 是带参类型，不能静态引用其属性）
    override val strategyId: String = "ordering"

    override fun supports(question: Question): Boolean =
        question.type == QuestionType.ORDERING && question.grading is GradingSpec.Ordering

    override fun evaluate(question: Question, answer: AnswerInput): EvaluationResult {
        val spec = question.grading as? GradingSpec.Ordering
            ?: return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("题目未声明重组判题规则"),
            )
        val actual = (answer as? AnswerInput.Ordering)?.orderedOptionIds
            ?: return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("作答输入不是片段次序，无法按重组判题"),
            )

        val canonical = spec.canonical.ifEmpty {
            question.options.map { it.optionId }.filter { it in question.correctOptionIds }
        }
        if (canonical.isEmpty()) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("题目没有可用的标准次序，无法判题"),
            )
        }

        val actualSet = actual.toSet()
        val correctSet = canonical.toSet()
        if (actualSet != correctSet) {
            return EvaluationResult(
                Verdict.INCORRECT,
                strategyId,
                EvaluationDetail.Ordering(
                    expected = canonical,
                    actual = actual,
                    missing = (correctSet - actualSet).sorted(),
                    extra = (actualSet - correctSet).sorted(),
                    sameSet = false,
                ),
            )
        }

        val acceptedOrders = listOf(canonical) + spec.alternatives
        val verdict = if (acceptedOrders.any { it == actual }) Verdict.CORRECT else Verdict.INCORRECT
        return EvaluationResult(
            verdict,
            strategyId,
            EvaluationDetail.Ordering(
                expected = canonical,
                actual = actual,
                missing = emptyList(),
                extra = emptyList(),
                sameSet = verdict != Verdict.CORRECT,
            ),
        )
    }
}
