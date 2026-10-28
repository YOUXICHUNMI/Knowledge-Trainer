package morefish.knowledge.trainer.presentation.history

import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 历史页的投影规则：四个筛选**叠加**，两份派生视图**不跟着筛选走**。
 *
 * 后者是这里最容易被改坏的一条：把日历按 `filtered` 算，用户切到"只看错题"，
 * 那天的学习记录就从日历上消失了 —— 他那天明明在学，只是没答错。
 */
class HistoryControllerTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    /** 被观察的"历史"。做成可变量，用来模拟组合根重读了历史。 */
    private var source: List<AttemptRecord> = emptyList()

    private fun controller(): HistoryController {
        val controller = HistoryController(clock = WallClock { now }) { source }
        controller.update(source)
        return controller
    }

    private fun rec(
        id: String,
        daysAgo: Long = 0,
        bank: String = "ham",
        verdict: Verdict = Verdict.CORRECT,
        locale: String = "",
        question: String = "q-$id",
    ): AttemptRecord = AttemptRecord(
        attemptId = id,
        sessionId = "s-$id",
        bankId = bank,
        questionId = question,
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
        submittedAtMillis = now - daysAgo * day,
        elapsedMillis = 1_000,
        explanationSeen = false,
        locale = locale,
    )

    @Test
    fun noFilterKeepsEverythingNewestFirst() {
        source = listOf(rec("old", daysAgo = 40), rec("new"), rec("mid", daysAgo = 3))
        val controller = controller()
        controller.selectRange(HistoryRange.ALL)

        assertEquals(listOf("new", "mid", "old"), controller.filtered.map { it.attemptId })
    }

    @Test
    fun timeWindowDropsRecordsOlderThanTheRange() {
        val controller = controller()
        source = listOf(rec("today"), rec("sixDays", daysAgo = 6), rec("eightDays", daysAgo = 8))
        controller.update(source)

        controller.selectRange(HistoryRange.ALL)
        assertEquals(3, controller.filtered.size)

        controller.selectRange(HistoryRange.WEEK)
        assertEquals(listOf("today", "sixDays"), controller.filtered.map { it.attemptId })
    }

    @Test
    fun bankVerdictAndLocaleFiltersCompose() {
        source = listOf(
            rec("a", bank = "ham", verdict = Verdict.CORRECT, locale = "zh-Hans"),
            rec("b", bank = "ham", verdict = Verdict.INCORRECT, locale = "zh-Hans"),
            rec("c", bank = "ham", verdict = Verdict.INCORRECT, locale = "en"),
            rec("d", bank = "extra", verdict = Verdict.INCORRECT, locale = "zh-Hans"),
        )
        val controller = controller()
        controller.selectRange(HistoryRange.ALL)

        // 四条记录的提交时刻相同，所以这里同时钉住"稳定排序"：不因为筛选而打乱顺序
        controller.selectBank("ham")
        assertEquals(listOf("a", "b", "c"), controller.filtered.map { it.attemptId })

        controller.selectVerdict(Verdict.INCORRECT)
        assertEquals(listOf("b", "c"), controller.filtered.map { it.attemptId })

        controller.selectLocale("zh-Hans")
        assertEquals(listOf("b"), controller.filtered.map { it.attemptId })

        // 清掉一个条件就松开一层，而不是要求用户全部重置
        controller.selectLocale(null)
        assertEquals(listOf("b", "c"), controller.filtered.map { it.attemptId })
    }

    @Test
    fun localesAreNormalisedDeduplicatedAndIgnoreBlank() {
        source = listOf(
            rec("a", locale = "en_US"),
            rec("b", locale = "en-US"),
            rec("c", locale = "zh-hans"),
            rec("d", locale = ""),
            rec("e", locale = "zh-Hans"),
        )

        assertEquals(listOf("en-US", "zh-Hans"), controller().locales)

        // 筛选按归一化后的值比对：存的是 en_US，选 en-US 也要能选中
        val controller = controller()
        controller.selectRange(HistoryRange.ALL)
        controller.selectLocale("en-US")
        assertEquals(setOf("a", "b"), controller.filtered.map { it.attemptId }.toSet())
    }

    @Test
    fun calendarAndWrongBookFollowFullHistoryNotFilters() {
        source = listOf(
            rec("wrong-1", bank = "ham", verdict = Verdict.INCORRECT),
            rec("right-1", bank = "extra", verdict = Verdict.CORRECT),
        )
        val controller = controller()
        controller.selectRange(HistoryRange.TODAY)
        controller.selectBank("extra")
        controller.selectVerdict(Verdict.CORRECT)

        assertEquals(listOf("right-1"), controller.filtered.map { it.attemptId })

        // 筛选只影响列表；日历与错题本仍按全量历史算
        assertEquals(2, controller.calendar.totalAttempts, "切到「只看已答对」不该让日历上的学习记录消失")
        assertEquals(listOf("q-wrong-1"), controller.wrongEntries.map { it.questionId })
        assertEquals("ham", controller.wrongEntries.single().bankId)
    }
}
