package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.application.port.ImportParseResult
import morefish.knowledge.trainer.infrastructure.importing.JsonQuestionImporter
import morefish.knowledge.trainer.infrastructure.storage.BANKS_FILE
import morefish.knowledge.trainer.infrastructure.storage.DeferredQuestions
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.infrastructure.storage.questionFileOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 大题库承载能力实测。
 *
 * 默认跑 5000 题（进入日常测试，几秒内结束）；设 `KT_BENCH=1` 跑十万题，
 * 数字会写到 `build/large-bank-report.txt` —— 文档里引用的就是这份实测结果。
 *
 * 这里**不写死耗时上限**：不同机器差异太大，写死一个数只会变成"把它改大让测试过"。
 * 硬约束只放在正确性与结构上：题数、指纹、目录不解析题目、文件布局。
 */
class LargeBankTest {

    private val target = if (System.getenv("KT_BENCH") == "1") 100_000 else 5_000

    @Test
    fun largeBankImportsStoresAndReloadsWithoutParsingOnList() = runTest {
        val root = createTempDir("kt-large-bank")
        val storage = FileTextStorage(root)
        val mark = Mark()

        // 导入 + 写入放在单独的作用域里：出了这个函数，那十万个题目对象就没人引用了。
        // 否则下面量到的堆是"导入结果 + 重载结果"两份，对读者没有参考价值。
        val fingerprint = importAndSave(target, storage, root, mark)

        val questionsFile = File(root, questionFileOf("bench-bank"))
        val catalogFile = File(root, BANKS_FILE)
        assertTrue(catalogFile.length() < 4_096, "目录只该有元数据，实际 ${catalogFile.length()} 字节")

        // 1) 列表只读目录：十万题不该在打开首页时被解析
        val listed = JsonBankRepository(storage).list().single()
        mark.record("重新打开（只读目录）", catalogFile.length())

        assertEquals(target, listed.questionCount, "题数来自目录")
        assertEquals(mapOf(QuestionType.SINGLE_CHOICE to target), listed.typeBreakdown(), "题型分布也来自目录")

        val deferred = listed.questions
        assertIs<DeferredQuestions>(deferred, "列表返回的题目应当是按需解析的")
        assertFalse(deferred.isLoaded, "只列表不该解析题目文件")

        // 2) 真要题面时才解析
        val questions = listed.questions
        assertEquals(target, questions.size)
        assertEquals("q-000001", questions.first().questionId)
        assertEquals("第 $target 题：以下说法正确的是？", questions.last().promptMarkdown)
        assertTrue(deferred.isLoaded, "取过题面之后应已缓存，同一场练习不该反复解析")
        mark.record("取题面（触发整库解析）", questionsFile.length())

        // 顺手核对文件布局：一行一题（这一步要读 50MB 文件，所以放在计时之后）
        assertEquals(target, questionsFile.readLines().count { it.isNotBlank() }, "每库文件应当一行一题")

        // 3) 内容零丢失：把读回来的题重新算指纹，必须与写入时一致
        assertEquals(fingerprint, listed.fingerprint, "指纹必须跨重启稳定")
        assertEquals(
            fingerprint,
            Fingerprint.ofQuestions(questions.map { it.contentFingerprint() }),
            "重算指纹不一致 = 有字段在往返中丢了",
        )

        mark.dump()
        print(mark.render())
    }

    @Test
    fun legacyInlineBankFileIsMigratedOnRead() = runTest {
        val root = createTempDir("kt-large-bank-migrate")
        val storage = FileTextStorage(root)

        // 旧布局：题目内嵌在 banks-v1.json 里，schemaVersion = 1
        storage.write(
            BANKS_FILE,
            """
            {"schemaVersion":1,"banks":[{"bankId":"old","title":"旧题库","sourceLocale":"zh-CN",
            "importedAtMillis":1,"sourceLabel":"legacy","questions":[
            {"questionId":"q1","type":"single_choice","promptMarkdown":"一加一等于？",
             "options":[{"optionId":"A","contentMarkdown":"2"},{"optionId":"B","contentMarkdown":"3"}],
             "correctOptionIds":["A"]},
            {"questionId":"q2","type":"short_answer","promptMarkdown":"说出你的名字","answerText":"无名"}]}]}
            """.trimIndent(),
        )

        val repository = JsonBankRepository(storage)
        val bank = repository.list().single()

        assertEquals(2, bank.questionCount)
        assertEquals("旧题库", bank.title)
        assertEquals(mapOf(QuestionType.SINGLE_CHOICE to 1, QuestionType.SHORT_ANSWER to 1), bank.typeBreakdown())
        assertEquals(listOf("q1", "q2"), bank.questions.map { it.questionId })
        assertTrue(
            repository.loadWarnings.any { it.contains("存储已升级") },
            "迁移是要告诉用户的，不该悄悄发生：${repository.loadWarnings}",
        )

        // 迁移后题目落到自己的文件里，一行一题
        val questionsFile = File(root, questionFileOf("old"))
        assertTrue(questionsFile.isFile, "迁移后应存在每库题目文件")
        assertEquals(2, questionsFile.readLines().count { it.isNotBlank() })

        // 再读一次不该再迁移，也不该报警
        val again = JsonBankRepository(storage)
        assertEquals(2, again.list().single().questionCount)
        assertTrue(again.loadWarnings.isEmpty())
    }

