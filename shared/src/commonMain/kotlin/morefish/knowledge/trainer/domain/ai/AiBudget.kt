package morefish.knowledge.trainer.domain.ai

/**
 * 单价（每百万 token，单位：微元）。
 *
 * 用微元而不用浮点：金额累加必须是精确的，"0.1 + 0.2" 这种误差出现在账单里就是事故。
 * 单价为 0 表示"不按费用计"——很多本地模型本来就不收费。
 */
data class AiPricing(
    val promptMicrosPerMillion: Long = 0,
    val completionMicrosPerMillion: Long = 0,
) {
    val isFree: Boolean get() = promptMicrosPerMillion == 0L && completionMicrosPerMillion == 0L

    fun costOf(promptTokens: Int, completionTokens: Int): Long {
        val prompt = promptTokens.coerceAtLeast(0).toLong() * promptMicrosPerMillion
        val completion = completionTokens.coerceAtLeast(0).toLong() * completionMicrosPerMillion
        return (prompt + completion) / 1_000_000
    }
}

/**
 * 每日预算。0 表示该项不限。
 *
 * 三个维度都留着，是因为它们拦住的不是同一件事：
 * 次数防"点了五十次重试"，token 防"一次问了整库上下文"，费用防"换了个贵的模型"。
 */
data class AiBudget(
    val maxCallsPerDay: Int = 40,
    val maxTokensPerDay: Int = 60_000,
    val maxCostMicrosPerDay: Long = 0,
) {
    fun check(usage: AiUsage, expectedTokens: Int, today: Long): BudgetDecision {
        val window = usage.onDay(today)
        if (maxCallsPerDay > 0 && window.calls >= maxCallsPerDay) {
            return BudgetDecision.Denied("今天的调用次数已用完（${window.calls}/$maxCallsPerDay）")
        }
        if (maxTokensPerDay > 0 && window.tokens + expectedTokens > maxTokensPerDay) {
            return BudgetDecision.Denied(
                "今天的 token 预算不够再问一次（已用 ${window.tokens}，本次最多 ${expectedTokens}，" +
                    "上限 $maxTokensPerDay）",
            )
        }
        if (maxCostMicrosPerDay > 0 && window.costMicros >= maxCostMicrosPerDay) {
            return BudgetDecision.Denied(
                "今天的费用已到上限（${formatMicros(window.costMicros)} / ${formatMicros(maxCostMicrosPerDay)}）",
            )
        }
        return BudgetDecision.Allowed
    }
}

sealed interface BudgetDecision {
    data object Allowed : BudgetDecision

    data class Denied(val reason: String) : BudgetDecision
}

/** 某一天的用量快照。[epochDay] 是本地日历天，不是 UTC 天（凌晨八点前算成昨天是错的）。 */
data class AiUsage(
    val epochDay: Long = 0,
    val calls: Int = 0,
    val failedCalls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
) {
    val tokens: Int get() = promptTokens + completionTokens

    /** 把快照对齐到指定日期：不是同一天就当作今天还没用过。 */
    fun onDay(day: Long): AiUsage = if (epochDay == day) this else AiUsage(epochDay = day)

    fun plus(entry: AiUsageEntry): AiUsage {
        val base = onDay(entry.epochDay)
        return AiUsage(
            epochDay = entry.epochDay,
            calls = base.calls + 1,
            failedCalls = base.failedCalls + (if (entry.ok) 0 else 1),
            promptTokens = base.promptTokens + entry.promptTokens,
            completionTokens = base.completionTokens + entry.completionTokens,
            costMicros = base.costMicros + entry.costMicros,
        )
    }
}

/** 账本的一行。只追加、不改写 —— 与作答历史同样的立场：花过的钱不能被后来的操作抹掉。 */
data class AiUsageEntry(
    val epochDay: Long,
    val atMillis: Long,
    val key: String,
    val model: String,
    val ok: Boolean,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
)

/** 金额展示。不足一分钱时不显示 "¥0.00" —— 那看起来像没花钱。 */
fun formatMicros(micros: Long): String {
    if (micros <= 0) return "¥0"
    if (micros < 10_000) return "<¥0.01"
    val whole = micros / 1_000_000
    val cents = ((micros % 1_000_000) / 10_000).toInt()
    return "¥$whole.${cents.toString().padStart(2, '0')}"
}
