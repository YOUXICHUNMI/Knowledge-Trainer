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
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 批量队列在真实控制器里的行为。
 *
 * 这里关心的是"用户按下按钮之后到底发生了什么"：
 * 排队列会不会立刻花钱、开始之后进度有没有推到界面、暂停再继续会不会重复请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiBatchFlowTest {

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private class CountingHttp : AiHttpClient {
        var calls = 0
            private set
        override suspend fun post(request: AiHttpRequest): AiHttpResponse {
            calls++
            return AiHttpResponse(
                200,
                """
                {"model":"test-model","choices":[{"message":{"content":"讲解 ${'$'}calls"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":50}}
                """.trimIndent(),
            )
        }
    }

    private fun bank(count: Int = 3) = QuestionBank(
        bankId = "bank-flow",
        title = "流程题库",
        sourceLocale = "zh-Hans",
        questions = (1..count).map { index ->
            Question(
                questionId = "q$index",
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "第 $index 道题",
                options = listOf(
                    QuestionOption("o$index-a", "选项 A$index"),
                    QuestionOption("o$index-b", "选项 B$index"),
                ),
                correctOptionIds = setOf("o$index-a"),
            )
        },
        importedAtMillis = 0,
        sourceLabel = "test",
    )

    private fun configured(budget: AiBudget = AiBudget()) = AiConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        budget = budget,
        uploadConsent = true,
    )

    private suspend fun controller(
        storage: InMemoryTextStorage,
        http: AiHttpClient,
        scheduler: TestCoroutineScheduler,
        bank: QuestionBank = bank(),
    ): AppController {
        val deps = AppDependencies(storage, clock, aiHttpClient = http)
        deps.bankRepository.save(bank)
        val c = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        c.refresh()
        scheduler.advanceUntilIdle()
        return c
    }

    @Test
    fun cannotQueueWithoutConfiguration() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)

        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()

        assertEquals(0, http.calls)
        assertEquals(emptyList(), c.aiJobs, "配置都没有，排出来的队列点开始也只会立刻暂停")
        assertTrue(assertNotNull(c.banner).text.contains("接口地址"))
    }

    @Test
    fun queueingDoesNotSpendAnything() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        c.openBatch()
        c.createBatch("bank-flow", "zh-Hans", 2)
        testScheduler.advanceUntilIdle()

        val job = assertNotNull(c.aiBatchSelected)
        assertEquals(0, http.calls, "排队列只是排，不能悄悄开始花钱")
        assertEquals(AiBatchState.QUEUED, job.state)
        assertEquals(3, job.total)
        assertEquals(2, job.maxCalls)
        assertEquals(Screen.AI_BATCH, c.screen, "排完队列应该直接停在批量页，让用户看数字")
    }

    @Test
    fun startingRunsToCompletionAndFillsTheCache() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()

        c.startBatch()
        testScheduler.advanceUntilIdle()

        val job = assertNotNull(c.aiBatchSelected)
        assertEquals(AiBatchState.COMPLETED, job.state)
        assertEquals(3, job.doneCount)
        assertEquals(3, http.calls)
        assertEquals(3, c.ai.records.size, "生成出来的讲解要进缓存，不然下次还要再花钱")
        assertNull(c.aiBatchRunningId)
        assertTrue(assertNotNull(c.banner).text.contains("批量完成"))
    }

    @Test
    fun alreadyGeneratedQuestionsAreExcludedBeforeQueueing() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()

        // 先跑一轮把三道都生成出来
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()
        c.startBatch()
        testScheduler.advanceUntilIdle()
        assertEquals(3, http.calls)

        // 再排一次队列：全都有讲解了，应该直接排不出来，也不该再花一分钱
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()

        assertEquals(3, http.calls)
        assertEquals(1, c.aiJobs.size, "没有需要生成的题就不该产生空任务")
        assertTrue(assertNotNull(c.banner).text.contains("都已经有讲解"))
    }

    @Test
    fun pausedJobCanResumeWithoutPayingTwice() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 2)
        testScheduler.advanceUntilIdle()

        c.startBatch()
        testScheduler.advanceUntilIdle()
        val paused = assertNotNull(c.aiBatchSelected)
        assertEquals(AiBatchState.PAUSED, paused.state)
        assertEquals(2, http.calls)
        assertEquals(1, paused.queuedCount)

        // 上限已经用掉了，用户放宽上限再继续
        c.createBatch("bank-flow", "zh-Hans", 5)
        testScheduler.advanceUntilIdle()
        // 新队列只会排剩下的那一 —— 前两道已经有讲解了
        c.startBatch()
        testScheduler.advanceUntilIdle()

        assertEquals(3, http.calls, "续跑只能补没做过的那一道")
        assertEquals(3, c.ai.records.size)
    }

    @Test
    fun cancellingLeavesTheQueueUntouched() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()

        c.cancelBatch()
        testScheduler.advanceUntilIdle()

        val job = assertNotNull(c.aiBatchSelected)
        assertEquals(AiBatchState.CANCELLED, job.state)
        assertEquals(0, http.calls, "还没开始就取消，一次请求都不该发")
        assertEquals(3, job.queuedCount, "取消不清空队列 —— 用户可能只是想改天再跑")
    }

    @Test
    fun budgetShortagePausesInsteadOfBurningThroughFailures() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(budget = AiBudget(maxCallsPerDay = 2)), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()

        c.startBatch()
        testScheduler.advanceUntilIdle()

        val job = assertNotNull(c.aiBatchSelected)
        assertEquals(2, http.calls)
        assertEquals(AiBatchState.PAUSED, job.state)
        assertEquals(2, job.doneCount)
        assertEquals(0, job.failedCount, "预算不够不是题坏了，不能记成失败")
        assertEquals(1, job.queuedCount)
    }

    @Test
    fun queueSurvivesARestartWithItsProgress() = runTest {
        val storage = InMemoryTextStorage()
        val http = CountingHttp()
        val c = controller(storage, http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 2)
        testScheduler.advanceUntilIdle()
        c.startBatch()
        testScheduler.advanceUntilIdle()
        assertEquals(2, http.calls)

        val reopened = AppController(
            deps = AppDependencies(storage, clock, aiHttpClient = http),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        reopened.refresh()
        testScheduler.advanceUntilIdle()

        val restored = assertNotNull(reopened.aiBatchSelected)
        assertEquals(AiBatchState.PAUSED, restored.state)
        assertEquals(2, restored.doneCount)
        assertEquals(1, restored.queuedCount)
        assertEquals(AiBatchItemState.QUEUED, restored.items.last().state)
    }

    @Test
    fun deletingAJobKeepsTheGeneratedExplanations() = runTest {
        val http = CountingHttp()
        val c = controller(InMemoryTextStorage(), http, testScheduler)
        c.ai.saveConfig(configured(), "sk-test")
        testScheduler.advanceUntilIdle()
        c.createBatch("bank-flow", "zh-Hans", 0)
        testScheduler.advanceUntilIdle()
        c.startBatch()
        testScheduler.advanceUntilIdle()
        val jobId = assertNotNull(c.aiBatchSelected).jobId

        c.deleteBatch(jobId)
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), c.aiJobs)
        assertEquals(3, c.ai.records.size, "删任务是删待办，不是删花过钱买来的内容")
    }
}
