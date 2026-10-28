package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 选择题判题：按 optionId 集合比较，与显示顺序无关。
 *
 * 默认采用"完全匹配"：多选少选或多选都算错，但必须区分
 * 漏选（missing）与误选（extra），供错题本与复习建议使用。
 * 部分得分策略（作者显式声明）留待 P4，此处不静默放宽。
 */
class ChoiceEvaluator : EvaluationStrategy {

    override val strategyId: String = GradingSpec.ExactSet.strategyId

    override fun supports(question: Question): Boolean =
        question.type.isChoice && question.grading.strategyId == strategyId

    override fun evaluate(question: Question, answer: AnswerInput): EvaluationResult {
        if (answer !is AnswerInput.Choices) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("作答输入不是选项集合，无法用集合规则判定"),
            )
        }

        val expected = question.correctOptionIds
        if (expected.isEmpty()) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("题目没有正确答案，不能判题"),
            )
        }

        val selected = answer.selectedOptionIds
        val missing = expected - selected
        val extra = selected - expected
        val detail = EvaluationDetail.Choice(expected, selected, missing, extra)

        val verdict = when {
            selected.isEmpty() -> Verdict.INCORRECT
            missing.isEmpty() && extra.isEmpty() -> Verdict.CORRECT
            else -> Verdict.INCORRECT
        }
        return EvaluationResult(verdict, strategyId, detail)
    }
}
