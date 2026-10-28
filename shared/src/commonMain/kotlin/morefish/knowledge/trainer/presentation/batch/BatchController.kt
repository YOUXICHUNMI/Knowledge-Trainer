package morefish.knowledge.trainer.presentation.batch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.AiBatchRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.AiPromptAssembler
import morefish.knowledge.trainer.application.usecase.BatchResolve
import morefish.knowledge.trainer.application.usecase.RunBatchUseCase
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.model.Ids
import morefish.knowledge.trainer.domain.model.QuestionBank

/**
 * 批量生成队列：建队列、跑、暂停、取消、删除，以及看板上那些"现在是哪个任务"。
 *
 * 为什么值得单独成类：它是全应用唯一**长时间在后台推进**的东西。别处都是一次动作
 * 一个结果，这里是"跑起来之后还要能被叫停"，状态机（排队 / 运行 / 暂停 / 取消）
 * 与那个"叫停意图"之间的配合，埋进三千行的组合根里没人能一眼确认它没跑飞。
 *
 * 三条边界：
 *
 * - **不导航。** 进页面 / 返回留在 `AppController`；这里只负责队列本身。
 * - **不弹横幅。** 要说的话经 [onNotice] 交回去 —— 跑完那一刻用户可能已经翻到别的页面，
 *   提示应该由组合根决定挂在哪里。
 * - **不直接改任务状态来达到暂停。** 用户点暂停只是写下 [stopIntent]，
 *   真正的状态由执行器在**请求边界**上收尾：打断正在飞的请求同样会扣费，
 *   还会留下一条不知道算成功还是失败的记录。
 *
 * 两处"题库"不是同一个东西，注意区分：
 *
 * - [create] 用的是 [banks] 读取器，也就是用户此刻在界面上看到的那个题库 ——
 *   建队列时报的"共 N 道"必须和他看到的数字对得上；
 * - 真正发请求时用 [bankRepository] 重读，因为暂停期间题库可能被改过，
 *   按磁盘上的新内容生成，缓存键才会跟着变（见 [resolve]）。
 */
