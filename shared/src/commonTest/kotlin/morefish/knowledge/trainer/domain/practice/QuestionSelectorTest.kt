package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 抽题是 P2 的核心：范围、题型、标签、题量、混合题型，
 * 全部用纯函数验证，不依赖 UI 与存储。
 */
class QuestionSelectorTest {

    private fun single(id: String, tags: List<String> = emptyList()) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "题目 $id",
        options = listOf(QuestionOption("a", "选项 A"), QuestionOption("b", "选项 B")),
        correctOptionIds = setOf("a"),
        explanationMarkdown = "解析",
        tags = tags,
    )

    private fun multiple(id: String) = Question(
        questionId = id,
        type = QuestionType.MULTIPLE_CHOICE,
        promptMarkdown = "多选 $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B"), QuestionOption("c", "C")),
        correctOptionIds = setOf("a", "b"),
        explanationMarkdown = "解析",
    )

    private fun bankOf(questions: List<Question>) = QuestionBank(
        bankId = "bank-1",
        title = "测试题库",
        sourceLocale = "zh-Hans",
        questions = questions,
        importedAtMillis = 0,
        sourceLabel = "test",
    )

    private fun attempt(questionId: String, verdict: Verdict, at: Long) = AttemptRecord(
        attemptId = "attempt-$questionId-$at",
        sessionId = "session",
        bankId = "bank-1",
        questionId = questionId,
        questionType = QuestionType.SINGLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = listOf("a", "b"),
        selectedOptionIds = setOf("a"),
        textAnswer = null,
        verdict = verdict,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "",
        submittedAtMillis = at,
        elapsedMillis = 1000,
        explanationSeen = false,
    )

    @Test
    fun unpracticedExcludesAnsweredButKeepsAbandoned() {
        val bank = bankOf(listOf(single("q1"), single("q2"), single("q3")))
        val stats = StudyStats.derive(
            listOf(
                attempt("q1", Verdict.CORRECT, 1_000),
                attempt("q2", Verdict.ABANDONED, 2_000),
            ),
        )

        val picked = QuestionSelector.pick(
            bank,
            stats,
            emptySet(),
            PracticePlan(scope = PracticeScope.UNPRACTICED, shuffleQuestions = false),
        )

        // 放弃不算练过：q2 仍然回到未练习范围。
        assertEquals(listOf("q2", "q3"), picked.map { it.questionId })
    }

    @Test
    fun currentWrongUsesLatestVerdictAndConsolidatedIsSeparate() {
        val bank = bankOf(listOf(single("q1"), single("q2"), single("q3")))
        val stats = StudyStats.derive(
            listOf(
                attempt("q1", Verdict.INCORRECT, 1_000),
                attempt("q1", Verdict.CORRECT, 2_000), // 已巩固
                attempt("q2", Verdict.INCORRECT, 3_000),
                attempt("q3", Verdict.CORRECT, 4_000),
            ),
        )

        val currentWrong = QuestionSelector.pick(
            bank,
            stats,
            emptySet(),
            PracticePlan(scope = PracticeScope.CURRENT_WRONG, shuffleQuestions = false),
        )
        assertEquals(listOf("q2"), currentWrong.map { it.questionId })

        val consolidated = QuestionSelector.pick(
            bank,
            stats,
            emptySet(),
            PracticePlan(scope = PracticeScope.CONSOLIDATED, shuffleQuestions = false),
        )
        assertEquals(listOf("q1"), consolidated.map { it.questionId })

        val historyWrong = QuestionSelector.pick(
            bank,
            stats,
            emptySet(),
            PracticePlan(scope = PracticeScope.HISTORY_WRONG, shuffleQuestions = false),
        )
        assertEquals(listOf("q1", "q2"), historyWrong.map { it.questionId })
    }

    @Test
    fun favoritesAndTagsNarrowThePool() {
        val bank = bankOf(
            listOf(
                single("q1", tags = listOf("第一章")),
                single("q2", tags = listOf("第二章")),
                single("q3", tags = listOf("第一章", "重点")),
            ),
        )
        val favorites = setOf(QuestionKey("bank-1", "q1"), QuestionKey("bank-1", "q3"))

        val favoritePicked = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            favorites,
            PracticePlan(scope = PracticeScope.FAVORITES, shuffleQuestions = false),
        )
        assertEquals(listOf("q1", "q3"), favoritePicked.map { it.questionId })

        val tagged = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            favorites,
            PracticePlan(tags = setOf("第一章"), shuffleQuestions = false),
        )
        assertEquals(listOf("q1", "q3"), tagged.map { it.questionId })
    }

    @Test
    fun questionCountIsClampedAndNeverDuplicates() {
        val bank = bankOf((1..5).map { single("q$it") })
        val picked = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            emptySet(),
            PracticePlan(questionCount = 99),
            Random(1),
        )
        assertEquals(5, picked.size)
        assertEquals(5, picked.map { it.questionId }.toSet().size)
    }

    @Test
    fun mixedTypesDrawsFromEveryAvailableType() {
        // 20 道单选 + 2 道多选：纯随机会大量出现单选，混合抽题必须两类都抽到。
        val bank = bankOf((1..20).map { single("s$it") } + (1..2).map { multiple("m$it") })

        val mixed = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            emptySet(),
            PracticePlan(questionCount = 6, mixedTypes = true),
            Random(42),
        )

        assertEquals(6, mixed.size)
        assertEquals(4, mixed.count { it.type == QuestionType.SINGLE_CHOICE })
        assertEquals(2, mixed.count { it.type == QuestionType.MULTIPLE_CHOICE })
        assertTrue(mixed.map { it.questionId }.toSet().size == mixed.size)
    }

    @Test
    fun mixedTypesDoesNotReorderBeyondTypeRotation() {
        // 只有一种题型时，混合抽题退化为普通抽取，不应该报错或减少题量。
        val bank = bankOf((1..4).map { single("q$it") })
        val picked = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            emptySet(),
            PracticePlan(questionCount = 3, mixedTypes = true),
            Random(7),
        )
        assertEquals(3, picked.size)
    }

    @Test
    fun typeFilterExcludesOtherTypes() {
        val bank = bankOf(listOf(single("s1"), multiple("m1")))
        val picked = QuestionSelector.pick(
            bank,
            StudyStats.Empty,
            emptySet(),
            PracticePlan(types = setOf(QuestionType.MULTIPLE_CHOICE)),
        )
        assertEquals(listOf("m1"), picked.map { it.questionId })
    }
}
