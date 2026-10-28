package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.grading.GradingRegistry
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionRules
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict

/**
 * 用户对一条旧记录做出的复核决定。
 *
 * 只有两种：接受新答案算出来的判定，或者明确保留当时的判定。
 * **没有"忽略"** —— 忽略就是什么都不写，那条记录下次还会出现在待复核里，
 * 而"我看到了，但我不想改"应该是一次可以被记住的决定，不是一次次重复的打扰。
 */
enum class ReviewDecision(val label: String) {
    /** 接受按当前题面重算出来的判定（例如答案改了之后，当时答对的其实算错）。 */
    ACCEPT("按新答案复核"),

    /** 保留当时的判定（作者改了答案，但那次作答按当时的标准仍然算数）。 */
    RESTORE("维持当时判定"),
}

/**
 * 一次复核决定。**只追加，不修改已有条目**；同一条历史以最后一条决定为准。
 *
 * 记录 [basisFingerprint]（复核时题目的内容指纹）是有意的：
 * 没有它，就没法回答"这条决定是按哪一版题的答案做的"。题目再改一次时，
 * 依据变了，旧决定就该重新问一次 —— 这个字段是那个判断的唯一依据。
 */
data class AttemptReview(
    val attemptId: String,
    val bankId: String,
    val questionId: String,
    val decision: ReviewDecision,
    /** 复核前记录里的判定。 */
    val previousVerdict: Verdict,
    /** 复核后生效的判定。`RESTORE` 时等于 [previousVerdict]。 */
    val effectiveVerdict: Verdict,
    val basisFingerprint: String,
    val decidedAtMillis: Long,
)

/**
 * 挂在历史记录上的复核痕迹。**只在读取时合并进来，不写回 attempts 文件。**
 *
 * 历史里那一行永远是"当时发生了什么"；这里说的是"后来我怎么判的"。
 * 两者混在一起写回文件，就再也分不清哪条是原始判定、哪条是事后改的。
 */
data class ReviewMark(
    val decision: ReviewDecision,
    val from: Verdict,
    val to: Verdict,
    val decidedAtMillis: Long,
    val basisFingerprint: String,
) {
    val changed: Boolean get() = from != to
}

object AttemptReviews {

    /** 同一条历史只保留最后一条决定：决定可以被推翻，但推翻这件事本身也是一条新记录。 */
    fun latest(reviews: List<AttemptReview>): Map<String, AttemptReview> {
        if (reviews.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, AttemptReview>()
        reviews.forEach { out[it.attemptId] = it }
        return out
    }

    /**
     * 把复核结果合并进历史视图。
     *
     * 返回的是**副本**：`verdict` 换成生效判定，原判定留在 [ReviewMark.from] 里。
     * 这样统计、错题本、日历都不用改一行代码就跟着变，而界面仍然能显示
     * "这条原来判的是什么、什么时候改的"。
     */
    fun apply(attempts: List<AttemptRecord>, reviews: List<AttemptReview>): List<AttemptRecord> {
        if (reviews.isEmpty()) return attempts
        val byAttempt = latest(reviews)
        return attempts.map { attempt ->
            val review = byAttempt[attempt.attemptId] ?: return@map attempt
            // 即使结论是"维持当时判定"，痕迹也要挂上：用户做过的决定必须在历史里看得见，
            // 否则他下次会怀疑"我到底复核过没有"，然后再点一遍
            attempt.copy(
                verdict = review.effectiveVerdict,
                review = ReviewMark(
                    decision = review.decision,
                    from = attempt.verdict,
                    to = review.effectiveVerdict,
                    decidedAtMillis = review.decidedAtMillis,
                    basisFingerprint = review.basisFingerprint,
                ),
            )
        }
    }
}

/**
 * 一条待复核的历史记录。
 *
 * 带着"当时的作答"与"现在的标准答案"，是因为用户要做的是一个判断题：
 * 我当时答的到底是什么、现在算对了还是算错了 —— 只给一个"判定变了"是没法判断的。
 */
data class ReviewCandidate(
    val attempt: AttemptRecord,
    val bankTitle: String,
    /** 现在的题干（按那次作答的语言）。用户要判断的是"我现在还认不认这道题"，只给答案不够。 */
    val prompt: String,
    val typeLabel: String,
    val previousVerdict: Verdict,
    /** 按当前题面重算出来的判定。 */
    val suggestedVerdict: Verdict,
    /** 当时选的选项 / 写的答案，按当时的显示顺序翻译成 A/B/C。 */
    val previousAnswer: String,
    /** 现在的标准答案。 */
    val currentAnswer: String,
    val basisFingerprint: String,
) {
    val questionId: String get() = attempt.questionId
    val bankId: String get() = attempt.bankId
}

data class ReviewPlan(
    val candidates: List<ReviewCandidate>,
    /**
     * 题目已经不在题库里、无法重算的记录数。
     *
     * 单独计数而不是混进候选：这些记录**做不了**复核，界面要说清楚，
     * 而不是悄悄少列几条让人以为"都处理完了"。
     */
    val unreviewable: Int = 0,
) {
    val count: Int get() = candidates.size
    val isEmpty: Boolean get() = candidates.isEmpty()
}

/**
 * 从历史里找出"按现在的题目内容重算，判定会不一样"的记录。
 *
 * 判定为"要不要复核"的依据不是补丁的修订日志，而是**重算结果本身**：
 * 作者改了答案、用户手工改了题、补丁跨了两个版本 —— 触发原因有很多，
 * 但只要"现在的标准与当时的判定不一致"，就值得摆到用户面前。
 * 反过来，只信修订日志会漏掉手工编辑这类改动。
 */
object ReviewPlanner {

