package morefish.knowledge.trainer.domain

import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SegmentKind
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GradingTest {

    private val registry = builtInGradingRegistry()

    private val fourOptions = listOf(
        QuestionOption("o1", "甲"),
        QuestionOption("o2", "乙"),
        QuestionOption("o3", "丙"),
        QuestionOption("o4", "丁"),
    )

    private fun multiple(vararg correct: String) = Question(
        questionId = "q",
        type = QuestionType.MULTIPLE_CHOICE,
        promptMarkdown = "多选题",
        options = fourOptions,
        correctOptionIds = correct.toSet(),
    )

    private fun single(correct: String) = Question(
        questionId = "q",
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "单选题",
        options = fourOptions,
        correctOptionIds = setOf(correct),
    )

    private fun choice(vararg ids: String) = AnswerInput.Choices(ids.toSet())

    @Test
    fun multipleChoiceIgnoresSelectionOrder() {
        val question = multiple("o1", "o2", "o3")
        assertEquals(Verdict.CORRECT, registry.evaluate(question, choice("o3", "o1", "o2")).verdict)
        assertEquals(Verdict.CORRECT, registry.evaluate(question, choice("o2", "o3", "o1")).verdict)
    }

    @Test
    fun multipleChoiceReportsMissingOptions() {
        val question = multiple("o1", "o2", "o3")
        val result = registry.evaluate(question, choice("o1", "o2"))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Choice>(result.detail)
        assertEquals(setOf("o3"), detail.missing)
        assertEquals(emptySet(), detail.extra)
    }

    @Test
    fun multipleChoiceReportsExtraOptions() {
        val question = multiple("o1", "o2", "o3")
        val result = registry.evaluate(question, choice("o1", "o2", "o4"))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Choice>(result.detail)
        assertEquals(setOf("o3"), detail.missing)
        assertEquals(setOf("o4"), detail.extra)
    }

    @Test
    fun multipleChoiceSelectingEverythingIsStillWrong() {
        val question = multiple("o1", "o2", "o3")
        val result = registry.evaluate(question, choice("o1", "o2", "o3", "o4"))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Choice>(result.detail)
        assertEquals(emptySet(), detail.missing)
        assertEquals(setOf("o4"), detail.extra)
    }

    @Test
    fun emptySelectionIsIncorrectNotPending() {
        val result = registry.evaluate(multiple("o1"), AnswerInput.Empty)
        assertEquals(Verdict.INCORRECT, result.verdict)
    }

    @Test
    fun singleChoiceMatchesExactOption() {
        val question = single("o2")
        assertEquals(Verdict.CORRECT, registry.evaluate(question, choice("o2")).verdict)
        assertEquals(Verdict.INCORRECT, registry.evaluate(question, choice("o1")).verdict)
    }

    @Test
    fun shuffledDisplayOrderDoesNotMoveCorrectAnswer() {
        val question = single("o2")
        // 界面把选项倒序显示：D C B A
        val item = SessionItem(question, listOf("o4", "o3", "o2", "o1"))

        assertEquals("C", item.labelOf("o2"))
        assertEquals(Verdict.CORRECT, registry.evaluate(item.question, choice("o2")).verdict)
        // 用户点了 C（显示序号），提交的仍然是稳定 ID o2
        assertEquals("o2", item.displayOptionIds[2])
    }

    @Test
    fun trueFalseUsesSameSetSemantics() {
        val question = Question(
            questionId = "tf",
            type = QuestionType.TRUE_FALSE,
            promptMarkdown = "判断题",
            options = listOf(QuestionOption("T", "正确"), QuestionOption("F", "错误")),
            correctOptionIds = setOf("F"),
        )
        assertEquals(Verdict.CORRECT, registry.evaluate(question, choice("F")).verdict)
        assertEquals(Verdict.INCORRECT, registry.evaluate(question, choice("T")).verdict)
    }

    @Test
    fun strictTextRequiresExactCharacters() {
        val question = Question(
            questionId = "s",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "默写",
            answerText = "实践是人类改造世界的活动。",
            grading = GradingSpec.StrictText,
        )

        assertEquals(Verdict.CORRECT, registry.evaluate(question, AnswerInput.Text("实践是人类改造世界的活动。")).verdict)
        assertEquals(Verdict.INCORRECT, registry.evaluate(question, AnswerInput.Text("实践是人类改造世界的活动")).verdict)
        assertEquals(
            Verdict.CORRECT,
            registry.evaluate(question, AnswerInput.Text("实践是人类改造世界的活动。")).verdict,
        )
    }

    @Test
    fun strictTextExplainsWhitespaceDifference() {
        val question = Question(
            questionId = "s",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "默写",
            answerText = "abc def",
            grading = GradingSpec.StrictText,
        )
        val result = registry.evaluate(question, AnswerInput.Text("abc  def"))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.TextDiff>(result.detail)
        assertTrue(detail.notes.any { it.contains("空白") }, "应给出空白差异说明，实际：${detail.notes}")
    }

    @Test
    fun strictTextDiffMarksDeletedAndInsertedSegments() {
        val question = Question(
            questionId = "s",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "默写",
            answerText = "abcd",
            grading = GradingSpec.StrictText,
        )
        val result = registry.evaluate(question, AnswerInput.Text("abXd"))
        val detail = assertIs<EvaluationDetail.TextDiff>(result.detail)

        val deleted = detail.segments.filter { it.kind == SegmentKind.DELETED }.joinToString("") { it.text }
        val inserted = detail.segments.filter { it.kind == SegmentKind.INSERTED }.joinToString("") { it.text }
        assertEquals("c", deleted)
        assertEquals("X", inserted)
    }

    @Test
    fun unsupportedStrategyFallsBackToPendingInsteadOfWrong() {
        val question = Question(
            questionId = "weird",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "简答但声明了集合策略",
            answerText = "x",
            grading = GradingSpec.ExactSet,
        )
        val result = registry.evaluate(question, AnswerInput.Text("x"))

        assertEquals(Verdict.PENDING, result.verdict)
        assertEquals("unresolved", result.strategyId)
    }

    @Test
    fun questionWithoutAnswerIsPending() {
        val question = Question(
            questionId = "no-answer",
            type = QuestionType.SINGLE_CHOICE,
            promptMarkdown = "没有答案",
            options = fourOptions,
        )
        assertEquals(Verdict.PENDING, registry.evaluate(question, choice("o1")).verdict)
    }

    // ---- 默知识点（keywords） ----

    private fun keywordsQuestion(
        required: List<String>,
        forbidden: List<String> = emptyList(),
    ) = Question(
        questionId = "kw",
        type = QuestionType.SHORT_ANSWER,
        promptMarkdown = "默知识点",
        answerText = "参考答案（判题只看关键词）",
        grading = GradingSpec.Keywords(required = required, forbidden = forbidden),
    )

    @Test
    fun keywordsAllHitIsCorrect() {
        val question = keywordsQuestion(listOf("实践", "能动"))
        assertEquals(Verdict.CORRECT, registry.evaluate(question, AnswerInput.Text("实践是能动的活动")).verdict)
    }

    @Test
    fun keywordsPartialHitIsPartiallyCorrect() {
        val question = keywordsQuestion(listOf("实践", "能动", "客观"))
        val result = registry.evaluate(question, AnswerInput.Text("实践很重要"))

        assertEquals(Verdict.PARTIALLY_CORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Keywords>(result.detail)
        assertEquals(listOf("实践"), detail.present)
        assertEquals(setOf("能动", "客观"), detail.missing.toSet())
    }

    @Test
    fun keywordsNoneHitIsIncorrect() {
        val question = keywordsQuestion(listOf("实践", "能动"))
        assertEquals(Verdict.INCORRECT, registry.evaluate(question, AnswerInput.Text("完全无关的回答")).verdict)
    }

    @Test
    fun keywordsForbiddenWordMeansIncorrect() {
        val question = keywordsQuestion(listOf("实践"), forbidden = listOf("迷信"))
        val result = registry.evaluate(question, AnswerInput.Text("实践是迷信的活动"))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Keywords>(result.detail)
        assertEquals(listOf("迷信"), detail.forbiddenHit)
    }

    @Test
    fun keywordsCaseInsensitiveForLatinLetters() {
        val question = keywordsQuestion(listOf("HTTP"))
        assertEquals(Verdict.CORRECT, registry.evaluate(question, AnswerInput.Text("http 协议")).verdict)
    }

    @Test
    fun keywordsWithoutRulesIsPendingNotWrong() {
        val question = Question(
            questionId = "kw-empty",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "没有规则",
            answerText = "参考答案",
            grading = GradingSpec.Keywords(),
        )
        assertEquals(Verdict.PENDING, registry.evaluate(question, AnswerInput.Text("任何回答")).verdict)
    }

    // ---- 语义重组（ordering） ----

    private fun orderingQuestion(
        canonical: List<String>,
        alternatives: List<List<String>> = emptyList(),
    ): Question {
        val fragments = listOf(
            QuestionOption("f1", "主体"),
            QuestionOption("f2", "谓语"),
            QuestionOption("f3", "宾语"),
            QuestionOption("f4", "干扰片段"),
        )
        return Question(
            questionId = "ord",
            type = QuestionType.ORDERING,
            promptMarkdown = "把片段排成正确次序",
            options = fragments,
            correctOptionIds = setOf("f1", "f2", "f3"),
            grading = GradingSpec.Ordering(canonical = canonical, alternatives = alternatives),
        )
    }

    @Test
    fun orderingCanonicalOrderIsCorrect() {
        val question = orderingQuestion(listOf("f1", "f2", "f3"))
        assertEquals(
            Verdict.CORRECT,
            registry.evaluate(question, AnswerInput.Ordering(listOf("f1", "f2", "f3"))).verdict,
        )
    }

    @Test
    fun orderingAcceptsDeclaredAlternativeOrder() {
        val question = orderingQuestion(listOf("f1", "f2", "f3"), alternatives = listOf(listOf("f2", "f1", "f3")))
        assertEquals(
            Verdict.CORRECT,
            registry.evaluate(question, AnswerInput.Ordering(listOf("f2", "f1", "f3"))).verdict,
        )
        assertEquals(
            Verdict.INCORRECT,
            registry.evaluate(question, AnswerInput.Ordering(listOf("f3", "f2", "f1"))).verdict,
        )
    }

    @Test
    fun orderingSameSetWrongOrderReportsIt() {
        val question = orderingQuestion(listOf("f1", "f2", "f3"))
        val result = registry.evaluate(question, AnswerInput.Ordering(listOf("f3", "f1", "f2")))

        assertEquals(Verdict.INCORRECT, result.verdict)
        val detail = assertIs<EvaluationDetail.Ordering>(result.detail)
        assertTrue(detail.sameSet, "片段一致仅次序不同，应标记 sameSet")
    }

    @Test
    fun orderingMissingOrExtraFragmentsReported() {
        val question = orderingQuestion(listOf("f1", "f2", "f3"))

        val missing = registry.evaluate(question, AnswerInput.Ordering(listOf("f1", "f2")))
        val detailMissing = assertIs<EvaluationDetail.Ordering>(missing.detail)
        assertEquals(Verdict.INCORRECT, missing.verdict)
        assertEquals(listOf("f3"), detailMissing.missing)

        val extra = registry.evaluate(question, AnswerInput.Ordering(listOf("f1", "f2", "f3", "f4")))
        val detailExtra = assertIs<EvaluationDetail.Ordering>(extra.detail)
        assertEquals(listOf("f4"), detailExtra.extra)
    }

    @Test
    fun orderingCanonicalDerivedFromOptionOrderWhenAbsent() {
        // options 顺序是 f1,f2,f3,f4；正确片段相对顺序 = f1,f2,f3
        val question = Question(
            questionId = "ord2",
            type = QuestionType.ORDERING,
            promptMarkdown = "未声明 canonical",
            options = listOf(
                QuestionOption("f1", "一"),
                QuestionOption("f2", "二"),
                QuestionOption("f3", "三"),
                QuestionOption("f4", "干扰"),
            ),
            correctOptionIds = setOf("f1", "f2", "f3"),
            grading = GradingSpec.Ordering(),
        )
        assertEquals(
            Verdict.CORRECT,
            registry.evaluate(question, AnswerInput.Ordering(listOf("f1", "f2", "f3"))).verdict,
        )
    }

    @Test
    fun orderingQuestionValidationRequiresCanonicalConsistency() {
        val bad = Question(
            questionId = "bad",
            type = QuestionType.ORDERING,
            promptMarkdown = "canonical 与正确集合不一致",
            options = listOf(QuestionOption("a", "甲"), QuestionOption("b", "乙"), QuestionOption("c", "丙")),
            correctOptionIds = setOf("a", "b"),
            grading = GradingSpec.Ordering(canonical = listOf("a", "c")),
        )
        assertTrue(bad.validate().any { it.code == "canonical-mismatch" })
    }
}
