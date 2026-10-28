package morefish.knowledge.trainer.presentation.history

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 历史页的投影规则：四个筛选**叠加**，两份派生视图**不跟着筛选走**。
 *
 * 后者是这里最容易被改坏的一条：把日历按 `filtered` 算，用户切到"只看错题"，
 * 那天的学习记录就从日历上消失了 —— 他那天明明在学，只是没答错。
 *
 * 时间固定成 UTC 的某一天，日期范围按**本地日历日**断言：
 * 用"现在减 N×24 小时"的滚动窗口写测试，会把"今天"和"最近 24 小时"混为一谈。
 */
class HistoryControllerTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 10, 28)

    private fun millisAt(dayOffset: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.fromEpochDays(today.toEpochDays() + dayOffset).toEpochDays() * DAY +
            hour * 3_600_000L + minute * 60_000L

    /** 当前时刻固定在今天 10:00 —— 昨天下午的记录最容易暴露"滚动窗口"的问题。 */
    private val now = millisAt(0, 10)

    /** 被观察的"历史"。做成可变量，用来模拟组合根重读了历史。 */
    private var source: List<AttemptRecord> = emptyList()

    private fun controller(): HistoryController {
        val controller = HistoryController(clock = WallClock { now }, zone = zone) { source }
        controller.update(source)
        return controller
    }

    private fun rec(
        id: String,
        at: Long = now,
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
        submittedAtMillis = at,
        elapsedMillis = 1_000,
        explanationSeen = false,
        locale = locale,
    )

    @Test
    fun noFilterKeepsEverythingNewestFirst() {
        source = listOf(
            rec("old", at = millisAt(-40, 12)),
            rec("new"),
            rec("mid", at = millisAt(-3, 12)),
        )
        val controller = controller()
        controller.selectRange(HistoryRange.ALL)

        assertEquals(listOf("new", "mid", "old"), controller.filtered.map { it.attemptId })
    }

    @Test
    fun todayIsTheCalendarDayNotTheLastTwentyFourHours() {
        source = listOf(
            rec("todayEarly", at = millisAt(0, 0, 1)),
            rec("yesterdayLate", at = millisAt(-1, 23, 30)),
        )
        val controller = controller()

        controller.selectRange(HistoryRange.TODAY)
        assertEquals(
            listOf("todayEarly"),
            controller.filtered.map { it.attemptId },
            "现在 10 点：昨天 23:30 落在 24 小时窗口内，但它不是今天",
        )
    }

    @Test
    fun weekIsSevenCalendarDaysIncludingToday() {
        source = listOf(
            rec("sixDaysAgo", at = millisAt(-6, 0, 5)),
            rec("sevenDaysAgo", at = millisAt(-7, 23, 0)),
        )
        val controller = controller()

        controller.selectRange(HistoryRange.WEEK)
        assertEquals(listOf("sixDaysAgo"), controller.filtered.map { it.attemptId })
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

    private companion object {
        const val DAY = 86_400_000L
    }
}
