package morefish.knowledge.trainer.domain

import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.WrongQuestions
import morefish.knowledge.trainer.domain.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {

    private val question = Question(
        questionId = "q1",
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "题干",
        options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
        correctOptionIds = setOf("o1"),
    )

    private fun attempt(
        questionId: String,
        verdict: Verdict,
        at: Long = 1L,
        selected: Set<String> = emptySet(),
        id: String = "a-$questionId-${at}",
    ) = AttemptRecord(
        attemptId = id,
        sessionId = "s1",
        bankId = "b1",
        questionId = questionId,
        questionType = QuestionType.SINGLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = listOf("o1", "o2"),
        selectedOptionIds = selected,
        textAnswer = null,
        verdict = verdict,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "fp",
        submittedAtMillis = at,
        elapsedMillis = 1000,
        explanationSeen = false,
    )

    private fun session(count: Int = 2): PracticeSession = PracticeSession.create(
        sessionId = "s1",
        bankId = "b1",
        items = List(count) { SessionItem(question, listOf("o1", "o2")) },
        mode = PracticeMode.NATIVE,
        nowMillis = 0L,
    )

    @Test
    fun submitDoesNotMoveCursor() {
        val started = session()
        val afterSubmit = started.append(attempt("q1", Verdict.CORRECT))

        assertEquals(0, afterSubmit.cursor)
        assertEquals(1, afterSubmit.answeredCount)
        assertEquals(listOf("o1", "o2"), afterSubmit.current?.displayOptionIds)
    }

    @Test
    fun advanceMovesToNextQuestion() {
        val advanced = session().append(attempt("q1", Verdict.CORRECT)).advance()
        assertEquals(1, advanced.cursor)
        assertEquals(1, advanced.answeredCount)
    }

    @Test
    fun summaryExcludesPendingAndAbandonedFromDenominator() {
        val session = session(count = 5)
            .append(attempt("q1", Verdict.CORRECT, at = 1))
            .append(attempt("q2", Verdict.INCORRECT, at = 2))
            .append(attempt("q3", Verdict.CORRECT, at = 3))
            .append(attempt("q4", Verdict.PENDING, at = 4))
            .append(attempt("q5", Verdict.ABANDONED, at = 5))

        val summary = session.summary()
        assertEquals(5, summary.answered)
        assertEquals(3, summary.graded)
        assertEquals(2.0 / 3.0, summary.accuracy!!, 0.0001)
        assertEquals(1, summary.pending)
        assertEquals(1, summary.abandoned)
    }

    @Test
    fun accuracyIsNullWhenNothingWasGraded() {
        val session = session(count = 1).append(attempt("q1", Verdict.PENDING))
        assertNull(session.summary().accuracy)
    }

    @Test
    fun wrongQuestionsAggregateByQuestionAndUseLatestAttempt() {
        val attempts = listOf(
            attempt("q1", Verdict.INCORRECT, at = 10),
            attempt("q1", Verdict.INCORRECT, at = 20),
            attempt("q2", Verdict.INCORRECT, at = 30),
            attempt("q2", Verdict.CORRECT, at = 40),
        )

        val wrong = WrongQuestions.derive(attempts)
        assertEquals(1, wrong.size)
        assertEquals("q1", wrong.single().questionId)
        assertEquals(2, wrong.single().wrongCount)
        assertEquals(Verdict.INCORRECT, wrong.single().latestAttempt.verdict)
    }

    @Test
    fun partiallyCorrectCountsAsGradedButNotCorrect() {
        val summary = session(count = 2)
            .append(attempt("q1", Verdict.PARTIALLY_CORRECT))
            .append(attempt("q2", Verdict.CORRECT))
            .summary()

        assertEquals(2, summary.graded)
        assertEquals(0.5, summary.accuracy!!, 0.0001)
        assertEquals(0.75, summary.weightedAccuracy!!, 0.0001)
    }

    @Test
    fun bankWithoutAnswerFailsValidation() {
        val broken = question.copy(correctOptionIds = emptySet())
        assertTrue(broken.validate().any { it.code == "correct-count" })

        val bank = QuestionBank(
            bankId = "b1",
            title = "t",
            sourceLocale = "zh-Hans",
            questions = listOf(question, broken),
            importedAtMillis = 0,
            sourceLabel = "test",
        )
        assertEquals(1, bank.practicableQuestions().size)
    }
}
