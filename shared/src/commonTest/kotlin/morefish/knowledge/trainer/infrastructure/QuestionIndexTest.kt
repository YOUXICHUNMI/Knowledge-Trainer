package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AddQuestionResult
import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.storage.QuestionIndex
import morefish.knowledge.trainer.infrastructure.storage.indexFileOf
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.infrastructure.storage.questionFileOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「题号 → 行位置」这张旁注索引的护栏。
 *
 * 索引是**派生数据**，它的风险不是"丢了"（丢了就重建），而是**撒谎**：
 * 指到一个错的位置，然后被人当成真数据用。这里每条用例都是在试探这一点，
 * 而且都刻意用中文题干 —— 偏移按字节算，按字符算会错位，这是最容易写错的一处。
 */
class QuestionIndexTest {

    private fun question(id: String, prompt: String) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = prompt,
        options = listOf(QuestionOption("A", "甲"), QuestionOption("B", "乙")),
        correctOptionIds = setOf("A"),
    )

    private fun bank(count: Int) = QuestionBank(
        bankId = "b1",
        title = "题库",
        sourceLocale = "zh-Hans",
        questions = (1..count).map { question("q-$it", "第 $it 题：以下说法正确的是？（中文题干让字节数与字符数不同）") },
        importedAtMillis = 1,
        sourceLabel = "test",
    )

    @Test
    fun locatingAQuestionReadsTheRightBytesEvenWithMultiBytePrompts() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(50))

        val repository = JsonBankRepository(storage)
        val refs = listOf("q-1", "q-17", "q-50").map { QuestionRef("b1", it) }
        val located = repository.locate(refs)

        assertEquals(3, located.size)
        assertEquals("第 50 题：以下说法正确的是？（中文题干让字节数与字符数不同）", located.getValue(refs[2]).promptMarkdown)
        assertEquals("第 1 题：以下说法正确的是？（中文题干让字节数与字符数不同）", located.getValue(refs[0]).promptMarkdown)
    }

    @Test
    fun locateReadsOnlyTheIndexedLineRatherThanTheWholeFile() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(400))

        // 第一次：解析索引（一张小表），可接受的成本
        JsonBankRepository(storage).locate(listOf(QuestionRef("b1", "q-1")))

        storage.resetReads()
        val located = JsonBankRepository(storage).locate(listOf(QuestionRef("b1", "q-400")))

        assertEquals(1, located.size)
        val bankBytes = storage.byteLength(questionFileOf("b1"))!!
        assertTrue(
            storage.bytesRead < 2_048,
            "取一道题只该读那一行，实际读了 ${storage.bytesRead} 字节（整库 $bankBytes 字节）",
        )
    }

    @Test
    fun indexIsRebuiltWhenTheFileGrowsBehindItsBack() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(3))

        // 模拟"崩在两次写之间"：题目行已经落盘，索引还没跟上
        storage.appendLine(
            questionFileOf("b1"),
            """{"questionId":"q-late","type":"single_choice","promptMarkdown":"迟到的题","options":[{"optionId":"A","contentMarkdown":"甲"}],"correctOptionIds":["A"]}""",
        )

        val located = JsonBankRepository(storage).locate(
            listOf(QuestionRef("b1", "q-late"), QuestionRef("b1", "q-2")),
        )

        // 索引落后于文件时不能拿来用：q-2 必须找得到，而且找出来的是它自己
        assertEquals(
            "第 2 题：以下说法正确的是？（中文题干让字节数与字符数不同）",
            located.getValue(QuestionRef("b1", "q-2")).promptMarkdown,
        )
        // 那一行确实在文件里（只是目录还没承认它），所以它照旧能被定位 —— 与扫描路径的行为一致
        assertEquals("迟到的题", located.getValue(QuestionRef("b1", "q-late")).promptMarkdown)

        // 条数对不上时不落盘：下一次仍然会重建，而不是把一张"比目录多一条"的表当成可信的。
        // 盘上那份还是老的 3 条 —— 这比"写一张对不上目录的表"更诚实。
        assertEquals(3, QuestionIndex.parse(storage.read(indexFileOf("b1"))!!)?.count, "旧索引文件原样留着")
    }

    @Test
    fun aCorruptIndexIsTreatedAsSuspectNotAsTruth() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(4))

        // 把两行的题号对调：字节数没变（结构锁照样过），但位置全指错了
        val indexFile = indexFileOf("b1")
        val rows = storage.read(indexFile)!!.trim().lines().toMutableList()
        val first = rows[0]
        rows[0] = rows[1]
        rows[1] = first
        storage.write(indexFile, rows.joinToString("\n") + "\n")

        val located = JsonBankRepository(storage).locate(
            listOf(QuestionRef("b1", "q-1"), QuestionRef("b1", "q-2")),
        )

        // 内容锁发现"读到的那一行不是这一道"，于是这一库退回扫描：答案仍然必须是对的
        assertEquals(
            "第 1 题：以下说法正确的是？（中文题干让字节数与字符数不同）",
            located.getValue(QuestionRef("b1", "q-1")).promptMarkdown,
        )
        assertEquals(
            "第 2 题：以下说法正确的是？（中文题干让字节数与字符数不同）",
            located.getValue(QuestionRef("b1", "q-2")).promptMarkdown,
        )

        // 并且顺手把索引修好了：下一次是新表
        val healed = QuestionIndex.parse(storage.read(indexFile)!!)
        assertEquals(4, healed?.count, "重建后的索引应当覆盖全部题目")
        assertEquals(storage.byteLength(questionFileOf("b1")), healed?.endOffset)
    }

    @Test
    fun addingAQuestionExtendsTheIndexInsteadOfRewritingIt() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(3))
        val repository = JsonBankRepository(storage)

        val result = repository.addQuestion("b1", question("q-new", "刚加的一道题"))
        assertIs<AddQuestionResult.Added>(result)

        // 索引必须跟着长：不跟着长的话，下一次 locate 会因为结构锁不过而重建（慢，但不错）
        val index = assertNotNull(QuestionIndex.parse(storage.read(indexFileOf("b1"))!!))
        assertEquals(4, index.count)
        assertEquals(storage.byteLength(questionFileOf("b1")), index.endOffset)

        val located = repository.locate(listOf(QuestionRef("b1", "q-new")))
        assertEquals("刚加的一道题", located.getValue(QuestionRef("b1", "q-new")).promptMarkdown)

        // 重复 id 连索引都不该动
        assertIs<AddQuestionResult.DuplicateId>(repository.addQuestion("b1", question("q-new", "又来一道")))
        assertEquals(4, QuestionIndex.parse(storage.read(indexFileOf("b1"))!!)?.count)
    }

    @Test
    fun removingABankRemovesItsIndexToo() = runTest {
        val storage = SliceStorage()
        JsonBankRepository(storage).save(bank(2))

        assertTrue(JsonBankRepository(storage).delete("b1"), "第一次移除应当返回 true")
        assertNull(storage.read(questionFileOf("b1")), "题目文件该没了")
        assertNull(storage.read(indexFileOf("b1")), "索引也不该留着")
    }

    /**
     * 支持「按字节读一段」的内存存储。
     *
     * 用字节数组而不是 StringBuilder，是因为索引的偏移是 **UTF-8 字节**：
     * 用字符来近似会让这些用例全部意外通过 —— 那正是它们要抓的错。
     */
    private class SliceStorage : TextStorage, RandomAccessText {

        private val files = mutableMapOf<String, ByteArray>()

        var bytesRead = 0L
            private set

        fun resetReads() {
            bytesRead = 0
        }

        override val isPersistent: Boolean = false

        override fun read(name: String): String? = files[name]?.decodeToString()

        override fun write(name: String, content: String) {
            files[name] = content.encodeToByteArray()
        }

        override fun appendLine(name: String, line: String) {
            val existing = files[name] ?: ByteArray(0)
            val addition = (if (existing.isEmpty() || existing.last() == '\n'.code.toByte()) line else "\n$line")
                .encodeToByteArray() + byteArrayOf('\n'.code.toByte())
            files[name] = existing + addition
        }

        override fun delete(name: String): Boolean = files.remove(name) != null

        override fun byteLength(name: String): Long? = files[name]?.size?.toLong()

        override fun readRange(name: String, offset: Long, length: Int): String? {
            val bytes = files[name] ?: return null
            if (offset < 0 || length <= 0 || offset + length > bytes.size) return null
            bytesRead += length
            return bytes.copyOfRange(offset.toInt(), (offset + length).toInt()).decodeToString()
        }
    }
}