    /**
     * 日常写操作的代价：在十万题的库上加**一道**题，会牵动整库。
     *
     * 走的不是合成路径，就是用户点「保存」的那条：
     * `QuestionEditorController.add()` = `find(bankId)` → `bank.questions + question` → `save(...)`。
     * 其中 `bank.questions` 是按需解析的，取第一道题就整库解析（十万个对象）；
     * `save()` 又把整库重新序列化写一遍（约 50MB）。所以"加一道 400 字节的题"= 整库读 + 整库写。
     *
     * 拆成几段单独量，是因为 P11 的 SQLite 要解决的正是**每一段各自**的问题：
     * 取一道题该变成 O(log n)、写一道题该变成"只写一行"。这里量到的数字是那件事的对照组。
     *
     * 同样不写死耗时上限。硬约束放在"改完真的读得回来、其余题没被改坏、指纹跟着内容走"。
     */
    @Test
    fun addingOneQuestionToLargeBankReadsAndRewritesEverything() = runTest {
        val root = createTempDir("kt-large-bank-writes")
        val storage = FileTextStorage(root)
        // 准备阶段的导入/写入不属于"日常操作"，记进这个临时 Mark（不写盘）
        val fingerprintBefore = importAndSave(target, storage, root, Mark("unused"))

        val mark = Mark("large-bank-writes-report.txt")
        val repository = JsonBankRepository(storage)
        val questionsFile = File(root, questionFileOf("bench-bank"))
        val catalogFile = File(root, BANKS_FILE)
        val bankBytes = questionsFile.length()

        // 1) 精确取一道题：图谱刷新、搜索结果点进去都属于这类
        val probe = target / 2
        val ref = QuestionRef("bench-bank", "q-" + probe.toString().padStart(6, '0'))
        val located = repository.locate(listOf(ref))
        mark.record("精确取一道题（读整份文件 + 扫描到该行）", bankBytes)
        assertEquals("第 $probe 题：以下说法正确的是？", located.getValue(ref).promptMarkdown)

        // 2) 搜一个只命中一道题的词，且这一道在文件末尾（最坏情况）
        val hits = repository.searchQuestions("第 $target 题", 5)
        mark.record("搜题干（全表扫描）", bankBytes)
        assertEquals(1, hits.size, "只该命中最后一道题：${hits.map { it.questionId }}")

        // 3) 加一道题 —— 把真实路径拆成两段分别量
        val listed = repository.find("bench-bank")!!
        mark.record("找到题库（只读目录）", catalogFile.length())

        val loaded = listed.questions.toList()
        assertEquals(target, loaded.size)
        mark.record("读回整库（加一题必须先全读）", bankBytes)

        // 3b) 为了让目录里的指纹不说谎，写库时还要把整库内容哈希一遍。
        // 这一步是 P11 的隐形天花板：SQLite 写一行是 O(1)，但"整库指纹"本身是 O(n)，
        // 不换成增量结构的话，写一道题的成本永远下不到"只写一行"那么多。
        Fingerprint.ofQuestions(loaded.map { it.contentFingerprint() })
        mark.record("重算整库指纹（save 内部还要再做一遍）", bankBytes)

        val added = Question(
            questionId = "q-new",
            type = QuestionType.SINGLE_CHOICE,
            promptMarkdown = "手工新建的一道题：以下说法正确的是？",
            options = listOf(QuestionOption("A", "选项甲"), QuestionOption("B", "选项乙")),
            correctOptionIds = setOf("B"),
        )
        repository.save(
            listed.copy(questions = loaded + added, contentRevision = listed.contentRevision + 1),
        )
        mark.record("重写整库（新增一行 → 整个文件）", questionsFile.length())

        // 4) 改完必须读得回来，且只多了那一题
        val reopened = JsonBankRepository(storage).list().single()
        assertEquals(target + 1, reopened.questionCount, "目录里的题数要跟着变")
        assertEquals("q-000001", reopened.questions.first().questionId, "原有题目的顺序不该被打乱")
        assertEquals("q-new", reopened.questions.last().questionId)
        assertEquals(
            Fingerprint.ofQuestions(reopened.questions.map { it.contentFingerprint() }),
            reopened.fingerprint,
            "目录里的指纹必须等于实际内容的指纹 —— 否则「这个库是不是导入过」从这一刻开始就答错了",
        )
        assertNotEquals(fingerprintBefore, reopened.fingerprint, "内容变了，指纹必须跟着变")

        mark.dump()
        print(mark.render())
        val lineBytes = questionsFile.readLines().last().toByteArray().size
        println(
            "新增一行 ${lineBytes} 字节，为它重写了 ${questionsFile.length() / 1024} KB" +
                "（${(questionsFile.length().toDouble() / lineBytes).toInt()} 倍）",
        )
    }

