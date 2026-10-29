package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.importing.QbankImporter
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 「专升本核心知识点题库」的导入与判题验收。
 *
 * 包里每道题的解析都是一张知识点卡片，另有 4 道按关键词判分的默写题。这里不只看"能不能导入"，
 * 而是把 43 道题**全部按标准答案答一遍**：任何一道的标准答案与它的题目对不上（选项 ID 错位、
 * 关键词写漏、判断题答案填反），这里就会红。这类错误在界面上看不出来 —— 用户只会遇到
 * "我明明选对了却判错"，而且只在碰到那一题时才发现。
 *
 * 包与源目录都在 tools/qbank/examples/ 下；不存在时跳过（这个测试需要在仓库内运行）。
 */
class ZhuanshengbenBankTest {

    private val bankFile: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples/zhuanshengben.qbank")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        File("")
    }

    private val grader = builtInGradingRegistry()

    private fun questions(): List<Question> {
        val file = bankFile
        if (!file.isFile) {
            println("跳过：找不到 tools/qbank/examples/zhuanshengben.qbank")
            return emptyList()
        }
        val parsed = QbankImporter.parse(file.readBytes(), PureDeflateCodec)
        return assertIs<QbankImporter.Result.Success>(parsed, "包应能解析：$parsed").bank.questions
    }

    private fun List<Question>.byId(id: String): Question =
        firstOrNull { it.questionId == id } ?: error("没有题目 $id；实际有 ${map { it.questionId }}")

    @Test
    fun parsesAllQuestionsWithTheirKnowledgeCards() {
        val all = questions()
        if (all.isEmpty()) return
        assertEquals(43, all.size, "题库共 43 题")
        assertEquals(24, all.count { it.type == QuestionType.SINGLE_CHOICE })
        assertEquals(5, all.count { it.type == QuestionType.MULTIPLE_CHOICE })
        assertEquals(10, all.count { it.type == QuestionType.TRUE_FALSE })
        assertEquals(4, all.count { it.type == QuestionType.SHORT_ANSWER })

        all.forEach { question ->
            val explanation = question.explanationMarkdown.orEmpty()
            assertTrue(
                explanation.startsWith("**知识点："),
                "${question.questionId} 的解析应以知识点卡片开头：$explanation",
            )
            assertTrue(question.tags.size >= 2, "${question.questionId} 应同时带知识点标签与学科标签")
            assertTrue(question.promptMarkdown.isNotBlank(), "${question.questionId} 题干为空")
        }

        // 默写题按关键词判分，必须真的带了关键词规则，否则会退化成 PENDING
        val shorts = all.filter { it.type == QuestionType.SHORT_ANSWER }
        shorts.forEach { question ->
            val grading = assertIs<GradingSpec.Keywords>(
                question.grading,
                "${question.questionId} 应声明 keywords 判题",
            )
            assertTrue(grading.required.isNotEmpty(), "${question.questionId} 没有关键词规则，将永远判 PENDING")
        }

        // 判断题固定两个选项，且答案只有一个
        all.filter { it.type == QuestionType.TRUE_FALSE }.forEach { question ->
            assertEquals(2, question.options.size, "${question.questionId} 判断题应恰好两个选项")
            assertEquals(1, question.correctOptionIds.size, "${question.questionId} 判断题答案应只有一个")
        }
    }

    /** 全部 43 题按标准答案作答，必须全判对。 */
    @Test
    fun everyQuestionAcceptsItsOwnCorrectAnswer() {
        val all = questions()
        if (all.isEmpty()) return
        all.forEach { question ->
            val answer = if (question.type == QuestionType.SHORT_ANSWER) {
                AnswerInput.Text(question.answerText.orEmpty())
            } else {
                AnswerInput.Choices(question.correctOptionIds)
            }
            val result = grader.evaluate(question, answer)
            assertEquals(
                Verdict.CORRECT,
                result.verdict,
                "${question.questionId} 按标准答案作答应判对；详情=${result.detail}",
            )
        }
    }

    /** 选错必须判错：单选/判断换一个非正确选项，多选漏选一项。 */
    @Test
    fun everyChoiceRejectsAWrongSelection() {
        val all = questions()
        if (all.isEmpty()) return
        all.filter { it.type != QuestionType.SHORT_ANSWER }.forEach { question ->
            val wrong = when (question.type) {
                QuestionType.MULTIPLE_CHOICE -> question.correctOptionIds.drop(1).toSet()
                else -> question.options.map { it.optionId }.first { it !in question.correctOptionIds }.let { setOf(it) }
            }
            assertEquals(
                Verdict.INCORRECT,
                grader.evaluate(question, AnswerInput.Choices(wrong)).verdict,
                "${question.questionId} 的错误作答 ${wrong} 应判错",
            )
        }
    }

    /** 默写题的三档：全命中判对、部分命中判部分正确、一个都没命中判错。 */
    @Test
    fun keywordQuestionsGradeInThreeSteps() {
        val all = questions()
        if (all.isEmpty()) return
        val question = all.byId("cs04")

        assertEquals(
            Verdict.CORRECT,
            grader.evaluate(
                question,
                AnswerInput.Text("十进制转其他进制用除基取余，其他进制转十进制用按权展开求和。"),
            ).verdict,
        )
        assertEquals(
            Verdict.PARTIALLY_CORRECT,
            grader.evaluate(question, AnswerInput.Text("十进制转二进制用除基取余法。")).verdict,
            "只写到一半应判部分正确，而不是直接判错",
        )
        assertEquals(
            Verdict.INCORRECT,
            grader.evaluate(question, AnswerInput.Text("我记不清了。")).verdict,
        )
        // 空作答不该被当成"答对"
        assertTrue(grader.evaluate(question, AnswerInput.Text("")).verdict != Verdict.CORRECT)
    }

    /** 解析里不能按"第几条"或选项 ID 指代选项：选项会打乱，用户对不上。 */
    @Test
    fun explanationsReferToOptionContentNotToPositions() {
        val all = questions()
        if (all.isEmpty()) return
        // 原始字符串里 $ 会被当模板，用 [$] 包一层避开
        val optionIdRef = Regex("""[$]o\d+[$]""")
        val letterRef = Regex("""选项\s*[A-D]""")
        all.filter { it.type != QuestionType.SHORT_ANSWER }.forEach { question ->
            val text = question.explanationMarkdown.orEmpty()
            assertTrue(
                optionIdRef.find(text) == null,
                "${question.questionId} 的解析用选项 ID 指代选项（如 \$o3\$）；" +
                    "选项顺序会被打乱，读者对不上，请改成引用选项内容",
            )
            assertTrue(
                letterRef.find(text) == null,
                "${question.questionId} 的解析用 A/B/C/D 指代选项；打乱后失去意义，请改成引用选项内容",
            )
        }
    }

    @Test
    fun importsIntoRepositoryAndStaysPracticeable() = runTest {
        val file = bankFile
        if (!file.isFile) {
            println("跳过：找不到 tools/qbank/examples/zhuanshengben.qbank")
        } else {
            val importer = ImportBankUseCase(JsonBankRepository(InMemoryTextStorage()))
            val outcome = importer.importQbank(file.readBytes(), "专升本题库", "zhuanshengben")
            val imported = assertIs<ImportOutcome.Imported>(outcome)
            assertEquals(43, imported.bank.questionCount)

            // 题干与选项文案都随包进来了（结构层与文案层对得上，不会出现"有题没字"）
            val multiple = imported.bank.questions.first { it.type == QuestionType.MULTIPLE_CHOICE }
            assertTrue(multiple.options.size >= 3, "${multiple.questionId} 的选项文案应已解析")
            assertTrue(multiple.options.all { it.contentMarkdown.isNotBlank() })

            // 再导一次必须被指纹识别成重复，而不是默默存两份
            assertIs<ImportOutcome.Duplicate>(importer.importQbank(file.readBytes(), "专升本题库", "zhuanshengben"))
        }
    }
}
