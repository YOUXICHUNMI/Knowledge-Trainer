package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.practice.PracticeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多语言练习的控制器级行为：默认语言、按语言抽题、按语言记历史、缺译文时的两种选择。
 *
 * 这里刻意不碰界面，只验证"控制器把语言当成了什么"——
 * 因为 UI 只是把这些状态画出来，判断错了 UI 再好看也没用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiLocalePracticeTest {

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private fun bank(): QuestionBank {
        fun choice(id: String, withEnglish: Boolean) = Question(
            questionId = id,
            type = QuestionType.SINGLE_CHOICE,
            promptMarkdown = "题干 $id",
            options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
            correctOptionIds = setOf("o1"),
            translations = if (!withEnglish) {
                emptyMap()
            } else {
                mapOf(
                    "en" to QuestionText(
                        locale = "en",
                        promptMarkdown = "Prompt $id",
                        optionTexts = mapOf("o1" to "first", "o2" to "second"),
                    ),
                )
            },
        )
        return QuestionBank(
            bankId = "bank-bilingual",
            title = "双语题库",
            sourceLocale = "zh-Hans",
            questions = listOf(choice("q1", true), choice("q2", false), choice("q3", true)),
            importedAtMillis = 0,
            sourceLabel = "test",
            localeCoverage = listOf(LocaleCoverage("zh-Hans", 3, 3), LocaleCoverage("en", 2, 3)),
        )
    }

    private fun controller(storage: InMemoryTextStorage, scheduler: TestCoroutineScheduler): AppController =
        AppController(
            deps = AppDependencies(storage, clock),
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )

    @Test
    fun defaultsToInterfaceLanguageAndSaysHowManyAreUntranslated() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-bilingual")
        testScheduler.advanceUntilIdle()

        // 界面是中文，题库源语言也是中文：默认就该练中文，而不是"有英文就练英文"
        assertEquals("zh-Hans", c.setup.locale)
        assertEquals(3, c.setup.candidateCount)
        assertEquals(0, c.setup.untranslated)
        assertEquals(listOf("zh-Hans", "en"), c.setup.locales)
    }

    @Test
    fun switchingLanguageShrinksCandidatesInsteadOfSubstituting() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-bilingual")
        c.setup.selectLocale("en")
        testScheduler.advanceUntilIdle()

        assertEquals(2, c.setup.candidateCount, "英文只翻了 2 题，就该只有 2 题可抽")
        assertEquals(1, c.setup.untranslated, "q2 没有英文译文")
        assertEquals(2, c.setup.coverage?.covered)
        assertEquals(3, c.setup.coverage?.total)

        // 打开「缺译文时看原文」才把没译文的题放回来
        c.setup.toggleFallback()
        assertEquals(LocaleFallback.ORIGINAL, c.setup.fallback)
        assertEquals(3, c.setup.candidateCount)
    }

    @Test
    fun sessionAndAttemptsRecordTheLanguageTheyWereAnsweredIn() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-bilingual")
        c.setup.selectLocale("en")
        c.setup.selectCount(null)
        c.startPlannedPractice()
        testScheduler.advanceUntilIdle()

        assertEquals("en", c.practice.session?.locale)
        assertEquals(2, c.practice.session?.items?.size)
        // 会话里的题面已经是英文，判题会用英文选项，不是中文
        assertTrue(c.practice.session!!.items.all { it.question.promptMarkdown.startsWith("Prompt") })

        c.practice.session!!.current!!.question.correctOptionIds.forEach { c.practice.toggleOption(it) }
        c.practice.submit()
        testScheduler.advanceUntilIdle()

        assertEquals("en", c.history.last().locale)
        assertEquals(listOf("en"), c.historyView.locales)
    }

    @Test
    fun historiesAreIndependentPerLanguage() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-bilingual")
        c.setup.selectLocale("en")
        c.startPlannedPractice()
        testScheduler.advanceUntilIdle()
        c.practice.session!!.current!!.question.correctOptionIds.forEach { c.practice.toggleOption(it) }
        c.practice.submit()
        testScheduler.advanceUntilIdle()

        // 英文里答过一道题，不该让中文的「未练习」少一题
        c.openSetup("bank-bilingual")
        c.setup.selectScope(PracticeScope.UNPRACTICED)
        testScheduler.advanceUntilIdle()
        assertEquals(3, c.setup.scopeCount(PracticeScope.UNPRACTICED), "中文视角下这 3 题都没练过")

        c.setup.selectLocale("en")
        testScheduler.advanceUntilIdle()
        assertEquals(1, c.setup.scopeCount(PracticeScope.UNPRACTICED), "英文视角下只剩 q3 没练")
    }

    @Test
    fun historyCanBeFilteredByLanguage() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()

        // 中文练一题
        c.startPractice(c.banks.single(), questionCount = 1)
        testScheduler.advanceUntilIdle()
        c.practice.session!!.current!!.question.correctOptionIds.forEach { c.practice.toggleOption(it) }
        c.practice.submit()
        testScheduler.advanceUntilIdle()
        c.finish()
        testScheduler.advanceUntilIdle()

        // 英文练一题
        c.openSetup("bank-bilingual")
        c.setup.selectLocale("en")
        c.startPlannedPractice()
        testScheduler.advanceUntilIdle()
        c.practice.session!!.current!!.question.correctOptionIds.forEach { c.practice.toggleOption(it) }
        c.practice.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("en", "zh-Hans"), c.historyView.locales)
        assertEquals(2, c.historyView.filtered.size)

        c.historyView.selectLocale("en")
        assertEquals(1, c.historyView.filtered.size)
        assertEquals("en", c.historyView.filtered.single().locale)

        c.historyView.selectLocale("zh-Hans")
        assertEquals(1, c.historyView.filtered.size)

        c.historyView.selectLocale(null)
        assertEquals(2, c.historyView.filtered.size)
    }

    @Test
    fun fallingBackToOriginalIsVisibleOnTheQuestion() = runTest {
        val storage = InMemoryTextStorage()
        AppDependencies(storage, clock).bankRepository.save(bank())

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-bilingual")
        c.setup.selectLocale("en")
        c.setup.toggleFallback()
        c.startPlannedPractice()
        testScheduler.advanceUntilIdle()

        // 会话里每道题都带着自己的语言：有译文的是 en，缺译文退回原文的是 zh-Hans
        val byLocale = c.practice.session!!.items.groupBy { it.question.locale }
        assertEquals(2, byLocale["en"]?.size, "两道有译文的题应标成 en")
        val original = assertNotNull(byLocale["zh-Hans"]).single()
        assertEquals("q2", original.question.questionId)
        assertTrue(original.question.promptMarkdown.startsWith("题干"), "退回的是原文，不是凭空造的译文")

        // 当前题是否显示原文，与它自己的语言一致 —— 界面靠这个标记决定要不要挂「原文」徽章
        val current = assertNotNull(c.practice.currentQuestion)
        assertEquals(
            current.locale == "zh-Hans",
            c.practice.currentQuestionShowsOriginal,
            "「原文」徽章必须跟着当前题实际用的语言走",
        )
    }

    @Test
    fun saysTranslationIsMissingRatherThanPretendingThereIsNoQuestion() = runTest {
        val storage = InMemoryTextStorage()
        val untranslatedOnly = QuestionBank(
            bankId = "bank-zh-only",
            title = "只有中文",
            sourceLocale = "zh-Hans",
            questions = listOf(
                Question(
                    questionId = "only",
                    type = QuestionType.SINGLE_CHOICE,
                    promptMarkdown = "只有中文的题干",
                    options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
                    correctOptionIds = setOf("o1"),
                ),
            ),
            importedAtMillis = 0,
            sourceLabel = "test",
            localeCoverage = listOf(LocaleCoverage("zh-Hans", 1, 1), LocaleCoverage("en", 0, 1)),
        )
        AppDependencies(storage, clock).bankRepository.save(untranslatedOnly)

        val c = controller(storage, testScheduler)
        c.refresh()
        c.openSetup("bank-zh-only")
        c.setup.selectLocale("en")

        assertEquals(0, c.setup.candidateCount)
        assertEquals(1, c.setup.untranslated)
        assertNull(c.practice.session)

        c.startPlannedPractice()

        // 原因必须说"这门语言没译文"，而不是含糊的"没有可练习的题目" ——
        // 后者会让人以为题库坏了、去翻题库，实际上只是没翻译。
        val banner = assertNotNull(c.banner)
        assertTrue(banner.isError)
        assertTrue(banner.text.contains("译文"), "实际横幅：${banner.text}")
        assertNull(c.practice.session)
    }
}
