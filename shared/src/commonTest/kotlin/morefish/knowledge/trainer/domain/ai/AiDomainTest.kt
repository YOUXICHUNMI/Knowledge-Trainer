package morefish.knowledge.trainer.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AI 领域的纯规则：文本结构校验、预算闸门、计价、缓存键、审核状态。
 *
 * 这些规则决定了"会不会发请求、会不会花钱"，所以每一条都单独测，
 * 而不是靠界面点一遍来证明。
 */
class AiDomainTest {

    // ---------------------------------------------------------------- 文本校验

    @Test
    fun blankExplanationIsAProblem() {
        val check = checkExplanationText("   \n  ")
        assertTrue(check is AiTextCheck.Problem)
    }

    @Test
    fun invisibleControlCharactersAreRejected() {
        val check = checkExplanationText("正常开头\u0007响铃符")
        assertTrue(check is AiTextCheck.Problem)
        assertTrue(check.reason.contains("U+0007"))
    }

    @Test
    fun overlongExplanationIsRejectedInsteadOfTruncated() {
        // 截断会把"讲到一半的推导"留下，比直接拦下更容易误导人
        val check = checkExplanationText("字".repeat(MAX_EXPLANATION_CHARS + 1))
        assertTrue(check is AiTextCheck.Problem)
    }

    @Test
    fun lineEndingsAreNormalizedBeforeStoring() {
        assertEquals("第一行\n第二行", normalizeExplanation("  第一行\r\n第二行  "))
    }

    @Test
    fun newlineAndTabAreNotTreatedAsControlCharacters() {
        assertEquals(AiTextCheck.Ok, checkExplanationText("第一行\n\t缩进"))
    }

    // ---------------------------------------------------------------- 预算

    @Test
    fun budgetDeniesWhenDailyCallsAreExhausted() {
        val budget = AiBudget(maxCallsPerDay = 2)
        val usage = AiUsage(epochDay = 10, calls = 2)
        val decision = budget.check(usage, expectedTokens = 100, today = 10)
        assertTrue(decision is BudgetDecision.Denied)
        assertTrue(decision.reason.contains("次数"))
    }

    @Test
    fun yesterdaySpendingDoesNotCountAgainstToday() {
        val budget = AiBudget(maxCallsPerDay = 1, maxTokensPerDay = 1000)
        val usage = AiUsage(epochDay = 9, calls = 5, promptTokens = 900, completionTokens = 900)
        assertEquals(BudgetDecision.Allowed, budget.check(usage, expectedTokens = 200, today = 10))
    }

    @Test
    fun budgetDeniesWhenThisCallWouldBlowTheTokenCeiling() {
        val budget = AiBudget(maxCallsPerDay = 0, maxTokensPerDay = 1000)
        val usage = AiUsage(epochDay = 10, calls = 3, promptTokens = 600, completionTokens = 300)
        val decision = budget.check(usage, expectedTokens = 200, today = 10)
        assertTrue(decision is BudgetDecision.Denied)
        assertTrue(decision.reason.contains("token"))
    }

    @Test
    fun zeroMeansUnlimited() {
        val budget = AiBudget(maxCallsPerDay = 0, maxTokensPerDay = 0, maxCostMicrosPerDay = 0)
        val usage = AiUsage(epochDay = 10, calls = 999, promptTokens = 9_999_999, costMicros = 9_999_999)
        assertEquals(BudgetDecision.Allowed, budget.check(usage, expectedTokens = 100_000, today = 10))
    }

    // ---------------------------------------------------------------- 计价

    @Test
    fun costIsComputedInExactMicros() {
        val pricing = AiPricing(promptMicrosPerMillion = 2_000_000, completionMicrosPerMillion = 8_000_000)
        // 1000 prompt × 2元/M + 500 completion × 8元/M = 0.002 + 0.004 = 0.006 元
        assertEquals(6_000, pricing.costOf(promptTokens = 1000, completionTokens = 500))
    }

