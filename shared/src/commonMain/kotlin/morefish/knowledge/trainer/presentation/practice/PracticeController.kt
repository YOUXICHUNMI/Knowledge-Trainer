package morefish.knowledge.trainer.presentation.practice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.SessionLifecycle
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.application.usecase.SubmitOutcome
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.i18n.localeLabel
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.PracticeDraft
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SessionSummary

/** [PracticeController.resume] 的结果。 */
sealed interface PracticeResume {
    /** 恢复成功，可以接着作答。 */
    data class Ready(val session: PracticeSession) : PracticeResume

    /**
     * 文件里这道会话其实已经做完（最后一题提交过、但没点"查看总结"）。
     *
     * 不算错误：组合根接过去直接走收尾，用户看到的是总结而不是一道空题。
     */
    data object Finished : PracticeResume
}

/**
 * 作答过程：当前会话、待继续的会话、输入中的答案、判题反馈、总结，以及草稿存盘。
 *
 * 为什么值得单独成类：它是**唯一一份会被并发改写两次的状态**。作答在协程里跑，
 * 用户的手指同时还在改草稿（选项、文本、片段顺序），而草稿又要合并存盘 ——
 * `draftJob` 的取消防抖、提交时先取消草稿、切题时清空输入，这三件事必须写在一起，
 * 否则"提交后草稿又把旧答案写回去"这类问题会随机出现，且事后无法复现。
 *
 * 跟着搬过来的还有**会话恢复的合并规则**：会话文件只存题目与游标，已答数量从历史合并 ——
 * 两处各存一份迟早失配。这条规则以前散在 `loadAll` 里，和题库、图谱、扩展包的读取搅在一起，
 * 看不出它其实只属于"练习"。
 *
 * 三条边界：
 *
 * - **不导航。** 进作答页 / 去总结页留在组合根；[next] 与 [finish] 返回"还有没有下一题"，
 *   去哪儿由调用点决定。
 * - **不写别人的数据。** 它写会话文件与作答记录（就是这道功能自己的东西），
 *   但历史、统计、日历的重算经 [onAttemptRecorded] / [onSessionClosed] 交回去。
 * - **不判"硬依赖是否过期"。** 那要看图谱关系与题库修订号，属于图谱那一侧；
 *   组合根算完通过 [reportBlockedDependencies] 递进来。
 */
