package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.application.usecase.StartPracticeSessionUseCase
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.presentation.SampleBank
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 桌面端真实文件存储的回归测试：用临时目录模拟用户主目录，
 * 验证"关闭再打开"之后题库与历史都还在。
 */
class FileStorageTest {

    private val clock = WallClock { 1_700_000_000_000L }

    @Test
    fun bankAndHistorySurviveRestart() = runTest {
        val root = Files.createTempDirectory("knowledge-trainer-test").toFile()
        try {
            val storage = FileTextStorage(root)
            val banks = JsonBankRepository(storage)
            val attempts = JsonAttemptRepository(storage)

            val imported = assertIs<ImportOutcome.Imported>(
                ImportBankUseCase(banks, clock = clock)
                    .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "sample"),
            )

            val session = StartPracticeSessionUseCase(clock, Random(11))
                .start(imported.bank, questionCount = 3, shuffleQuestions = false)
            val submit = SubmitAnswerUseCase(attempts, clock = clock)
            val item = session.items.first()
            val answer = if (item.question.type == morefish.knowledge.trainer.domain.model.QuestionType.SHORT_ANSWER) {
                AnswerInput.Text(item.question.answerText ?: "")
            } else {
                AnswerInput.Choices(item.question.correctOptionIds)
            }
            submit.submit(session, item, answer, elapsedMillis = 1_500)

            assertTrue(File(root, "banks-v1.json").isFile, "题库文件应存在")
            assertTrue(File(root, "attempts-v1.ndjson").isFile, "历史文件应存在")

            // 全新实例 = 重启应用
            val reloadedBanks = JsonBankRepository(FileTextStorage(root))
            val reloadedAttempts = JsonAttemptRepository(FileTextStorage(root))

            assertEquals(1, reloadedBanks.list().size)
            assertEquals(imported.bank.bankId, reloadedBanks.list().single().bankId)
            assertEquals(1, reloadedAttempts.all().size)
            assertEquals(emptyList(), reloadedAttempts.loadWarnings)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writingSameBankTwiceReplacesInsteadOfDuplicating() = runTest {
        val root = Files.createTempDirectory("knowledge-trainer-test").toFile()
        try {
            val banks = JsonBankRepository(FileTextStorage(root))
            val imported = assertIs<ImportOutcome.Imported>(
                ImportBankUseCase(banks, clock = clock)
                    .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "sample"),
            )
            banks.save(imported.bank.copy(title = "改过的标题"))

            val list = JsonBankRepository(FileTextStorage(root)).list()
            assertEquals(1, list.size)
            assertEquals("改过的标题", list.single().title)
        } finally {
            root.deleteRecursively()
        }
    }
}
