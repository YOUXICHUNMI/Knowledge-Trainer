package morefish.knowledge.trainer.application.usecase

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiHttpResponse
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchPlanner
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.domain.ai.AiBudget
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.ai.OpenAiCompatibleProvider
import morefish.knowledge.trainer.infrastructure.storage.JsonAiBatchRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonAiRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批量队列的行为约束。
 *
 * 这些断言全都在回答同一个问题：**这次会花多少次钱，停下来的那一刻账单对不对。**
 * 所以每条测试都同时检查"结果"和"实际发出的请求数"——
 * 只看结果的话，一个重复请求但结果正确的实现在这里会被判成通过。
 */
class AiBatchTest {

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    /** 按调用次序依次返回预设响应；用完之后重复最后一个。 */
    private class ScriptedHttp(private val responses: List<AiHttpResponse>) : AiHttpClient {
        var calls = 0
            private set
        override suspend fun post(request: AiHttpRequest): AiHttpResponse {
            val index = calls.coerceAtMost(responses.size - 1)
            calls++
            return responses[index]
        }
    }

    private fun ok(text: String = "这道题考的是电场的矢量性。"): AiHttpResponse = AiHttpResponse(
        200,
        """
        {"model":"test-model","choices":[{"message":{"content":"$text"}}],
         "usage":{"prompt_tokens":100,"completion_tokens":50}}
        """.trimIndent(),
    )

    private fun tooManyRequests(): AiHttpResponse = AiHttpResponse(429, """{"error":{"message":"rate limited"}}""")

    private fun bank(count: Int = 4, withEnglish: Boolean = false): QuestionBank = QuestionBank(
        bankId = "bank-batch",
        title = "批量题库",
        sourceLocale = "zh-Hans",
        questions = (1..count).map { index ->
            val options = listOf(
                QuestionOption("o$index-a", "选项 A$index"),
                QuestionOption("o$index-b", "选项 B$index"),
            )
            Question(
                questionId = "q$index",
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "第 $index 道题",
                options = options,
                correctOptionIds = setOf("o$index-a"),
                translations = if (withEnglish && index == 1) {
                    mapOf(
                        "en" to QuestionText(
                            locale = "en",
                            promptMarkdown = "Question $index",
                            optionTexts = mapOf("o$index-a" to "Option A$index", "o$index-b" to "Option B$index"),
                        ),
                    )
                } else {
                    emptyMap()
                },
            )
        },
        importedAtMillis = 0,
        sourceLabel = "test",
    )

