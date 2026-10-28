package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiHttpResponse
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.ai.AiBudget
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 讲解的端到端行为：什么时候**不发**请求、什么时候缓存命中、失败之后留下什么。
 *
 * 这里用的是真适配器 + 假传输层：报文构造、状态判定、落盘、账本全都走真代码，
 * 只有"网线"是假的 —— 这样测出来的行为就是用户会遇到的行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiFlowTest {

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private class FakeHttp(private val response: AiHttpResponse) : AiHttpClient {
        var calls = 0
        var last: AiHttpRequest? = null
        override suspend fun post(request: AiHttpRequest): AiHttpResponse {
            calls++
            last = request
            return response
        }
    }

    private fun reply(text: String = "因为电场是矢量，有方向。"): AiHttpResponse = AiHttpResponse(
        200,
        """
        {"model":"test-model","choices":[{"message":{"content":"$text"}}],
         "usage":{"prompt_tokens":120,"completion_tokens":40}}
        """.trimIndent(),
    )

    private fun bank(): QuestionBank = QuestionBank(
        bankId = "bank-ai",
        title = "AI 题库",
        sourceLocale = "zh-Hans",
        questions = listOf(
            Question(
                questionId = "q1",
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "关于电场，下列说法正确的是？",
                options = listOf(
                    QuestionOption("o1", "电场是矢量"),
                    QuestionOption("o2", "电场是标量"),
                ),
                correctOptionIds = setOf("o1"),
            ),
        ),
        importedAtMillis = 0,
        sourceLabel = "test",
    )

    /** 两道题：用来验证讲解不会跟着题目一起被"带过去"。 */
    private fun twoQuestionBank(): QuestionBank = bank().copy(
        questions = bank().questions + Question(
            questionId = "q2",
            type = QuestionType.SINGLE_CHOICE,
            promptMarkdown = "关于磁场，下列说法正确的是？",
            options = listOf(
                QuestionOption("o1", "磁场没有方向"),
                QuestionOption("o2", "磁场有方向"),
            ),
            correctOptionIds = setOf("o2"),
        ),
    )

    private fun configured(
        consent: Boolean = true,
        budget: AiBudget = AiBudget(),
    ) = AiConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        budget = budget,
        uploadConsent = consent,
    )

    /** 建控制器、导入题库、练一题并作答 —— AI 只在作答之后才允许用。 */
    private suspend fun answered(
        storage: InMemoryTextStorage,
        http: AiHttpClient,
        scheduler: TestCoroutineScheduler,
    ): AppController {
        AppDependencies(storage, clock, aiHttpClient = http).bankRepository.save(bank())
        val controller = AppController(
            deps = AppDependencies(storage, clock, aiHttpClient = http),
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )
        controller.refresh()
        controller.startPractice(controller.banks.single(), questionCount = 1)
        scheduler.advanceUntilIdle()
        controller.session!!.current!!.question.correctOptionIds.forEach { controller.toggleOption(it) }
        controller.submit()
        scheduler.advanceUntilIdle()
        return controller
    }

    /** 两道题的会话，第一题已作答。 */
    private suspend fun answeredFirstOfTwo(
        storage: InMemoryTextStorage,
        http: AiHttpClient,
        scheduler: TestCoroutineScheduler,
    ): AppController {
        AppDependencies(storage, clock, aiHttpClient = http).bankRepository.save(twoQuestionBank())
        val controller = AppController(
            deps = AppDependencies(storage, clock, aiHttpClient = http),
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )
        controller.refresh()
        controller.startPractice(controller.banks.single(), questionCount = 2)
        scheduler.advanceUntilIdle()
        controller.session!!.current!!.question.correctOptionIds.forEach { controller.toggleOption(it) }
        controller.submit()
        scheduler.advanceUntilIdle()
        return controller
    }

    @Test
    fun withoutConfigNothingIsSent() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        assertEquals(0, http.calls, "没配置就发请求，等于拿用户的密钥去试错")
        assertEquals(emptyList(), c.ai.records)
        assertTrue(assertNotNull(c.banner).text.contains("接口地址"))
    }

    @Test
    fun consentIsRequiredBeforeTheFirstRequest() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(consent = false), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        assertEquals(0, http.calls)
        assertEquals(emptyList(), c.ai.records)
        assertTrue(assertNotNull(c.banner).text.contains("同意"))
    }

    @Test
    fun unansweredQuestionDoesNotGetAnExplanation() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock, aiHttpClient = http).bankRepository.save(bank())
        val c = AppController(
            deps = AppDependencies(storage, clock, aiHttpClient = http),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        c.refresh()
        c.ai.saveConfig(configured(), "sk-test")
        c.startPractice(c.banks.single(), questionCount = 1)
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        assertEquals(0, http.calls, "没作答就给答案，比不给更糟")
        assertTrue(assertNotNull(c.banner).text.contains("先作答"))
    }

    @Test
    fun successIsCachedAndCounted() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        assertEquals(1, http.calls)
        val record = assertNotNull(c.ai.currentExplanation)
        assertEquals(AiRecordState.SCHEMA_VALIDATED, record.state)
        assertTrue(record.text.contains("电场"))
        assertEquals(120, record.promptTokens)
        assertEquals(40, record.completionTokens)
        assertEquals(1, c.ai.usageToday.calls)
        assertEquals(160, c.ai.usageToday.tokens)

        // 同一道题再点一次：命中缓存，不再花钱，也不弹"已生成"
        c.ai.generate()
        testScheduler.advanceUntilIdle()
        assertEquals(1, http.calls, "同样的题重复请求就是把用户的钱当柴烧")
        assertNull(c.banner)
    }

    @Test
    fun forceRegeneratesAndSpendsAgain() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()
        c.ai.generate(force = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, http.calls)
        assertEquals(2, c.ai.usageToday.calls)
        assertEquals(1, c.ai.records.size, "重生成是替换同一条记录，不是堆一条新的")
    }

    @Test
    fun budgetStopsBeforeTheRequestGoesOut() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(budget = AiBudget(maxCallsPerDay = 1)), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()
        c.ai.generate(force = true)
        testScheduler.advanceUntilIdle()

        assertEquals(1, http.calls, "预算到了就该在发请求之前拦住")
        assertTrue(assertNotNull(c.banner).text.contains("次数"))
    }

    @Test
    fun failureIsRecordedAndNeverRetriedAutomatically() = runTest {
        val http = FakeHttp(AiHttpResponse(429, """{"error":{"message":"rate limited"}}"""))
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        assertEquals(1, http.calls, "自动重试会在计费接口上重复扣费")
        val record = assertNotNull(c.ai.currentExplanation)
        assertEquals(AiRecordState.FAILED, record.state)
        assertTrue(record.error.contains("429"))
        assertEquals(1, c.ai.usageToday.calls, "失败也占一次调用额度，否则重试可以把上限刷没")
        assertEquals(1, c.ai.usageToday.failedCalls)
        assertTrue(assertNotNull(c.banner).text.contains("不会自动重试"))
    }

    @Test
    fun approvingSurvivesARestart() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.ai.generate()
        testScheduler.advanceUntilIdle()

        c.ai.reviewCurrent(AiRecordState.APPROVED)
        testScheduler.advanceUntilIdle()
        assertEquals(AiRecordState.APPROVED, c.ai.currentExplanation?.state)

        // 重开一个控制器（同一份存储）：采纳状态必须还在，否则"审核"只是演戏
        val reopened = AppController(
            deps = AppDependencies(storage, clock, aiHttpClient = http),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        reopened.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(AiRecordState.APPROVED, reopened.ai.records.single().state)
    }

    @Test
    fun explanationDoesNotFollowYouToTheNextQuestion() = runTest {
        val http = FakeHttp(reply())
        val storage = InMemoryTextStorage()
        val c = answeredFirstOfTwo(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()
        val firstKey = assertNotNull(c.ai.currentKey)
        assertNotNull(c.ai.currentExplanation)

        c.next()
        testScheduler.advanceUntilIdle()

        // 当前题变了，缓存键要跟着变，讲解就必须跟着"没有"：还挂着上一题的解释，
        // 等于在用户读下一题时给他看上一题的答案，而且面板会被当成"已生成"。
        assertNotEquals(firstKey, assertNotNull(c.ai.currentKey), "换了题，缓存键就该换")
        assertNull(c.ai.currentExplanation)
        assertEquals(firstKey, c.ai.records.single().key, "记录仍是上一题那条，只是不再属于当前这题")

        // 没作答的新题同样不许生成，也不许把上一题的记录当成它的
        c.ai.generate()
        testScheduler.advanceUntilIdle()
        assertEquals(1, http.calls, "换题不该悄悄再发一次请求")
        assertTrue(assertNotNull(c.banner).text.contains("先作答"))
        assertNull(c.ai.currentExplanation)
        assertEquals(1, c.ai.records.size)
    }

    @Test
    fun overlongReplyIsKeptButFlaggedForReview() = runTest {
        val http = FakeHttp(reply(text = "字".repeat(20_001)))
        val storage = InMemoryTextStorage()
        val c = answered(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.ai.generate()
        testScheduler.advanceUntilIdle()

        val record = assertNotNull(c.ai.currentExplanation)
        assertEquals(AiRecordState.NEEDS_REVIEW, record.state)
        assertTrue(record.error.contains("上限"))
        assertEquals(1, http.calls, "结构不合格也不该自动重试")
    }
}
