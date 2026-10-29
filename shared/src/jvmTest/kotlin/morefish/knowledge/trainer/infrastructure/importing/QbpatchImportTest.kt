package morefish.knowledge.trainer.infrastructure.importing

import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.patch.BankPatchOp
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `.qbpatch` 读取器。
 *
 * 正向用 `tools/qbank` 真打出来的示例补丁（跨语言链路一起测）；
 * 反向用 `java.util.zip` 现场造包 —— 作者完全可能用别的工具手搓一个补丁，
 * Kotlin 这边必须同样拒绝，而不是"Python 那边拦过了所以放心"。
 */
class QbpatchImportTest {

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
    private val fp = "0123456789abcdef"

    private val goodQuestion = """
        {"questionId":"q9","type":"single_choice","promptMarkdown":"新增题目",
         "options":[{"optionId":"o1","contentMarkdown":"干扰项"},{"optionId":"o2","contentMarkdown":"正确答案"}],
         "correctOptionIds":["o2"]}
    """.trimIndent()

    private val goodOperations = """
        [{"op":"update","questionId":"q1","expect":"$fp","set":{"promptMarkdown":"新题干"}},
         {"op":"add","questionId":"q9","question":$goodQuestion}]
    """.trimIndent()

    private fun parse(bytes: ByteArray) = QbpatchImporter.parse(bytes, PureDeflateCodec)