    private fun config(consent: Boolean = true, budget: AiBudget = AiBudget()) = AiConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        budget = budget,
        uploadConsent = consent,
    )

    private class Fixture(
        val storage: InMemoryTextStorage,
        val ai: JsonAiRepository,
        val batches: JsonAiBatchRepository,
        val http: AiHttpClient,
        val usecase: RunBatchUseCase,
        val generate: GenerateExplanationUseCase,
    )

    private suspend fun fixture(
        http: AiHttpClient,
        config: AiConfig = config(),
        key: String? = "sk-test",
    ): Fixture {
        val storage = InMemoryTextStorage()
        val ai = JsonAiRepository(storage)
        val batches = JsonAiBatchRepository(storage)
        ai.saveConfig(config)
        if (key != null) ai.saveCredential(key)
        val generate = GenerateExplanationUseCase(ai, OpenAiCompatibleProvider(http), clock)
        return Fixture(
            storage = storage,
            ai = ai,
            batches = batches,
            http = http,
            usecase = RunBatchUseCase(ai, batches, generate, clock),
            generate = generate,
        )
    }

    private fun resolver(bank: QuestionBank, gone: Set<String> = emptySet()): suspend (AiBatchItem) -> BatchResolve =
        { item ->
            if (item.questionId in gone) {
                BatchResolve.Missing("题库里已经没有这道题")
            } else {
                val question = bank.questions.first { it.questionId == item.questionId }
                BatchResolve.Ready(AiPromptAssembler.inputOf(bank.bankId, question, item.locale))
            }
        }

    private fun jobOf(outcome: BatchPlanOutcome, maxCalls: Int = 0): AiBatchJob = AiBatchJob(
        jobId = "job-1",
        bankId = "bank-batch",
        bankTitle = "批量题库",
        model = "test-model",
        locale = "zh-Hans",
        items = outcome.items,
        maxCalls = maxCalls,
        createdAtMillis = now,
        updatedAtMillis = now,
    )

    // ------------------------------------------------------------ 排版

    @Test
    fun planningSkipsQuestionsThatAlreadyHaveAnExplanation() = runTest {
        val bank = bank(count = 3)
        val f = fixture(ScriptedHttp(listOf(ok())))
        // 先生成第 1 道，模拟"用户之前单独看过这一道"
        val first = AiBatchPlanner.plan(bank, "zh-Hans").items.first()
        f.generate.generate(AiPromptAssembler.inputOf(bank.bankId, bank.questions.first(), "zh-Hans"))

        val outcome = f.usecase.plan(bank, "zh-Hans", "test-model")

        assertEquals(2, outcome.items.size)
        assertEquals(1, outcome.alreadyDone)
        assertTrue(outcome.items.none { it.questionId == first.questionId })
    }

    @Test
    fun planningSkipsQuestionsWithoutTheRequestedLanguage() = runTest {
        val bank = bank(count = 3, withEnglish = true)
        val f = fixture(ScriptedHttp(listOf(ok())))

        val outcome = f.usecase.plan(bank, "en", "test-model")

        // 只有第 1 道有英文题面，另外两道不该排进队列 —— 排进去也只会失败
        assertEquals(1, outcome.items.size)
        assertEquals(2, outcome.skippedNoText)
        assertEquals("q1", outcome.items.single().questionId)
    }

    @Test
    fun planningTruncatesToTheSingleJobLimit() = runTest {
        val bank = bank(count = 8)
        val f = fixture(ScriptedHttp(listOf(ok())))

        val outcome = f.usecase.plan(bank, "zh-Hans", "test-model", maxItems = 5)

        assertEquals(5, outcome.items.size)
        assertEquals(3, outcome.truncated, "截掉多少必须报出来，否则用户以为跑完了全库")
    }

    // ------------------------------------------------------------ 执行

    @Test
    fun runGeneratesEveryItemAndCountsEachCall() = runTest {
        val bank = bank(count = 3)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(job, resolver(bank))

        assertEquals(AiBatchState.COMPLETED, finished.state)
        assertEquals(3, finished.doneCount)
        assertEquals(0, finished.failedCount)
        assertEquals(3, http.calls)
        assertEquals(3, finished.callsUsed)
        assertEquals(3, f.ai.records().size)
        assertTrue(finished.summaryText().contains("成功 3"))
    }

    @Test
    fun oneFailureDoesNotStopTheRestAndIsNotRetried() = runTest {
        val bank = bank(count = 3)
        val http = ScriptedHttp(listOf(ok(), tooManyRequests(), ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(job, resolver(bank))

        assertEquals(AiBatchState.COMPLETED, finished.state)
        assertEquals(2, finished.doneCount)
        assertEquals(1, finished.failedCount)
        assertEquals(3, http.calls, "失败的那道不能被自动重试 —— 100 道 × 3 次就是 300 次请求")
        val failed = finished.items.single { it.state == AiBatchItemState.FAILED }
        assertEquals(1, failed.attempts, "失败也发过一次请求，次数要记上")
        assertTrue(failed.note.isNotBlank())
    }

    @Test
    fun exhaustedDailyBudgetPausesInsteadOfFailingTheRest() = runTest {
        val bank = bank(count = 4)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http, config = config(budget = AiBudget(maxCallsPerDay = 2)))
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(job, resolver(bank))

        assertEquals(2, http.calls)
        assertEquals(AiBatchState.PAUSED, finished.state, "预算到顶该暂停，不该把剩下的标成失败")
        assertEquals(2, finished.doneCount)
        assertEquals(0, finished.failedCount)
        assertEquals(2, finished.queuedCount, "没处理的项必须还是待处理，才能明天接着跑")
        assertTrue(finished.note.contains("上限") || finished.note.contains("次数"))
    }

    @Test
    fun perJobCallLimitStopsAtTheNumberTheUserTyped() = runTest {
        val bank = bank(count = 4)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"), maxCalls = 2)

        val finished = f.usecase.run(job, resolver(bank))

        assertEquals(2, http.calls)
        assertEquals(AiBatchState.PAUSED, finished.state)
        assertEquals(0, finished.callsLeft)
        assertEquals(2, finished.queuedCount)
    }

    @Test
    fun pauseIntentStopsBeforeTheNextRequest() = runTest {
        val bank = bank(count = 4)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))
        var seen = 0

        val finished = f.usecase.run(
            job = job,
            resolve = resolver(bank),
            onProgress = { seen++ },
            // 第一道之后就"用户点了暂停"
            shouldStop = { if (http.calls >= 1) AiBatchState.PAUSED else null },
        )

        assertEquals(1, http.calls, "暂停要在请求边界生效，不能在飞请求中间打断")
        assertEquals(AiBatchState.PAUSED, finished.state)
        assertEquals(3, finished.queuedCount)
        assertTrue(seen > 0, "进度回调一次都没调，界面就不会动")
    }

    @Test
    fun cancelIntentStopsAndKeepsWhatAlreadyFinished() = runTest {
        val bank = bank(count = 4)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(
            job = job,
            resolve = resolver(bank),
            shouldStop = { if (http.calls >= 2) AiBatchState.CANCELLED else null },
        )

        assertEquals(2, http.calls)
        assertEquals(AiBatchState.CANCELLED, finished.state)
        assertEquals(2, finished.doneCount, "取消不能把已经花过钱生成的内容一起丢掉")
    }

    @Test
    fun questionsMissingFromTheBankAreSkippedNotFailed() = runTest {
        val bank = bank(count = 3)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(job, resolver(bank, gone = setOf("q2")))

        assertEquals(2, http.calls)
        assertEquals(2, finished.doneCount)
        assertEquals(1, finished.skippedCount)
        assertEquals(0, finished.failedCount, "题目不在了不是失败 —— 标失败会让用户去查网络")
    }

    // ------------------------------------------------------------ 续跑

    @Test
    fun resumingPicksUpWhereItStoppedWithoutPayingTwice() = runTest {
        val bank = bank(count = 4)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val planned = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val partial = f.usecase.run(
            job = planned,
            resolve = resolver(bank),
            shouldStop = { if (http.calls >= 2) AiBatchState.PAUSED else null },
        )
        assertEquals(2, http.calls)

        val resumed = f.usecase.run(partial.copy(state = AiBatchState.QUEUED), resolver(bank))

        assertEquals(AiBatchState.COMPLETED, resumed.state)
        assertEquals(4, http.calls, "续跑只能补没做过的两道")
        assertEquals(4, resumed.doneCount)
        assertEquals(4, f.ai.records().size)
    }

    @Test
    fun rescanAfterPauseSkipsWhatWasGeneratedMeanwhile() = runTest {
        val bank = bank(count = 3)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        // 暂停期间用户自己把第三道单独生成了
        f.generate.generate(AiPromptAssembler.inputOf(bank.bankId, bank.questions[2], "zh-Hans"))

        val rescanned = f.usecase.scan(job)

        assertEquals(2, rescanned.queuedCount)
        assertEquals(1, rescanned.skippedCount)
        val finished = f.usecase.run(rescanned, resolver(bank))
        // 1 次是测试自己预生成的，2 次是队列补的两道 —— 第三道没有重复花钱
        assertEquals(3, http.calls, "重扫之后不该为已经有的那道再花一次钱")
        assertEquals(2, finished.doneCount)
        assertEquals(1, finished.skippedCount)
    }

    @Test
    fun cachedItemDuringRunIsRecordedAsSkipped() = runTest {
        val bank = bank(count = 2)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        // 建队列时还没有讲解，执行前恰好有了（另一个入口刚生成过）
        val items = AiBatchPlanner.plan(bank, "zh-Hans").items
        f.generate.generate(AiPromptAssembler.inputOf(bank.bankId, bank.questions.first(), "zh-Hans"))
        val job = AiBatchJob(
            jobId = "job-x",
            bankId = bank.bankId,
            bankTitle = bank.title,
            model = "test-model",
            locale = "zh-Hans",
            items = items,
            createdAtMillis = now,
            updatedAtMillis = now,
        )

        val finished = f.usecase.run(job, resolver(bank))

        // 一次是测试自己预生成的，队列只补了剩下那一——命中缓存的那道没有再发请求
        assertEquals(2, http.calls)
        assertEquals(1, finished.skippedCount)
        assertEquals(1, finished.doneCount)
    }

    @Test
    fun unavailableConfigurationPausesOnTheFirstStep() = runTest {
        val bank = bank(count = 3)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http, config = config(consent = false))
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))

        val finished = f.usecase.run(job, resolver(bank))

        assertEquals(0, http.calls)
        assertEquals(AiBatchState.PAUSED, finished.state)
        assertEquals(3, finished.queuedCount)
        assertTrue(assertNotNull(finished.note).contains("同意"))
    }

    @Test
    fun finishedJobIsNotRunnableAgain() = runTest {
        val bank = bank(count = 2)
        val http = ScriptedHttp(listOf(ok()))
        val f = fixture(http)
        val job = jobOf(f.usecase.plan(bank, "zh-Hans", "test-model"))
        val finished = f.usecase.run(job, resolver(bank))
        assertEquals(2, http.calls)

        val again = f.usecase.run(finished, resolver(bank))

        assertEquals(2, http.calls, "已完成的任务再点一次不该继续扣费")
        assertEquals(AiBatchState.COMPLETED, again.state)
    }
}
