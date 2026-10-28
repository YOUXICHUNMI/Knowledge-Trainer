package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.practice.PracticePlan
import morefish.knowledge.trainer.domain.practice.QuestionKey
import morefish.knowledge.trainer.domain.practice.QuestionSelector
import morefish.knowledge.trainer.domain.practice.StudyStats
import kotlin.random.Random

/**
 * 按练习计划抽题并创建会话。
 *
 * 抽不到题时返回 null —— 由界面解释原因（范围太窄 / 标签没有匹配），
 * 不抛异常，也不偷偷放宽条件凑一题。
 */
class PlanPracticeUseCase(
    private val start: StartPracticeSessionUseCase = StartPracticeSessionUseCase(),
) {
    fun build(
        bank: QuestionBank,
        plan: PracticePlan,
        stats: StudyStats,
        favorites: Set<QuestionKey>,
        random: Random = Random.Default,
        locale: String? = null,
        fallback: LocaleFallback = LocaleFallback.SKIP,
    ): PracticeSession? {
        val questions = QuestionSelector.pick(bank, stats, favorites, plan, random, locale, fallback)
        if (questions.isEmpty()) return null
        return start.startFor(
            bank = bank,
            questions = questions,
            planLabel = plan.describe(),
            shuffleOptions = plan.shuffleOptions,
            locale = locale ?: bank.sourceLocale,
        )
    }

    /** 设置页用来显示"将抽到 N 题"，与真正抽题共用同一套筛选。 */
    fun candidateCount(
        bank: QuestionBank,
        plan: PracticePlan,
        stats: StudyStats,
        favorites: Set<QuestionKey>,
        locale: String? = null,
        fallback: LocaleFallback = LocaleFallback.SKIP,
    ): Int = QuestionSelector.candidates(bank, stats, favorites, plan, locale, fallback).size
}
