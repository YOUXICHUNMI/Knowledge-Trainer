package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AddQuestionResult
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
     * 拆的是"用 `save` 加一道题"那条老路的成本：`find(bankId)` → `bank.questions + question` → `save(...)`。
     * 其中 `bank.questions` 是按需解析的，取第一道题就整库解析（十万个对象）；
     * `save()` 又把整库重新序列化写一遍（约 50MB）。所以"加一道 512 字节的题"= 整库读 + 整库写。
     *
     * `QuestionEditorController` 现在已经改走 `addQuestion`（见下面那条），这条留着当**对照组**：
     * 改完之后取一道题、搜一个词、改一道题仍然是这个代价 —— 那是 P11 剩下的活。
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
            "新增一行 $lineBytes 字节，为它整库重写了 ${questionsFile.length() / 1024} KB" +
                "（${(questionsFile.length().toDouble() / lineBytes).toInt()} 倍）",
        )
    }

    /**
     * 同一件事的另一条路：[BankRepository.addQuestion] —— 只写一行。
     *
     * 上面那条量的是 `save(整库重写)`，它必须先读回整库（十万个对象、103MB）；
     * 这条量的是"库已经在那儿了，再往末尾加一道"。
     *
     * 判据是**写盘字节数**，不是文件长度：整库重写之后文件只多出一行，长度差看不出重写，
     * 只有"这次到底写进去多少字节"才看得出。所以套一个计数的 [TextStorage]。
     */
    @Test
    fun appendingOneQuestionWritesOneLineNotTheWholeBank() = runTest {
        val root = createTempDir("kt-large-bank-append")
        val files = FileTextStorage(root)
        importAndSave(target, files, root, Mark("unused"))
        val questionsFile = File(root, questionFileOf("bench-bank"))
        val bankBytes = questionsFile.length()
        val storage = CountingStorage(files)
        val mark = Mark("large-bank-append-report.txt")
        val repository = JsonBankRepository(storage)

        val repeats = 5
        val started = System.nanoTime()
        repeat(repeats) { i ->
            val extra = Question(
                questionId = "q-append-$i",
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "第 $i 道手工新建的题：以下说法正确的是？",
                options = listOf(QuestionOption("A", "选项甲"), QuestionOption("B", "选项乙")),
                correctOptionIds = setOf("B"),
            )
            assertEquals(AddQuestionResult.Added, repository.addQuestion("bench-bank", extra))
        }
        val avgMs = (System.nanoTime() - started) / 1_000_000 / repeats
        val written = storage.writtenBytes
        mark.record("追加一道题 ×$repeats（只写一行 + 更新目录）", written / repeats, avgMs)

        val reloaded = JsonBankRepository(storage).list().single()
        assertEquals(target + repeats, reloaded.questionCount, "目录里的题数要跟着变")
        assertEquals("q-append-${repeats - 1}", reloaded.questions.last().questionId)
        assertEquals(
            Fingerprint.ofQuestions(reloaded.questions.map { it.contentFingerprint() }),
            reloaded.fingerprint,
            "增量累加出来的指纹必须等于整库重算的结果",
        )

        // 写盘量：只该是这几行加几次目录，与库有多大无关。
        // 一旦实现退化成"读出来改完再整库写回"，这里会立刻变成整个文件的大小。
        val lineBytes = questionsFile.readLines().last().toByteArray().size
        val budget = (lineBytes + 2) * repeats + 8 * 1024 // 8KB 余量给目录那几次小写入
        assertTrue(
            written < budget,
            "追加 $repeats 道题只该写约 ${lineBytes * repeats} 字节（加目录），实际写了 $written 字节" +
                "（整个库才 $bankBytes 字节）",
        )

        mark.dump()
        print(mark.render())
        println(
            "库 $bankBytes 字节；追加 $repeats 道题共写盘 $written 字节（每题 ${written / repeats}，行均 $lineBytes），" +
                "平均 ${avgMs}ms/题",
        )
    }

    /**
     * 量"这次到底写了多少字节"的装饰器。
     *
     * 用它而不是比较文件长度：整库重写之后文件也只多一行，长度差分辨不出重写与追加。
     */
    private class CountingStorage(private val delegate: TextStorage) : TextStorage {
        var writtenBytes = 0L
            private set

        override val isPersistent: Boolean get() = delegate.isPersistent

        override fun read(name: String): String? = delegate.read(name)

        override fun write(name: String, content: String) {
            writtenBytes += content.encodeToByteArray().size
            delegate.write(name, content)
        }

        override fun appendLine(name: String, line: String) {
            writtenBytes += line.encodeToByteArray().size + 1
            delegate.appendLine(name, line)
        }

        override fun delete(name: String): Boolean = delegate.delete(name)
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

        /** [elapsedOverrideMs] 用于「这一步自己已经量过了」的情形（例如循环 N 次取平均）。 */
        fun record(step: String, bytes: Long, elapsedOverrideMs: Long? = null) {
            val ms = elapsedOverrideMs ?: ((System.nanoTime() - lastAt) / 1_000_000)
            val heap = usedHeapMb()
            rows += "| $step | ${ms}ms | ${formatBytes(bytes)} | ${heap} MB |"
            // 量堆时强制 GC 了，它的时间不该算到下一步头上，
            // 否则"只读目录"会被算成两百毫秒
            lastAt = System.nanoTime()
        }

        /** 小于 1KB 时按字节显示：追加一道题只增长几百字节，写成 "0 KB" 等于说"没写盘"。 */
        private fun formatBytes(bytes: Long): String =
            if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

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