    private fun assertRejected(bytes: ByteArray, code: String) {
        val result = parse(bytes)
        val issues = assertIs<QbpatchImporter.Result.Failure>(result, "应当被拒绝，实际解析成功").issues
        assertTrue(
            issues.any { it.code == code },
            "应报 $code，实际是 ${issues.map { it.code }}：${issues.map { it.message }}",
        )
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun patch(
        operations: String = goodOperations,
        bankId: String = bank,
        baseFingerprint: String = fp,
        baseRevision: Int = 1,
        targetRevision: Int = 2,
        patchId: String = "demo-v2",
        schemaVersion: String = "1.0.0",
        format: String = QbpatchImporter.FORMAT_ID,
        corruptHash: Boolean = false,
        unlisted: ByteArray? = null,
    ): ByteArray {
        // 清单只覆盖"已列出"的条目；unlisted 在算完之后才塞进去，才叫未列入
        val listed = LinkedHashMap<String, ByteArray>()
        if (corruptHash) listed["media/a.png"] = byteArrayOf(9)
        val files = listed.entries.joinToString(",") { (path, bytes) ->
            val hash = if (corruptHash) "0".repeat(64) else Sha256.hex(bytes)
            """{"path":"$path","sha256":"$hash"}"""
        }
        val entries = LinkedHashMap(listed)
        unlisted?.let { entries["media/extra.png"] = it }
        val manifest = """
            {"schemaVersion":"$schemaVersion","format":"$format","patchId":"$patchId","version":"2.0.0",
             "name":"示例补丁","bankId":"$bankId","baseRevision":$baseRevision,
             "targetRevision":$targetRevision,"baseFingerprint":"$baseFingerprint",
             "operations":$operations,"files":[$files]}
        """.trimIndent()
        return zip(linkedMapOf("manifest.json" to manifest.encodeToByteArray()) + entries)
    }

    // ---------------------------------------------------------------- 正向

    @Test
    fun parsesTheShippedExamplePatch() {
        val file = File(examples, "starter-v2.qbpatch")
        if (!file.isFile) fail("缺少示例补丁 ${file.absolutePath}，先运行 python tools/qbank/qbpatch.py build")

        val patch = assertIs<QbpatchImporter.Result.Success>(parse(file.readBytes())).patch
        assertEquals("starter-v2", patch.patchId)
        assertEquals("2.0.0", patch.version)
        assertEquals(bank, patch.bankId)
        assertEquals(1, patch.baseRevision)
        assertEquals(2, patch.targetRevision)
        // 与 Python 工具算出来的锚点必须一字不差，否则所有补丁都会变成"版本不符"
        assertEquals("827015e596399cd3", patch.baseFingerprint)

        assertEquals(3, patch.operations.size)
        assertEquals(1, patch.addedCount)
        assertEquals(2, patch.updatedCount)
        assertEquals(0, patch.removedCount)

        val q2 = patch.operations.filterIsInstance<BankPatchOp.Update>().single { it.questionId == "q002" }
        assertEquals(setOf("o1", "o2", "o3", "o5"), q2.changes.correctOptionIds)
        assertEquals(5, q2.changes.options?.size)
        // expect 是工具从旧包算出来的，不是作者手抄的
        assertEquals("2cf5290769b52058", q2.expect)

        val added = patch.operations.filterIsInstance<BankPatchOp.Add>().single()
        assertEquals(QuestionType.TRUE_FALSE, added.question.type)
        assertEquals(setOf("t"), added.question.correctOptionIds)
    }

    // ---------------------------------------------------------------- 反向

    @Test
    fun rejectsQbankRenamedToPatch() {
        assertRejected(patch(format = PackageProbe.QBANK_FORMAT_ID), "MANIFEST_INVALID")
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        assertRejected(patch(schemaVersion = "2.0.0"), "SCHEMA_VERSION_UNSUPPORTED")
    }

    @Test
    fun rejectsBankIdThatIsNotUuid() {
        assertRejected(patch(bankId = "starter-bank"), "MANIFEST_INVALID")
    }

    @Test
    fun rejectsMissingAnchor() {
        assertRejected(patch(baseFingerprint = ""), "BASE_FINGERPRINT_MISSING")
    }

    @Test
    fun rejectsAnchorThatIsNotSixteenHexChars() {
        assertRejected(patch(baseFingerprint = "sha256-is-not-our-fingerprint"), "BASE_FINGERPRINT_MISSING")
    }

    @Test
    fun rejectsRevisionGoingBackwards() {
        assertRejected(patch(baseRevision = 3, targetRevision = 3), "MANIFEST_INVALID")
    }

    @Test
    fun rejectsEmptyOperations() {
        assertRejected(patch(operations = "[]"), "PATCH_EMPTY")
    }

    @Test
    fun rejectsUnknownOperation() {
        val ops = """[{"op":"retitle","questionId":"q1","title":"新标题"}]"""
        assertRejected(patch(operations = ops), "OPERATION_INVALID")
    }

    @Test
    fun rejectsTwoOperationsOnTheSameQuestion() {
        val ops = """
            [{"op":"remove","questionId":"q1"},
             {"op":"update","questionId":"q1","set":{"promptMarkdown":"x"}}]
        """.trimIndent()
        assertRejected(patch(operations = ops), "OPERATION_CONFLICT")
    }

    @Test
    fun rejectsChangingQuestionType() {
        // 题型一变，判题策略与历史口径同时变 —— 那是换了一道题，不是改了这道题
        val ops = """[{"op":"update","questionId":"q1","set":{"type":"multiple_choice"}}]"""
        assertRejected(patch(operations = ops), "OPERATION_INVALID")
    }

    @Test
    fun rejectsChangingGradingStrategy() {
        val ops = """[{"op":"update","questionId":"q1","set":{"grading":{"strategy":"keywords"}}}]"""
        assertRejected(patch(operations = ops), "OPERATION_INVALID")
    }

    @Test
    fun rejectsEmptySet() {
        assertRejected(patch(operations = """[{"op":"update","questionId":"q1","set":{}}]"""), "OPERATION_INVALID")
    }

    @Test
    fun rejectsBadExpectFingerprint() {
        val ops = """[{"op":"remove","questionId":"q1","expect":"abc"}]"""
        assertRejected(patch(operations = ops), "OPERATION_INVALID")
    }

    @Test
    fun rejectsAddWithMismatchedId() {
        val ops = """[{"op":"add","questionId":"q10","question":$goodQuestion}]"""
        assertRejected(patch(operations = ops), "OPERATION_INVALID")
    }

    @Test
    fun rejectsAddThatIsNotSelfConsistent() {
        val broken = """
            {"questionId":"q9","type":"multiple_choice","promptMarkdown":"只有一个答案的多选",
             "options":[{"optionId":"o1","contentMarkdown":"甲"},{"optionId":"o2","contentMarkdown":"乙"}],
             "correctOptionIds":["o1"]}
        """.trimIndent()
        val ops = """[{"op":"add","questionId":"q9","question":$broken}]"""
        assertRejected(patch(operations = ops), "QUESTION_INVALID")
    }

    @Test
    fun rejectsHashMismatch() {
        assertRejected(patch(corruptHash = true, unlisted = byteArrayOf(1, 2, 3)), "HASH_MISMATCH")
    }

    @Test
    fun rejectsUnlistedFile() {
        assertRejected(patch(unlisted = byteArrayOf(1, 2, 3)), "FILE_NOT_LISTED")
    }

    @Test
    fun rejectsPackageWithoutManifest() {
        assertRejected(zip(mapOf("media/a.png" to byteArrayOf(1))), "MANIFEST_MISSING")
    }

    @Test
    fun rejectsNonZip() {
        assertRejected("""{"format":"knowledge-trainer-bank-patch"}""".encodeToByteArray(), "ZIP_INVALID")
    }
}
