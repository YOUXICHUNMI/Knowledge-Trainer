package morefish.knowledge.trainer.infrastructure.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.patch.BankPatchOp
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import java.io.File

/**
 * 指纹与制包工具的一致性守卫。
 *
 * `.qbpatch` 的 `baseFingerprint` 由 Python 工具算出，应用端在导入补丁时用 Kotlin 重算一遍比对。
 * 两边算法一旦漂移，所有补丁在用户那儿都会变成「版本不符」，而作者那边完全看不出问题 ——
 * 所以这里拿同一份示例包做锚点：Python 那边的 golden 值写在
 * `tools/qbank/tests/test_fingerprint.py`，两边必须给出同一个数。
 *
 * 找不到示例包时跳过并打印 —— 这个测试守的是"两套实现一致"，
 * 不该因为换了个构建目录就让整个测试套件变红。
 */
class FingerprintParityTest {

    private fun repoFile(relative: String): File {
        // jvmTest 的工作目录是模块目录（shared/），仓库根在上一级
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        return candidates.firstOrNull { it.isFile } ?: File("")
    }

    @Test
    fun starterPackageFingerprintMatchesPythonTool() {
        val file = repoFile("tools/qbank/examples/starter.qbank")
        if (!file.isFile) {
            println("跳过：找不到 ${file.absoluteFile}（这个测试需要在仓库内运行）")
            return
        }
        val parsed = QbankImporter.parse(file.readBytes(), PureDeflateCodec)
        val bank = when (parsed) {
            is QbankImporter.Result.Success -> parsed.bank
            is QbankImporter.Result.Failure -> {
                println("示例包解析失败：${parsed.issues.map { it.code to it.message }}")
                assertTrue(false, "示例包应当能通过校验")
                return
            }
        }
        val questions = bank.questions

        // golden 值来自 `python qbpatch.py fingerprint examples/starter.qbank`
        assertEquals("fe59fc0696f2f5c9", Fingerprint.ofQuestions(questions.map { it.contentFingerprint() }))
        assertEquals(
            listOf(
                "q001" to "7a946af22e6b29e0",
                "q002" to "2cf5290769b52058",
                "q003" to "808549b3a724fbab",
            ),
            questions.map { it.questionId to it.contentFingerprint() },
        )
    }

    @Test
    fun samplePatchParsesAndMatchesLocalBank() = runBlocking {
        val patch = repoFile("tools/qbank/examples/starter-v2.qbpatch")
        val qbank = repoFile("tools/qbank/examples/starter.qbank")
        if (!patch.isFile || !qbank.isFile) {
            println("跳过：示例补丁还没生成")
            return@runBlocking
        }
        val parsedBank = QbankImporter.parse(qbank.readBytes(), PureDeflateCodec)
        assertTrue(parsedBank is QbankImporter.Result.Success)
        val bank = (parsedBank as QbankImporter.Result.Success).bank

        val outcome = QbpatchImporter.parse(patch.readBytes(), PureDeflateCodec)
        assertTrue(outcome is QbpatchImporter.Result.Success, "示例补丁应当能通过校验：$outcome")
        val doc = (outcome as QbpatchImporter.Result.Success).patch

        // 工具算出来的锚点必须和本地题库对得上 —— 这就是"这份补丁是给我这一版用的"的判定
        assertEquals(bank.contentRevision, doc.baseRevision)
        assertEquals(
            Fingerprint.ofQuestions(bank.questions.map { it.contentFingerprint() }),
            doc.baseFingerprint,
            "补丁的基础指纹与本机题库不符，说明 Python 与 Kotlin 的指纹算法漂移了",
        )

        // 逐题 expect 也要对得上：作者没手抄这些值，是工具从旧包里算的
        val expected = doc.operations.filterIsInstance<BankPatchOp.Update>()
        assertEquals(2, expected.size, "示例补丁改两道题")
        expected.forEach { op ->
            val target = bank.questions.first { it.questionId == op.questionId }
            assertEquals(target.contentFingerprint(), op.expect, "${op.questionId} 的 expect 与旧包不符")
        }
    }
}
