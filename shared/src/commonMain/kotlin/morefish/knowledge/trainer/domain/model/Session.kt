package morefish.knowledge.trainer.domain.model

/**
 * 训练模式与题型正交：同一道单选题可以在"原生作答"或未来的
 * "语义重组"模式出现。P1 只实现原生模式。
 */
enum class PracticeMode(val wireName: String, val label: String) {
    NATIVE("native", "原生题型"),
}

enum class SessionState { CREATED, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED }

/**
 * 会话中的一题。显示顺序在会话创建时冻结，
 * 之后无论题库如何更新，本会话继续使用同一份题干与答案。
 */
data class SessionItem(
    val question: Question,
    val displayOptionIds: List<String>,
) {
    fun orderedOptions(): List<QuestionOption> =
        displayOptionIds.mapNotNull { id -> question.option(id) }

    /** 用于界面展示的选项标签（A/B/C…），与 optionId 身份无关。 */
    fun labelOf(optionId: String): String {
        val index = displayOptionIds.indexOf(optionId)
        return if (index < 0) "?" else optionLabel(index)
    }

    companion object {
        fun optionLabel(index: Int): String = if (index in 0..25) ('A' + index).toString() else "${index + 1}"
    }
}

/**
 * 一次提交的不可变记录。写入后永不修改；
 * 重新判定必须产生独立事件，而不是覆盖原记录。
 */
data class AttemptRecord(
    val attemptId: String,
    val sessionId: String,
    val bankId: String,
    val questionId: String,
    val questionType: QuestionType,
    val mode: PracticeMode,
    val displayOptionIds: List<String>,
    val selectedOptionIds: Set<String>,
    /** 语义重组作答的片段次序；非重组题为空。 */
    val orderedOptionIds: List<String> = emptyList(),
    val textAnswer: String?,
    val verdict: Verdict,
    val strategyId: String,
    val gradingSpec: String,
    val answerRevision: Int,
    val questionFingerprint: String,
    val submittedAtMillis: Long,
    val elapsedMillis: Long,
    val explanationSeen: Boolean,
    /**
     * 作答过程中是否打开过辅助工具（计算器 / 单位换算）。
     *
     * 记录它不是为了惩罚，而是为了事后能区分"这题是真会"和"这题是用计算器凑出来的"。
     * 默认 false：旧历史里没有这个字段，读回来是"未使用"，而不是"未知"。
     */
    val usedTools: Boolean = false,
    /**
     * 这次作答用的是哪种语言的题面与答案。
     *
     * 必须逐条记录，而不是从会话推：同一道题今天用中文答、明天用英文答，
     * 两条记录的判题依据（标准答案原文）本来就不同。空串表示旧记录未声明。
     */
    val locale: String = "",
    /**
     * 后来按新答案复核过吗？
     *
     * **只在读取时由 `AttemptReviews.apply` 合并进来，不落盘**：
     * 历史文件里那一行永远记"当时判成了什么"，复核是另一个事件。
     * 合并后 [verdict] 会被换成生效判定，原判定留在 `review.from` 里，
     * 所以界面既能按新判定统计，又能显示"原来是正确，某日改判为错误"。
     */
    val review: morefish.knowledge.trainer.domain.practice.ReviewMark? = null,
)

/**
 * 未提交的作答草稿。只用于"关掉应用再回来能接着选"，
 * 不参与判题，也不写入历史 —— 草稿不是作答事件。
 */
data class PracticeDraft(
    val selectedOptionIds: Set<String> = emptySet(),
    val textAnswer: String = "",
    /** 重组题已排好的片段次序。 */
    val orderedOptionIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = selectedOptionIds.isEmpty() && textAnswer.isEmpty() && orderedOptionIds.isEmpty()

    companion object {
        val Empty: PracticeDraft = PracticeDraft()
    }
}

