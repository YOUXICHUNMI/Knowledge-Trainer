package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 默知识点判题：按作者声明的关键词规则离线判定。
 *
 * 规则语义：
 * - required 全部出现 → CORRECT；部分出现 → PARTIALLY_CORRECT；
 * - 出现任一 forbidden → INCORRECT（禁止词优先，覆盖 required 的命中）；
 * - required 与 forbidden 都为空 → PENDING：没有可判依据时不得默认判错。
 *
 * 关键词匹配为大小写不敏感的子串包含；不做同义、词形还原等任何"聪明"处理 ——
 * 那是语义层面的判断，本策略刻意保持确定性，以便结果可审计、可复现。
 */
class KeywordsEvaluator : EvaluationStrategy {

    // 与 GradingSpec.Keywords.wireName 保持一致（Keywords 是带参类型，不能静态引用其属性）
    override val strategyId: String = "keywords"

    override fun supports(question: Question): Boolean =
        question.type == QuestionType.SHORT_ANSWER && question.grading is GradingSpec.Keywords

    override fun evaluate(question: Question, answer: AnswerInput): EvaluationResult {
        val spec = question.grading as? GradingSpec.Keywords
            ?: return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("题目未声明关键词规则，无法按默知识点判题"),
            )
        if (!spec.hasRules) {
            return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("作者没有提供关键词规则（required/forbidden 均为空），无法自动判定"),
            )
        }
        val text = (answer as? AnswerInput.Text)?.text
            ?: return EvaluationResult(
                Verdict.PENDING,
                strategyId,
                EvaluationDetail.Pending("作答输入不是文本，无法按关键词判题"),
            )

        val lowered = text.lowercase()
        fun hit(keyword: String) = lowered.contains(keyword.lowercase())

        val forbiddenHit = spec.forbidden.filter { hit(it) }
        if (forbiddenHit.isNotEmpty()) {
            return EvaluationResult(
                Verdict.INCORRECT,
                strategyId,
                EvaluationDetail.Keywords(
                    present = spec.required.filter { hit(it) },
                    missing = spec.required.filterNot { hit(it) },
                    forbiddenHit = forbiddenHit,
                ),
            )
        }

        val missing = spec.required.filterNot { hit(it) }
        val verdict = when {
            spec.required.isEmpty() -> Verdict.CORRECT // 只有禁止词且未命中：判对
            missing.isEmpty() -> Verdict.CORRECT
            missing.size == spec.required.size -> Verdict.INCORRECT
            else -> Verdict.PARTIALLY_CORRECT
        }
        return EvaluationResult(
            verdict,
            strategyId,
            EvaluationDetail.Keywords(
                present = spec.required.filter { hit(it) },
                missing = missing,
                forbiddenHit = emptyList(),
            ),
        )
    }
}
