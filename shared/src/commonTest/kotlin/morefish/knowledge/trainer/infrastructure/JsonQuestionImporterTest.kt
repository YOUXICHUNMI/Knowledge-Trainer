package morefish.knowledge.trainer.infrastructure

import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.ImportParseResult
import morefish.knowledge.trainer.infrastructure.importing.JsonQuestionImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonQuestionImporterTest {

    private val importer = JsonQuestionImporter()

    private fun ok(raw: String, mapping: ChoiceMapping = ChoiceMapping.AUTO) =
        assertIs<ImportParseResult.Ok>(importer.parse(raw, mapping)).preview

    @Test
    fun simpleQuestionAnswerArrayBecomesShortAnswer() {
        val preview = ok("""[{"question":"什么是实践？","answer":"实践是人类能动地改造世界的客观物质活动。"}]""")

        assertEquals(1, preview.accepted.size)
        val question = preview.accepted.single()
        assertEquals(QuestionType.SHORT_ANSWER, question.type)
        assertEquals("实践是人类能动地改造世界的客观物质活动。", question.answerText)
    }

    @Test
    fun typedSingleChoiceKeepsStableOptionIds() {
        val preview = ok(
            """
            [{"questionId":"q1","type":"single_choice","question":"题干",
              "options":[{"optionId":"a","contentMarkdown":"甲"},{"optionId":"b","contentMarkdown":"乙"}],
              "correctOptionIds":["b"]}]
            """.trimIndent(),
        )
        val question = preview.accepted.single()
        assertEquals("q1", question.questionId)
        assertEquals(QuestionType.SINGLE_CHOICE, question.type)
        assertEquals(setOf("b"), question.correctOptionIds)
        assertEquals(listOf("a", "b"), question.options.map { it.optionId })
    }

    @Test
    fun numericAnswerIsTreatedAsOneBasedIndex() {
        val preview = ok("""[{"question":"q","options":["甲","乙","丙"],"correctOptionIds":["2"]}]""")
        assertEquals(setOf("o2"), preview.accepted.single().correctOptionIds)
    }

    @Test
    fun ambiguousMultiLetterAnswerRequiresUserMapping() {
        val raw = """[{"question":"q","options":["甲","乙","丙"],"answer":"AB"}]"""

        val auto = ok(raw)
        assertTrue(auto.needsMapping)
        assertTrue(auto.accepted.isEmpty())
        assertEquals(1, auto.rejected.size)

        val forced = ok(raw, ChoiceMapping.MULTIPLE)
        assertFalse(forced.needsMapping)
        assertEquals(QuestionType.MULTIPLE_CHOICE, forced.accepted.single().type)
        assertEquals(setOf("o1", "o2"), forced.accepted.single().correctOptionIds)
    }

    @Test
    fun forcingSingleChoiceWithMultiAnswerIsRejected() {
        val preview = ok(
            """[{"question":"q","options":["甲","乙","丙"],"answer":"AB"}]""",
            ChoiceMapping.SINGLE,
        )
        assertTrue(preview.accepted.isEmpty())
        assertEquals(1, preview.rejected.size)
    }

    @Test
    fun flatLetterFieldsAreSupported() {
        val preview = ok("""[{"question":"q","A":"甲","B":"乙","C":"丙","answer":"B"}]""")
        val question = preview.accepted.single()

        assertEquals(QuestionType.SINGLE_CHOICE, question.type)
        assertEquals(listOf("A", "B", "C"), question.options.map { it.optionId })
        assertEquals(setOf("B"), question.correctOptionIds)
    }

    @Test
    fun ndjsonIsDetected() {
        val raw = """
            {"question":"q1","answer":"a1"}
            {"question":"q2","answer":"a2"}
        """.trimIndent()
        val preview = ok(raw)

        assertEquals(2, preview.accepted.size)
        assertTrue(preview.notes.any { it.contains("NDJSON") })
    }

    @Test
    fun utf8BomIsStripped() {
        val preview = ok("\uFEFF[{\"question\":\"q\",\"answer\":\"a\"}]")
        assertEquals(1, preview.accepted.size)
    }

    @Test
    fun duplicateQuestionIdsAreRejected() {
        val preview = ok(
            """
            [{"questionId":"same","question":"q1","answer":"a"},
             {"questionId":"same","question":"q2","answer":"b"}]
            """.trimIndent(),
        )
        assertEquals(1, preview.accepted.size)
        assertEquals(1, preview.rejected.size)
        assertTrue(preview.rejected.single().issues.any { it.code == "duplicate-question-id" })
    }

    @Test
    fun missingAnswerIsRejectedWithReason() {
        val preview = ok("""[{"question":"只有题干"}]""")
        assertTrue(preview.accepted.isEmpty())
        assertTrue(preview.rejected.single().issues.any { it.message.contains("缺少答案") })
    }

    @Test
    fun unparsableInputFails() {
        val result = importer.parse("这不是 JSON")
        assertIs<ImportParseResult.Failed>(result)
    }

    @Test
    fun questionsWrappedInObjectAreSupported() {
        val preview = ok("""{"title":"t","questions":[{"question":"q","answer":"a"}]}""")
        assertEquals(1, preview.accepted.size)
    }

    @Test
    fun trueFalseWithoutOptionsGetsStableChoices() {
        val preview = ok("""[{"question":"地球是圆的","type":"true_false","answer":"正确"}]""")
        val question = preview.accepted.single()

        assertEquals(QuestionType.TRUE_FALSE, question.type)
        assertEquals(listOf("T", "F"), question.options.map { it.optionId })
        assertEquals(setOf("T"), question.correctOptionIds)
    }

    @Test
    fun noQuestionIdGeneratesDeterministicPlaceholder() {
        val preview = ok("""[{"question":"q","answer":"a"}]""")
        assertEquals("q-0001", preview.accepted.single().questionId)
    }

    // ---- P4：默知识点 / 语义重组 ----

    @Test
    fun keywordsShortAnswerImportsWithRules() {
        val preview = ok(
            """
            [{"question":"什么是实践？","answer":"实践是人类能动地改造世界的客观物质活动。",
              "grading":{"strategy":"keywords","required":["实践","能动","改造"],"forbidden":["被动"]}}]
            """.trimIndent(),
        )
        val question = preview.accepted.single()

        assertEquals(QuestionType.SHORT_ANSWER, question.type)
        val grading = assertIs<GradingSpec.Keywords>(question.grading)
        assertEquals(listOf("实践", "能动", "改造"), grading.required)
        assertEquals(listOf("被动"), grading.forbidden)
    }

    @Test
    fun keywordsShorthandFieldsAreAccepted() {
        val preview = ok("""[{"question":"q","answer":"a","requiredKeywords":["x"],"forbiddenKeywords":["y"]}]""")
        val grading = assertIs<GradingSpec.Keywords>(preview.accepted.single().grading)
        assertEquals(listOf("x"), grading.required)
        assertEquals(listOf("y"), grading.forbidden)
    }

    @Test
    fun orderingImportsCanonicalFromAnswerOrder() {
        val preview = ok(
            """
            [{"questionId":"ord1","type":"ordering","question":"排出正确次序",
              "options":[{"optionId":"f1","text":"主体"},{"optionId":"f2","text":"谓语"},{"optionId":"f3","text":"宾语"},{"optionId":"f4","text":"干扰"}],
              "answer":["f1","f2","f3"],
              "grading":{"strategy":"ordering","alternatives":[["f2","f1","f3"]]}}]
            """.trimIndent(),
        )
        val question = preview.accepted.single()

        assertEquals(QuestionType.ORDERING, question.type)
        val grading = assertIs<GradingSpec.Ordering>(question.grading)
        assertEquals(listOf("f1", "f2", "f3"), grading.canonical)
        assertEquals(listOf(listOf("f2", "f1", "f3")), grading.alternatives)
        assertEquals(setOf("f1", "f2", "f3"), question.correctOptionIds)
    }

    @Test
    fun orderingWithSingleFragmentIsRejected() {
        val preview = ok(
            """
            [{"type":"ordering","question":"只有一片段",
              "options":[{"optionId":"f1","text":"唯一"}],"answer":["f1"]}]
            """.trimIndent(),
        )
        assertTrue(preview.accepted.isEmpty())
        assertTrue(preview.rejected.single().issues.any { it.code == "ordering-fragments" })
    }

    @Test
    fun badNdjsonLineOnlyCostsThatLine() {
        val raw = """
            {"question":"q1","answer":"a1"}
            {这一行不是 JSON}
            {"question":"q3","answer":"a3"}
        """.trimIndent()
        val preview = ok(raw)

        assertEquals(2, preview.accepted.size, "一行坏数据不该把另外两题一起拖下水")
        assertEquals(1, preview.rejected.size)
        assertEquals(2, preview.rejected.single().ordinal, "拒绝项要指向出问题的那一行")
        assertTrue(preview.rejected.single().issues.any { it.code == "line-not-json" })
    }

    @Test
    fun ndjsonWithoutIdsNumbersQuestionsByLine() {
        val preview = ok(
            """
            {"question":"q1","answer":"a1"}
            {"question":"q2","answer":"a2"}
            """.trimIndent(),
        )

        assertEquals(listOf("q-0001", "q-0002"), preview.accepted.map { it.questionId })
    }
}
