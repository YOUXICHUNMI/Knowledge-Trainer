package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 历史复核的纯函数部分。
 *
 * 这里守的是三条不能破的规矩：
 *
 * 1. **原始判定不许被覆盖** —— 合并只产生副本，`from` 永远留着当时那一判；
 * 2. **不重复打扰** —— 同一条历史、同一版题目，问过一次就不再问；
 * 3. **选项顺序变化不算改动** —— 判题按 optionId，翻个顺序不是"答案变了"，
 *    否则每次打乱选项都会给用户制造几十条假待办。
 */
class AttemptReviewTest {

    private val registry = builtInGradingRegistry()

    private fun multi(
        id: String = "q2",
        correct: Set<String> = setOf("a", "b", "c"),
        options: List<QuestionOption> = listOf(
            QuestionOption("a", "A"),
            QuestionOption("b", "B"),
            QuestionOption("c", "C"),
            QuestionOption("d", "D"),
        ),
    ) = Question(
        questionId = id,
        type = QuestionType.MULTIPLE_CHOICE,
        promptMarkdown = "多选题 $id",
        options = options,
        correctOptionIds = correct,
        explanationMarkdown = "解析",
    )

    private fun bank(questions: List<Question>) = QuestionBank(
        bankId = "bank-1",
        title = "测试题库",
        sourceLocale = "zh-Hans",
        questions = questions,
        importedAtMillis = 0,
        sourceLabel = "test",
    )

    private fun attempt(
        questionId: String = "q2",
        verdict: Verdict = Verdict.CORRECT,
        selected: Set<String> = setOf("a", "b"),
        display: List<String> = listOf("a", "b", "c", "d"),
        at: Long = 1_000,
        type: QuestionType = QuestionType.MULTIPLE_CHOICE,
        text: String? = null,
    ) = AttemptRecord(
        attemptId = "attempt-$questionId-$at",
        sessionId = "session",
        bankId = "bank-1",
        questionId = questionId,
        questionType = type,
        mode = PracticeMode.NATIVE,
        displayOptionIds = display,
        selectedOptionIds = selected,
        textAnswer = text,
        verdict = verdict,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "",
        submittedAtMillis = at,
        elapsedMillis = 1_000,
        explanationSeen = false,
    )

    private fun review(
        attempt: AttemptRecord,
        decision: ReviewDecision = ReviewDecision.ACCEPT,
        effective: Verdict = Verdict.CORRECT,
        basis: String = "basis-1",
        at: Long = 5_000,
    ) = AttemptReview(
        attemptId = attempt.attemptId,
        bankId = attempt.bankId,
        questionId = attempt.questionId,
        decision = decision,
        previousVerdict = attempt.verdict,
        effectiveVerdict = effective,
        basisFingerprint = basis,
        decidedAtMillis = at,
    )

    // ---------------------------------------------------------------- 合并

    @Test
    fun leavingHistoryAloneWhenThereIsNothingDecided() {
        val history = listOf(attempt(verdict = Verdict.INCORRECT))
        val merged = AttemptReviews.apply(history, emptyList())
        assertEquals(history, merged)
    }

    @Test
    fun acceptReplacesEffectiveVerdictAndKeepsOriginalInMark() {
        val history = attempt(verdict = Verdict.CORRECT)
        val merged = AttemptReviews.apply(
            listOf(history),
            listOf(review(history, ReviewDecision.ACCEPT, Verdict.INCORRECT)),
        ).single()

        assertEquals(Verdict.INCORRECT, merged.verdict, "生效判定要跟着复核走，统计才会变")
        val mark = merged.review
        assertEquals(Verdict.CORRECT, mark?.from, "当时判的必须原样留着")
        assertEquals(Verdict.INCORRECT, mark?.to)
        assertTrue(mark!!.changed)
        // 原始对象没有被就地改写：历史文件那一行的语义就是"当时判的"
        assertEquals(Verdict.CORRECT, history.verdict)
    }

    @Test
    fun restoreKeepsVerdictButStillLeavesAVisibleMark() {
        val history = attempt(verdict = Verdict.CORRECT)
        val merged = AttemptReviews.apply(
            listOf(history),
            listOf(review(history, ReviewDecision.RESTORE, Verdict.CORRECT)),
        ).single()

        assertEquals(Verdict.CORRECT, merged.verdict)
        val mark = merged.review
        assertEquals(ReviewDecision.RESTORE, mark?.decision)
        assertFalse(mark!!.changed, "维持当时判定不该被显示成改判")
    }

