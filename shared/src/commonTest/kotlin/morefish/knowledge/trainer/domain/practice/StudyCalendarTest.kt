package morefish.knowledge.trainer.domain.practice

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** 日历按本地时区切天，连续天数从"今天或昨天"往前数。 */
class StudyCalendarTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 10, 19)

    private fun day(offset: Long): LocalDate = LocalDate.fromEpochDays(today.toEpochDays() + offset)

    private fun millisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.toEpochDays() * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    private fun attempt(at: Long, verdict: Verdict = Verdict.CORRECT) = AttemptRecord(
        attemptId = "a-$at",
        sessionId = "s",
        bankId = "b",
        questionId = "q",
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
    )

    @Test
    fun groupsAttemptsByLocalDay() {
        val calendar = StudyCalendar.build(
            attempts = listOf(
                attempt(millisAt(today, 0, 30)),
                attempt(millisAt(today, 23, 50), Verdict.INCORRECT),
                attempt(millisAt(day(-1), 12, 0)),
            ),
            nowMillis = millisAt(today, 23, 55),
            spanDays = 7,
            zone = zone,
        )

        assertEquals(7, calendar.days.size)
        val todayCell = calendar.on(today.toEpochDays())!!
        assertEquals(2, todayCell.attempts)
        assertEquals(1, todayCell.correct)
        assertEquals(1, todayCell.wrong)

        val yesterday = calendar.on(day(-1).toEpochDays())!!
        assertEquals(1, yesterday.attempts)

        assertEquals(3, calendar.totalAttempts)
        assertEquals(2, calendar.activeDays)
        assertEquals(2, calendar.currentStreak)
    }

    @Test
    fun todayWithoutPracticeDoesNotBreakStreak() {
        val calendar = StudyCalendar.build(
            attempts = listOf(
                attempt(millisAt(day(-1), 9, 0)),
                attempt(millisAt(day(-2), 9, 0)),
            ),
            nowMillis = millisAt(today, 8, 0),
            spanDays = 7,
            zone = zone,
        )

        // 今天还没开始练不算断，连续天数应仍是 2。
        assertEquals(2, calendar.currentStreak)
        assertEquals(0, calendar.on(today.toEpochDays())!!.attempts)
    }

    @Test
    fun gapBreaksStreak() {
        val calendar = StudyCalendar.build(
            attempts = listOf(
                attempt(millisAt(today, 9, 0)),
                attempt(millisAt(day(-2), 9, 0)),
            ),
            nowMillis = millisAt(today, 10, 0),
            spanDays = 7,
            zone = zone,
        )

        assertEquals(1, calendar.currentStreak)
    }

    @Test
    fun levelReflectsDailyVolume() {
        val calendar = StudyCalendar.build(
            attempts = (0 until 8).map { attempt(millisAt(today, 8 + it)) },
            nowMillis = millisAt(today, 23, 0),
            spanDays = 7,
            zone = zone,
        )
        assertEquals(4, calendar.levelOf(calendar.on(today.toEpochDays())!!))
        assertEquals(0, calendar.levelOf(calendar.on(today.toEpochDays() - 1)!!))
    }
}
