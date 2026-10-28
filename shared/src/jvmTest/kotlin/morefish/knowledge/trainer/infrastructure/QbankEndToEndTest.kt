package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.application.usecase.StartPracticeSessionUseCase
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * KT-QBF 端到端回归：真实 .qbank 包 → 导入 → 抽题 → 作答 → 判题 → 历史。
 *
 * 对应规范里那条强制测试：
 * ".qbank 三题型真实包生成 → validate → 导入 → 正确作答 → 导出/重导入"。
 * 生成与 validate 在 tools/qbank（Python），导入与判题在这里（Kotlin）。
 */
class QbankEndToEndTest {

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples，请先运行 tools/qbank/build_qbank.py 生成样例包")
    }

    private class FixedClock(var now: Long = 1_700_000_000_000) : WallClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun starterPackageCanBePracticedEndToEnd() = runTest {
        val storage = InMemoryTextStorage()
        val banks: BankRepository = JsonBankRepository(storage)
        val attempts: AttemptRepository = JsonAttemptRepository(storage)
        val clock = FixedClock()
        val importer = ImportBankUseCase(banks, clock = clock)

        val imported = assertIs<ImportOutcome.Imported>(
            importer.importQbank(File(examples, "starter.qbank").readBytes(), "占位", "starter.qbank"),
        )
        // 标题来自包内 manifest，而不是文件名
        assertEquals("KT-QBF 最小示例题库", imported.bank.title)
        assertEquals(3, imported.bank.questions.size)

        val starter = StartPracticeSessionUseCase(clock = clock)
        val submit = SubmitAnswerUseCase(attempts, clock = clock)
        val session = starter.start(imported.bank, questionCount = null, shuffleQuestions = false)
        assertEquals(3, session.items.size)

        var current = session
        for (item in session.items) {
            val qid = item.question.questionId
            val answer = if (qid == "q003") {
                AnswerInput.Text("实践是人类能动地改造世界的客观物质活动。")
            } else {
                AnswerInput.Choices(
                    when (qid) {
                        "q001" -> setOf("o2")
                        // 顺序无关：包内是 o1,o2,o3，这里故意倒着选
                        else -> setOf("o3", "o1", "o2")
                    },
                )
            }
            val outcome = submit.submit(current, item, answer, elapsedMillis = 1200)
            assertEquals(Verdict.CORRECT, outcome.evaluation.verdict, "题目 $qid 应判对")
            current = outcome.session
        }
        assertEquals(3, attempts.forSession(session.sessionId).size)
    }

    @Test
    fun multiChoiceFromPackageRequiresExactSet() = runTest {
        val storage = InMemoryTextStorage()
        val banks: BankRepository = JsonBankRepository(storage)
        val attempts: AttemptRepository = JsonAttemptRepository(storage)
        val clock = FixedClock()
        val importer = ImportBankUseCase(banks, clock = clock)
        val starter = StartPracticeSessionUseCase(clock = clock)
        val submit = SubmitAnswerUseCase(attempts, clock = clock)

        val bank = assertIs<ImportOutcome.Imported>(
            importer.importQbank(File(examples, "starter.qbank").readBytes(), "x", "starter.qbank"),
        ).bank
        val session = starter.start(bank, null, shuffleQuestions = false)
        val item = session.items.first { it.question.questionId == "q002" }

        // 包内答案是 o1,o2,o3
        val cases = mapOf(
            setOf("o1", "o2") to Verdict.INCORRECT,               // 漏选
            setOf("o1", "o2", "o4") to Verdict.INCORRECT,         // 漏选 + 误选
            setOf("o1", "o2", "o3", "o4") to Verdict.INCORRECT,   // 全选也错
            setOf("o1", "o2", "o3") to Verdict.CORRECT,
        )
        for ((choice, expected) in cases) {
            val outcome = submit.submit(session, item, AnswerInput.Choices(choice), elapsedMillis = 800)
            assertEquals(expected, outcome.evaluation.verdict, "选择 $choice")
        }
    }

    @Test
    fun shortAnswerIsCharacterExact() = runTest {
        val storage = InMemoryTextStorage()
        val banks: BankRepository = JsonBankRepository(storage)
        val attempts: AttemptRepository = JsonAttemptRepository(storage)
        val clock = FixedClock()
        val importer = ImportBankUseCase(banks, clock = clock)
        val starter = StartPracticeSessionUseCase(clock = clock)
        val submit = SubmitAnswerUseCase(attempts, clock = clock)

        val bank = assertIs<ImportOutcome.Imported>(
            importer.importQbank(File(examples, "starter.qbank").readBytes(), "x", "starter.qbank"),
        ).bank
        val session = starter.start(bank, null, shuffleQuestions = false)
        val item = session.items.first { it.question.questionId == "q003" }

        // 少一个句号就必须判错（严格默写的核心承诺）
        val noPeriod = submit.submit(session, item, AnswerInput.Text("实践是人类能动地改造世界的客观物质活动"), 0)
        assertEquals(Verdict.INCORRECT, noPeriod.evaluation.verdict)

        val exact = submit.submit(session, item, AnswerInput.Text("实践是人类能动地改造世界的客观物质活动。"), 0)
        assertEquals(Verdict.CORRECT, exact.evaluation.verdict)
    }

    @Test
    fun reimportingSamePackageIsDetectedAsDuplicate() = runTest {
        val storage = InMemoryTextStorage()
        val banks: BankRepository = JsonBankRepository(storage)
        val importer = ImportBankUseCase(banks, clock = FixedClock())
        val bytes = File(examples, "starter.qbank").readBytes()

        assertIs<ImportOutcome.Imported>(importer.importQbank(bytes, "x", "starter.qbank"))
        // 同一份内容再导入：必须提示已存在，不能悄悄多出一份
        assertIs<ImportOutcome.Duplicate>(importer.importQbank(bytes, "x", "starter.qbank"))
        assertEquals(1, banks.list().size)
    }

    @Test
    fun advancedPackageImportsAsPracticeableBank() = runTest {
        val storage = InMemoryTextStorage()
        val banks: BankRepository = JsonBankRepository(storage)
        val importer = ImportBankUseCase(banks, clock = FixedClock())
        val imported = assertIs<ImportOutcome.Imported>(
            importer.importQbank(File(examples, "advanced.qbank").readBytes(), "x", "advanced.qbank"),
        )
        assertEquals(6, imported.bank.questions.size)
        val starter = StartPracticeSessionUseCase(clock = FixedClock())
        assertEquals(6, starter.start(imported.bank, null, shuffleQuestions = false).items.size)
    }
}
