package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextSpan
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.infrastructure.storage.SearchIndex
import morefish.knowledge.trainer.infrastructure.storage.SearchIndexBuilder
import morefish.knowledge.trainer.infrastructure.storage.matchesQuery
import morefish.knowledge.trainer.infrastructure.storage.normalizeSearchText
import morefish.knowledge.trainer.infrastructure.storage.questionFileOf
import morefish.knowledge.trainer.infrastructure.storage.searchIndexFileOf
import morefish.knowledge.trainer.infrastructure.storage.searchQuerySegments
import morefish.knowledge.trainer.infrastructure.storage.searchTailFileOf
import morefish.knowledge.trainer.infrastructure.storage.searchTermsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 倒排索引的护栏。
 *
 * 这里最要紧的一条不是"快"，而是**两条路径给出同一批题**：
 * 有索引时走候选 + 复核，没索引时全表扫描。任何一条用例如果只在一条路径上通过，
 * 那它就是没通过 —— 用户换台设备（Web 端没有随机读）就会看到不一样的搜索结果。
 */
class SearchIndexTest {

    // ---- 切词 ----

    @Test
    fun chineseIsIndexedAsOverlappingBigrams() {
        assertEquals(listOf("以下", "下说", "说法"), searchTermsOf("以下说法"))
        // 同样的内容加标点，切出来必须一模一样 —— 否则索引与查询两边会永远对不上
        assertEquals(searchTermsOf("以下说法"), searchTermsOf("以下，说法！"))
    }

    @Test
    fun caseAndFullWidthPunctuationFoldTheSameWayOnBothSides() {
        // 标点是噪声，不是词边界：中文题干里的逗号不该决定"搜得到/搜不到"
        assertEquals("以下说法", normalizeSearchText("以下，说法！"))
        assertEquals("hello world", normalizeSearchText("  Hello,  World!  "))
        assertEquals(searchTermsOf("以下说法"), searchTermsOf("以下，说法"))
        // 空白仍是词边界（英文靠它按词搜），标点不是
        assertEquals(listOf("hello", "world"), searchQuerySegments("Hello, World!"))
    }

    @Test
    fun singleCharacterSegmentsDoNotBecomeIndexTermsButStillTakePartInVerification() {
        // "第 1 题" 里的单字不产生索引项（那会让索引体积翻倍），
        // 但它们必须参与"这一行算不算命中"，否则第 37 题会被第 3 题带出来
        assertEquals(listOf("37"), searchTermsOf("第 37 题"))
        assertTrue(matchesQuery("第 37 题：以下说法正确的是？", searchQuerySegments("第 37 题")))
        assertTrue(!matchesQuery("第 3 题：以下说法正确的是？", searchQuerySegments("第 37 题")))
    }

    // ---- 落盘格式 ----

    @Test
    fun renderedIndexRoundTripsThroughParse() {
        val builder = SearchIndexBuilder()
        builder.add(0, "第 1 题：以下说法正确的是？")
        builder.add(1, "第 2 题：以下说法错误的是？")
        builder.add(2, "第 3 题：完全无关的一道题")

        val parsed = assertNotNull(SearchIndex.parse(builder.render(1234, 3)))
        assertTrue(parsed.covers(1234, 3))
        assertEquals(listOf(0, 1), parsed.candidates(searchTermsOf("以下说法")).toList())
        assertEquals(listOf(0), parsed.candidates(searchTermsOf("正确的")).toList())
        assertEquals(0, parsed.candidates(searchTermsOf("不存在的词")).size)
    }

    @Test
    fun parseRejectsAZeroDelta() {
        // 同一行里同一个词写了两次 → delta 为 0。这种表会让"按 delta 还原行号"永远原地打转，
        // 必须在解析时就整体作废，而不是悄悄少几行
        val broken = "#ktsearch 1 100 3\naa\t0,1\n"
        assertNull(SearchIndex.parse(broken))
    }

    @Test
    fun parseRejectsRowsBeyondItsHeader() {
        // 行号 5 但它声称只有 3 行 —— 说明表与文件不是同一次生成的
        assertNull(SearchIndex.parse("#ktsearch 1 100 3\naa\t5\n"))
        assertNull(SearchIndex.parse("垃圾\n"))
        assertNull(SearchIndex.parse(""))
    }