    fun plan(
        banks: List<QuestionBank>,
        attempts: List<AttemptRecord>,
        reviews: List<AttemptReview>,
        registry: GradingRegistry,
    ): ReviewPlan {
        if (attempts.isEmpty()) return ReviewPlan(emptyList())
        val byBank = banks.associateBy { it.bankId }
        val decided = AttemptReviews.latest(reviews)

        val candidates = mutableListOf<ReviewCandidate>()
        var unreviewable = 0
        attempts.forEach { attempt ->
            if (attempt.verdict == Verdict.ABANDONED) return@forEach
            val bank = byBank[attempt.bankId] ?: run {
                unreviewable++
                return@forEach
            }
            val raw = bank.questions.firstOrNull { it.questionId == attempt.questionId } ?: run {
                unreviewable++
                return@forEach
            }
            val question = raw.displayIn(attempt.locale, bank.sourceLocale)
            val basis = question.contentFingerprint()

            // 已经就这一版内容决定过：不再重复打扰。内容又变了才重新问。
            val prior = decided[attempt.attemptId]
            if (prior != null && prior.basisFingerprint == basis) return@forEach

            val suggested = registry.evaluate(question, attempt.answerInput()).verdict
            if (suggested == attempt.verdict) return@forEach

            candidates += ReviewCandidate(
                attempt = attempt,
                bankTitle = bank.title,
                prompt = question.promptMarkdown,
                typeLabel = question.type.label,
                previousVerdict = attempt.verdict,
                suggestedVerdict = suggested,
                previousAnswer = attempt.answerSummary(question),
                currentAnswer = QuestionRules.answerLabel(question),
                basisFingerprint = basis,
            )
        }
        return ReviewPlan(candidates.sortedBy { it.attempt.submittedAtMillis }, unreviewable)
    }
}

/**
 * 把历史记录还原成一次作答输入。
 *
 * 判题只认这个，不认"判定字符串" —— 复核必须真的重算一遍，
 * 而不是拿旧判定套个新标签。
 */
internal fun AttemptRecord.answerInput(): AnswerInput = when (questionType) {
    QuestionType.SHORT_ANSWER -> AnswerInput.Text(textAnswer.orEmpty())
    QuestionType.ORDERING -> AnswerInput.Ordering(
        orderedOptionIds.ifEmpty { displayOptionIds.filter { it in selectedOptionIds } },
    )
    else -> AnswerInput.Choices(selectedOptionIds)
}

/**
 * 当时的作答摘要，按**当时的显示顺序**翻译成 A/B/C（历史里存了那次冻结的顺序）。
 * 选项后来被删掉了就照实写 id —— 编一个字母出来只会让人以为那是当时看到的选项。
 */
internal fun AttemptRecord.answerSummary(question: Question): String = when (questionType) {
    QuestionType.SHORT_ANSWER -> textAnswer?.takeIf { it.isNotBlank() } ?: "（未作答）"
    QuestionType.ORDERING -> {
        val order = orderedOptionIds.ifEmpty { displayOptionIds.filter { it in selectedOptionIds } }
        if (order.isEmpty()) "（未作答）" else order.joinToString(" → ") { id -> optionLabel(id, question) }
    }
    else -> {
        if (selectedOptionIds.isEmpty()) "（未作答）"
        else selectedOptionIds
            .map { id -> optionLabel(id, question) }
            .sorted()
            .joinToString("、")
    }
}

private fun AttemptRecord.optionLabel(optionId: String, question: Question): String {
    val index = displayOptionIds.indexOf(optionId)
    val letter = if (index < 0) optionId else QuestionRules.label(index)
    val text = question.option(optionId)?.contentMarkdown ?: return letter
    return "$letter. ${text.take(18)}"
}

/**
 * 按"那次作答的语言"取题面，缺译文退回原文。
 *
 * 抽成一处是为了让复核与历史页看到的是**同一份题面**：两处各写一遍回退链，
 * 迟早会出现"历史页显示英文、复核却按中文答案判"这种没法解释的差异。
 */
fun Question.displayIn(locale: String, sourceLocale: String): Question {
    val want = locale.ifBlank { sourceLocale }
    return localized(want, sourceLocale)
        ?: localized(sourceLocale, sourceLocale)
        ?: this
}