data class PracticeSession(
    val sessionId: String,
    val bankId: String,
    val mode: PracticeMode,
    val state: SessionState,
    val items: List<SessionItem>,
    val cursor: Int,
    val attempts: List<AttemptRecord>,
    val createdAtMillis: Long,
    /** 题库标题快照：题库被删除后，恢复入口仍能说清"这是哪次练习"。 */
    val bankTitle: String = "",
    /** 抽取条件的人类可读描述，只用于展示，不参与恢复计算。 */
    val planLabel: String = "",
    val draft: PracticeDraft = PracticeDraft.Empty,
    val updatedAtMillis: Long = createdAtMillis,
    /** 本场练习使用的语言（题面与判题答案都取自它）。空串 = 源语言 / 旧会话。 */
    val locale: String = "",
) {
    val current: SessionItem? get() = items.getOrNull(cursor)
    val isLastQuestion: Boolean get() = cursor >= items.lastIndex
    val answeredCount: Int get() = attempts.size
    val nextOrdinal: Int get() = attempts.size + 1

    val isResumable: Boolean
        get() = state == SessionState.IN_PROGRESS || state == SessionState.PAUSED || state == SessionState.CREATED

    /**
     * 第一道还没提交过的题的序号。
     * 提交后界面停在原题看反馈，此时游标没有前进，所以不能用 cursor 判断"练到哪了"。
     */
    val firstUnansweredIndex: Int
        get() {
            val answered = attempts.map { it.questionId }.toSet()
            var index = 0
            while (index < items.size && items[index].question.questionId in answered) index++
            return index
        }

    /** 追加记录但不移动游标：提交后界面还要停留在此题展示反馈。 */
    fun append(attempt: AttemptRecord): PracticeSession = copy(
        attempts = attempts + attempt,
        state = SessionState.IN_PROGRESS,
    )

    /** 前进到下一题。由"下一题"操作显式调用，而不是提交时自动移动。 */
    fun advance(): PracticeSession = copy(
        cursor = minOf(cursor + 1, items.size),
        state = SessionState.IN_PROGRESS,
    )

    fun completed(): PracticeSession = copy(state = SessionState.COMPLETED, cursor = items.size)

    fun touch(nowMillis: Long): PracticeSession = copy(updatedAtMillis = nowMillis)

    fun withDraft(draft: PracticeDraft, nowMillis: Long): PracticeSession =
        copy(draft = draft, updatedAtMillis = nowMillis)

    fun paused(nowMillis: Long): PracticeSession =
        copy(state = SessionState.PAUSED, updatedAtMillis = nowMillis)

    fun cancelled(nowMillis: Long): PracticeSession =
        copy(state = SessionState.CANCELLED, updatedAtMillis = nowMillis)

    /**
     * 恢复时把游标对齐到"第一道还没提交过的题"。
     *
     * 提交后界面会停留在原题展示反馈，此时游标并没有前进；
     * 如果直接按保存的游标恢复，用户会看到一道已经答过的题，并且能重复提交。
     */
    fun resumeNormalized(nowMillis: Long): PracticeSession {
        val index = firstUnansweredIndex
        val resumed = copy(cursor = index, state = SessionState.IN_PROGRESS, updatedAtMillis = nowMillis)
        return if (index == cursor) resumed else resumed.copy(draft = PracticeDraft.Empty)
    }

    fun summary(): SessionSummary = SessionSummary.from(items.size, attempts)

    companion object {
        fun create(
            sessionId: String,
            bankId: String,
            items: List<SessionItem>,
            mode: PracticeMode,
            nowMillis: Long,
            bankTitle: String = "",
            planLabel: String = "",
            locale: String = "",
        ): PracticeSession = PracticeSession(
            sessionId = sessionId,
            bankId = bankId,
            mode = mode,
            state = SessionState.IN_PROGRESS,
            items = items,
            cursor = 0,
            attempts = emptyList(),
            createdAtMillis = nowMillis,
            bankTitle = bankTitle,
            planLabel = planLabel,
            locale = locale,
        )
    }
}

/**
 * 会话总结。分母必须显式：正确率只对"已判定"的题目计算，
 * 待确认与放弃不进入分母，也不假装被计入。
 */
data class SessionSummary(
    val totalQuestions: Int,
    val answered: Int,
    val correct: Int,
    val partiallyCorrect: Int,
    val incorrect: Int,
    val pending: Int,
    val abandoned: Int,
    val totalElapsedMillis: Long,
) {
    val graded: Int get() = correct + partiallyCorrect + incorrect

    /** 正确 / 已判定。无已判定题时为 null，界面显示"—"而不是 0%。 */
    val accuracy: Double? get() = if (graded == 0) null else correct.toDouble() / graded

    /** 部分正确按 0.5 计入的加权正确率，仅作辅助说明。 */
    val weightedAccuracy: Double? get() = if (graded == 0) null else (correct + partiallyCorrect * 0.5) / graded

    companion object {
        fun from(totalQuestions: Int, attempts: List<AttemptRecord>): SessionSummary = SessionSummary(
            totalQuestions = totalQuestions,
            answered = attempts.size,
            correct = attempts.count { it.verdict == Verdict.CORRECT },
            partiallyCorrect = attempts.count { it.verdict == Verdict.PARTIALLY_CORRECT },
            incorrect = attempts.count { it.verdict == Verdict.INCORRECT },
            pending = attempts.count { it.verdict == Verdict.PENDING },
            abandoned = attempts.count { it.verdict == Verdict.ABANDONED },
            totalElapsedMillis = attempts.sumOf { it.elapsedMillis },
        )
    }
}

/** 错题聚合结果：一道题一条，不是错一次复制一份。 */
data class WrongQuestionEntry(
    val questionId: String,
    val bankId: String,
    val latestAttempt: AttemptRecord,
    val wrongCount: Int,
    val lastWrongAtMillis: Long,
)

object WrongQuestions {
    /** 以"最近一次提交"为准判断是否仍为错题；已答对则视为已巩固。 */
    fun derive(attempts: List<AttemptRecord>): List<WrongQuestionEntry> {
        val latestByQuestion = attempts
            .groupBy { it.bankId to it.questionId }
            .mapValues { (_, list) -> list.maxBy { it.submittedAtMillis } }

        return latestByQuestion.entries
            .filter { (_, latest) -> latest.verdict == Verdict.INCORRECT || latest.verdict == Verdict.PARTIALLY_CORRECT }
            .map { (key, latest) ->
                val wrongCount = attempts.count {
                    it.bankId == key.first && it.questionId == key.second &&
                        (it.verdict == Verdict.INCORRECT || it.verdict == Verdict.PARTIALLY_CORRECT)
                }
                WrongQuestionEntry(key.second, key.first, latest, wrongCount, latest.submittedAtMillis)
            }
            .sortedByDescending { it.lastWrongAtMillis }
    }
}
