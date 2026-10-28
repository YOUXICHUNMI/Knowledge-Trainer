package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.patch.BankRevision
import morefish.knowledge.trainer.infrastructure.storage.JsonRevisionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 修订日志的读写。
 *
 * 它记的是"哪几道题的答案什么时候变的"。这份东西一旦丢了，
 * 用户就再也说不清"我上次做错那道题时，答案是不是现在这个" ——
 * 所以除了正常读写，这里重点验证两件事：同一版本不重复、坏文件不静默抹掉。
 */
class RevisionPersistenceTest {

    private val bankA = "550e8400-e29b-41d4-a716-446655440000"
    private val bankB = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

    private fun revision(
        number: Int,
        patchId: String = "demo-v$number",
        added: Int = 1,
        answerChanged: List<String> = emptyList(),
        affected: Int = 0,
    ) = BankRevision(
        revision = number,
        patchId = patchId,
        name = "补丁 $patchId",
        atMillis = 1_000L * number,
        added = added,
        updated = 2,
        removed = 0,
        answerChangedIds = answerChanged,
        affectedAttempts = affected,
        note = "说明 $number",
    )

    @Test
    fun appendsAndReadsBackInOrder() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonRevisionRepository(storage)
        repo.append(bankA, revision(2))
        repo.append(bankA, revision(3))

        val list = repo.of(bankA)
        assertEquals(listOf(2, 3), list.map { it.revision })
        assertEquals("补丁 demo-v3", list.last().name)
        assertTrue(repo.loadWarnings.isEmpty())
    }

    @Test
    fun sameRevisionReplacesInsteadOfDuplicating() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonRevisionRepository(storage)
        repo.append(bankA, revision(2, patchId = "first"))
        // 重放同一个补丁：不该出现两条"第 2 版"，否则界面会显示两次更新
        repo.append(bankA, revision(2, patchId = "second"))

        val list = repo.of(bankA)
        assertEquals(1, list.size)
        assertEquals("second", list.single().patchId)
    }

    @Test
    fun keepsBanksSeparate() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonRevisionRepository(storage)
        repo.append(bankA, revision(2))
        repo.append(bankB, revision(7))

        assertEquals(2, repo.latest(bankA)?.revision)
        assertEquals(7, repo.latest(bankB)?.revision)
        assertNull(repo.latest("11111111-1111-1111-1111-111111111111"))

        val all = repo.latestAll()
        assertEquals(2, all[bankA]?.revision)
        assertEquals(7, all[bankB]?.revision)
    }

    @Test
    fun survivesReopening() = runTest {
        val storage = InMemoryTextStorage()
        JsonRevisionRepository(storage).append(bankA, revision(2, answerChanged = listOf("q1"), affected = 3))

        val reopened = JsonRevisionRepository(storage)
        val entry = reopened.of(bankA).single()
        assertEquals(listOf("q1"), entry.answerChangedIds)
        assertEquals(3, entry.affectedAttempts)
        assertTrue(entry.touchedHistory)
        assertEquals("说明 2", entry.note)
    }

    @Test
    fun corruptFileIsBackedUpBeforeOverwrite() = runTest {
        val storage = InMemoryTextStorage()
        storage.write("bank-revisions-v1.json", "{ this is not json")
        val repo = JsonRevisionRepository(storage)

        // 读的时候是空的，但要说出来
        assertTrue(repo.of(bankA).isEmpty())
        assertTrue(repo.loadWarnings.single().contains("无法解析"))

        // 写之前先把原文另存 —— 这份记录重建不出来
        repo.append(bankA, revision(2))
        assertEquals("{ this is not json", storage.read("bank-revisions-v1.json.corrupt"))
        assertEquals(2, JsonRevisionRepository(storage).of(bankA).single().revision)
    }

    @Test
    fun emptyStorageIsNotAnError() = runTest {
        val repo = JsonRevisionRepository(InMemoryTextStorage())
        assertTrue(repo.of(bankA).isEmpty())
        assertTrue(repo.latestAll().isEmpty())
        assertTrue(repo.loadWarnings.isEmpty())
    }
}
