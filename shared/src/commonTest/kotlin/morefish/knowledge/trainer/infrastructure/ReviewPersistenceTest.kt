package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.practice.AttemptReview
import morefish.knowledge.trainer.domain.practice.ReviewDecision
import morefish.knowledge.trainer.infrastructure.storage.JsonReviewRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 复核决定的读写。
 *
 * 三个要点：
 *
 * 1. 决定是**只追加**的，推翻上一次只能再写一条；
 * 2. 坏行跳过但要出声（不能安静吞掉，否则用户会以为决定丢了）；
 * 3. 读回来的判定枚举必须还是同一套语义 —— 这个文件将来会被别的版本读。
 */
class ReviewPersistenceTest {

    private fun review(
        attemptId: String,
        decision: ReviewDecision = ReviewDecision.ACCEPT,
        from: Verdict = Verdict.CORRECT,
        to: Verdict = Verdict.INCORRECT,
        at: Long = 1_000,
    ) = AttemptReview(
        attemptId = attemptId,
        bankId = "550e8400-e29b-41d4-a716-446655440000",
        questionId = "q002",
        decision = decision,
        previousVerdict = from,
        effectiveVerdict = to,
        basisFingerprint = "2cf5290769b52058",
        decidedAtMillis = at,
    )

    @Test
    fun appendsAndReadsBack() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonReviewRepository(storage)

        repo.append(review("a1"))
        repo.append(review("a2", ReviewDecision.RESTORE, Verdict.INCORRECT, Verdict.INCORRECT, at = 2_000))

        val all = repo.all()
        assertEquals(listOf("a1", "a2"), all.map { it.attemptId })
        assertEquals(ReviewDecision.ACCEPT, all[0].decision)
        assertEquals(Verdict.CORRECT, all[0].previousVerdict)
        assertEquals(Verdict.INCORRECT, all[0].effectiveVerdict)
        assertEquals(ReviewDecision.RESTORE, all[1].decision)
        assertEquals(Verdict.INCORRECT, all[1].effectiveVerdict)
        assertEquals("2cf5290769b52058", all[0].basisFingerprint)
    }

    @Test
    fun emptyFileReadsAsNoDecision() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonReviewRepository(storage)
        assertTrue(repo.all().isEmpty())
        assertTrue(repo.loadWarnings.isEmpty())
    }

    @Test
    fun corruptLinesAreSkippedWithAWarning() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonReviewRepository(storage)
        repo.append(review("a1"))

        // 手工把半行写坏（进程被杀、磁盘满都可能留下这种文件）
        storage.appendLine(REVIEWS, """{"attemptId":"a2","decision":"ac"""")

        val all = repo.all()
        assertEquals(1, all.size, "坏行不能带走好行")
        assertEquals("a1", all.single().attemptId)
        assertTrue(repo.loadWarnings.single().contains("1 行"), "实际：${repo.loadWarnings}")

        // 原始文件没有被改写：读不出来的东西也许还能人工救回来
        assertTrue(storage.read(REVIEWS)!!.contains("""{"attemptId":"a2""""))
    }

    @Test
    fun unknownDecisionIsTreatedAsCorruptRatherThanGuessed() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonReviewRepository(storage)
        // 将来版本可能加新的决定类型；旧版本读到它必须拒绝，而不是猜成 accept
        storage.appendLine(
            REVIEWS,
            """{"attemptId":"a1","decision":"maybe","previousVerdict":"CORRECT",""" +
                """"effectiveVerdict":"INCORRECT"}""",
        )

        assertTrue(repo.all().isEmpty())
        assertEquals(1, repo.loadWarnings.size)
    }
    /** 复核决定的文件名。写死在这里是有意的：这是一份会被跨版本读取的文件，改名等于迁移。 */
    private val REVIEWS = "attempt-reviews-v1.ndjson"
}
