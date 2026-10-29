package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.application.usecase.PatchIntake
import morefish.knowledge.trainer.application.usecase.PatchOutcome
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.patch.PatchApply
import morefish.knowledge.trainer.domain.patch.PatchChangeKind
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.importing.QbankImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 补丁只改出处的情况。
 *
 * 这是最容易写错的一条路径：出处**不进内容指纹**，所以如果"有没有变"只按指纹判断，
 * 补丁会报告"没有变化"，作者的修复被静默丢弃 —— 而用户只看到"不需要更新"。
 *
 * 用真实示例包 + 现场造的补丁：补丁里只有一条 `update`，`set` 里只有出处三个字段。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvenancePatchTest {

    private val bank = "550e8400-e29b-41d4-a716-446655440000"
    private var now = 1_900_000_000_000L
    private val clock = WallClock { now }

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples")
    }

    private fun starterBytes(): ByteArray = File(examples, "starter.qbank").readBytes()

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

    /** 只改 q003 的出处。expect 用本地真实指纹，模拟作者的 build 工具会算出来的值。 */
    private fun provenanceOnlyPatch(expect: String, revision: Int = 2): ByteArray {
        val manifest = """
            {"schemaVersion":"1.0.0","format":"knowledge-trainer-bank-patch",
             "patchId":"prov-only","version":"2.0.0","name":"补出处",
             "bankId":"$bank","baseRevision":1,"targetRevision":$revision,
             "baseFingerprint":"827015e596399cd3",
             "operations":[{"op":"update","questionId":"q003","expect":"$expect",
               "set":{"origin":"adapted","sourceId":"marx-textbook","sourceLocator":"第二章 第一节"}}],
             "files":[]}
        """.trimIndent()
        return zip(linkedMapOf("manifest.json" to manifest.encodeToByteArray()))
    }

    private suspend fun loaded(storage: InMemoryTextStorage, scheduler: TestCoroutineScheduler): AppController {
        val deps = AppDependencies(storage, clock)
        assertIs<ImportOutcome.Imported>(
            ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test"),
        )
        val controller = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        controller.refresh()
        scheduler.advanceUntilIdle()
        return controller
    }

    private fun q003Fingerprint(): String {
        val parsed = assertIs<QbankImporter.Result.Success>(QbankImporter.parse(starterBytes(), PureDeflateCodec))
        return parsed.bank.questions.single { it.questionId == "q003" }.contentFingerprint()
    }

    @Test
    fun provenanceOnlyChangeIsAppliedNotSilentlyDropped() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        val before = c.banks.single().questionBy("q003")
        assertEquals(ProvenanceOrigin.UNSPECIFIED, before.origin)

        c.openPatch(provenanceOnlyPatch(q003Fingerprint()), "prov.qbpatch")
        testScheduler.advanceUntilIdle()
        val intake = assertIs<PatchIntake.Ready>(c.patchIntake)
        val plan = assertIs<PatchApply.Ok>(intake.plan)
        assertEquals(1, plan.report.entries.size)
        assertEquals(
            PatchChangeKind.PROVENANCE,
            plan.report.entries.single().kind,
            "只改出处不该被算成「题面变更」——那会让人以为这道题被改过",
        )
        assertTrue(plan.report.answerChanges.isEmpty(), "出处变更不是答案变更，不该进复核清单")

        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()
        assertNull(c.patchIntake, "应用之后应当离开预览页")
        assertTrue(c.banner?.text?.contains("已更新到第 2 版") == true, "实际横幅：${c.banner?.text}")

        val after = c.banks.single().questionBy("q003")
        assertEquals(ProvenanceOrigin.ADAPTED, after.origin)
        assertEquals("marx-textbook", after.sourceId)
        assertEquals("第二章 第一节", after.sourceLocator)
        // 内容没变，内容版本就不该跳：它会让这道题的 AI 讲解缓存白失效一次
        assertEquals(before.contentRevision, after.contentRevision)
        // 而题库版本要变：用户得能看出"我装过一版"
        assertEquals(2, c.banks.single().contentRevision)
    }

    @Test
    fun applyingTheSameProvenancePatchAgainIsANoOp() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        val bytes = provenanceOnlyPatch(q003Fingerprint())

        c.openPatch(bytes, "prov.qbpatch")
        testScheduler.advanceUntilIdle()
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()

        // 再装一次：本地已经和补丁一致。这不是错误，是"已经装过了"。
        c.openPatch(bytes, "prov.qbpatch")
        testScheduler.advanceUntilIdle()
        /* 严格模式下会先校验 expect，本地内容没变所以匹配；随后 update 无效果 →
           降级为「不需要更新」而不是报失败。只看预览状态：横幅是上一步留下的。 */
        val again = assertIs<PatchIntake.Ready>(c.patchIntake).plan
        assertIs<PatchApply.NoOp>(again, "重复安装应当预览为「无需更新」，实际 $again")
        assertEquals("第二章 第一节", c.banks.single().questionBy("q003").sourceLocator)
        assertEquals("第二章 第一节", c.banks.single().questionBy("q003").sourceLocator)
    }

    @Test
    fun clearingASourceIsPossibleWithEmptyString() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        // 先补上出处，再用空串把它清掉 —— 作者发现标错了来源时要能撤回
        c.openPatch(provenanceOnlyPatch(q003Fingerprint()), "prov.qbpatch")
        testScheduler.advanceUntilIdle()
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()

        val clear = """
            {"schemaVersion":"1.0.0","format":"knowledge-trainer-bank-patch",
             "patchId":"prov-clear","version":"2.1.0","name":"撤掉出处",
             "bankId":"$bank","baseRevision":2,"targetRevision":3,
             "baseFingerprint":"827015e596399cd3",
             "operations":[{"op":"update","questionId":"q003","expect":"${q003Fingerprint()}",
               "set":{"sourceId":"","sourceLocator":"","origin":"authored"}}],
             "files":[]}
        """.trimIndent()
        c.openPatch(zip(linkedMapOf("manifest.json" to clear.encodeToByteArray())), "clear.qbpatch")
        testScheduler.advanceUntilIdle()
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()

        val q3 = c.banks.single().questionBy("q003")
        assertNull(q3.sourceId, "空串应当把它清成 null，而不是留下一个空字符串当出处")
        assertNull(q3.sourceLocator)
        assertEquals(ProvenanceOrigin.AUTHORED, q3.origin)
    }
}

/** 从当前题库取一道题的最终状态（题目是延迟加载的，测试里可以就地展开）。 */
private fun morefish.knowledge.trainer.domain.model.QuestionBank.questionBy(id: String) =
    questions.single { it.questionId == id }
