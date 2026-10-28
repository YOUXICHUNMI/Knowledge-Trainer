package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AiBatchRepository
import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.application.port.AiRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchPlanner
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.model.QuestionBank

/** 解析一道题当前该发什么。题目不在了 / 没有该语言题面时返回 [Missing]。 */
sealed interface BatchResolve {
    data class Ready(val input: AiPromptInput) : BatchResolve

    /** [reason] 直接给用户看，例如"题库里已经没有这道题"。 */
    data class Missing(val reason: String) : BatchResolve
}

/**
 * 建队列的结果。
 *
 * 四个数字回答四个不同的问题，缺一个用户就会开始猜：
 * [items] 是"这次要生成的"，[alreadyDone] 是"不用花钱的"，
 * [skippedNoText] 是"这个库根本没有该语言题面的"，[truncated] 是"太多、这次只排了一部分"。
 */
data class BatchPlanOutcome(
    val items: List<AiBatchItem>,
    val total: Int,
    val alreadyDone: Int,
    val skippedNoText: Int,
    val truncated: Int,
) {
    val needsCalls: Int get() = items.size
}

/**
 * 批量生成队列的执行器。
 *
 * 四条硬规则，每一条都是为了让"账单不出意外"：
 *
 * 1. **一次只发一个请求。** 并发跑十道看着快，实际是预算瞬间超限、服务端 429、
 *    错误混在一起分不清哪道是限流哪道是内容问题。
 * 2. **失败项不重试，继续下一道。** 100 道 × 3 次重试 = 300 次请求。
 *    失败原因留在项上，最后统一报告，要重试由用户再点一次。
 * 3. **预算不足 / 达到次数上限 → 暂停整个任务，而不是把剩下的标失败。**
 *    把 40 道标成"失败"会让用户以为题库坏了；标成"暂停"才说得清发生了什么。
 * 4. **进度不靠本文件保证。** 每生成一道，讲解记录就已经落盘了；
 *    任务文件只存清单与指针，节流写。重启后重新扫描缓存即可算出真实进度 ——
 *    恢复只有一条路径，就不会出现"任务说完成了但缓存里没有"这种对不上的状态。
 */
