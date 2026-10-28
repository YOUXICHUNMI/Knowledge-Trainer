package morefish.knowledge.trainer.infrastructure.importing

import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipFormatException
import morefish.knowledge.trainer.infrastructure.archive.ZipReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * .qbank 导入的端到端测试。
 *
 * 数据源是 tools/qbank 真实生成的样例包 —— 不是手工拼的 JSON。
 * 这样"Python 打包器 → Kotlin 读取器"这条跨语言链路本身也被测到了。
 */
class QbankImportTest {

    /**
     * Gradle 的测试工作目录是模块目录（shared/），而样例包在工程根的 tools/ 下。
     * 逐级向上找到包含 tools/qbank/examples 的目录，避免写死相对层级。
     */
    private val toolDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        File(System.getProperty("user.dir"), "tools/qbank/examples")
    }

    private fun packageBytes(name: String): ByteArray {
        val f = File(toolDir, name)
        if (!f.isFile) fail("找不到样例包 ${f.absolutePath}；请先运行 tools/qbank 的 build_qbank.py")
        return f.readBytes()
    }

    // ---- SHA-256 自身的正确性（纯 Kotlin 实现，必须和 JVM 一致）----

    @Test
    fun sha256MatchesJvmForKnownVectors() {
        val cases = listOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, Sha256.hex(input.encodeToByteArray()), "SHA-256($input)")
        }
        // 与平台实现交叉验证（避免只跟自己的固定值对齐）
        val long = ByteArray(100_000) { (it * 31 % 251).toByte() }
        val jvm = java.security.MessageDigest.getInstance("SHA-256").digest(long)
            .joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        assertEquals(jvm, Sha256.hex(long), "10 万字节随机数据应与 JVM MessageDigest 一致")
    }

    // ---- 正向：真实样例包能被读出来 ----

    @Test
    fun importsMinimalStarterPackage() {
        val bytes = packageBytes("starter.qbank")
        assertTrue(QbankImporter.isQbank(bytes), "按 ZIP magic 应识别为包")

        val parsed = when (val r = QbankImporter.parse(bytes, PureDeflateCodec)) {
            is QbankImporter.Result.Success -> r.bank
            is QbankImporter.Result.Failure -> fail("解析失败：${r.issues}")
        }

        assertEquals("KT-QBF 最小示例题库", parsed.title)
        assertEquals("1.0.0", parsed.schemaVersion)
        assertEquals(3, parsed.questions.size)
        assertEquals(1, parsed.questions.count { it.type.wireName == "single_choice" })
        assertEquals(1, parsed.questions.count { it.type.wireName == "multiple_choice" })
        assertEquals(1, parsed.questions.count { it.type.wireName == "short_answer" })

        val single = parsed.questions.first { it.questionId == "q001" }
        assertEquals(setOf("o2"), single.correctOptionIds)
        assertEquals(listOf("o1", "o2", "o3"), single.options.map { it.optionId }, "应保持作者给出的选项顺序")

        val multi = parsed.questions.first { it.questionId == "q002" }
        assertEquals(setOf("o1", "o2", "o3"), multi.correctOptionIds)

        val short = parsed.questions.first { it.questionId == "q003" }
        assertEquals("实践是人类能动地改造世界的客观物质活动。", short.answerText)
    }

    @Test
    fun importsP4ExtensionsPackage() {
        val bytes = packageBytes("p4-extensions.qbank")
        val parsed = when (val r = QbankImporter.parse(bytes, PureDeflateCodec)) {
            is QbankImporter.Result.Success -> r.bank
            is QbankImporter.Result.Failure -> fail("解析失败：${r.issues}")
        }

        assertEquals(2, parsed.questions.size)

        // 默知识点：关键词规则随导入保留
        val kw = parsed.questions.first { it.questionId == "k001" }
        val kwSpec = assertIs<GradingSpec.Keywords>(kw.grading)
        assertEquals(listOf("实践", "能动", "改造"), kwSpec.required)
        assertEquals(listOf("被动"), kwSpec.forbidden)

        // 语义重组：correctOptionIds 书写顺序即标准次序
        val ord = parsed.questions.first { it.questionId == "r001" }
        assertEquals(QuestionType.ORDERING, ord.type)
        val ordSpec = assertIs<GradingSpec.Ordering>(ord.grading)
        assertEquals(listOf("f1", "f2", "f3"), ordSpec.canonical)
        assertEquals(listOf(listOf("f2", "f1", "f3")), ordSpec.alternatives)
        assertEquals(4, ord.options.size, "干扰片段仍在选项里")
        assertEquals(false, ord.shuffleAllowed, "重组题片段展示顺序固定，不允许打乱")
    }

    @Test
    fun importsAdvancedPackageWithPartialLocales() {        val bytes = packageBytes("advanced.qbank")
        val parsed = when (val r = QbankImporter.parse(bytes, PureDeflateCodec)) {
            is QbankImporter.Result.Success -> r.bank
            is QbankImporter.Result.Failure -> fail("解析失败：${r.issues}")
        }

        assertEquals(6, parsed.questions.size)
        assertEquals("zh-Hans", parsed.sourceLocale)
        assertTrue(parsed.assetCount > 0, "应统计到媒体资源")

        // 部分语言覆盖只产生警告，不影响导入
        val en = parsed.locales.first { it.locale == "en" }
        assertEquals(2, en.covered)
        assertEquals(6, en.total)
        assertTrue(parsed.warnings.any { it.code == "LOCALE_PARTIAL" })
        // 被题目引用的媒体应随解析提取（tcp-handshake.png），且不再有"不渲染"类警告
        assertEquals(listOf("tcp-handshake"), parsed.media.map { it.assetId })
        assertTrue(parsed.warnings.none { it.code.startsWith("MEDIA_") }, "被引用的媒体不产生警告")
        assertTrue(parsed.warnings.any { it.code == "ENHANCEMENT_IGNORED" }, "增强资料暂不使用，必须明确告知")
    }

    @Test
    fun latexAndMarkdownSurviveIntact() {
        val bytes = packageBytes("advanced.qbank")
        val parsed = (QbankImporter.parse(bytes, PureDeflateCodec) as QbankImporter.Result.Success).bank
        val math = parsed.questions.first { it.questionId == "math-derivative" }
        assertTrue(math.promptMarkdown.contains("\$f(x)=x^2\$"), "行内公式必须原样保留")
        assertTrue(math.explanationMarkdown!!.contains("\$\$"), "块级公式必须原样保留")
        assertEquals("6", math.answerText, "简答答案不能被公式渲染影响")
    }

    // ---- 反向：坏包必须在 Kotlin 侧也被拒绝 ----

    @Test
    fun rejectsNonZip() {
        val r = QbankImporter.parse("{\"questions\":[]}".encodeToByteArray(), PureDeflateCodec)
        assertIs<QbankImporter.Result.Failure>(r)
        assertEquals("ZIP_INVALID", r.issues.first().code)
    }

    @Test
    fun rejectsTruncatedZip() {
        val bytes = packageBytes("starter.qbank")
        val cut = bytes.copyOfRange(0, bytes.size / 2)
        val r = QbankImporter.parse(cut, PureDeflateCodec)
        assertIs<QbankImporter.Result.Failure>(r)
    }

    @Test
    fun rejectsTamperedContentByHash() {
        // 条目是 deflate 压缩的，直接在包字节里搜原文是找不到的。
        // 正确做法：先正常解出条目，改掉明文，再用相同压缩方法重新打包 ——
        // 这样 manifest 里的 sha256 必然与内容不符。
        val original = packageBytes("starter.qbank")
        val entries = ZipReader.entries(original)
        val qEntry = entries.first { it.name.startsWith("questions/") }
        val plain = ZipReader.read(original, qEntry, PureDeflateCodec).decodeToString()
        val tamperedText = plain.replace("\"correctOptionIds\":[\"o2\"]", "\"correctOptionIds\":[\"o1\"]")
        assertTrue(tamperedText != plain, "应成功改到正确答案")

        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { out ->
            entries.filter { !it.isDirectory }.forEach { e ->
                out.putNextEntry(java.util.zip.ZipEntry(e.name))
                val data = if (e.name == qEntry.name) {
                    tamperedText.encodeToByteArray()
                } else {
                    ZipReader.read(original, e, PureDeflateCodec)
                }
                out.write(data)
                out.closeEntry()
            }
        }

        val r = QbankImporter.parse(buf.toByteArray(), PureDeflateCodec)
        assertIs<QbankImporter.Result.Failure>(r)
        assertTrue(
            r.issues.any { it.code == "HASH_MISMATCH" },
            "改了内容却没更新清单摘要，必须报 HASH_MISMATCH；实际：${r.issues.map { it.code }}",
        )
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val bytes = rewriteManifest(packageBytes("starter.qbank")) { it.replace("\"1.0.0\"", "\"2.0.0\"") }
        val r = QbankImporter.parse(bytes, PureDeflateCodec)
        assertIs<QbankImporter.Result.Failure>(r)
        assertEquals("SCHEMA_VERSION_UNSUPPORTED", r.issues.first().code)
    }

    @Test
    fun zipReaderRejectsPathTraversal() {
        val bad = buildZip(mapOf("../evil.txt" to "x".encodeToByteArray()))
        val e = runCatching { ZipReader.entries(bad) }.exceptionOrNull()
        assertIs<ZipFormatException>(e)
        assertTrue(e.message!!.contains("上级目录") || e.message!!.contains("非法"))
    }

    @Test
    fun zipReaderRejectsAbsolutePath() {
        val bad = buildZip(mapOf("/etc/passwd" to "x".encodeToByteArray()))
        val e = runCatching { ZipReader.entries(bad) }.exceptionOrNull()
        assertIs<ZipFormatException>(e)
    }

    // ---- 工具 ----

    /** 重新打一个包，同时改写 manifest —— 用来构造"版本不兼容"这类合法 ZIP 但语义不合规的包。 */
    private fun rewriteManifest(original: ByteArray, edit: (String) -> String): ByteArray {
        val entries = ZipReader.entries(original)
        val manifestEntry = entries.first { it.name == "manifest.json" }
        val manifestText = ZipReader.read(original, manifestEntry, PureDeflateCodec).decodeToString()
        val updated = edit(manifestText)

        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            out.write(updated.encodeToByteArray())
            out.closeEntry()
            entries.filter { it.name != "manifest.json" && !it.isDirectory }.forEach { e ->
                out.putNextEntry(java.util.zip.ZipEntry(e.name))
                out.write(ZipReader.read(original, e, PureDeflateCodec))
                out.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    private fun buildZip(files: Map<String, ByteArray>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { out ->
            files.forEach { (name, data) ->
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
