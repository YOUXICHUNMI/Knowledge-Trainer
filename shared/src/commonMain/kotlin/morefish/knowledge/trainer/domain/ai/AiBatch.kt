package morefish.knowledge.trainer.domain.ai

import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank

/**
 * 批量生成任务的状态。
 *
 * [PAUSED] 与 [CANCELLED] 的区别是有意的：
 * 暂停是"先停一下，回头还能接着跑"，取消是"这个任务不要了"。
 * 把两者合成一个"停止"，用户就没法区分"我晚点继续"和"我放弃"。
 */
enum class AiBatchState(val wireName: String, val label: String) {
    QUEUED("queued", "待开始"),
    RUNNING("running", "生成中"),
    PAUSED("paused", "已暂停"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
    ;

    /** 还能继续推进的状态。 */
    val isOpen: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED

    companion object {
        fun fromWire(value: String?): AiBatchState? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 单项的状态。
 *
 * [SKIPPED] 不是失败：它表示"这一项不需要再花一次钱"——
 * 缓存里已经有可复用的讲解，或者题库里已经没有这道题了。
 * 把这两件事标成 FAILED，用户会以为自己有几十道题坏了。
 */
enum class AiBatchItemState(val wireName: String, val label: String) {
    QUEUED("queued", "待处理"),
    DONE("done", "已生成"),
    FAILED("failed", "失败"),
    SKIPPED("skipped", "已跳过"),
    ;

    val isSettled: Boolean get() = this != QUEUED

    companion object {
        fun fromWire(value: String?): AiBatchItemState? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 队列里的一项：一道题在**某种语言**下的一次生成需求。
 *
 * [questionRevision] 与 [contentFingerprint] 是创建任务那一刻的快照。
 * 执行时会拿题库里的当前内容重新算一遍 —— 值不一样说明题目改过，
 * 那就用新内容重新生成（缓存键自然也变了），而不是拒绝执行。
 * 快照的作用只是让"这道题当时是什么样"可查。
 */
data class AiBatchItem(
    val bankId: String,
    val questionId: String,
    val locale: String,
    val questionRevision: Int,
    val contentFingerprint: String,
    val state: AiBatchItemState = AiBatchItemState.QUEUED,
    /**
     * 实际发出的请求次数。一次任务内**不自动重试**，所以正常是 0 或 1。
     * 留着它是为了让"失败也花了一次钱"这件事在账面上看得见。
     */
    val attempts: Int = 0,
    /** 跳过原因 / 失败原因，中文，直接给用户看。 */
    val note: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
) {
    val tokens: Int get() = promptTokens + completionTokens
}

/**
 * 一次批量生成任务。
 *
 * 只存"要做什么"和"做到哪了"，**不存讲解正文**：正文在 `AiExplanation` 里，
 * 一份内容只有一处。两者靠缓存键对应（见 [AiExplanation.keyFor]）。
 *
 * 计数字段一律现算而不落盘，理由很实际：冗余计数一旦和 items 对不上，
 * 就会出现"进度条 100% 但还剩两道"这种没人能解释的界面。
 */
data class AiBatchJob(
    val jobId: String,
    val bankId: String,
    val bankTitle: String,
    val model: String,
    val locale: String,
    val items: List<AiBatchItem>,
    val state: AiBatchState = AiBatchState.QUEUED,
    /** 下次从第几项开始。只会向后走，所以它足够当续跑指针。 */
    val cursor: Int = 0,
    /** 本次任务最多发几次请求；0 表示不限（仍受每日预算约束）。 */
    val maxCalls: Int = 0,
    /** 暂停 / 取消 / 扫描时的说明，中文。 */
    val note: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    val total: Int get() = items.size

    val doneCount: Int get() = items.count { it.state == AiBatchItemState.DONE }

    val failedCount: Int get() = items.count { it.state == AiBatchItemState.FAILED }

    val skippedCount: Int get() = items.count { it.state == AiBatchItemState.SKIPPED }

    val queuedCount: Int get() = items.count { it.state == AiBatchItemState.QUEUED }

    /** 已经有结论的项（生成成功 / 失败 / 跳过）。 */
    val settledCount: Int get() = doneCount + failedCount + skippedCount

    /** 本次已花掉的请求次数（失败也算）。 */
    val callsUsed: Int get() = items.count { it.attempts > 0 }

    val spentMicros: Long get() = items.sumOf { it.costMicros }

    val spentTokens: Int get() = items.sumOf { it.tokens }

    /** 进度按"有结论的项"算，不按游标算 —— 游标是内部指针，用户看的是结果。 */
    val progress: Float get() = if (total == 0) 0f else settledCount.toFloat() / total

    /** 本次还剩几次请求额度；null 表示用户没设上限。 */
    val callsLeft: Int? get() = if (maxCalls > 0) (maxCalls - callsUsed).coerceAtLeast(0) else null

    val hitCallLimit: Boolean get() = maxCalls > 0 && callsUsed >= maxCalls

    val isOpen: Boolean get() = state.isOpen

    /** 还能不能继续跑：状态允许且还有待处理项。 */
    val canResume: Boolean get() = isOpen && queuedCount > 0 && !hitCallLimit

    fun itemAt(index: Int): AiBatchItem? = items.getOrNull(index)

    fun replace(index: Int, item: AiBatchItem): AiBatchJob =
        copy(items = items.toMutableList().also { it[index] = item })

    /** 从当前游标往后找第一个待处理项；没有就停在末尾。 */
    fun cursorToNextQueued(): Int {
        val next = items.indexOfFirst { it.state == AiBatchItemState.QUEUED }
        return if (next < 0) items.size else maxOf(next, 0)
    }

    /**
     * 把任务整体改成一个"已结束"的状态。
     *
     * 暂停/取消时**不碰未处理的项** —— 它们还是 QUEUED，所以"继续"就是原样接着跑。
     * 只有真正跑完（没有 QUEUED 了）才标 COMPLETED。
     */
    fun settled(next: AiBatchState, reason: String, nowMillis: Long): AiBatchJob = copy(
        state = next,
        note = reason,
        updatedAtMillis = nowMillis,
    )

    /** 跑完一轮后按实际结果决定是完成还是留着待续。 */
    fun afterRun(reason: String, nowMillis: Long): AiBatchJob = when {
        queuedCount == 0 -> settled(AiBatchState.COMPLETED, reason.ifBlank { summaryText() }, nowMillis)
        else -> copy(
            state = AiBatchState.PAUSED,
            note = reason,
            updatedAtMillis = nowMillis,
            cursor = cursorToNextQueued(),
        )
    }

    fun summaryText(): String = buildString {
        append("共 ").append(total).append(" 道：成功 ").append(doneCount)
        append("，跳过 ").append(skippedCount)
        if (failedCount > 0) append("，失败 ").append(failedCount)
        if (callsUsed > 0) append("；本次发出 ").append(callsUsed).append(" 次请求")
    }
}

/**
 * 建任务时的扫描结果。
 *
 * [skippedNoText] 是"这个库里根本没有该语言题面"的题数。
 * 它必须单独告诉用户 —— 否则用户会以为批量漏了题。
 */
data class AiBatchPlan(
    val items: List<AiBatchItem>,
    val skippedNoText: Int,
) {
    val total: Int get() = items.size
}

object AiBatchPlanner {

    /**
     * 把题库里"有该语言题面"的题排成队列。
     *
     * 语言处理与练习完全一致：先用 [Question.localized] 换成该语言的文案，
     * 换不出来的题**不进队列**（不是进队列再失败）。指纹按该语言的文案算，
     * 所以中文题面和英文题面各自有自己的缓存键 —— 翻译改过不会复用旧讲解。
     */
    fun plan(bank: QuestionBank, locale: String, questions: List<Question> = bank.questions): AiBatchPlan {
        val want = LocaleTag.parse(locale)?.value ?: bank.sourceLocale
        var skipped = 0
        val items = ArrayList<AiBatchItem>(questions.size)
        questions.forEach { question ->
            val inLocale = question.localized(want, bank.sourceLocale)
            if (inLocale == null) {
                skipped++
                return@forEach
            }
            items += AiBatchItem(
                bankId = bank.bankId,
                questionId = inLocale.questionId,
                locale = want,
                questionRevision = inLocale.contentRevision,
                contentFingerprint = inLocale.contentFingerprint(),
            )
        }
        return AiBatchPlan(items = items, skippedNoText = skipped)
    }
}
