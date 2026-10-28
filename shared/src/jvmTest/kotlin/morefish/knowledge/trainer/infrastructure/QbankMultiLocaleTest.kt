package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 多语言题库的导入：综合样例包（中文 6 题、英文只翻 2 题）+ 手工构造的坏包。
 *
 * 样例包是 tools/qbank 真实生成的，所以"Python 打包器 → Kotlin 读取器"这条链路里的语言维度
 * 也被一并测到，而不是只测自己拼的 JSON。
 */
class QbankMultiLocaleTest {

    private val samples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples")
    }

    private suspend fun importSample(file: String): ImportOutcome {
        val banks = JsonBankRepository(InMemoryTextStorage())
        return ImportBankUseCase(banks, clock = WallClock { 0L })
            .importQbank(File(samples, file).readBytes(), "占位", file)
    }

    // ---- 正向：样例包 ----

    @Test
    fun shippedBilingualPackageKeepsBothLocales() = runTest {
        val imported = assertIs<ImportOutcome.Imported>(importSample("advanced.qbank"))
        val bank = imported.bank

        assertEquals("zh-Hans", bank.sourceLocale)
        assertEquals(listOf("zh-Hans", "en"), bank.availableLocales)
        // 英文只翻了一部分：覆盖度必须如实反映，而不是"有 en 就算完整"
        assertEquals(2, bank.coverageOf("en").covered)
        assertEquals(6, bank.coverageOf("en").total)
        assertEquals(6, bank.coverageOf("zh-Hans").covered)
        assertTrue(bank.coverageOf("en").missing == 4)
    }

    @Test
    fun englishPracticeOnlyYieldsTranslatedQuestions() = runTest {
        val bank = assertIs<ImportOutcome.Imported>(importSample("advanced.qbank")).bank

        val english = bank.questionsIn("en", LocaleFallback.SKIP)
        assertEquals(2, english.size)
        // 抽出来的题面必须真的是英文：不能混进中文题干
        english.forEach { question ->
            assertTrue(
                question.promptMarkdown.none { it in '\u4e00'..'\u9fff' },
                "英文练习里出现了中文题干：${question.questionId}",
            )
        }
    }

    @Test
    fun fallbackModeKeepsUntranslatedQuestionsButMarksSourceLocale() = runTest {
        val bank = assertIs<ImportOutcome.Imported>(importSample("advanced.qbank")).bank

        val relaxed = bank.questionsIn("en", LocaleFallback.ORIGINAL)
        assertEquals(6, relaxed.size)
        val fromSource = relaxed.filter { it.locale == "zh-Hans" }
        assertEquals(4, fromSource.size, "没译文的 4 题应退回原文并标成源语言")
    }

    @Test
    fun translatedQuestionsCarryPerLocaleRevision() = runTest {
        val bank = assertIs<ImportOutcome.Imported>(importSample("advanced.qbank")).bank
        val question = assertNotNull(bank.questions.firstOrNull { "en" in it.translations })
        val english = assertNotNull(question.localized("en", "zh-Hans"))

        // 译文版本来自 locales/en 那一行，而不是中文那一行
        val expected = question.translations.getValue("en").contentRevision
        assertEquals(expected, english.contentRevision)
        assertTrue(english.translations.isEmpty())
    }

    // ---- 反向：翻译本身有问题时必须整包拒绝 ----

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 一份单题双语的合法包，测试里按需替换某一层做出坏包。 */
    private fun packageOf(
        structure: String,
        locales: Map<String, String>,
        supported: List<String> = (listOf("zh-Hans") + locales.keys).distinct(),
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["questions/part-0001.ndjson"] = structure.encodeToByteArray()
        locales.forEach { (locale, text) ->
            entries["locales/$locale/questions.ndjson"] = text.encodeToByteArray()
        }
        val files = entries.entries.joinToString(",") { (path, bytes) ->
            """{"path":"$path","sha256":"${Sha256.hex(bytes)}"}"""
        }
        val manifest = """
            {"schemaVersion":"1.0.0","format":"knowledge-trainer-question-bank","title":"双语样例",
             "sourceLocale":"zh-Hans","supportedLocales":[${supported.joinToString(",") { "\"$it\"" }}],
             "author":{"displayName":"测试"},"files":[$files]}
        """.trimIndent()
        return zip(linkedMapOf("manifest.json" to manifest.encodeToByteArray()) + entries)
    }

    private val structure = """{"questionId":"q1","type":"single_choice","optionIds":["o1","o2"],""" +
        """"correctOptionIds":["o1"],"grading":{"strategy":"exact-set"}}"""

    private val zh = """{"questionId":"q1","contentRevision":1,"promptMarkdown":"题干",""" +
        """"options":[{"optionId":"o1","contentMarkdown":"甲"},{"optionId":"o2","contentMarkdown":"乙"}]}"""

    private suspend fun issuesOf(bytes: ByteArray): String =
        assertIs<ImportOutcome.Failed>(
            ImportBankUseCase(JsonBankRepository(InMemoryTextStorage()), clock = WallClock { 0L })
                .importQbank(bytes, "占位", "test.qbank"),
        ).message

    @Test
    fun translationWithWrongOptionIdsIsRejected() = runTest {
        val en = """{"questionId":"q1","promptMarkdown":"Prompt",""" +
            """"options":[{"optionId":"o1","contentMarkdown":"first"}]}"""
        val message = issuesOf(packageOf(structure, linkedMapOf("zh-Hans" to zh, "en" to en)))
        assertTrue(message.contains("翻译错位"), "应报翻译错位，实际：$message")
    }

    @Test
    fun translationWithBlankPromptIsRejected() = runTest {
        val en = """{"questionId":"q1","promptMarkdown":"   ",""" +
            """"options":[{"optionId":"o1","contentMarkdown":"first"},{"optionId":"o2","contentMarkdown":"second"}]}"""
        val message = issuesOf(packageOf(structure, linkedMapOf("zh-Hans" to zh, "en" to en)))
        assertTrue(message.contains("promptMarkdown"), "应指出缺题干，实际：$message")
    }

    @Test
    fun translatedShortAnswerWithoutAnswerIsRejected() = runTest {
        val shortStructure = """{"questionId":"q2","type":"short_answer","grading":{"strategy":"strict-text"}}"""
        val shortZh = """{"questionId":"q2","promptMarkdown":"什么是实践？","answerText":"实践"}"""
        val shortEn = """{"questionId":"q2","promptMarkdown":"What is practice?"}"""
        val message = issuesOf(packageOf(shortStructure, linkedMapOf("zh-Hans" to shortZh, "en" to shortEn)))
        assertTrue(message.contains("answerText"), "应指出该语言缺标准答案，实际：$message")
    }

    @Test
    fun duplicateQuestionInOneLocaleIsRejected() = runTest {
        val doubled = listOf(zh, zh).joinToString("\n")
        val message = issuesOf(packageOf(structure, linkedMapOf("zh-Hans" to doubled)))
        assertTrue(message.contains("不止一次"), "应报同一语言里重复，实际：$message")
    }

    @Test
    fun invalidLocaleDirectoryIsRejected() = runTest {
        val message = issuesOf(packageOf(structure, linkedMapOf("zh-Hans" to zh, "12345" to zh)))
        assertTrue(message.contains("语言目录") || message.contains("语言标签"), "应报语言标签非法，实际：$message")
    }

    @Test
    fun undeclaredLocaleIsAWarningNotAFailure() = runTest {
        val bytes = packageOf(
            structure,
            linkedMapOf("zh-Hans" to zh, "en" to en),
            supported = listOf("zh-Hans"), // 故意漏写 en
        )
        val imported = assertIs<ImportOutcome.Imported>(
            ImportBankUseCase(JsonBankRepository(InMemoryTextStorage()), clock = WallClock { 0L })
                .importQbank(bytes, "占位", "test.qbank"),
        )
        // 文案能用就照常用，只在导入提示里说清清单没声明
        assertEquals(2, imported.bank.availableLocales.size)
        assertTrue(
            imported.preview.notes.any { it.contains("supportedLocales") },
            "提示里应提到清单没声明：${imported.preview.notes}",
        )
    }

    private val en = """{"questionId":"q1","promptMarkdown":"Prompt","contentRevision":2,""" +
        """"options":[{"optionId":"o1","contentMarkdown":"first"},{"optionId":"o2","contentMarkdown":"second"}],""" +
        """"explanationMarkdown":"Because."}"""
}