    @Test
    fun latestDecisionWinsSoTheUserCanChangeTheirMind() {
        val history = attempt(verdict = Verdict.CORRECT)
        val merged = AttemptReviews.apply(
            listOf(history),
            listOf(
                review(history, ReviewDecision.ACCEPT, Verdict.INCORRECT, at = 5_000),
                review(history, ReviewDecision.RESTORE, Verdict.CORRECT, at = 6_000),
            ),
        ).single()

        assertEquals(Verdict.CORRECT, merged.verdict)
        assertEquals(ReviewDecision.RESTORE, merged.review?.decision)
    }

    // ---------------------------------------------------------------- 找差异

    @Test
    fun noCandidatesWhenEveryVerdictStillHolds() {
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(attempt(selected = setOf("a", "b", "c"))),
            reviews = emptyList(),
            registry = registry,
        )
        assertTrue(plan.isEmpty, "判定没变就不该摆到用户面前")
    }

    @Test
    fun answerChangeProducesCandidateWithBothAnswers() {
        // 作者把 c 也算进正确答案：当时答 a+b 是"正确"，按现在的标准是"漏选"
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(attempt(selected = setOf("a", "b"), verdict = Verdict.CORRECT)),
            reviews = emptyList(),
            registry = registry,
        )

        val candidate = plan.candidates.single()
        assertEquals(Verdict.CORRECT, candidate.previousVerdict)
        assertEquals(Verdict.INCORRECT, candidate.suggestedVerdict)
        assertEquals("A. A、B. B", candidate.previousAnswer, "当时的作答要按当时看到的顺序翻成字母")
        assertTrue(candidate.currentAnswer.contains("C. C"), "现在的标准答案要一起给出")
        assertEquals("测试题库", candidate.bankTitle)
        assertEquals("多选题 q2", candidate.prompt)
    }

    @Test
    fun frozenDisplayOrderIsUsedToLabelTheOldAnswer() {
        // 那次作答时选项被打乱成 b,a,c,d：用户看到的"A"其实是选项 b
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(
                attempt(
                    selected = setOf("b"),
                    display = listOf("b", "a", "c", "d"),
                    verdict = Verdict.CORRECT,
                ),
            ),
            reviews = emptyList(),
            registry = registry,
        )

        assertEquals("A. B", plan.candidates.single().previousAnswer)
    }

    @Test
    fun optionOrderChangeAloneIsNotACandidate() {
        // 题库里选项顺序换了，正确集合没变 —— 判定结果一样，用户没理由被打扰
        val plan = ReviewPlanner.plan(
            banks = listOf(
                bank(
                    listOf(
                        multi(
                            options = listOf(
                                QuestionOption("d", "D"),
                                QuestionOption("c", "C"),
                                QuestionOption("b", "B"),
                                QuestionOption("a", "A"),
                            ),
                        ),
                    ),
                ),
            ),
            attempts = listOf(attempt(selected = setOf("a", "b", "c"), verdict = Verdict.CORRECT)),
            reviews = emptyList(),
            registry = registry,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun abandonedAttemptsStayOutOfReview() {
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(attempt(selected = setOf("a"), verdict = Verdict.ABANDONED)),
            reviews = emptyList(),
            registry = registry,
        )
        assertTrue(plan.isEmpty, "放弃的作答本来就没有判定，没什么可复核的")
    }

    @Test
    fun decidedOnThisBasisIsNotAskedTwice() {
        val question = multi()
        val record = attempt(selected = setOf("a"), verdict = Verdict.CORRECT)
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(question))),
            attempts = listOf(record),
            reviews = listOf(review(record, ReviewDecision.RESTORE, Verdict.CORRECT, basis = question.contentFingerprint())),
            registry = registry,
        )
        assertTrue(plan.isEmpty, "已经就这一版内容表过态，就不该再问一遍")
    }

    @Test
    fun newContentReopensTheSameAttempt() {
        val question = multi()
        val record = attempt(selected = setOf("a"), verdict = Verdict.CORRECT)
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(question))),
            attempts = listOf(record),
            // 上一次决定依据的是另一版题目（basis 对不上）
            reviews = listOf(review(record, ReviewDecision.RESTORE, Verdict.CORRECT, basis = "旧版本的指纹")),
            registry = registry,
        )
        assertEquals(1, plan.count, "题目又改了，旧决定的前提就没了，得重新问")
    }

    @Test
    fun deletedQuestionsAreCountedInsteadOfSilentlyDropped() {
        val plan = ReviewPlanner.plan(
            banks = emptyList(),
            attempts = listOf(attempt(), attempt(at = 2_000)),
            reviews = emptyList(),
            registry = registry,
        )
        assertTrue(plan.isEmpty)
        assertEquals(2, plan.unreviewable, "做不了复核的记录要单独报数，不能让人以为都处理完了")
    }

    @Test
    fun shortAnswerFlipsWhenTheReferenceAnswerChanges() {
        val question = Question(
            questionId = "q3",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "什么是实践？",
            answerText = "实践是人类能动地改造世界的客观物质活动。",
        )
        val record = attempt(
            questionId = "q3",
            type = QuestionType.SHORT_ANSWER,
            selected = emptySet(),
            display = emptyList(),
            text = "实践是人类能动地改造世界的客观物质活动。",
            verdict = Verdict.INCORRECT, // 当时的标准答案不是这句，所以判错
        )

        val candidate = ReviewPlanner.plan(
            banks = listOf(bank(listOf(question))),
            attempts = listOf(record),
            reviews = emptyList(),
            registry = registry,
        ).candidates.single()

        assertEquals(Verdict.INCORRECT, candidate.previousVerdict)
        assertEquals(Verdict.CORRECT, candidate.suggestedVerdict)
        assertEquals("实践是人类能动地改造世界的客观物质活动。", candidate.previousAnswer)
    }

    @Test
    fun orderingIsReevaluatedBySequenceNotBySet() {
        val question = Question(
            questionId = "q4",
            type = QuestionType.ORDERING,
            promptMarkdown = "按顺序排列",
            options = listOf(
                QuestionOption("s1", "第一步"),
                QuestionOption("s2", "第二步"),
                QuestionOption("s3", "第三步"),
            ),
            correctOptionIds = setOf("s1", "s2", "s3"),
            grading = morefish.knowledge.trainer.domain.model.GradingSpec.Ordering(
                canonical = listOf("s1", "s2", "s3"),
            ),
        )
        val record = attempt(
            questionId = "q4",
            type = QuestionType.ORDERING,
            selected = setOf("s1", "s2", "s3"),
            display = listOf("s1", "s2", "s3"),
            verdict = Verdict.CORRECT,
            // 那次作答按当时的认可次序排成了 s2→s1→s3 并判对；作者后来把标准次序改成 s1→s2→s3
        ).copy(orderedOptionIds = listOf("s2", "s1", "s3"))

        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(question))),
            attempts = listOf(record),
            reviews = emptyList(),
            registry = registry,
        )

        // 记录说当时排对了，但按记录里的顺序重算并不是标准次序 → 需要用户确认
        val candidate = plan.candidates.single()
        assertEquals(Verdict.CORRECT, candidate.previousVerdict)
        assertEquals(Verdict.INCORRECT, candidate.suggestedVerdict)
    }

    @Test
    fun planIsOrderedBySubmissionTime() {
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(
                attempt(at = 3_000, selected = setOf("a")),
                attempt(at = 1_000, selected = setOf("a")),
                attempt(at = 2_000, selected = setOf("a")),
            ),
            reviews = emptyList(),
            registry = registry,
        )
        assertEquals(listOf(1_000L, 2_000L, 3_000L), plan.candidates.map { it.attempt.submittedAtMillis })
    }

    @Test
    fun staleOptionIdsAreShownAsIdsInsteadOfInventedLetters() {
        // 记录里的作答引用了题库已经没有的选项（题被作者改过）。复核页必须照实写 id ——
        // 编一个字母出来会让人以为"我当时真的选了 Z"，而当时根本没有这个选项
        val plan = ReviewPlanner.plan(
            banks = listOf(bank(listOf(multi()))),
            attempts = listOf(
                attempt(selected = setOf("a", "zzz"), verdict = Verdict.CORRECT),
            ),
            reviews = emptyList(),
            registry = registry,
        )
        val candidate = plan.candidates.single()
        assertEquals(Verdict.INCORRECT, candidate.suggestedVerdict)
        assertTrue(candidate.previousAnswer.contains("zzz"), "实际：${candidate.previousAnswer}")
        assertTrue(candidate.previousAnswer.contains("A. A"))
    }
}
