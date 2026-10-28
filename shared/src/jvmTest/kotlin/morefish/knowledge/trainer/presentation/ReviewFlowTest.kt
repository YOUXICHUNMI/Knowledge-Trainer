package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.practice.ReviewDecision
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonReviewRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 历史复核的端到端行为。
 *
 * 用真实的 starter → starter-v2 补丁做场景：补丁给 q002 加了一个正确选项，
 * 于是"当时选了 o1+o2+o3 判对"的记录，按新答案重算会变成"漏选"。
 * 这正是这一块要处理的那件事，不是构造出来的假场景。
 *
 * 四条底线：
 *
 * 1. 装完补丁，历史**一条判定都没变**；
 * 2. 只有用户点了"按新答案复核"，统计才动；
 * 3. 复核**不改写** history 文件，决定单独存；
 * 4. "维持当时判定"也要被记住，不能反复问。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFlowTest {

    private val bank = "550e8400-e29b-41d4-a716-446655440000"
    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples")
    }

    private fun starterBytes(): ByteArray = File(examples, "starter.qbank").let {
        if (!it.isFile) fail("缺少 ${it.absolutePath}")
        it.readBytes()
    }

    private fun patchBytes(): ByteArray = File(examples, "starter-v2.qbpatch").let {
        if (!it.isFile) fail("缺少 ${it.absolutePath}")
        it.readBytes()
    }

    /** q002 的一次作答：第 1 版标准答案是 o1+o2+o3，所以当时判对。 */
    private fun correctOnQ002(at: Long = now) = AttemptRecord(
        attemptId = "a-q002-$at",
        sessionId = "s1",
        bankId = bank,
        questionId = "q002",
        questionType = QuestionType.MULTIPLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = listOf("o1", "o2", "o3", "o4"),
        selectedOptionIds = setOf("o1", "o2", "o3"),
        textAnswer = null,
        verdict = Verdict.CORRECT,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "2cf5290769b52058",
        submittedAtMillis = at,
        elapsedMillis = 8_000,
        explanationSeen = false,
    )

    private suspend fun controllerWith(
        storage: InMemoryTextStorage,
        scheduler: TestCoroutineScheduler,
        attempts: List<AttemptRecord> = listOf(correctOnQ002()),
        applyPatch: Boolean = true,
    ): AppController {
        val deps = AppDependencies(storage, clock)
        ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test")
        attempts.forEach { deps.attemptRepository.append(it) }
        val controller = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        controller.refresh()
        scheduler.advanceUntilIdle()
        if (applyPatch) {
            controller.openPatch(patchBytes(), "starter-v2.qbpatch")
            scheduler.advanceUntilIdle()
            controller.applyPickedPatch()
            scheduler.advanceUntilIdle()
        }
        return controller
    }

    // ---------------------------------------------------------------- 暴露

    @Test
    fun applyingAPatchNeverRewritesHistory() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)

        assertEquals(2, c.banks.single().contentRevision, "补丁应该已经生效")
        val record = c.history.single()
        assertEquals(Verdict.CORRECT, record.verdict, "装补丁这一步不许改判定")
        assertNull(record.review, "没有用户表态就不该有复核痕迹")
        assertEquals(1, c.review.pending, "首页徽章应该提示有 1 条待复核")
    }

    @Test
    fun openingReviewRecomputesAgainstCurrentContent() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)
        c.openReview()
        testScheduler.advanceUntilIdle()

        val plan = assertNotNull(c.review.plan)
        assertEquals(1, plan.count)
        val candidate = plan.candidates.single()
        assertEquals(Verdict.CORRECT, candidate.previousVerdict)
        assertEquals(Verdict.INCORRECT, candidate.suggestedVerdict, "漏了新增的正确选项 o5")
        assertTrue(candidate.bankTitle.contains("示例题库"), "候选要带上题库标题：实际 ${candidate.bankTitle}")
        assertTrue(candidate.currentAnswer.contains("直接现实性"), "现在的标准答案要带上新选项")
        assertEquals(0, plan.unreviewable)
    }

    // ---------------------------------------------------------------- 决定

    @Test
    fun acceptingRewritesTheEffectiveVerdictButNotTheHistoryFile() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)
        c.openReview()
        testScheduler.advanceUntilIdle()

        c.review.decide(c.history.single().attemptId, accept = true)
        testScheduler.advanceUntilIdle()

        val merged = c.history.single()
        assertEquals(Verdict.INCORRECT, merged.verdict, "生效判定要跟着复核走")
        val mark = assertNotNull(merged.review)
        assertEquals(ReviewDecision.ACCEPT, mark.decision)
        assertEquals(Verdict.CORRECT, mark.from)
        assertEquals(Verdict.INCORRECT, mark.to)
        assertTrue(mark.changed)
        assertEquals(1, c.historyView.wrongEntries.size, "改判为错误，错题本应该跟着出现这道题")
        assertEquals(0, c.review.pending, "处理过就不再算待复核")

        // 原始历史文件那一行仍然是当时的判定
        val raw = assertNotNull(JsonAttemptRepository(storage).all().singleOrNull())
        assertEquals(Verdict.CORRECT, raw.verdict)
        assertNull(raw.review, "复核结果不能写进历史文件")
        assertEquals(1, JsonReviewRepository(storage).all().size)
    }

    @Test
    fun keepingTheOriginalVerdictLeavesNumbersAloneAndStopsAsking() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)
        c.openReview()
        testScheduler.advanceUntilIdle()

        c.review.decide(c.history.single().attemptId, accept = false)
        testScheduler.advanceUntilIdle()

        val merged = c.history.single()
        assertEquals(Verdict.CORRECT, merged.verdict, "维持当时判定就不该动统计")
        val mark = assertNotNull(merged.review)
        assertEquals(ReviewDecision.RESTORE, mark.decision)
        assertTrue(!mark.changed)
        assertTrue(c.historyView.wrongEntries.isEmpty())
        assertTrue(assertNotNull(c.review.plan).isEmpty, "表态过的记录要从列表里消失")
        assertEquals(0, c.review.pending)

        // 再进一次页也不该重新出现
        c.openReview()
        testScheduler.advanceUntilIdle()
        assertTrue(assertNotNull(c.review.plan).isEmpty)
    }

    @Test
    fun decidingAllHandlesEveryCandidateAtOnce() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(
            storage,
            testScheduler,
            attempts = listOf(correctOnQ002(at = 1_000), correctOnQ002(at = 2_000), correctOnQ002(at = 3_000)),
        )
        c.openReview()
        testScheduler.advanceUntilIdle()
        assertEquals(3, assertNotNull(c.review.plan).count)

        c.review.decideAll(accept = true)
        testScheduler.advanceUntilIdle()

        assertEquals(3, c.history.count { it.verdict == Verdict.INCORRECT })
        assertTrue(assertNotNull(c.review.plan).isEmpty)
        val banner = assertNotNull(c.banner)
        assertTrue(banner.text.contains("3 条"), "实际：${banner.text}")
        assertTrue(banner.text.contains("没有改写"), "要明说历史记录本身没被改写")
    }

    @Test
    fun oneDecisionPerAttemptPerContentVersion() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)
        c.openReview()
        testScheduler.advanceUntilIdle()
        val attemptId = c.history.single().attemptId

        c.review.decide(attemptId, accept = true)
        testScheduler.advanceUntilIdle()

        // 再进一次页：同一版题目已经表过态，不该再写一条决定
        c.openReview()
        testScheduler.advanceUntilIdle()
        assertTrue(assertNotNull(c.review.plan).isEmpty)
        assertEquals(Verdict.INCORRECT, c.history.single().verdict)
        assertEquals(1, JsonReviewRepository(storage).all().size, "重复打开复核页不能反复追加决定")
    }

    @Test
    fun noPatchMeansNothingToReview() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler, applyPatch = false)
        assertEquals(0, c.review.pending, "没装过补丁就没有'答案变了'这回事")
        c.openReview()
        testScheduler.advanceUntilIdle()
        assertTrue(assertNotNull(c.review.plan).isEmpty)
    }

    @Test
    fun questionRemovedByAuthorIsReportedAsUnreviewable() = runTest {
        val storage = InMemoryTextStorage()
        // 历史指向一道路径上没有的题（例如题库被卸了）
        val orphan = correctOnQ002().copy(questionId = "q999", attemptId = "a-orphan")
        val c = controllerWith(storage, testScheduler, attempts = listOf(orphan))
        c.openReview()
        testScheduler.advanceUntilIdle()

        val plan = assertNotNull(c.review.plan)
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unreviewable, "做不了复核的记录要报数，不能安静地少一条")
    }

    @Test
    fun aFullReloadKeepsTheDecisionAndStillReportsTheRawHistory() = runTest {
        val storage = InMemoryTextStorage()
        val c = controllerWith(storage, testScheduler)
        c.openReview()
        testScheduler.advanceUntilIdle()

        c.review.decide(c.history.single().attemptId, accept = true)
        testScheduler.advanceUntilIdle()

        // 整体重读一遍（恢复备份、装扩展包、手动刷新都走这条路）。
        // 套上复核结果这一步以前在 loadAll 与 reloadHistory 里各写一行，
        // 只要漏掉其中一处，重读之后统计就会悄悄退回旧判定，而界面上看不出区别。
        c.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(Verdict.INCORRECT, c.history.single().verdict, "重读之后生效判定不能丢")
        assertEquals(0, c.review.pending, "表过态的记录重读之后也不该重新冒出来")

        // 备份概况报的是**历史文件**的条数与决定条数，不是合并后的视图
        assertEquals(1, c.backup.summary.attempts, "复核不改写历史文件，条数不该变")
        assertEquals(1, c.backup.summary.reviews)
    }
}
