package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.application.usecase.StartPracticeSessionUseCase
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.WrongQuestions
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.presentation.SampleBank
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 端到端闭环测试：导入示例 → 开始会话 → 逐题作答 → 判题写入历史 → 总结。
 * 这是 P1 的验收用例，失败即代表"做题闭环"不可用。
 */
class PracticeLoopTest {

    private val clock = WallClock { 1_000L }

    @Test
    fun importThenAnswerEverythingCorrectly() = runTest {
        val storage = InMemoryTextStorage()
        val banks = JsonBankRepository(storage)
        val attempts = JsonAttemptRepository(storage)

        val outcome = ImportBankUseCase(banks, clock = clock)
            .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "sample")
        val imported = assertIs<ImportOutcome.Imported>(outcome)
        assertEquals(6, imported.bank.questionCount)
        assertEquals(6, imported.preview.accepted.size)

        val session = StartPracticeSessionUseCase(clock, Random(7))
            .start(imported.bank, questionCount = null, shuffleQuestions = true)
        assertEquals(6, session.items.size)

        val submit = SubmitAnswerUseCase(attempts, clock = clock)
        var current: PracticeSession = session
        session.items.forEach { item ->
            val question = item.question
            val answer = if (question.type == QuestionType.SHORT_ANSWER) {
                AnswerInput.Text(question.answerText!!)
            } else {
                AnswerInput.Choices(question.correctOptionIds)
            }
            val result = submit.submit(current, item, answer, elapsedMillis = 2_000)
            assertEquals(Verdict.CORRECT, result.evaluation.verdict, "题目 ${question.questionId} 应判正确")
            current = result.session
        }

        val summary = current.summary()
        assertEquals(6, summary.answered)
        assertEquals(6, summary.correct)
        assertEquals(1.0, summary.accuracy!!, 0.0001)
        assertEquals(6, attempts.all().size)
        assertTrue(WrongQuestions.derive(attempts.all()).isEmpty())
    }

    @Test
    fun wrongAnswerIsRecordedAndAggregated() = runTest {
        val storage = InMemoryTextStorage()
        val banks = JsonBankRepository(storage)
        val attempts = JsonAttemptRepository(storage)

        val imported = assertIs<ImportOutcome.Imported>(
            ImportBankUseCase(banks, clock = clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "sample"),
        )

        val multi = imported.bank.questions.first { it.type == QuestionType.MULTIPLE_CHOICE }
        val session = StartPracticeSessionUseCase(clock, Random(1))
            .startFor(imported.bank, listOf(multi))
        assertEquals(PracticeMode.NATIVE, session.mode)

        // 少选一个：应判错误，并标记漏选
        val partialSelection = multi.correctOptionIds.drop(1).toSet()
        val result = SubmitAnswerUseCase(attempts, clock = clock)
            .submit(session, session.items.first(), AnswerInput.Choices(partialSelection), 500)

        assertEquals(Verdict.INCORRECT, result.evaluation.verdict)
        assertEquals(1, attempts.all().size)

        val wrong = WrongQuestions.derive(attempts.all())
        assertEquals(1, wrong.size)
        assertEquals(multi.questionId, wrong.single().questionId)
        assertEquals(1, wrong.single().wrongCount)
    }

    @Test
    fun reimportingSameContentDoesNotCreateDuplicateBank() = runTest {
        val storage = InMemoryTextStorage()
        val banks = JsonBankRepository(storage)
        val useCase = ImportBankUseCase(banks, clock = clock)

        useCase.import(SampleBank.json, ChoiceMapping.AUTO, "第一次", "sample")
        val second = useCase.import(SampleBank.json, ChoiceMapping.AUTO, "第二次", "sample")

        assertIs<ImportOutcome.Duplicate>(second)
        assertEquals(1, banks.list().size)
    }

    @Test
    fun abandonedQuestionStaysOutOfAccuracyDenominator() = runTest {
        val storage = InMemoryTextStorage()
        val banks = JsonBankRepository(storage)
        val attempts = JsonAttemptRepository(storage)

        val imported = assertIs<ImportOutcome.Imported>(
            ImportBankUseCase(banks, clock = clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "sample"),
        )
        val session = StartPracticeSessionUseCase(clock, Random(3))
            .start(imported.bank, questionCount = 2, shuffleQuestions = false)

        val submit = SubmitAnswerUseCase(attempts, clock = clock)
        val abandoned = submit.abandon(session, session.items.first(), 100)

        assertEquals(Verdict.ABANDONED, abandoned.evaluation.verdict)
        assertEquals(1, abandoned.session.summary().abandoned)
        assertEquals(0, abandoned.session.summary().graded)
        assertEquals(null, abandoned.session.summary().accuracy)
    }
}
