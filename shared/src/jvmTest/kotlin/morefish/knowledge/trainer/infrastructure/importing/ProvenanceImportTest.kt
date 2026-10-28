package morefish.knowledge.trainer.infrastructure.importing

import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 出处声明与整包摘要。
 *
 * 正向用 `tools/qbank` 真打出来的示例包（Python 算的摘要要在 Kotlin 这边重算一致）；
 * 反向用 `java.util.zip` 现场造包 —— 用户拿到手的包完全可能绕过工具，
 * 应用端必须自己拒绝。
 */
class ProvenanceImportTest {

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples")
    }

    private val bank = "550e8400-e29b-41d4-a716-446655440000"

    private fun parse(bytes: ByteArray) = QbankImporter.parse(bytes, PureDeflateCodec)

    // ---------------------------------------------------------------- 真实示例包

    @Test
    fun readsRealPackageProvenance() {
        val bytes = File(examples, "starter.qbank").readBytes()
        val parsed = assertIs<QbankImporter.Result.Success>(parse(bytes), "示例包应当能导入").bank

        assertTrue(parsed.digestVerified, "示例包带 contentDigest 且应当核对一致")
        assertEquals(64, parsed.digest.length)
        assertEquals("Knowledge Trainer", parsed.authorName)
        assertEquals("CC-BY-4.0", parsed.license)

        assertEquals(1, parsed.sources.size, "示例包声明了一处出处")
        val source = parsed.sources.single()
        assertEquals("marx-textbook", source.sourceId)
        assertEquals("马克思主义基本原理（示例出处）", source.title)
        assertEquals("2023 年版", source.edition)
    }

    @Test
    fun readsPerQuestionOrigin() {
        val bytes = File(examples, "starter.qbank").readBytes()
        val questions = assertIs<QbankImporter.Result.Success>(parse(bytes)).bank.questions
            .associateBy { it.questionId }

        assertEquals(ProvenanceOrigin.AUTHORED, questions.getValue("q001").origin)
        assertNull(questions.getValue("q001").sourceId, "作者原创不该挂着外部出处")

        val q2 = questions.getValue("q002")
        assertEquals(ProvenanceOrigin.ADAPTED, q2.origin)
        assertEquals("marx-textbook", q2.sourceId)
        assertEquals("第一章 第二节 课后练习 3", q2.sourceLocator)

        // q003 故意什么都没标：界面必须显示"来源未标注"，而不是悄悄当成原创
        assertEquals(ProvenanceOrigin.UNSPECIFIED, questions.getValue("q003").origin)
        assertNull(questions.getValue("q003").sourceId)
    }

    // ---------------------------------------------------------------- 摘要算法

    private fun identity(
        title: String = "题库",
        author: String = "tester",
        license: String = "",
    ) = PackageDigest.Identity(
        format = "knowledge-trainer-question-bank",
        schemaVersion = "1.0.0",
        bankId = bank,
        contentRevision = 1,
        sourceLocale = "zh-Hans",
        supportedLocales = listOf("zh-Hans"),
        title = title,
        authorName = author,
        license = license,
    )

    @Test
    fun digestIgnoresFileInsertionOrder() {
        val a = mapOf("questions/part-0001.ndjson" to "A".encodeToByteArray(), "locales/x" to "B".encodeToByteArray())
        val b = LinkedHashMap<String, ByteArray>().apply {
            put("locales/x", "B".encodeToByteArray())
            put("questions/part-0001.ndjson", "A".encodeToByteArray())
        }
        // 摘要按路径排序后拼接：同一个内容换个遍历顺序不该换摘要
        assertEquals(PackageDigest.of(identity(), a), PackageDigest.of(identity(), b))
    }

    @Test
    fun digestChangesWhenContentChanges() {
        val before = mapOf("questions/part-0001.ndjson" to "A".encodeToByteArray())
        val after = mapOf("questions/part-0001.ndjson" to "B".encodeToByteArray())
        assertTrue(PackageDigest.of(identity(), before) != PackageDigest.of(identity(), after))
    }

    @Test
    fun digestCoversAuthorAndLicense() {
        val files = mapOf("questions/part-0001.ndjson" to "A".encodeToByteArray())
        val base = PackageDigest.of(identity(), files)
        assertTrue(base != PackageDigest.of(identity(author = "别人"), files), "改作者必须换摘要")
        assertTrue(base != PackageDigest.of(identity(license = "CC0"), files), "改许可必须换摘要")
        assertTrue(base != PackageDigest.of(identity(title = "另一个题库"), files), "改标题必须换摘要")
    }

    @Test
    fun digestSurvivesNewlinesInMetadata() {
        // 多行标题不能把摘要输入的"键=值"结构撑破：换行必须先被转义
        val files = mapOf("questions/part-0001.ndjson" to "A".encodeToByteArray())
        val multi = PackageDigest.of(identity(title = "第一行\n第二行"), files)
        val injected = PackageDigest.of(identity(title = "第一行\nauthor=伪造"), files)
        assertTrue(multi != injected, "标题里的换行不能让后续字段被顶掉")
        assertEquals(64, multi.length)
    }

    // ---------------------------------------------------------------- 坏包

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, value) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(value)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun questionJson(extra: String = ""): String =
        "{\"questionId\":\"q1\",\"type\":\"single_choice\",\"optionIds\":[\"o1\",\"o2\"]," +
            "\"correctOptionIds\":[\"o2\"],\"grading\":{\"strategy\":\"exact-set\"}$extra}"

    private fun localeJson(): String =
        "{\"questionId\":\"q1\",\"locale\":\"zh-Hans\",\"promptMarkdown\":\"1+1=?\"," +
            "\"options\":[{\"optionId\":\"o1\",\"contentMarkdown\":\"1\"}," +
            "{\"optionId\":\"o2\",\"contentMarkdown\":\"2\"}]}"

    /** 按真实规则造包：files 与 contentDigest 都可控，方便只坏一处。 */
    private fun pack(
        question: String = questionJson(),
        sources: String = "[]",
        digestOverride: String? = null,
        omitDigest: Boolean = false,
    ): ByteArray {
        val files = linkedMapOf(
            "questions/part-0001.ndjson" to (question + "\n").encodeToByteArray(),
            "locales/zh-Hans/questions.ndjson" to (localeJson() + "\n").encodeToByteArray(),
        )
        val fileList = files.entries.sortedBy { it.key }
            .joinToString(",") { "{\"path\":\"${it.key}\",\"sha256\":\"${Sha256.hex(it.value)}\"}" }
        val meta = StringBuilder()
        meta.append("\"format\":\"knowledge-trainer-question-bank\",")
        meta.append("\"schemaVersion\":\"1.0.0\",")
        meta.append("\"bankId\":\"$bank\",")
        meta.append("\"contentRevision\":1,")
        meta.append("\"title\":\"测试题库\",")
        meta.append("\"sourceLocale\":\"zh-Hans\",")
        meta.append("\"supportedLocales\":[\"zh-Hans\"],")
        meta.append("\"author\":{\"displayName\":\"tester\",\"contacts\":[]},")
        meta.append("\"sources\":$sources,")
        meta.append("\"files\":[$fileList]")
        val digest = digestOverride ?: PackageDigest.of(
            PackageDigest.Identity(
                format = "knowledge-trainer-question-bank",
                schemaVersion = "1.0.0",
                bankId = bank,
                contentRevision = 1,
                sourceLocale = "zh-Hans",
                supportedLocales = listOf("zh-Hans"),
                title = "测试题库",
                authorName = "tester",
                license = "",
            ),
            files,
        )
        val manifest = "{" + meta + (if (omitDigest) "" else ",\"contentDigest\":\"$digest\"") + "}"
        return zip(files + ("manifest.json" to manifest.encodeToByteArray()))
    }

    private fun assertRejected(bytes: ByteArray, code: String) {
        val result = parse(bytes)
        val issues = assertIs<QbankImporter.Result.Failure>(result, "应当被拒绝，实际解析成功").issues
        assertTrue(
            issues.any { it.code == code },
            "应报 $code，实际是 ${issues.map { it.code }}：${issues.map { it.message }}",
        )
    }

    @Test
    fun rejectsDigestMismatch() {
        // 内容改了但摘要没跟着改 —— 手工编辑后忘记重新打包的典型样子
        assertRejected(pack(digestOverride = "0".repeat(64)), "CONTENT_DIGEST_MISMATCH")
    }

    @Test
    fun rejectsMalformedDigest() {
        assertRejected(pack(digestOverride = "abc"), "MANIFEST_INVALID")
    }

    @Test
    fun oldPackageWithoutDigestStillImports() {
        val parsed = assertIs<QbankImporter.Result.Success>(parse(pack(omitDigest = true))).bank
        assertEquals("", parsed.digest)
        // 关键：没声明摘要不等于"核对过"。界面据此写"未提供"。
        assertTrue(!parsed.digestVerified)
    }

    @Test
    fun rejectsDanglingSourceReference() {
        assertRejected(
            pack(
                question = questionJson(",\"sourceId\":\"s9\""),
                sources = "[{\"sourceId\":\"s1\",\"title\":\"甲\"}]",
            ),
            "SOURCE_UNKNOWN",
        )
    }

    @Test
    fun rejectsDuplicateSourceIds() {
        assertRejected(
            pack(sources = "[{\"sourceId\":\"s1\",\"title\":\"甲\"},{\"sourceId\":\"s1\",\"title\":\"乙\"}]"),
            "SOURCE_DUPLICATE",
        )
    }

    @Test
    fun rejectsBadOriginValue() {
        assertRejected(pack(question = questionJson(",\"origin\":\"copied\"")), "ORIGIN_INVALID")
    }

    @Test
    fun missingLocatorIsAWarningNotARejection() {
        // 有出处没页码：读者核对不了，但题本身没问题。警告而不拒绝。
        val parsed = assertIs<QbankImporter.Result.Success>(
            parse(
                pack(
                    question = questionJson(",\"sourceId\":\"s1\""),
                    sources = "[{\"sourceId\":\"s1\",\"title\":\"甲\"}]",
                ),
            ),
        ).bank
        assertTrue(
            parsed.warnings.any { it.code == "SOURCE_LOCATOR_MISSING" },
            "应提示缺 sourceLocator，实际 ${parsed.warnings.map { it.code }}",
        )
        assertEquals("s1", parsed.questions.single().sourceId)
    }

    @Test
    fun importedClaimWithoutSourceIsAWarning() {
        val parsed = assertIs<QbankImporter.Result.Success>(
            parse(pack(question = questionJson(",\"origin\":\"imported\""))),
        ).bank
        assertTrue(
            parsed.warnings.any { it.code == "ORIGIN_WITHOUT_SOURCE" },
            "「抄来的」却不说从哪抄，应当提示，实际 ${parsed.warnings.map { it.code }}",
        )
    }

    @Test
    fun sourceKindAndNoteArePreserved() {
        val parsed = assertIs<QbankImporter.Result.Success>(
            parse(
                pack(
                    question = questionJson(",\"sourceId\":\"s1\",\"sourceLocator\":\"p.7\""),
                    sources = "[{\"sourceId\":\"s1\",\"title\":\"甲\",\"kind\":\"web\"," +
                        "\"url\":\"https://example.org/a\",\"license\":\"CC0\",\"note\":\"示例\"}]",
                ),
            ),
        ).bank
        val source = assertNotNull(parsed.sources.singleOrNull())
        assertEquals("web", source.kind)
        assertEquals("https://example.org/a", source.url)
        assertEquals("CC0", source.license)
        assertEquals("示例", source.note)
        assertEquals(QuestionType.SINGLE_CHOICE, parsed.questions.single().type)
    }
}
