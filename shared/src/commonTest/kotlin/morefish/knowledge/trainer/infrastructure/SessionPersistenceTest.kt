package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.model.PracticeDraft
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.SessionState
import morefish.knowledge.trainer.infrastructure.storage.JsonSessionRepository
import morefish.knowledge.trainer.infrastructure.storage.SESSION_FILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话落盘：题目必须原样冻结（含选项顺序），草稿与游标必须能回来，
 * 坏文件必须被忽略并告警，而不是让应用崩在启动阶段。
 */
class SessionPersistenceTest {

    private fun question(id: String) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "题目 $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a"),
        explanationMarkdown = "解析",
    )

    private fun session(
        sessionId: String = "session-1",
        draft: PracticeDraft = PracticeDraft.Empty,
        cursor: Int = 0,
        state: SessionState = SessionState.PAUSED,
    ) = PracticeSession(
        sessionId = sessionId,
        bankId = "bank-1",
        mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
        state = state,
        items = listOf(
            SessionItem(question("q1"), listOf("b", "a")),
            SessionItem(question("q2"), listOf("a", "b")),
        ),
        cursor = cursor,
        attempts = emptyList(),
        createdAtMillis = 1_000,
        bankTitle = "测试题库",
        planLabel = "未练习 · 10 题",
        draft = draft,
        updatedAtMillis = 2_000,
    )

    @Test
    fun roundTripKeepsFrozenOptionsDraftAndLabel() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonSessionRepository(storage)
        repo.save(session(cursor = 1, draft = PracticeDraft(setOf("b"), "写了一半")))

        val loaded = assertNotNull(repo.active())
        assertEquals("session-1", loaded.sessionId)
        assertEquals(1, loaded.cursor)
        // 选项显示顺序是会话创建时冻结的：恢复后仍然是 b、a。
        assertEquals(listOf("b", "a"), loaded.items.first().displayOptionIds)
        assertEquals("题目 q1", loaded.items.first().question.promptMarkdown)
        assertEquals("写了一半", loaded.draft.textAnswer)
        assertEquals(setOf("b"), loaded.draft.selectedOptionIds)
        assertEquals("未练习 · 10 题", loaded.planLabel)
        assertEquals(2_000, loaded.updatedAtMillis)
    }

    @Test
    fun completedSessionIsNotOfferedForResume() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonSessionRepository(storage)
        repo.save(session(state = SessionState.COMPLETED))

        assertNull(repo.active())
    }

    @Test
    fun clearOnlyRemovesTheMatchingSession() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonSessionRepository(storage)
        repo.save(session(sessionId = "old"))
        repo.save(session(sessionId = "new"))

        repo.clear("old")
        assertEquals("new", repo.active()?.sessionId)

        repo.clear("new")
        assertNull(repo.active())
    }

    @Test
    fun corruptFileIsIgnoredWithWarning() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(SESSION_FILE, "{ 这不是 JSON")
        val repo = JsonSessionRepository(storage)

        assertNull(repo.active())
        assertTrue(repo.loadWarnings.single().contains("无法解析"))
    }

    @Test
    fun resumeNormalizationSkipsAlreadyAnsweredQuestions() {
        val submitted = session(cursor = 0).copy(
            attempts = emptyList(),
        )
        assertEquals(0, submitted.firstUnansweredIndex)

        val withOneAnswered = submitted.copy(
            attempts = listOf(
                morefish.knowledge.trainer.domain.model.AttemptRecord(
                    attemptId = "a1",
                    sessionId = "session-1",
                    bankId = "bank-1",
                    questionId = "q1",
                    questionType = QuestionType.SINGLE_CHOICE,
                    mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
                    displayOptionIds = listOf("b", "a"),
                    selectedOptionIds = setOf("a"),
                    textAnswer = null,
                    verdict = morefish.knowledge.trainer.domain.model.Verdict.CORRECT,
                    strategyId = "exact-set",
                    gradingSpec = "exact-set",
                    answerRevision = 1,
                    questionFingerprint = "",
                    submittedAtMillis = 5_000,
                    elapsedMillis = 100,
                    explanationSeen = false,
                ),
            ),
        )

        val resumed = withOneAnswered.resumeNormalized(9_000)
        assertEquals(1, resumed.cursor)
        assertEquals(SessionState.IN_PROGRESS, resumed.state)
        assertTrue(resumed.draft.isEmpty)
    }
}
