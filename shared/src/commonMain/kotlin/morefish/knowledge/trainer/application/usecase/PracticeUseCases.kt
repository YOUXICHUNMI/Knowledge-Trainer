package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.grading.GradingRegistry
import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.EvaluationResult
import morefish.knowledge.trainer.domain.model.Ids
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.isPracticable
import kotlin.random.Random

/**
 * 创建练习会话：冻结题目清单与选项显示顺序。
 * 会话一旦创建，题库后续更新不得影响它。
 */
class StartPracticeSessionUseCase(
    private val clock: WallClock = WallClock.System,
    private val random: Random = Random.Default,
) {
    fun start(
        bank: QuestionBank,
        questionCount: Int? = null,
        shuffleQuestions: Boolean = true,
        locale: String? = null,
        fallback: LocaleFallback = LocaleFallback.SKIP,
    ): PracticeSession {
        // 指定语言时先从该语言可用的题里取，再排序 —— 否则会把没译文的题也练了
        val base = if (locale == null) {
            bank.practicableQuestions()
        } else {
            bank.questionsIn(locale, fallback).filter { it.isPracticable() }
        }
        val pool = base.let { if (shuffleQuestions) it.shuffled(random) else it }
        val selected = questionCount?.let { pool.take(it.coerceAtLeast(1)) } ?: pool
        return build(bank, selected, planLabel = "全部", locale = locale ?: bank.sourceLocale)
    }

    /** 用指定题目集合开会话，用于"只练错题"这类定向复习。 */
    fun startFor(
        bank: QuestionBank,
        questions: List<Question>,
        planLabel: String = "自定义",
        shuffleOptions: Boolean = true,
        locale: String = "",
    ): PracticeSession =
        build(bank, questions.filter { it.isPracticable() }, planLabel, shuffleOptions, locale)

    private fun build(
        bank: QuestionBank,
        questions: List<Question>,
        planLabel: String,
        shuffleOptions: Boolean = true,
        locale: String = "",
    ): PracticeSession {
        val items = questions.map { question ->
            val options = if (question.shuffleAllowed && shuffleOptions) {
                question.options.shuffled(random)
            } else {
                question.options
            }
            SessionItem(question = question, displayOptionIds = options.map { it.optionId })
        }
        return PracticeSession.create(
            sessionId = Ids.uuid(),
            bankId = bank.bankId,
            items = items,
            mode = PracticeMode.NATIVE,
            nowMillis = clock.nowMillis(),
            bankTitle = bank.title,
            planLabel = planLabel,
            locale = locale,
        )
    }
}

data class SubmitOutcome(
    val session: PracticeSession,
    val attempt: AttemptRecord,
    val evaluation: EvaluationResult,
)

/**
 * 提交作答：判题 → 追加历史 → 推进会话。
 * 历史写入发生在返回给 UI 之前，用户看到反馈时记录已经落盘。
 */
class SubmitAnswerUseCase(
    private val attempts: AttemptRepository,
    private val grading: GradingRegistry = builtInGradingRegistry(),
    private val clock: WallClock = WallClock.System,
) {
    suspend fun submit(
        session: PracticeSession,
        item: SessionItem,
        answer: AnswerInput,
        elapsedMillis: Long,
        explanationSeen: Boolean = false,
        usedTools: Boolean = false,
    ): SubmitOutcome {
        val evaluation = grading.evaluate(item.question, answer)
        return persist(session, item, answer, evaluation, elapsedMillis, explanationSeen, usedTools)
    }

    /** 放弃：记录为 ABANDONED，不算错，也不进正确率分母。 */
    suspend fun abandon(
        session: PracticeSession,
        item: SessionItem,
        elapsedMillis: Long,
        usedTools: Boolean = false,
    ): SubmitOutcome {
        val evaluation = EvaluationResult(
            verdict = Verdict.ABANDONED,
            strategyId = "abandoned",
            detail = EvaluationDetail.Pending("用户放弃本题，未作答"),
        )
        return persist(
            session, item, AnswerInput.Empty, evaluation, elapsedMillis,
            explanationSeen = false, usedTools = usedTools,
        )
    }

    private suspend fun persist(
        session: PracticeSession,
        item: SessionItem,
        answer: AnswerInput,
        evaluation: EvaluationResult,
        elapsedMillis: Long,
        explanationSeen: Boolean,
        usedTools: Boolean,
    ): SubmitOutcome {
        val question = item.question
        val record = AttemptRecord(
            attemptId = Ids.uuid(),
            sessionId = session.sessionId,
            bankId = session.bankId,
            questionId = question.questionId,
            questionType = question.type,
            mode = session.mode,
            displayOptionIds = item.displayOptionIds,
            selectedOptionIds = when (answer) {
                is AnswerInput.Choices -> answer.selectedOptionIds
                is AnswerInput.Ordering -> answer.orderedOptionIds.toSet()
                else -> emptySet()
            },
            orderedOptionIds = (answer as? AnswerInput.Ordering)?.orderedOptionIds ?: emptyList(),
            textAnswer = (answer as? AnswerInput.Text)?.text,
            verdict = evaluation.verdict,
            strategyId = evaluation.strategyId,
            gradingSpec = question.grading.wireName,
            answerRevision = question.contentRevision,
            questionFingerprint = question.contentFingerprint(),
            submittedAtMillis = clock.nowMillis(),
            elapsedMillis = elapsedMillis.coerceAtLeast(0),
            explanationSeen = explanationSeen,
            usedTools = usedTools,
            locale = session.locale,
        )

        attempts.append(record)
        return SubmitOutcome(session.append(record), record, evaluation)
    }
}