    // ---- 集成：两条路径必须一致 ----

    @Test
    fun searchUsesTheIndexWithoutReadingTheWholeFile() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(50))

        val repository = JsonBankRepository(storage)
        storage.resetReads()
        val hits = repository.searchQuestions("第 37 题", 5)

        assertEquals(1, hits.size, "只该命中第 37 题：${hits.map { it.questionId }}")
        assertEquals("q-37", hits.single().questionId)
        assertEquals(0, storage.wholeQuestionReads, "走索引的搜索不该把整份题目文件读回来")
        assertTrue(storage.rangeBytes > 0, "该读回候选那一行来复核")
    }

    /**
     * 复核候选不能"一行开一次文件"。
     *
     * 两百个候选逐个读，就是两百次 `open/close` —— 比要读的那一百多 KB 贵得多。
     * 判据用是非量而不是耗时：单段读的次数必须是 0（走了批量），整批读的次数得是"几次"
     * 而不是"两百次"。把它们改回逐行读，第一条立刻变 200。
     */
    @Test
    fun verifyingManyCandidatesReadsThemInBatchesNotOneOpenPerLine() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(200))
        val repository = JsonBankRepository(storage)

        storage.resetReads()
        val hits = repository.searchQuestions("以下说法", 200)

        assertEquals(200, hits.size, "上限 200 就该拿到 200 条")
        assertEquals(0, storage.rangeReads, "候选复核不该一行开一次文件")
        assertTrue(
            storage.spanBatches in 1..4,
            "200 个候选该在不超过 4 次批量读里读完，实际 ${storage.spanBatches} 次",
        )
        assertTrue(storage.rangeBytes > 0, "该把候选行真读回来复核")
    }

    @Test
    fun indexAndScanPathsReturnTheSameQuestions() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(60))

        val indexed = JsonBankRepository(storage)
        // 只暴露 TextStorage：拿不到"读一段"，只能整份读 + 扫描（Web / 内存后端走这条）
        val scanning = JsonBankRepository(PlainStorage(storage))

        val queries = listOf(
            "第 37 题",
            "以下说法",
            "第 5 题",
            "正确的",
            "第 1 题：以下说法",
            "完全不存在",
        )
        for (query in queries) {
            val expected = scanning.searchQuestions(query, 10).map { it.questionId }
            val actual = indexed.searchQuestions(query, 10).map { it.questionId }
            assertEquals(expected, actual, "查询「$query」在两条路径上结果不同")
        }
    }

    @Test
    fun aQuestionAddedLaterIsFoundThroughTheTail() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(3))
        val repository = JsonBankRepository(storage)

        // 先让主索引被建好（否则下面测的就不是尾巴，是重建）
        assertEquals(0, repository.searchQuestions("绝密口令", 5).size)

        val added = repository.addQuestion("b1", question("q-new", "这是一道暗号：绝密口令 Alpha"))
        assertEquals("Added", added.toString())
        val hits = repository.searchQuestions("绝密口令", 5)
        assertEquals(listOf("q-new"), hits.map { it.questionId })
        assertNotNull(storage.read(searchTailFileOf("b1")), "追加之后该有一行尾巴")
    }

    @Test
    fun theIndexIsRebuiltWhenTheFileChangesBehindIt() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(3))
        val repository = JsonBankRepository(storage)
        assertEquals(0, repository.searchQuestions("突如其来的词", 5).size)

        // 绕过仓储直接往题目文件里加一行（另一个进程、或旧版本写的）。
        // 拿仓储自己写出的行改造成新行 —— 手写 JSON 只要有一个字段名不对，
        // 这一行就变成"读得出题号、读不出内容"，测的就不是这条锁了。
        val firstLine = storage.read(questionFileOf("b1"))!!.lines().first()
        val line = firstLine
            .replace("\"questionId\":\"q-1\"", "\"questionId\":\"q-x\"")
            .replace("第 1 题：以下说法正确的是？（中文题干）", "突如其来的词")
        assertTrue(line.contains("突如其来的词"), "改造失败，这一行还是老内容")
        storage.appendLine(questionFileOf("b1"), line)

        val hits = repository.searchQuestions("突如其来的词", 5)
        assertEquals(listOf("q-x"), hits.map { it.questionId }, "文件变了就必须重建，而不是拿旧行号硬读")
    }

    @Test
    fun aCorruptIndexIsRebuiltRatherThanTrusted() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(20))
        storage.write(searchIndexFileOf("b1"), "这不是索引")

        val hits = JsonBankRepository(storage).searchQuestions("第 11 题", 5)
        assertEquals(listOf("q-11"), hits.map { it.questionId })
    }

    @Test
    fun deletingABankRemovesItsSearchFiles() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(5))
        val repository = JsonBankRepository(storage)
        repository.searchQuestions("第 2 题", 5)

        assertTrue(repository.delete("b1"))
        assertNull(storage.read(searchIndexFileOf("b1")))
        assertNull(storage.read(searchTailFileOf("b1")))
    }

    @Test
    fun theTailIsAbsorbedOnceItGrowsTooLong() = runTest {
        val storage = RecordingStorage()
        JsonBankRepository(storage).save(bank(5))
        val repository = JsonBankRepository(storage)
        repository.searchQuestions("第 2 题", 5)

        // 把尾巴写成 64KB 以上（格式合法，行号都在范围内）
        storage.write(searchTailFileOf("b1"), (0 until 8000).joinToString("") { "2\t以下,下说\n" })
        assertTrue(
            storage.read(searchTailFileOf("b1"))!!.encodeToByteArray().size > 64 * 1024,
            "夹具本身要先超过阈值",
        )

        repository.addQuestion("b1", question("q-tail", "尾巴太长该收一收了"))

        val tail = assertNotNull(storage.read(searchTailFileOf("b1")))
        assertEquals(
            1,
            tail.lineSequence().count { it.isNotBlank() },
            "重建之后尾巴只剩这次追加的那一行：${tail.take(200)}",
        )
        // 重建出来的主索引必须已经包含新行之前的所有内容
        val hits = repository.searchQuestions("以下说法", 10)
        assertEquals(5, hits.size)
    }

    // ---- 夹具 ----

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
        questions = (1..count).map { question("q-$it", "第 $it 题：以下说法正确的是？（中文题干）") },
        importedAtMillis = 1,
        sourceLabel = "test",
    )

    /** 只暴露 [TextStorage]：模拟 Web / 内存后端 —— 拿不到"读一段"的能力。 */
    private class PlainStorage(private val delegate: TextStorage) : TextStorage {
        override val isPersistent: Boolean get() = delegate.isPersistent
        override fun read(name: String): String? = delegate.read(name)
        override fun write(name: String, content: String) = delegate.write(name, content)
        override fun appendLine(name: String, line: String) = delegate.appendLine(name, line)
        override fun delete(name: String): Boolean = delegate.delete(name)
    }

    /** 内存里的文件后端，带"读了多少"的计数器。 */
    private class RecordingStorage : TextStorage, RandomAccessText {

        private val files = mutableMapOf<String, ByteArray>()

        var wholeQuestionReads = 0
            private set

        var rangeBytes = 0L
            private set

        /** 单段读过几次 —— 搜索复核候选不该走这条路（一次 = 开一次文件）。 */
        var rangeReads = 0
            private set

        /** 整批读过几次。 */
        var spanBatches = 0
            private set

        fun resetReads() {
            wholeQuestionReads = 0
            rangeBytes = 0
            rangeReads = 0
            spanBatches = 0
        }

        override val isPersistent: Boolean = false

        override fun read(name: String): String? {
            if (name.endsWith(".ndjson")) wholeQuestionReads++
            return files[name]?.decodeToString()
        }

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
            rangeReads++
            val bytes = files[name] ?: return null
            if (offset < 0 || length <= 0 || offset + length > bytes.size) return null
            rangeBytes += length
            return bytes.copyOfRange(offset.toInt(), (offset + length).toInt()).decodeToString()
        }

        override fun readSpans(name: String, spans: List<TextSpan>): List<String?> {
            spanBatches++
            val bytes = files[name] ?: return List(spans.size) { null }
            return spans.map { span ->
                if (span.offset < 0 || span.length <= 0 || span.offset + span.length > bytes.size) {
                    null
                } else {
                    rangeBytes += span.length
                    bytes.copyOfRange(span.offset.toInt(), (span.offset + span.length).toInt()).decodeToString()
                }
            }
        }
    }
}
