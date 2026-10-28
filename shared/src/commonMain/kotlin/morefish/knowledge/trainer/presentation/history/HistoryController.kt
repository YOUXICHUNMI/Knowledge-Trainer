package morefish.knowledge.trainer.presentation.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.datetime.TimeZone
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.WrongQuestionEntry
import morefish.knowledge.trainer.domain.model.WrongQuestions
import morefish.knowledge.trainer.domain.practice.LocalDates
import morefish.knowledge.trainer.domain.practice.StudyCalendar

/**
 * 历史页的日期范围筛选。
 *
 * [days] 是**含今天在内的日历日数**，不是"往前推 24×N 小时"：
 * 滚动窗口会让"今天"把昨天下午的记录也算进来（现在 10 点，窗口回到昨天 10 点），
 * 而标签上写着今天。日历日才和界面上的字对得上。
 */
enum class HistoryRange(val days: Int?, val label: String) {
    TODAY(1, "今天"),
    WEEK(7, "近 7 天"),
    MONTH(30, "近 30 天"),
    ALL(null, "全部");

    /** 窗口起点（本地日历日的 00:00）；null = 不限。 */
    fun cutoffMillis(nowMillis: Long, zone: TimeZone): Long? {
        val span = days ?: return null
        val today = LocalDates.todayEpochDay(nowMillis, zone)
        return LocalDates.startOfDayMillis(today - (span - 1), zone)
    }
}

/**
 * 历史页的全部状态：四个筛选条件 + 两份派生视图（日历、错题本）。
 *
 * 为什么值得单独成类：这四个筛选**互相叠加**，谁改一个都要重新算同一份投影；
 * 而它们与本场练习、题库、AI 全无关系 —— 回看过去的作答不需要知道现在正在做哪道题。
 * 留在 `AppController` 里时，`filteredHistory` 这种平凡逻辑被埋在三千行中间，
 * 改一个筛选条件的边界（"近 7 天"含不含今天）得先在一堆无关代码里定位。
 *
 * 两条边界：
 * - **不读磁盘。** 历史由组合根读完再交给 [update]；这里只做投影，因此可以直接单测。
 * - **不持有历史副本。** 列表通过构造参数里的 `history` 按需读取，
 *   否则会出现"组合根换了历史、这里还是旧的"两份真相。
 *
 * 导航（进历史页 / 错题本）留在 `AppController`：那是路由，不是历史的状态。
 */
class HistoryController(
    private val clock: WallClock,
    /** 日历与日期范围都按它切天。显式传入是为了测试能固定时区，而不是跟着跑测试的机器走。 */
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val history: () -> List<AttemptRecord>,
) {

    // ---------------------------------------------------------------- 筛选

    var range by mutableStateOf(HistoryRange.WEEK)
        private set

    var bankFilter by mutableStateOf<String?>(null)
        private set

    var verdictFilter by mutableStateOf<Verdict?>(null)
        private set

    /** 按语言筛选。null = 全部语言（默认不隐藏记录，隐藏会让人以为记录丢了）。 */
    var localeFilter by mutableStateOf<String?>(null)
        private set

    fun selectRange(next: HistoryRange) {
        range = next
    }

    fun selectBank(bankId: String?) {
        bankFilter = bankId
    }

    fun selectVerdict(verdict: Verdict?) {
        verdictFilter = verdict
    }

    fun selectLocale(locale: String?) {
        localeFilter = locale
    }

    /** 历史里出现过的语言（按标签排序）。只出现一种语言时界面不显示筛选行。 */
    val locales: List<String>
        get() = history()
            .map { it.locale }
            .filter { it.isNotBlank() }
            .map { LocaleTag.parse(it)?.value ?: it }
            .distinct()
            .sorted()

    /**
     * 按四个筛选条件投影出的历史，新的在前。
     *
     * 用 getter 而不是缓存：历史列表本身就不大（一屏几十条），
     * 而缓存要多维护一个"什么时候失效"的问题，答案一定是"又忘了某处"。
     */
    val filtered: List<AttemptRecord>
        get() {
            val cutoff = range.cutoffMillis(clock.nowMillis(), zone)
            return history()
                .asSequence()
                .filter { cutoff == null || it.submittedAtMillis >= cutoff }
                .filter { bankFilter == null || it.bankId == bankFilter }
                .filter { verdictFilter == null || it.verdict == verdictFilter }
                .filter {
                    localeFilter == null ||
                        (LocaleTag.parse(it.locale)?.value ?: it.locale) == localeFilter
                }
                .sortedByDescending { it.submittedAtMillis }
                .toList()
        }

    // ---------------------------------------------------------------- 派生视图

    /**
     * 连续学习日历。
     *
     * 与 [filtered] 不同，这里**不跟着筛选走**：日历回答的是"我哪天在学"，
     * 用户把历史筛成"只看错题"不代表那天没学。所以它按全量历史算，且必须缓存 ——
     * 建一次要遍历全部记录，不该每帧重算。
     */
    var calendar by mutableStateOf(StudyCalendar.build(emptyList(), clock.nowMillis()))
        private set

    /** 错题本：同样按全量历史算，"只看某题库"是筛选，不是重新定义错题。 */
    var wrongEntries by mutableStateOf<List<WrongQuestionEntry>>(emptyList())
        private set

    /**
     * 历史变了（新作答、复核改判、恢复备份）后重算两份派生视图。
     *
     * 只给组合根调：它是唯一知道"历史什么时候换了"的地方。
     * 传参而不是自己读 `history()`，是为了让调用点显式——重算是有代价的，
     * 不该在某个 getter 里被偷偷触发。
     */
    fun update(history: List<AttemptRecord>) {
        wrongEntries = WrongQuestions.derive(history)
        calendar = StudyCalendar.build(history, clock.nowMillis(), zone = zone)
    }
}