class PracticeController(
    private val submitAnswer: SubmitAnswerUseCase,
    private val lifecycle: SessionLifecycle,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** 题库只用来算"这道题现在显示的是原文还是译文"，不存快照。 */
    private val banks: () -> List<QuestionBank>,
    /** 一条作答落库了：统计、日历、错题本都变了，让组合根重读历史。 */
    private val onAttemptRecorded: () -> Unit,
    /** 一场练习收尾了：会话文件已清，首页的"继续练习"与统计都要换。 */
    private val onSessionClosed: () -> Unit,
    private val onNotice: (text: String, isError: Boolean) -> Unit,
) {

    /** 正在作答的会话；null = 不在作答页。 */
    var session by mutableStateOf<PracticeSession?>(null)
        private set

    /** 上次未结束的练习。首页据此显示"继续练习"，不自动跳回作答页。 */
    var resumable by mutableStateOf<PracticeSession?>(null)
        private set

    var selectedOptionIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var textAnswer by mutableStateOf("")
        private set

    /** 重组题：已排好的片段次序（optionId），提交时转成 AnswerInput.Ordering。 */
    var orderedOptionIds by mutableStateOf<List<String>>(emptyList())
        private set

    var feedback by mutableStateOf<SubmitOutcome?>(null)
        private set

    var summary by mutableStateOf<SessionSummary?>(null)
        private set

    /**
     * 当前这道题是否打开过工具箱（计算器 / 单位换算）。
     * 它会被写进历史：用了辅助工具不是作弊，但记录必须是真的。
     */
    var usedToolsForCurrentQuestion by mutableStateOf(false)
        private set

    /**
     * 本题有多少条判题硬依赖已经过期（题面改过、依据对不上了）。
     *
     * 数字由组合根算 —— 只有它同时看得到图谱关系与题库修订号。这里只负责显示，
     * 以及"换题就归零"这条时间规则。
     */
    var blockedHardDependencies by mutableStateOf(0)
        private set

    private var questionStartedAt: Long = 0L
    private var draftJob: Job? = null

    val currentQuestion: Question? get() = session?.current?.question

    val currentBank: QuestionBank?
        get() = session?.bankId?.let { id -> banks().firstOrNull { it.bankId == id } }

    /** 练习页顶部的语言提示；单语言题库不显示（那是噪音）。 */
    val sessionLocaleLabel: String?
        get() {
            val bank = currentBank ?: return null
            if (bank.availableLocales.size <= 1) return null
            val locale = session?.locale.orEmpty()
            return if (locale.isBlank()) null else localeLabel(locale)
        }

    /**
     * 当前这道题显示的是原文吗（本场选了某语言，但这题没有该语言译文，退回了原文）。
     * 是就必须在题干上标出来 —— 否则用户会以为自己看到的是译文。
     */
    val currentQuestionShowsOriginal: Boolean
        get() {
            val bank = currentBank ?: return false
            val question = currentQuestion ?: return false
            val locale = session?.locale.orEmpty()
            if (locale.isBlank()) return false
            val want = LocaleTag.parse(locale)?.value ?: locale
            val source = LocaleTag.parse(bank.sourceLocale)?.value ?: bank.sourceLocale
            if (want == source) return false
            val actual = question.locale?.let { LocaleTag.parse(it)?.value ?: it } ?: source
            return actual == source
        }

    /**
     * 开一场练习。返回 false 表示这个会话里没有可作答的题目（调用方去说明原因）。
     *
     * 清空的是"上一场留下的输入" —— 不清会让新一场的第一题带着旧答案，
     * 用户以为系统替他答了。
     */
    fun begin(created: PracticeSession): Boolean {
        if (created.items.isEmpty()) return false
        session = created
        resumable = null
        selectedOptionIds = emptySet()
        textAnswer = ""
        orderedOptionIds = emptyList()
        usedToolsForCurrentQuestion = false
        blockedHardDependencies = 0
        feedback = null
        summary = null
        questionStartedAt = clock.nowMillis()
        scope.launch { lifecycle.saveProgress(created) }
        return true
    }

    /**
     * 从会话文件 + 历史重建"上次未结束的练习"。
     *
     * 会话文件只存题目与游标，已答数量从历史合并 —— 两处各存一份迟早会失配。
     * 最后一题提交过但没点"查看总结"的会话，实际已经做完了：清掉，而不是永远挂着。
     */
    suspend fun reconcileResumable(history: List<AttemptRecord>) {
        val active = lifecycle.active()
        if (active == null) {
            resumable = null
            return
        }
        val merged = active.copy(
            attempts = history
                .filter { it.sessionId == active.sessionId }
                .sortedBy { it.submittedAtMillis },
        )
        if (merged.firstUnansweredIndex >= merged.items.size) {
            lifecycle.discard(merged)
            resumable = null
        } else {
            resumable = merged
        }
    }

    /**
     * 离开作答页（回首页）。进行中的会话不是"被丢弃"，而是暂停：存游标与草稿，
     * 首页出现"继续练习"入口。
     */
    fun leave() {
        val active = session
        if (active != null && active.isResumable && active.current != null) {
            val paused = active.copy(draft = currentDraft()).paused(clock.nowMillis())
            session = null
            resumable = paused
            scope.launch { lifecycle.saveProgress(paused) }
        } else {
            session = null
        }
        feedback = null
        selectedOptionIds = emptySet()
        textAnswer = ""
    }

    /**
     * 显式"暂停并退出"。与 [leave] 的区别只在语义：用户点了按钮，所以要说一句
     * —— 那句话归组合根，这里只负责真的存下来。
     *
     * 返回 false 表示当前没有会话可暂停。
     */
    fun pauseAndExit(): Boolean {
        val active = session ?: return false
        val paused = active.copy(draft = currentDraft()).paused(clock.nowMillis())
        session = null
        resumable = paused
        feedback = null
        selectedOptionIds = emptySet()
        textAnswer = ""
        scope.launch { lifecycle.saveProgress(paused) }
        return true
    }

    /** 恢复上次的练习。null = 没有待继续的会话。 */
    suspend fun resume(): PracticeResume? {
        val stored = resumable ?: return null
        val restored = lifecycle.resume(stored)
        resumable = null
        session = restored
        selectedOptionIds = restored.draft.selectedOptionIds
        textAnswer = restored.draft.textAnswer
        orderedOptionIds = restored.draft.orderedOptionIds
        usedToolsForCurrentQuestion = false
        blockedHardDependencies = 0
        feedback = null
        summary = null
        questionStartedAt = clock.nowMillis()
        return if (restored.current == null) PracticeResume.Finished else PracticeResume.Ready(restored)
    }

    /** 放弃待继续的会话。已提交的记录不动 —— 历史只追加，不删除。 */
    fun discard() {
        val stored = resumable ?: return
        resumable = null
        scope.launch { lifecycle.discard(stored) }
    }

    /**
     * 题库被移除：挂在它上面的"待继续练习"一起丢掉。
     *
     * 必须是 suspend，而且**要在重读之前完成**：会话文件里还留着这份清单，
     * 先重读就会把它又捧回首页，点进去一道题都找不到。返回 true 表示确实丢掉了一场。
     */
    suspend fun dropResumableOf(bankId: String): Boolean {
        val stored = resumable ?: return false
        if (stored.bankId != bankId) return false
        lifecycle.discard(stored)
        resumable = null
        return true
    }

    fun toggleOption(optionId: String) {
        if (feedback != null) return
        val type = currentQuestion?.type ?: return
        selectedOptionIds = when (type) {
            QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE ->
                if (selectedOptionIds.contains(optionId)) emptySet() else setOf(optionId)

            QuestionType.MULTIPLE_CHOICE ->
                if (selectedOptionIds.contains(optionId)) selectedOptionIds - optionId
                else selectedOptionIds + optionId

            QuestionType.SHORT_ANSWER, QuestionType.ORDERING -> selectedOptionIds
        }
        scheduleProgressSave()
    }

    /** 重组题：把片段追加到答案序列末尾（一个片段只能用一次）。 */
    fun appendFragment(optionId: String) {
        if (feedback != null) return
        if (optionId in orderedOptionIds) return
        orderedOptionIds = orderedOptionIds + optionId
        scheduleProgressSave()
    }

    /** 重组题：把片段从答案序列的指定位置移除。 */
    fun removeFragmentAt(index: Int) {
        if (feedback != null) return
        if (index !in orderedOptionIds.indices) return
        orderedOptionIds = orderedOptionIds.toMutableList().apply { removeAt(index) }
        scheduleProgressSave()
    }

    fun updateTextAnswer(value: String) {
        if (feedback != null) return
        textAnswer = value
        // 输入框每次按键都落盘代价太高，延迟合并；提交/暂停时另有立即保存。
        scheduleProgressSave(delayMillis = 350)
    }

    fun submit() {
        val current = session ?: return
        val item = current.current ?: return
        if (feedback != null) return

        val answer: AnswerInput = when (item.question.type) {
            QuestionType.SHORT_ANSWER -> AnswerInput.Text(textAnswer)
            QuestionType.ORDERING -> AnswerInput.Ordering(orderedOptionIds)
            else -> AnswerInput.Choices(selectedOptionIds)
        }
        if (answer.isEmpty) {
            onNotice("请先作答再提交", true)
            return
        }

        scope.launch {
            draftJob?.cancel()
            val outcome = submitAnswer.submit(
                session = current,
                item = item,
                answer = answer,
                elapsedMillis = clock.nowMillis() - questionStartedAt,
                usedTools = usedToolsForCurrentQuestion,
            )
            session = outcome.session
            feedback = outcome
            lifecycle.saveProgress(outcome.session)
            onAttemptRecorded()
        }
    }

    fun abandon() {
        val current = session ?: return
        val item = current.current ?: return
        if (feedback != null) return
        scope.launch {
            draftJob?.cancel()
            val outcome = submitAnswer.abandon(
                session = current,
                item = item,
                elapsedMillis = clock.nowMillis() - questionStartedAt,
                usedTools = usedToolsForCurrentQuestion,
            )
            session = outcome.session
            feedback = outcome
            lifecycle.saveProgress(outcome.session)
            onAttemptRecorded()
        }
    }

    /**
     * 下一题。返回 false 表示后面没有题了 —— 调用方去收尾（总结页）。
     *
     * 换题必须把输入与"用过工具"一起清掉：留着会让下一题带着上一题的答案，
     * 也会让"这题用过计算器"记到另一道题头上。
     */
    fun next(): Boolean {
        val current = session ?: return false
        feedback = null
        selectedOptionIds = emptySet()
        textAnswer = ""
        orderedOptionIds = emptyList()
        usedToolsForCurrentQuestion = false
        blockedHardDependencies = 0
        val advanced = current.advance().withDraft(PracticeDraft.Empty, clock.nowMillis())
        session = advanced
        if (advanced.current == null) return false
        questionStartedAt = clock.nowMillis()
        scope.launch { lifecycle.saveProgress(advanced) }
        return true
    }

    /**
     * 收尾：算总结、把会话标成已完成、清掉待继续入口。
     *
     * 返回 false 表示没有会话可收。总结页的跳转与统计重算都不在这里 ——
     * 前者是导航，后者是别人的数据。
     */
    fun finish(): Boolean {
        val current = session ?: return false
        summary = current.summary()
        val done = current.completed()
        session = done
        resumable = null
        draftJob?.cancel()
        scope.launch {
            lifecycle.complete(done)
            onSessionClosed()
        }
        return true
    }

    /** 从练习页打开工具箱：算用过，且这件事要写进历史。 */
    fun markToolUsed() {
        usedToolsForCurrentQuestion = true
    }

    /** 组合根算好的"本题硬依赖过期条数"，见属性说明。 */
    fun reportBlockedDependencies(count: Int) {
        blockedHardDependencies = count
    }

    private fun currentDraft(): PracticeDraft =
        PracticeDraft(selectedOptionIds, textAnswer, orderedOptionIds)

    /**
     * 把草稿并进会话并存盘。
     *
     * [delayMillis] 用于输入框：每敲一个字写一次盘既慢又没必要，350ms 的合并窗口
     * 足够挡住连续输入，而提交与暂停各有一次立即保存。
     */
    private fun scheduleProgressSave(delayMillis: Long = 0L) {
        val current = session ?: return
        val snapshot = current.withDraft(currentDraft(), clock.nowMillis())
        session = snapshot
        draftJob?.cancel()
        draftJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            lifecycle.saveProgress(snapshot)
        }
    }
}