class BatchController(
    private val useCase: RunBatchUseCase,
    private val repository: AiBatchRepository,
    private val bankRepository: BankRepository,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** 当前接入了哪个模型：建队列时要把模型名写进任务，缓存键也按它算。 */
    private val config: () -> AiConfig,
    private val banks: () -> List<QuestionBank>,
    /** 有任务跑完：讲解记录与今日用量都变了，让组合根刷新一次。 */
    private val onJobFinished: suspend () -> Unit,
    private val onNotice: (text: String, isError: Boolean) -> Unit,
) {

    /** 全部任务，新的在前。 */
    var jobs by mutableStateOf<List<AiBatchJob>>(emptyList())
        private set

    /** 正在跑的任务 id；null 表示队列没在动。 */
    var runningId by mutableStateOf<String?>(null)
        private set

    /**
     * 用户点了暂停 / 取消时的目标状态。
     *
     * 不直接改任务的 state：状态由执行器在请求边界上收尾。打断正在飞的请求
     * 同样会扣费，而且会留下一条不知道算成功还是失败的记录。
     */
    private var stopIntent by mutableStateOf<AiBatchState?>(null)

    val running: Boolean get() = runningId != null

    /** 正在跑的任务；没在跑时取最近一个，让用户回到页面能看见上次的结果。 */
    val current: AiBatchJob?
        get() = runningId?.let { id -> jobs.firstOrNull { it.jobId == id } }
            ?: jobs.firstOrNull()

    /** 看板上显示的任务（可能是刚建的空队列，也可能是历史任务）。 */
    private var selectedJobId by mutableStateOf<String?>(null)

    val selected: AiBatchJob?
        get() = runningId?.let { id -> jobs.firstOrNull { it.jobId == id } }
            ?: selectedJobId?.let { id -> jobs.firstOrNull { it.jobId == id } }
            ?: jobs.firstOrNull()

    /** 重读任务列表。组合根的整体刷新也走这里，避免"两处各读一次"。 */
    suspend fun refresh() {
        jobs = repository.jobs()
    }

    fun select(jobId: String) {
        selectedJobId = jobId
    }

    /**
     * 建队列。**只建不跑** —— 用户先看到"要花多少次"，再决定要不要开始。
     *
     * 这一步就把"已有讲解 / 没有译文 / 超出上限"三类排除掉并报数，
     * 因为"点开始之后才发现大部分早就有了"是最容易让人失去信任的体验。
     */
    fun create(bankId: String, locale: String, maxCalls: Int) {
        val bank = banks().firstOrNull { it.bankId == bankId }
        if (bank == null) {
            onNotice("这个题库已经不在了", true)
            return
        }
        val model = config()
        if (!model.configured) {
            onNotice("先填好接口地址与模型，再排批量任务", true)
            return
        }
        scope.launch {
            val outcome = useCase.plan(bank, locale, model.model)
            if (outcome.items.isEmpty()) {
                onNotice(
                    when {
                        outcome.total == 0 -> "这个题库里没有题目"
                        outcome.skippedNoText > 0 && outcome.alreadyDone == 0 ->
                            "这个库里没有该语言的题面，没法按这种语言生成"
                        else -> "这个语言的题都已经有讲解了，不用再生成"
                    },
                    true,
                )
                return@launch
            }
            val now = clock.nowMillis()
            val job = AiBatchJob(
                jobId = Ids.prefixed("batch"),
                bankId = bank.bankId,
                bankTitle = bank.title,
                model = model.model,
                locale = locale,
                items = outcome.items,
                state = AiBatchState.QUEUED,
                cursor = 0,
                maxCalls = maxCalls.coerceAtLeast(0),
                note = buildString {
                    append("共 ").append(outcome.total).append(" 道")
                    if (outcome.alreadyDone > 0) append("，").append(outcome.alreadyDone).append(" 道已有讲解已排除")
                    if (outcome.skippedNoText > 0) append("，").append(outcome.skippedNoText).append(" 道没有该语言题面")
                    if (outcome.truncated > 0) {
                        append("；本次只排了 ").append(outcome.items.size)
                        append(" 道，还剩 ").append(outcome.truncated).append(" 道下次再排")
                    }
                },
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            repository.save(job)
            selectedJobId = job.jobId
            refresh()
            onNotice("队列已排好：${job.total} 道待生成，点开始才会发请求", false)
        }
    }

    /**
     * 开始 / 继续。
     *
     * 继续之前**重扫一遍缓存**：暂停期间用户可能自己单题生成过几道，
     * 不重扫就会为它们再花一次钱。
     */
    fun start(jobId: String? = null) {
        val target = jobId ?: selected?.jobId
        val job = jobs.firstOrNull { it.jobId == target }
        if (job == null) {
            onNotice("先排一个队列", true)
            return
        }
        if (running) return
        if (stopIntent == AiBatchState.CANCELLED && job.state == AiBatchState.CANCELLED) return
        stopIntent = null
        runningId = job.jobId
        scope.launch {
            try {
                val rescanned = useCase.scan(job.copy(state = AiBatchState.QUEUED))
                jobs = jobs.map { if (it.jobId == rescanned.jobId) rescanned else it }
                val finished = useCase.run(
                    job = rescanned,
                    resolve = ::resolve,
                    onProgress = { progress ->
                        jobs = jobs.map { if (it.jobId == progress.jobId) progress else it }
                    },
                    shouldStop = { stopIntent },
                )
                jobs = jobs.map { if (it.jobId == finished.jobId) finished else it }
                // 讲解记录、今日用量、任务列表都会变；一起刷才对得上账
                onJobFinished()
                onNotice(
                    when (finished.state) {
                        AiBatchState.COMPLETED -> "批量完成：${finished.summaryText()}"
                        AiBatchState.CANCELLED -> "已取消，已生成的讲解都保留着"
                        else -> finished.note.ifBlank { "已暂停，点继续会接着跑" }
                    },
                    finished.state != AiBatchState.COMPLETED,
                )
            } finally {
                runningId = null
                stopIntent = null
            }
        }
    }

    fun pause() {
        if (runningId == null) return
        stopIntent = AiBatchState.PAUSED
        onNotice("正在收尾：当前这道题返回后就停", false)
    }

    fun cancel() {
        if (runningId == null) {
            // 没在跑：直接把这个排队中的任务落成"已取消"，不用等执行器
            val job = selected ?: return
            if (!job.isOpen) return
            scope.launch {
                repository.save(job.settled(AiBatchState.CANCELLED, "已取消", clock.nowMillis()))
                refresh()
            }
            return
        }
        stopIntent = AiBatchState.CANCELLED
        onNotice("正在取消：当前这道题返回后就停，剩下的不会再发请求", false)
    }

    fun delete(jobId: String) {
        if (runningId == jobId) return
        scope.launch {
            repository.delete(jobId)
            if (selectedJobId == jobId) selectedJobId = null
            refresh()
            onNotice("任务已删除（已经生成的讲解不会跟着删）", false)
        }
    }

    /**
     * 把队列项还原成一次请求。
     *
     * 这里重新从题库里取题，而不是用建队列时的快照：题目改过就用新内容生成，
     * 缓存键也随之改变。快照只用来判断"题目还在不在"。
     */
    private suspend fun resolve(item: AiBatchItem): BatchResolve {
        val bank = bankRepository.list().firstOrNull { it.bankId == item.bankId }
            ?: return BatchResolve.Missing("题库已删除")
        val question = bank.questions.firstOrNull { it.questionId == item.questionId }
            ?: return BatchResolve.Missing("题库里已经没有这道题")
        val inLocale = question.localized(item.locale, bank.sourceLocale)
            ?: return BatchResolve.Missing("这道题没有该语言的题面")
        return BatchResolve.Ready(
            AiPromptAssembler.inputOf(
                bankId = bank.bankId,
                question = inLocale,
                locale = item.locale,
            ),
        )
    }
}
