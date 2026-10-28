package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 死记硬背判题：与作者标准答案逐字符完全一致（含标点、空格、换行、顺序）。
 *
 * 不做任何归一化 —— 一旦归一化，用户就无法通过本模式验证"准确默写"。
 * 差异提示只能作为解释，不能改变判定结果。
 */
class StrictTextEvaluator : EvaluationStrategy {

    override val strategyId: String = GradingSpec.StrictText.strategyId

    override fun supports(question: Question): Boolean =
        question.type == QuestionType.SHORT_ANSWER &&
            question.grading.strategyId == strategyId

    override fun evaluate(question: Question, answer: AnswerInput): EvaluationResult {
        if (answer !is AnswerInput.Text) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("作答输入不是文本，无法按原文比对"),
            )
        }
        val expected = question.answerText
        if (expected.isNullOrBlank()) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("题目缺少标准答案，不能判题"),
            )
        }

        val actual = answer.text
        val verdict = if (expected == actual) Verdict.CORRECT else Verdict.INCORRECT
        val detail = EvaluationDetail.TextDiff(
            expected = expected,
            actual = actual,
            segments = TextDiffCalculator.diff(expected, actual),
            notes = if (verdict == Verdict.CORRECT) emptyList() else TextDiffCalculator.notes(expected, actual),
        )
        return EvaluationResult(verdict, strategyId, detail)
    }
}
