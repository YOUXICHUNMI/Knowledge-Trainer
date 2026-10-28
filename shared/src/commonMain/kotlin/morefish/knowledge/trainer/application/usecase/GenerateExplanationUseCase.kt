package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AiCallResult
import morefish.knowledge.trainer.application.port.AiExplainProvider
import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.application.port.AiRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.ai.AiTextCheck
import morefish.knowledge.trainer.domain.ai.AiUsageEntry
import morefish.knowledge.trainer.domain.ai.BudgetDecision
import morefish.knowledge.trainer.domain.ai.checkExplanationText
import morefish.knowledge.trainer.domain.ai.normalizeExplanation
import morefish.knowledge.trainer.domain.practice.LocalDates

sealed interface AiGenerationOutcome {
    /** 有内容可展示。[fromCache] 为真表示这次没发请求、也没花钱。 */
    data class Ready(val record: AiExplanation, val fromCache: Boolean) : AiGenerationOutcome

    /** 没法发请求（平台不支持 / 没配置 / 没同意上传）。**一次请求都没发出去。** */
    data class Unavailable(val reason: String) : AiGenerationOutcome

    /** 被预算拦住。同样一次请求都没发出去。 */
    data class Blocked(val reason: String) : AiGenerationOutcome

    /** 发出去了，失败了。失败记录已落盘，重试要用户自己点。 */
    data class Failed(val reason: String, val retryable: Boolean) : AiGenerationOutcome
}

/**
 * 生成一条题目讲解。
 *
 * 顺序是有意的，每一步都在"花用户的钱"之前：
 * 平台支持 → 配置完整 → 用户同意上传 → 命中缓存 → 预算允许 → 才发请求。
 * 任何一步不过就返回中文原因，且**不产生任何请求**；失败也不自动重试，
 * 因为自动重试在计费接口上等于"帮用户多花一次钱"。
 */
class GenerateExplanationUseCase(
    private val repo: AiRepository,
    private val provider: AiExplainProvider?,
    private val clock: WallClock,
) {

    suspend fun generate(input: AiPromptInput, force: Boolean = false): AiGenerationOutcome {
        val config = repo.config()
        val key = AiExplanation.keyFor(
            bankId = input.bankId,
            questionId = input.questionId,
            locale = input.locale,
            questionRevision = input.questionRevision,
            contentFingerprint = input.contentFingerprint,
            model = config.model,
        )

        val existing = repo.find(key)
        if (!force && existing != null && existing.state.isReusable) {
            return AiGenerationOutcome.Ready(existing, fromCache = true)
        }

        if (provider == null) {
            return AiGenerationOutcome.Unavailable("当前平台没有接入联网 AI（目前只有桌面端可用）")
        }
        val problems = config.validate()
        if (problems.isNotEmpty()) {
            return AiGenerationOutcome.Unavailable(problems.first())
        }
        if (!config.uploadConsent) {
            return AiGenerationOutcome.Unavailable("还没有同意把题目内容发送给该服务")
        }
        val apiKey = repo.credential()
        if (apiKey.isNullOrBlank() && !config.isLocalEndpoint) {
            return AiGenerationOutcome.Unavailable("还没有填写 API Key")
        }

        val now = clock.nowMillis()
        val today = LocalDates.epochDayOf(now)
        val usage = repo.usage(today)
        // 预算是"最多可能要花多少"而不是"预计会花多少"：按单次输出上限估，宁可早拦
        when (val decision = config.budget.check(usage, config.maxOutputTokens + estimatePromptTokens(input), today)) {
            is BudgetDecision.Denied -> return AiGenerationOutcome.Blocked(decision.reason)
            BudgetDecision.Allowed -> Unit
        }

        val startedAt = clock.nowMillis()
        return when (val result = provider.explain(config, apiKey, input)) {
            is AiCallResult.Ok -> {
                val text = normalizeExplanation(result.text)
                val check = checkExplanationText(text)
                val state = if (check is AiTextCheck.Ok) AiRecordState.SCHEMA_VALIDATED else AiRecordState.NEEDS_REVIEW
                val record = AiExplanation(
                    key = key,
                    bankId = input.bankId,
                    questionId = input.questionId,
                    locale = input.locale,
                    questionRevision = input.questionRevision,
                    model = result.model.ifBlank { config.model },
                    state = state,
                    text = text,
                    error = (check as? AiTextCheck.Problem)?.reason.orEmpty(),
                    createdAtMillis = existing?.createdAtMillis ?: startedAt,
                    updatedAtMillis = clock.nowMillis(),
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    costMicros = config.pricing.costOf(result.promptTokens, result.completionTokens),
                )
                repo.save(record)
                repo.appendUsage(
                    AiUsageEntry(
                        epochDay = today,
                        atMillis = startedAt,
                        key = key,
                        model = record.model,
                        ok = true,
                        promptTokens = record.promptTokens,
                        completionTokens = record.completionTokens,
                        costMicros = record.costMicros,
                    ),
                )
                AiGenerationOutcome.Ready(record, fromCache = false)
            }

            is AiCallResult.Failed -> {
                val record = AiExplanation(
                    key = key,
                    bankId = input.bankId,
                    questionId = input.questionId,
                    locale = input.locale,
                    questionRevision = input.questionRevision,
                    model = config.model,
                    state = AiRecordState.FAILED,
                    // 上一次生成的内容不删：网络抖一下就把花过钱的内容丢掉，是最糟的处理
                    text = existing?.text.orEmpty(),
                    error = result.reason,
                    createdAtMillis = existing?.createdAtMillis ?: startedAt,
                    updatedAtMillis = clock.nowMillis(),
                )
                repo.save(record)
                repo.appendUsage(
                    AiUsageEntry(
                        epochDay = today,
                        atMillis = startedAt,
                        key = key,
                        model = config.model,
                        ok = false,
                    ),
                )
                AiGenerationOutcome.Failed(result.reason, result.retryable)
            }
        }
    }

    /** 粗估提示词 token。中文按 1 字 ≈ 1 token 是保守估计，宁可多算一点。 */
    private fun estimatePromptTokens(input: AiPromptInput): Int {
        val chars = input.promptMarkdown.length +
            input.options.sumOf { it.second.length } +
            input.answerText.length +
            (input.userAnswer?.length ?: 0)
        return chars / 2 + 400
    }
}
