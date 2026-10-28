package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.PatchIntake
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.patch.PatchApply
import morefish.knowledge.trainer.domain.patch.PatchChangeKind
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.importing.QbpatchImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 更新补丁的端到端行为。
 *
 * 重点不在"能改"，而在三件容易被忽略的事：
 *
 * 1. 预览阶段**一次写都没有**；
 * 2. 应用之后**历史一条不少、判定一条不变**；
 * 3. 已经更新过的补丁再来一次，不报错也不重复改。
 *
 * 用真实例包（`tools/qbank` 打出来的 starter + starter-v2）：
 * 跨语言链路一起测，而不是在测试里自己拼一个"理想中的补丁"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatchFlowTest {

    private val bank = "550e8400-e29b-41d4-a716-446655440000"
    private var now = 1_800_000_000_000L
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

    private fun starterBytes(): ByteArray {
        val file = File(examples, "starter.qbank")
        if (!file.isFile) fail("缺少 ${file.absolutePath}")
        return file.readBytes()
    }

    private fun patchBytes(): ByteArray {
        val file = File(examples, "starter-v2.qbpatch")
        if (!file.isFile) fail("缺少 ${file.absolutePath}，先跑 python tools/qbank/qbpatch.py build")
        return file.readBytes()
    }

    /** 建控制器并装入示例题库（走真正的导入路径，bankId 来自包声明）。 */
    private suspend fun loaded(
        storage: InMemoryTextStorage,
        scheduler: TestCoroutineScheduler,
    ): AppController {
        val deps = AppDependencies(storage, clock)
        val imported = assertIs<ImportOutcome.Imported>(
            ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test"),
        )
        assertEquals(bank, imported.bank.bankId, "题库身份必须来自包声明")
        val controller = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        controller.refresh()
        scheduler.advanceUntilIdle()
        return controller
    }

    private fun attempt(questionId: String, verdict: Verdict = Verdict.CORRECT) = AttemptRecord(
        attemptId = "a-$questionId-${now}",
        sessionId = "s1",
        bankId = bank,
        questionId = questionId,
        questionType = QuestionType.SINGLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = listOf("o1", "o2", "o3"),
        selectedOptionIds = setOf("o2"),
        textAnswer = null,
        verdict = verdict,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "7a946af22e6b29e0",
        submittedAtMillis = now,
        elapsedMillis = 5_000,
        explanationSeen = false,
    )

    // ---------------------------------------------------------------- 预览

    @Test
    fun previewShowsChangesWithoutTouchingAnything() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        val before = storage.read("bank-list-v1.json")
        val beforeQuestions = storage.read("questions-$bank.ndjson")

        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()

        val intake = assertIs<PatchIntake.Ready>(c.patchIntake)
        val plan = assertIs<PatchApply.Ok>(intake.plan)
        assertEquals(3, plan.report.entries.size)
        assertEquals(1, plan.report.added)
        assertEquals(2, plan.report.updated)
        assertEquals(1, plan.report.answerChanges.size, "只有 q002 的答案变了")
        assertEquals("q002", plan.report.answerChanges.single().questionId)

        // 预览不改任何东西：题库文件与目录一个字节都没动
        assertEquals(before, storage.read("bank-list-v1.json"))
        assertEquals(beforeQuestions, storage.read("questions-$bank.ndjson"))
        assertTrue(c.latestRevisions.isEmpty())
    }

    @Test
    fun affectedAttemptsCountsHistoryOnChangedQuestions() = runTest {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, clock)
        ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test")
        // q002 是答案变更的那道；q001 只是题面变了，不该被算进"受影响"
        deps.attemptRepository.append(attempt("q002"))
        deps.attemptRepository.append(attempt("q002"))
        deps.attemptRepository.append(attempt("q001"))

        val c = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.refresh()
        testScheduler.advanceUntilIdle()
        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()

        val intake = assertIs<PatchIntake.Ready>(c.patchIntake)
        assertEquals(2, intake.affectedAttempts, "只数答案变更那道题的历史")
    }

    // ---------------------------------------------------------------- 应用

    @Test
    fun applyingUpdatesBankAndLeavesHistoryUntouched() = runTest {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, clock)
        ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test")
        deps.attemptRepository.append(attempt("q002", Verdict.INCORRECT))
        val historyBefore = deps.attemptRepository.all()

        val c = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.refresh()
        testScheduler.advanceUntilIdle()
        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()

        val updated = c.banks.single()
        assertEquals(2, updated.contentRevision)
        assertEquals(4, updated.questionCount, "3 题 + 新增 1 题")
        assertEquals(setOf("o1", "o2", "o3", "o5"), updated.questions.first { it.questionId == "q002" }.correctOptionIds)
        assertEquals("TCP 三次握手主要是为了解决什么问题？", updated.questions.first { it.questionId == "q001" }.promptMarkdown)
        assertTrue(updated.questions.any { it.questionId == "q004" })

        // 历史：条数、判定、题目指纹一个都没变
        val historyAfter = c.history
        assertEquals(historyBefore.size, historyAfter.size)
        assertEquals(Verdict.INCORRECT, historyAfter.single().verdict)
        assertEquals("7a946af22e6b29e0", historyAfter.single().questionFingerprint)
        assertEquals(1, historyAfter.single().answerRevision)

        // 修订日志记下了"哪几道题的答案变了"
        val revision = assertNotNull(c.latestRevisions[bank])
        assertEquals(2, revision.revision)
        assertEquals(listOf("q002"), revision.answerChangedIds)
        assertEquals(1, revision.affectedAttempts)
        assertTrue(revision.touchedHistory)

        assertTrue(assertNotNull(c.banner).text.contains("第 2 版"))
        assertTrue(c.banner!!.text.contains("一条都没改"))
    }

    @Test
    fun applyingAgainIsNoOp() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()

        // 用户又点了一次同一个文件（重复下载很常见）
        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()
        val intake = assertIs<PatchIntake.Ready>(c.patchIntake)
        assertIs<PatchApply.NoOp>(intake.plan)

        // 再点"应用"什么都不该发生
        c.applyPickedPatch()
        testScheduler.advanceUntilIdle()
        assertEquals(2, c.banks.single().contentRevision)
        assertEquals(
            1,
            morefish.knowledge.trainer.infrastructure.storage.JsonRevisionRepository(storage).of(bank).size,
            "同一个修订号不该记两次",
        )
    }

    @Test
    fun brokenPatchChangesNothing() = runTest {
        val storage = InMemoryTextStorage()
        val c = loaded(storage, testScheduler)
        c.openPatch("not a zip".encodeToByteArray(), "broken.qbpatch")
        testScheduler.advanceUntilIdle()

        assertIs<PatchIntake.Rejected>(c.patchIntake)
        assertTrue(assertNotNull(c.banner).isError)
        assertEquals(1, c.banks.single().contentRevision)
    }

    @Test
    fun missingBankIsReportedInsteadOfGuessed() = runTest {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, clock)
        val c = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.refresh()
        testScheduler.advanceUntilIdle()

        c.openPatch(patchBytes(), "starter-v2.qbpatch")
        testScheduler.advanceUntilIdle()

        assertIs<PatchIntake.BankMissing>(c.patchIntake)
        val banner = assertNotNull(c.banner)
        assertTrue(banner.text.contains("没有这个补丁要更新的题库"))
        assertTrue(c.banks.isEmpty(), "不能因为来了个补丁就凭空建一个题库")
    }

    // ---------------------------------------------------------------- 完整包与身份

    @Test
    fun sameBankIdWithDifferentContentIsNotSilentlyOverwritten() = runTest {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, clock)
        val importer = ImportBankUseCase(deps.bankRepository, clock = clock)

        assertIs<ImportOutcome.Imported>(importer.importQbank(starterBytes(), "示例", "test"))
        // 同一个 bankId、内容不同的包：这是"作者发了新版本"，不是"另一个题库"
        val again = importer.importQbank(repackedBank(), "示例 v2", "test")
        val conflict = assertIs<ImportOutcome.IdentityConflict>(again, "实际结果：$again")
        assertEquals(bank, conflict.existing.bankId)
        // 没有被覆盖：本地还是原来那 3 题
        assertEquals(3, deps.bankRepository.list().single().questionCount)
    }

    /** 同 bankId、只有题干不同的一份最小 `.qbank`。 */
    private fun repackedBank(): ByteArray {
        // NDJSON：一条记录必须独占一行，别用 trimIndent 写跨行字符串
        val structure = """{"questionId":"q001","type":"single_choice","optionIds":["o1","o2"],""" +
            """"correctOptionIds":["o2"],"grading":{"strategy":"exact-set"}}""" + "\n"
        val locale = """{"questionId":"q001","locale":"zh-Hans","promptMarkdown":"被改过的题干",""" +
            """"options":[{"optionId":"o1","contentMarkdown":"否"},""" +
            """{"optionId":"o2","contentMarkdown":"是"}]}""" + "\n"
        val files = listOf(
            "questions/part-0001.ndjson" to structure.encodeToByteArray(),
            "locales/zh-Hans/questions.ndjson" to locale.encodeToByteArray(),
        )
        val manifest = """
            {"format":"knowledge-trainer-question-bank","schemaVersion":"1.0.0","bankId":"$bank",
             "contentRevision":2,"title":"示例题库","sourceLocale":"zh-Hans","supportedLocales":["zh-Hans"],
             "author":{"displayName":"测试"},
             "files":[${files.joinToString(",") { (path, bytes) ->
            """{"path":"$path","sha256":"${Sha256.hex(bytes)}"}"""
        }}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.encodeToByteArray())
            zos.closeEntry()
            files.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun changedQuestionIsMarkedAsAnswerChange() {
        // 报告分类是界面高亮的依据：题面变更用中性色，答案变更必须用琥珀色提醒
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, clock)
        kotlinx.coroutines.runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = clock).importQbank(starterBytes(), "示例", "test")
            val parsed = QbpatchImporter.parse(patchBytes(), PureDeflateCodec)
            val patch = assertIs<QbpatchImporter.Result.Success>(parsed).patch
            val local = deps.bankRepository.list().single()
            val plan = assertIs<PatchApply.Ok>(morefish.knowledge.trainer.domain.patch.BankPatcher.apply(local, patch))
            val kinds = plan.report.entries.associate { it.questionId to it.kind }
            assertEquals(PatchChangeKind.ANSWER, kinds["q002"])
            assertEquals(PatchChangeKind.CONTENT, kinds["q001"])
            assertEquals(PatchChangeKind.ADDED, kinds["q004"])
            // 旧答案写成人能读的样子，而不是 {o1,o2,o3}
            assertTrue(plan.report.answerChanges.single().before.contains("客观物质性"))
        }
    }
}
