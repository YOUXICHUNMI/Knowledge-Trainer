package morefish.knowledge.trainer.presentation.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.ReviewRepository
import morefish.knowledge.trainer.application.usecase.ReviewHistoryUseCase
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.patch.BankRevision
import morefish.knowledge.trainer.domain.practice.AttemptReview
import morefish.knowledge.trainer.domain.practice.AttemptReviews
import morefish.knowledge.trainer.domain.practice.ReviewPlan

/**
 * 历史复核：待复核清单、逐条表态、首页徽章上的数字。
 *
 * 为什么值得单独成类，而且比前几刀更该单独成类：
 * **"历史文件里的判定"与"现在生效的判定"是两份东西**，这个区别只有复核这一个功能
 * 说得清。`attempts` 文件存的是**当时按那时的标准**判成了什么，复核决定另存一处，
 * 读取时才合并 —— 所以"生效历史"必须由知道复核的人算出来。
 * 拆分之前，那行合并代码在 `loadAll` 与 `reloadHistory` 里各写了一次，
 * 任何一处漏写都会让统计与错题本停在旧判定上，而界面上看不出区别。
 *
 * 两条纪律（都在 [ReviewHistoryUseCase] 里落实，这里负责不破坏它们）：
 *
 * 1. **不自动重判。** 不写任何东西之前，历史、统计、错题本一个数字都不变；
 *    用户看见差异之前，磁盘上不该有变化。
 * 2. **扫描昂贵。** 算一次要解析题库内容（十万题的量级），所以只在进页面时扫，
 *    不在每次刷新时扫；首页徽章另走一条便宜的路（见 [pending]）。
 *
 * 三条边界：
 *
 * - **不导航。** 进页面 / 返回留在 `AppController`。
 * - **不弹横幅。** 要说的话经 [onNotice] 交回去。
 * - **不自己更新读模型。** 决定写完后调 [onHistoryChanged]，由组合根重读历史；
 *   统计、错题本、日历、抽题缓存都挂在那份读模型上，一起换才对得上。
 */
class ReviewController(
    private val useCase: ReviewHistoryUseCase,
    private val attempts: AttemptRepository,
    private val reviews: ReviewRepository,
    private val scope: CoroutineScope,
    /** 各题库最近一次修订。首页徽章靠它判断"这道题的答案被改过"，不解析题库内容。 */
    private val latestRevisions: () -> Map<String, BankRevision>,
    private val onHistoryChanged: suspend () -> Unit,
    private val onNotice: (text: String, isError: Boolean) -> Unit,
) {

    /**
     * 原始历史（未经复核合并）。
     *
     * 与"生效历史"分开存不是洁癖：复核要拿它跟现在的标准比。若直接拿合并后的历史比，
     * "我上次改过的判定"会被当成"当时判的"，第二次复核就永远看不出差异。
     */
    private var attemptLog: List<AttemptRecord> = emptyList()

    /** 已写入的复核决定（可能有多条指向同一条历史，读取时以最后一条为准）。 */
    private var decisions: List<AttemptReview> = emptyList()

    /** 原始历史：引用自检与备份概况要的是"磁盘上真实存在的记录"，不是合并后的视图。 */
    val rawAttempts: List<AttemptRecord> get() = attemptLog

    /** 已表态的条数。备份概况里报"复核决定 N 条"。 */
    val decisionCount: Int get() = decisions.size

    /** 待复核清单。null = 还没扫过（扫描要读题库内容，不在每次刷新时做）。 */
    var plan by mutableStateOf<ReviewPlan?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /**
     * 首页徽章上的数字：有多少条历史落在"答案改过、且我还没表过态"的题上。
     *
     * 只用已经读进来的历史 + 修订日志算，**不碰题库内容** ——
     * 首页刷新不该为了一个徽章去解析十万道题。真正的判定差异等用户点进去再算，
     * 所以这个数字可能比清单条数多（答案变了但重算结果相同，进页面后不算候选）。
     */
    val pending: Int
        get() {
            val revisions = latestRevisions()
            if (attemptLog.isEmpty() || revisions.isEmpty()) return 0
            val decided = decisions.mapTo(mutableSetOf()) { it.attemptId }
            return attemptLog.count { attempt ->
                if (attempt.attemptId in decided) return@count false
                if (attempt.verdict == Verdict.ABANDONED) return@count false
                revisions[attempt.bankId]?.answerChangedIds?.contains(attempt.questionId) == true
            }
        }

    /**
     * 读原始历史与复核决定，返回**合并后**的历史。
     *
     * 组合根负责把返回值放进读模型；它同时也要重算统计、错题本、日历。
     * 这里只保证一件事：合并永远发生、且只在这一处发生。
     */
    suspend fun readHistory(): List<AttemptRecord> {
        attemptLog = attempts.all()
        decisions = reviews.all()
        return AttemptReviews.apply(attemptLog, decisions)
    }

    /** 题库变了（装了补丁、恢复了备份），上一份清单作废；进页面时重扫。 */
    fun invalidate() {
        plan = null
    }

    /** 进页面时重算一遍清单。 */
    fun scan() {
        scope.launch {
            busy = true
            plan = useCase.plan()
            busy = false
        }
    }

    /**
     * 对一条记录表态。[accept] = 按现在的标准重算，否则维持当时的判定。
     *
     * 两条路都写一条决定：只接受不保留，会让"我看过了，不改"变成每次打开都要重看一遍。
     */
    fun decide(attemptId: String, accept: Boolean) {
        scope.launch {
            val written = useCase.decide(attemptId, accept)
            if (written == null) {
                // 用户看的是几步之前那份清单：期间可能又装了补丁，这条已经没有差异
                onNotice("这条记录已经不需要复核了（题目可能又被改过），列表已更新", false)
                scan()
                return@launch
            }
            onHistoryChanged()
            // 局部删除而不是整表重扫：用户可能正在一条条处理，列表不该突然回到顶部
            plan = plan?.let { current ->
                current.copy(candidates = current.candidates.filterNot { it.attempt.attemptId == attemptId })
            }
        }
    }

    fun decideAll(accept: Boolean) {
        scope.launch {
            busy = true
            val n = useCase.decideAll(accept)
            onHistoryChanged()
            plan = ReviewPlan(emptyList())
            busy = false
            onNotice(
                if (n == 0) {
                    "没有需要处理的记录"
                } else if (accept) {
                    "已按新答案复核 $n 条记录：统计与错题本已跟着变，历史记录本身没有改写"
                } else {
                    "已保留 $n 条记录的当时判定：它们不会再出现在待复核里"
                },
                false,
            )
        }
    }
}
