package morefish.knowledge.trainer.domain.practice

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Verdict
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 本地日历换算。日历按用户所在时区切天，
 * 不能用 `millis / 86400000` —— 那算的是 UTC 天，中国用户凌晨 8 点前会错一天。
 */
@OptIn(ExperimentalTime::class)
object LocalDates {

    fun epochDayOf(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date.toEpochDays()

    fun todayEpochDay(nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        epochDayOf(nowMillis, zone)

    /** 0 = 周一 … 6 = 周日。1970-01-01 是周四，所以偏移 3 天。 */
    fun weekdayIndex(epochDay: Long): Int {
        val mod = (epochDay + 3) % 7
        return ((mod + 7) % 7).toInt()
    }

    fun monthDayLabel(epochDay: Long): String {
        val date = LocalDate.fromEpochDays(epochDay)
        return "${date.month.ordinal + 1}/${date.day}"
    }

    fun yearMonthDay(epochDay: Long): String {
        val date = LocalDate.fromEpochDays(epochDay)
        return "${date.year}-${pad(date.month.ordinal + 1)}-${pad(date.day)}"
    }

    fun dayOfWeekLabel(weekdayIndex: Int): String =
        listOf("一", "二", "三", "四", "五", "六", "日")[weekdayIndex.coerceIn(0, 6)]

    private fun pad(value: Int): String = if (value < 10) "0$value" else "$value"
}

data class StudyDay(
    val epochDay: Long,
    val attempts: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val pending: Int = 0,
    val abandoned: Int = 0,
) {
    val isActive: Boolean get() = attempts > 0
}

/**
 * 学习日历：连续 [spanDays] 天，每天统计提交数与判定分布。
 * 刻意只统计"提交事件"，不用"打开应用"之类无法可靠记录的信号冒充学习行为。
 */
data class StudyCalendar(
    val days: List<StudyDay>,
    val todayEpochDay: Long,
    val currentStreak: Int,
    val activeDays: Int,
    val totalAttempts: Int,
) {
    val maxPerDay: Int get() = days.maxOfOrNull { it.attempts } ?: 0

    fun on(epochDay: Long): StudyDay? = days.firstOrNull { it.epochDay == epochDay }

    /** 0（无）到 4（多）。色阶分档写死，避免 1 次和 20 次看起来一样。 */
    fun levelOf(day: StudyDay): Int = when {
        day.attempts <= 0 -> 0
        day.attempts == 1 -> 1
        day.attempts <= 3 -> 2
        day.attempts <= 7 -> 3
        else -> 4
    }

    companion object {
        fun build(
            attempts: List<AttemptRecord>,
            nowMillis: Long,
            spanDays: Int = 56,
            zone: TimeZone = TimeZone.currentSystemDefault(),
        ): StudyCalendar {
            val span = spanDays.coerceAtLeast(1)
            val today = LocalDates.todayEpochDay(nowMillis, zone)
            val from = today - (span - 1)

            val byDay = attempts
                .filter { it.submittedAtMillis <= nowMillis }
                .groupBy { LocalDates.epochDayOf(it.submittedAtMillis, zone) }

            val days = (from..today).map { epochDay ->
                val records = byDay[epochDay].orEmpty()
                StudyDay(
                    epochDay = epochDay,
                    attempts = records.size,
                    correct = records.count { it.verdict == Verdict.CORRECT },
                    wrong = records.count {
                        it.verdict == Verdict.INCORRECT || it.verdict == Verdict.PARTIALLY_CORRECT
                    },
                    pending = records.count { it.verdict == Verdict.PENDING },
                    abandoned = records.count { it.verdict == Verdict.ABANDONED },
                )
            }

            return StudyCalendar(
                days = days,
                todayEpochDay = today,
                currentStreak = streak(days),
                activeDays = days.count { it.isActive },
                totalAttempts = days.sumOf { it.attempts },
            )
        }

        /** 连续天数：今天还没练不算断，从昨天往前数。 */
        private fun streak(days: List<StudyDay>): Int {
            if (days.isEmpty()) return 0
            var index = days.lastIndex
            if (!days[index].isActive) index -= 1
            var count = 0
            while (index >= 0 && days[index].isActive) {
                count++
                index--
            }
            return count
        }
    }
}