    @Test
    fun usageAccumulatesCallsAndFailuresSeparately() {
        var usage = AiUsage(epochDay = 10)
        usage = usage.plus(AiUsageEntry(epochDay = 10, atMillis = 1, key = "a", model = "m", ok = true, promptTokens = 10, completionTokens = 5, costMicros = 100))
        usage = usage.plus(AiUsageEntry(epochDay = 10, atMillis = 2, key = "b", model = "m", ok = false))
        assertEquals(2, usage.calls)
        assertEquals(1, usage.failedCalls)
        assertEquals(15, usage.tokens)
        assertEquals(100, usage.costMicros)
    }

    @Test
    fun moneyFormattingNeverClaimsZeroWhenSomethingWasSpent() {
        assertEquals("¥0", formatMicros(0))
        assertEquals("<¥0.01", formatMicros(3_000))
        assertEquals("¥1.23", formatMicros(1_230_000))
    }

    // ---------------------------------------------------------------- 缓存键

    @Test
    fun cacheKeyChangesWhenContentOrModelChanges() {
        val base = AiExplanation.keyFor("bank", "q1", "zh-Hans", 1, "fp1", "model-a")
        val sameAgain = AiExplanation.keyFor("bank", "q1", "zh-Hans", 1, "fp1", "model-a")
        assertEquals(base, sameAgain, "同样的输入必须得到同一个键，否则缓存永远不命中")

        assertNotEquals(base, AiExplanation.keyFor("bank", "q1", "zh-Hans", 1, "fp1", "model-b"))
        assertNotEquals(base, AiExplanation.keyFor("bank", "q1", "en", 1, "fp1", "model-a"))
        assertNotEquals(base, AiExplanation.keyFor("bank", "q1", "zh-Hans", 2, "fp1", "model-a"))
        assertNotEquals(base, AiExplanation.keyFor("bank", "q1", "zh-Hans", 1, "fp2", "model-a"))
    }

    // ---------------------------------------------------------------- 审核状态

    @Test
    fun onlyHumanStatesCanBeAppliedByReview() {
        val record = AiExplanation(
            key = "k", bankId = "b", questionId = "q", locale = "zh-Hans", questionRevision = 1,
            model = "m", state = AiRecordState.SCHEMA_VALIDATED, text = "讲解",
        )
        assertEquals(AiRecordState.APPROVED, record.reviewed(AiRecordState.APPROVED, 5).state)
        assertEquals(5, record.reviewed(AiRecordState.APPROVED, 5).updatedAtMillis)
        assertFailsWith<IllegalArgumentException> { record.reviewed(AiRecordState.FAILED, 5) }
        assertFailsWith<IllegalArgumentException> { record.reviewed(AiRecordState.SCHEMA_VALIDATED, 5) }
    }

    @Test
    fun failedRecordsAreNotReusedAsCache() {
        assertFalse(AiRecordState.FAILED.isReusable)
        assertFalse(AiRecordState.REJECTED.isReusable, "弃用的讲解下次要重新生成，不能原样再端上来")
        assertTrue(AiRecordState.SCHEMA_VALIDATED.isReusable)
        assertTrue(AiRecordState.APPROVED.isReusable)
    }

    // ---------------------------------------------------------------- 配置

    @Test
    fun publicHostsMustUseHttps() {
        val insecure = AiConfig(baseUrl = "http://api.example.com/v1", model = "m")
        assertTrue(insecure.validate().any { it.contains("https") })

        val secure = AiConfig(baseUrl = "https://api.example.com/v1", model = "m")
        assertEquals(emptyList(), secure.validate())
    }

    @Test
    fun localhostMayUsePlainHttpForLocalModels() {
        val local = AiConfig(baseUrl = "http://localhost:11434/v1", model = "qwen2.5:7b")
        assertEquals(emptyList(), local.validate())
        assertTrue(local.isLocalEndpoint)
        assertEquals("http://localhost:11434/v1/chat/completions", local.endpoint())
    }

    @Test
    fun trailingSlashInBaseUrlDoesNotProduceDoubleSlashes() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "https://api.deepseek.com/v1/", model = "x").endpoint(),
        )
    }
}
