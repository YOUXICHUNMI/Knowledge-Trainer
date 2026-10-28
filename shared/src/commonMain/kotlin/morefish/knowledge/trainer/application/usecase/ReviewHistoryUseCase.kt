package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.ReviewRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.grading.GradingRegistry
import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.practice.AttemptReview
import morefish.knowledge.trainer.domain.practice.ReviewCandidate
import morefish.knowledge.trainer.domain.practice.ReviewDecision
import morefish.knowledge.trainer.domain.practice.ReviewPlan
import morefish.knowledge.trainer.domain.practice.ReviewPlanner

/**
 * 用户控制的历史复核。
 *
 * 题库更新改了答案之后，**软件不替用户重判历史** —— 那一行记录的是"当时按那时
 * 的标准判成了什么"，改写它就等于篡改记忆。这里做的是另外一件事：
 * 把"按现在的标准重算会变成什么"摆出来，一条一条由用户决定。
 *
 * 三条纪律：
 *
 * 1. **没有自动**：不写任何东西之前，历史、统计、错题本一个数字都不变。
 * 2. **决定也重算**：`decide` 不相信界面上那份列表，重新算一遍再写 ——
 *    用户可能在两步之间又装了个补丁。
 * 3. **可以推翻**：再点一次就是追加一条新决定，读取时以最后一条为准。
 */
class ReviewHistoryUseCase(
    private val banks: BankRepository,
    private val attempts: AttemptRepository,
    private val reviews: ReviewRepository,
    private val registry: GradingRegistry = builtInGradingRegistry(),
    private val clock: WallClock = WallClock.System,
) {

    suspend fun plan(): ReviewPlan = ReviewPlanner.plan(
        banks = banks.list(),
        attempts = attempts.all(),
        reviews = reviews.all(),
        registry = registry,
    )

    /**
     * 对一条历史做决定。返回写入的那条记录，或 null（这条已经不需要复核了）。
     *
     * `accept = true` 采用按当前题面重算出来的判定；`false` 明确保留当时的判定
     * —— 后者也写一条，是为了让"我看过了，不改"成为一次不会被反复追问的决定。
     */
    suspend fun decide(attemptId: String, accept: Boolean): AttemptReview? {
        val candidate = plan().candidates.firstOrNull { it.attempt.attemptId == attemptId }
            ?: return null
        return record(candidate, accept)
    }

    /** 批量处理。逐条重算后再写，返回值是实际写入的条数。 */
    suspend fun decideAll(accept: Boolean): Int {
        val plan = plan()
        plan.candidates.forEach { record(it, accept) }
        return plan.candidates.size
    }

    private suspend fun record(candidate: ReviewCandidate, accept: Boolean): AttemptReview {
        val review = AttemptReview(
            attemptId = candidate.attempt.attemptId,
            bankId = candidate.bankId,
            questionId = candidate.questionId,
            decision = if (accept) ReviewDecision.ACCEPT else ReviewDecision.RESTORE,
            previousVerdict = candidate.previousVerdict,
            effectiveVerdict = if (accept) candidate.suggestedVerdict else candidate.previousVerdict,
            basisFingerprint = candidate.basisFingerprint,
            decidedAtMillis = clock.nowMillis(),
        )
        reviews.append(review)
        return review
    }
}
