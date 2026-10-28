package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 判题策略。刻意设计成"一道题 → 一个策略"的可注册单元，
 * 避免出现一个巨大 when 覆盖所有题型与模式的上帝类。
 */
interface EvaluationStrategy {
    val strategyId: String

    /** 该策略是否愿意处理这道题（题型 + 作者声明的 strategy 双重校验）。 */
    fun supports(question: Question): Boolean

    fun evaluate(question: Question, answer: AnswerInput): EvaluationResult
}

/**
 * 策略注册表。找不到匹配策略时返回 PENDING，
 * 这是"宁可待确认，不可默认判错"的执行点。
 */
class GradingRegistry(private val strategies: List<EvaluationStrategy>) {

    init {
        require(strategies.map { it.strategyId }.toSet().size == strategies.size) {
            "判题策略 ID 必须唯一：${strategies.map { it.strategyId }}"
        }
    }

    fun evaluate(question: Question, answer: AnswerInput): EvaluationResult {
        val strategy = strategies.firstOrNull {
            it.strategyId == question.grading.strategyId && it.supports(question)
        } ?: return EvaluationResult(
            verdict = Verdict.PENDING,
            strategyId = "unresolved",
            detail = EvaluationDetail.Pending(
                "没有可用的判题策略（声明为 ${question.grading.strategyId}，题型 ${question.type.label}）",
            ),
        )
        return strategy.evaluate(question, answer)
    }
}

/** 内置策略集合：全部为离线确定性规则，不依赖网络与 AI。 */
fun builtInGradingRegistry(): GradingRegistry = GradingRegistry(
    listOf(
        ChoiceEvaluator(),
        StrictTextEvaluator(),
        KeywordsEvaluator(),
        OrderingEvaluator(),
    ),
)
