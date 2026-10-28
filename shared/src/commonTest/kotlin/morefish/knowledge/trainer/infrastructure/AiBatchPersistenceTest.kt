package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.infrastructure.storage.JsonAiBatchRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 批量任务清单的落盘行为。
 *
 * 重点不在"能存能读"，而在两件会让人丢工作的事：
 * **文件坏掉时不能把原文件覆盖掉**，以及**清理旧任务时不能把没跑完的也清掉**。
 */
class AiBatchPersistenceTest {

    private fun job(
        id: String,
        state: AiBatchState = AiBatchState.QUEUED,
        createdAt: Long = 1_000L,
        itemStates: List<AiBatchItemState> = listOf(AiBatchItemState.QUEUED),
    ) = AiBatchJob(
        jobId = id,
        bankId = "bank-1",
        bankTitle = "题库一",
        model = "test-model",
        locale = "zh-Hans",
        items = itemStates.mapIndexed { index, itemState ->
            AiBatchItem(
                bankId = "bank-1",
                questionId = "q$index",
                locale = "zh-Hans",
                questionRevision = 1,
                contentFingerprint = "fp$index",
                state = itemState,
                attempts = if (itemState == AiBatchItemState.DONE) 1 else 0,
                note = if (itemState == AiBatchItemState.SKIPPED) "已有讲解" else "",
                promptTokens = 100,
                completionTokens = 50,
                costMicros = 1200,
            )
        },
        state = state,
        maxCalls = 30,
        note = "共 3 道",
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt + 5,
    )

    @Test
    fun jobSurvivesARestartWithEveryField() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiBatchRepository(storage)
        repo.save(
            job(
                id = "job-a",
                state = AiBatchState.PAUSED,
                itemStates = listOf(AiBatchItemState.DONE, AiBatchItemState.FAILED, AiBatchItemState.QUEUED),
            ),
        )

        val reopened = JsonAiBatchRepository(storage).jobs().single()

        assertEquals("job-a", reopened.jobId)
        assertEquals(AiBatchState.PAUSED, reopened.state)
        assertEquals(30, reopened.maxCalls)
        assertEquals("共 3 道", reopened.note)
        assertEquals(AiBatchItemState.DONE, reopened.items[0].state)
        assertEquals(AiBatchItemState.FAILED, reopened.items[1].state)
        assertEquals(1, reopened.items[0].attempts)
        assertEquals(150, reopened.items[0].tokens)
        assertEquals(3600, reopened.spentMicros, "三项各 1200 微元，合计要能对得上")
    }

    @Test
    fun savingTheSameJobReplacesItInsteadOfDuplicating() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiBatchRepository(storage)
        repo.save(job("job-a"))
        repo.save(job("job-a", state = AiBatchState.PAUSED))

        assertEquals(1, JsonAiBatchRepository(storage).jobs().size)
        assertEquals(AiBatchState.PAUSED, repo.find("job-a")?.state)
    }

    @Test
    fun brokenFileIsReportedButNotOverwritten() = runTest {
        val storage = InMemoryTextStorage()
        storage.write("ai-batches-v1.json", "{ this is not json")

        val repo = JsonAiBatchRepository(storage)
        assertEquals(emptyList(), repo.jobs())
        assertTrue(repo.loadWarnings.single().contains("无法解析"))
        assertEquals("{ this is not json", storage.read("ai-batches-v1.json"), "坏了也不能覆盖：里面是用户排好的待办")
    }

    @Test
    fun singleBadJobIsSkippedAndCounted() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(
            "ai-batches-v1.json",
            """{"schemaVersion":1,"jobs":[
                {"jobId":"","bankId":"bank-1","state":"queued","items":[{"questionId":"q1","state":"queued"}]},
                {"jobId":"job-ok","bankId":"bank-1","state":"queued","items":[{"questionId":"q1","state":"queued"}]}
            ]}""".trimIndent(),
        )

        val repo = JsonAiBatchRepository(storage)
        val jobs = repo.jobs()

        assertEquals(1, jobs.size)
        assertEquals("job-ok", jobs.single().jobId)
        assertTrue(repo.loadWarnings.single().contains("1 个批量任务不完整"))
    }

    @Test
    fun oldFinishedJobsAreTrimmedButOpenOnesAreKept() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiBatchRepository(storage)
        // 一个还没跑完的，加上一堆早年的已完成任务
        repo.save(job("job-open", state = AiBatchState.PAUSED, createdAt = 1L))
        (1..24).forEach { index ->
            repo.save(
                job(
                    id = "job-old-$index",
                    state = AiBatchState.COMPLETED,
                    createdAt = 100L + index,
                    itemStates = listOf(AiBatchItemState.DONE),
                ),
            )
        }

        val jobs = JsonAiBatchRepository(storage).jobs()

        assertEquals(20, jobs.size)
        assertTrue(jobs.any { it.jobId == "job-open" }, "没跑完的任务被清掉，等于偷偷删了用户的待办")
        assertTrue(jobs.any { it.jobId == "job-old-24" }, "最新的完成记录应该留着")
        assertTrue(jobs.none { it.jobId == "job-old-1" }, "最老的完成记录该被腾位置")
    }

    @Test
    fun deleteRemovesOnlyTheRequestedJob() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiBatchRepository(storage)
        repo.save(job("job-a", createdAt = 1L))
        repo.save(job("job-b", createdAt = 2L))

        repo.delete("job-a")

        val jobs = JsonAiBatchRepository(storage).jobs()
        assertEquals(listOf("job-b"), jobs.map { it.jobId })
        assertNull(repo.find("job-a"))
    }
}