    // ---- 造数据 ----

    private suspend fun importAndSave(count: Int, storage: TextStorage, root: File, mark: Mark): String {
        val importer = JsonQuestionImporter()
        val raw = buildNdjson(count)
        mark.record("生成 $count 题 NDJSON", raw.length.toLong())

        val result = importer.parse(raw)
        assertIs<ImportParseResult.Ok>(result)
        mark.record("导入解析", raw.length.toLong())

        val preview = result.preview
        assertEquals(count, preview.accepted.size, "全部题目都应通过校验")
        assertTrue(preview.rejected.isEmpty(), "不该有被拒的题目：${preview.notes}")

        val bank = QuestionBank(
            bankId = "bench-bank",
            title = "大题库实测",
            sourceLocale = "zh-CN",
            questions = preview.accepted,
            importedAtMillis = 1_700_000_000_000,
            sourceLabel = "bench",
        )
        JsonBankRepository(storage).save(bank)
        mark.record("写入题库", File(root, questionFileOf(bank.bankId)).length())
        return bank.fingerprint
    }

    private fun buildNdjson(count: Int): String {
        val builder = StringBuilder(count * 220)
        for (i in 1..count) {
            builder.append("""{"questionId":"q-""")
                .append(i.toString().padStart(6, '0'))
                .append("""","type":"single_choice","question":"第 """)
                .append(i)
                .append(""" 题：以下说法正确的是？","A":"选项甲的说明文本","B":"选项乙的说明文本",""")
                .append(""""C":"选项丙的说明文本","D":"选项丁的说明文本","answer":"B",""")
                .append(""""explanation":"解析：这里放一点点解释性的文字，长度接近真实题库的平均水平。"}""")
                .append('\n')
        }
        return builder.toString()
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ---- 记录 ----

    private class Mark(private val reportName: String = "large-bank-report.txt") {
        private val rows = mutableListOf<String>()
        private var lastAt = System.nanoTime()

        fun record(step: String, bytes: Long) {
            val ms = (System.nanoTime() - lastAt) / 1_000_000
            val heap = usedHeapMb()
            rows += "| $step | ${ms}ms | ${bytes / 1024} KB | ${heap} MB |"
            // 量堆时强制 GC 了，它的时间不该算到下一步头上，
            // 否则"只读目录"会被算成两百毫秒
            lastAt = System.nanoTime()
        }

        fun render(): String = buildString {
            appendLine()
            appendLine("大题库实测：")
            appendLine("| 阶段 | 耗时 | 数据量 | 驻留堆 |")
            appendLine("|---|---|---|---|")
            rows.forEach { appendLine(it) }
        }

        fun dump() {
            runCatching { File("build/$reportName").writeText(render()) }
        }

        /**
         * 驻留堆（先 GC 再量）：不 GC 的话量到的全是上一阶段留下的垃圾，
         * 读者会以为"打开一个题库要 500MB"。
         */
        private fun usedHeapMb(): Long {
            System.gc()
            val runtime = Runtime.getRuntime()
            return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        }
    }
}