class RunBatchUseCase(
    private val repo: AiRepository,
    private val batches: AiBatchRepository,
    private val generate: GenerateExplanationUseCase,
    private val clock: WallClock,
) {

    /**
     * 排版队列：从题库里挑出"真的需要生成"的题。
     *
     * 三个排除条件各有用处，而且**必须在建任务时就算清**：
     * - 已有可复用讲解 → 不发请求（用户最关心的就是这个数字）
     * - 该语言没有题面 → 进队列也只会失败，不如当场排除并报数
     * - 超过单任务上限 → 一千万道题排成一个队列，界面和文件都撑不住
     */
    suspend fun plan(
        bank: QuestionBank,
        locale: String,
        model: String,
        maxItems: Int = MAX_ITEMS_PER_JOB,
    ): BatchPlanOutcome {
        val planned = AiBatchPlanner.plan(bank, locale)
        val cached = reusableKeys(model)
        val need = planned.items.filter { it.cacheKey(model) !in cached }
        val alreadyDone = planned.items.size - need.size
        val kept = if (need.size > maxItems) need.take(maxItems) else need
        return BatchPlanOutcome(
            items = kept,
            total = planned.total + planned.skippedNoText,
            alreadyDone = alreadyDone,
            skippedNoText = planned.skippedNoText,
            truncated = need.size - kept.size,
        )
    }

    /** 当前可复用的讲解缓存键集合。一次读全部记录再建索引，不要逐项查。 */
    private suspend fun reusableKeys(model: String): Set<String> =
        if (model.isBlank()) {
            emptySet()
        } else {
            repo.records().filter { it.state.isReusable }.map { it.key }.toSet()
        }

    private fun AiBatchItem.cacheKey(model: String): String = AiExplanation.keyFor(
        bankId = bankId,
        questionId = questionId,
        locale = locale,
        questionRevision = questionRevision,
        contentFingerprint = contentFingerprint,
        model = model,
    )

    /**
     * 扫描：把缓存里已有可复用讲解的项标成跳过，并算出"这次真正要发几次请求"。
     *
     * 继续之前要重扫：用户暂停期间可能自己单题生成过几道，
     * 不重扫就会为它们再花一次钱。
     *
     * 一次 `records()` 拉全套再建索引：逐项 `find()` 会把整份文件读 N 遍。
     */
    suspend fun scan(job: AiBatchJob): AiBatchJob {
        if (!job.isOpen) return job
        val cached = reusableKeys(job.model)
        val now = clock.nowMillis()
        var already = 0
        val items = job.items.map { item ->
            if (item.state.isSettled) return@map item
            if (item.cacheKey(job.model) in cached) {
                already++
                item.copy(state = AiBatchItemState.SKIPPED, note = "已有讲解，不会重复请求")
            } else {
                item
            }
        }
        val rescanned = job.copy(items = items, updatedAtMillis = now)
        val scanned = rescanned.copy(
            cursor = rescanned.cursorToNextQueued(),
            note = if (already > 0) {
                "重新扫描：$already 道已有讲解，本次实际还需 ${items.count { it.state == AiBatchItemState.QUEUED }} 次请求"
            } else {
                job.note
            },
        )
        batches.save(scanned)
        return scanned
    }

    /**
     * 跑一轮。返回时任务已经被保存过，状态是 COMPLETED / PAUSED / CANCELLED 之一。
     *
     * [onProgress] 每次有变化就回调，用来刷界面；它**不负责落盘**。
     * [shouldStop] 由界面提供（用户点了暂停或取消），返回非 null 时立即收尾。
     */
    suspend fun run(
        job: AiBatchJob,
        resolve: suspend (AiBatchItem) -> BatchResolve,
        onProgress: suspend (AiBatchJob) -> Unit = {},
        shouldStop: () -> AiBatchState? = { null },
    ): AiBatchJob {
        if (!job.isOpen) return job

        var current = job.copy(state = AiBatchState.RUNNING, note = "", updatedAtMillis = clock.nowMillis())
        batches.save(current)
        onProgress(current)

        var index = current.cursor.coerceIn(0, current.items.size)
        var unflushed = 0
        var stopWith: String? = null

        while (index < current.items.size) {
            shouldStop()?.let { wanted ->
                stopWith = when (wanted) {
                    AiBatchState.CANCELLED -> CANCELLED_NOTE
                    else -> PAUSED_NOTE
                }
                current = current.settled(wanted, stopWith, clock.nowMillis())
                break
            }
            if (current.hitCallLimit) {
                stopWith = "已到本次上限 ${current.maxCalls} 次请求，剩下 ${current.queuedCount} 道没有处理"
                current = current.settled(AiBatchState.PAUSED, stopWith, clock.nowMillis())
                break
            }

            val item = current.items[index]
            if (item.state != AiBatchItemState.QUEUED) {
                index++
                continue
            }

            when (val resolved = resolve(item)) {
                is BatchResolve.Missing -> {
                    current = current.replace(index, item.copy(state = AiBatchItemState.SKIPPED, note = resolved.reason))
                    index++
                }

                is BatchResolve.Ready -> {
                    when (val outcome = generate.generate(resolved.input)) {
                        is AiGenerationOutcome.Ready -> {
                            current = if (!outcome.fromCache) {
                                current.replace(
                                    index,
                                    item.copy(
                                        state = AiBatchItemState.DONE,
                                        attempts = item.attempts + 1,
                                        note = "",
                                        promptTokens = outcome.record.promptTokens,
                                        completionTokens = outcome.record.completionTokens,
                                        costMicros = outcome.record.costMicros,
                                    ),
                                )
                            } else {
                                // 扫描之后、执行之前别人刚好生成了：一样算跳过，不算重复花钱
                                current.replace(
                                    index,
                                    item.copy(state = AiBatchItemState.SKIPPED, note = "已有讲解，不会重复请求"),
                                )
                            }
                            index++
                        }

                        is AiGenerationOutcome.Failed -> {
                            // 失败也花了一次（可能已扣费），所以 attempts 照样 +1，然后继续下一道
                            current = current.replace(
                                index,
                                item.copy(
                                    state = AiBatchItemState.FAILED,
                                    attempts = item.attempts + 1,
                                    note = outcome.reason,
                                ),
                            )
                            index++
                        }

                        is AiGenerationOutcome.Blocked -> {
                            stopWith = outcome.reason
                            current = current.settled(AiBatchState.PAUSED, outcome.reason, clock.nowMillis())
                            break
                        }

                        is AiGenerationOutcome.Unavailable -> {
                            // 配置被改坏 / 密钥被删 / 平台不支持：再跑也是同样的错
                            stopWith = outcome.reason
                            current = current.settled(AiBatchState.PAUSED, outcome.reason, clock.nowMillis())
                            break
                        }
                    }
                }
            }

            current = current.copy(
                cursor = index,
                updatedAtMillis = clock.nowMillis(),
            )
            if (++unflushed >= FLUSH_EVERY) {
                batches.save(current)
                onProgress(current)
                unflushed = 0
            }
        }

        if (stopWith == null) {
            current = current.afterRun("", clock.nowMillis())
        }
        batches.save(current)
        onProgress(current)
        return current
    }

    private companion object {
        /**
         * 每处理几项落一次盘。
         *
         * 不逐项写：任务文件是整份重写的，几百道题就是几百次全量写。
         * 也不整轮只写一次：中途被强杀会丢掉"做到哪了"。
         * 丢几项的代价很小 —— 讲解记录早就落盘了，重扫时那几道会被标成跳过，不会重复付费。
         */
        const val FLUSH_EVERY = 5

        /**
         * 单个任务最多排多少道。
         *
         * 不是性能上限，是"能不能讲清楚"的上限：一万项的任务既不能一眼看到进度，
         * 保存一次的 JSON 也要几兆。超出时截断并如实报数，让用户自己再排一个。
         */
        const val MAX_ITEMS_PER_JOB = 500

        const val PAUSED_NOTE = "已暂停，点继续会接着跑"
        const val CANCELLED_NOTE = "已取消，未处理的项不会再发请求"
    }
}
