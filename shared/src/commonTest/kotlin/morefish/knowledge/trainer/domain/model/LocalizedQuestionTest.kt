package morefish.knowledge.trainer.domain.model

import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多语言的领域规则：翻译是"一层覆盖"，缺席就是缺席，不做逐字段拼凑。
 */
class LocalizedQuestionTest {

    private fun choice(
        id: String,
        prompt: String = "题干 $id",
        translated: Boolean = true,
        answerText: String? = null,
    ) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = prompt,
        options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
        correctOptionIds = setOf("o1"),
        contentRevision = 3,
        translations = if (!translated) {
            emptyMap()
        } else {
            mapOf(
                "en" to QuestionText(
                    locale = "en",
                    promptMarkdown = "Prompt $id",
                    optionTexts = mapOf("o1" to "first", "o2" to "second"),
                    explanationMarkdown = "Because.",
                    contentRevision = 7,
                ),
            )
        },
        answerText = answerText,
    )

    private fun bank(questions: List<Question>) = QuestionBank(
        bankId = "b1",
        title = "双语库",
        sourceLocale = "zh-Hans",
        questions = questions,
        importedAtMillis = 0,
        sourceLabel = "test",
        localeCoverage = listOf(
            LocaleCoverage("zh-Hans", questions.size, questions.size),
            LocaleCoverage("en", 1, questions.size),
        ),
    )

    @Test
    fun translationReplacesPromptAndOptionTextsByOptionId() {
        val english = assertNotNull(choice("q1").localized("en", "zh-Hans"))
        assertEquals("Prompt q1", english.promptMarkdown)
        assertEquals("first", english.option("o1")?.contentMarkdown)
        assertEquals("second", english.option("o2")?.contentMarkdown)
        // 身份与答案不变：翻译只换文案，不能让 A/B 顺序或正确项漂移
        assertEquals(setOf("o1"), english.correctOptionIds)
        assertEquals(listOf("o1", "o2"), english.options.map { it.optionId })
        // 版本按语言的：英文版第 7 版不妨碍中文还是第 3 版
        assertEquals(7, english.contentRevision)
        assertEquals("en", english.locale)
    }

    @Test
    fun localizedCopyDropsTranslationsSoSessionsStaySmall() {
        val english = assertNotNull(choice("q1").localized("en", "zh-Hans"))
        assertTrue(english.translations.isEmpty(), "会话里存的题面不该再拖着一整套译文")
    }

    @Test
    fun missingTranslationIsNullNotSilentSourceFallback() {
        // 关键：不返回原文。回退必须是调用方的显式选择，否则"没翻译"会被当成"翻译了但一样"
        assertNull(choice("q2", translated = false).localized("en", "zh-Hans"))
    }

    @Test
    fun partialTranslationCountsAsNoTranslation() {
        val broken = choice("q3").copy(
            translations = mapOf(
                "en" to QuestionText(
                    locale = "en",
                    promptMarkdown = "Prompt q3",
                    optionTexts = mapOf("o1" to "first"), // 少了 o2
                ),
            ),
        )
        assertNull(broken.localized("en", "zh-Hans"), "少一个选项译文就是没有这一份，不拼半句英文半句中文")
    }

    @Test
    fun shortAnswerWithoutTranslatedAnswerIsUnusable() {
        val short = Question(
            questionId = "q4",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "什么是实践？",
            answerText = "实践是人类能动地改造世界的客观物质活动。",
            translations = mapOf(
                "en" to QuestionText(
                    locale = "en",
                    promptMarkdown = "What is practice?",
                    answerText = null,
                ),
            ),
        )
        assertNull(short.localized("en", "zh-Hans"), "没有该语言的标准答案就无法判题，不能拿中文答案顶")
    }

    @Test
    fun sourceLocaleIsItsOwnCopy() {
        val source = assertNotNull(choice("q1").localized("zh-Hans", "zh-Hans"))
        assertEquals("题干 q1", source.promptMarkdown)
        assertEquals("zh-Hans", source.locale)
        assertTrue(source.translations.isEmpty())
    }

    @Test
    fun hasTextInOnlyCountsRealTranslations() {
        val question = choice("q1")
        assertTrue(question.hasTextIn("en"))
        assertFalse(question.hasTextIn("en-US"), "不做语言/地区模糊匹配")
        assertFalse(question.hasTextIn("ja"))
    }

    @Test
    fun questionsInSkipsUntranslatedByDefaultAndKeepsSourceOnRequest() {
        val q1 = choice("q1")
        val q2 = choice("q2", translated = false)
        val b = bank(listOf(q1, q2))

        val strict = b.questionsIn("en", LocaleFallback.SKIP)
        assertEquals(listOf("q1"), strict.map { it.questionId })

        val relaxed = b.questionsIn("en", LocaleFallback.ORIGINAL)
        assertEquals(listOf("q1", "q2"), relaxed.map { it.questionId })
        // 退回原文的那道题语言标记必须是源语言 —— 界面靠它显示「原文」
        assertEquals("zh-Hans", relaxed[1].locale)
        assertEquals("题干 q2", relaxed[1].promptMarkdown)
    }

    @Test
    fun sourceLanguagePracticeReturnsEverythingWithoutTranslationLookup() {
        val b = bank(listOf(choice("q1"), choice("q2", translated = false)))
        assertEquals(2, b.questionsIn("zh-Hans").size)
        assertEquals(listOf("zh-Hans", "en"), b.availableLocales)
        assertEquals(1, b.coverageOf("en").covered)
        assertEquals(2, b.coverageOf("en").total)
    }

    @Test
    fun coverageOfUnknownLocaleIsZeroNotCrash() {
        val coverage = bank(listOf(choice("q1"))).coverageOf("fr")
        assertEquals(0, coverage.covered)
        assertEquals(1, coverage.total)
    }
}
