package morefish.knowledge.trainer.domain.practice

import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.isPracticable
import kotlin.random.Random

/**
 * 抽题：先按范围/题型/标签求候选集，再决定题量与顺序。
 *
 * 刻意与"会话创建"分开：设置页需要在不建会话的情况下显示"会抽到多少题"，
 * 所以筛选必须是纯函数、可重复调用且无副作用。
 */
object QuestionSelector {

    /**
     * @param locale 练习语言。null = 不按语言筛选（旧行为，用源语言原文）。
     *   给了语言就只从"有这份译文"的题里抽（或按 [fallback] 退回原文的题）。
     */
    fun candidates(
        bank: QuestionBank,
        stats: StudyStats,
        favorites: Set<QuestionKey>,
        plan: PracticePlan,
        locale: String? = null,
        fallback: LocaleFallback = LocaleFallback.SKIP,
    ): List<Question> {
        val pool = if (locale == null) {
            bank.practicableQuestions()
        } else {
            bank.questionsIn(locale, fallback).filter { it.isPracticable() }
        }
        return pool.filter { question ->
            plan.matchesType(question.type) &&
                plan.matchesTags(question.tags) &&
                matchesScope(question, bank.bankId, stats, favorites, plan.scope)
        }
    }

    fun select(
        candidates: List<Question>,
        plan: PracticePlan,
        random: Random = Random.Default,
    ): List<Question> {
        if (candidates.isEmpty()) return emptyList()
        val limit = plan.questionCount?.coerceIn(1, candidates.size) ?: candidates.size

        if (plan.mixedTypes) {
            return selectMixed(candidates, limit, plan.shuffleQuestions, random)
        }

        val ordered = if (plan.shuffleQuestions) candidates.shuffled(random) else candidates
        return ordered.take(limit)
    }

    fun pick(
        bank: QuestionBank,
        stats: StudyStats,
        favorites: Set<QuestionKey>,
        plan: PracticePlan,
        random: Random = Random.Default,
        locale: String? = null,
        fallback: LocaleFallback = LocaleFallback.SKIP,
    ): List<Question> = select(candidates(bank, stats, favorites, plan, locale, fallback), plan, random)

    /**
     * 按题型轮转抽取：每一轮从各题型池里各取一题，直到凑够题量或池子抽空。
     * 这样"混合抽题"是结构性的，而不是靠随机碰运气。
     */
    private fun selectMixed(
        candidates: List<Question>,
        limit: Int,
        shuffle: Boolean,
        random: Random,
    ): List<Question> {
        val pools = QuestionType.entries.map { type ->
            candidates.filter { it.type == type }.let { if (shuffle) it.shuffled(random) else it }.toMutableList()
        }.filter { it.isNotEmpty() }.map { it.toMutableList() }

        if (pools.isEmpty()) return emptyList()

        val picked = mutableListOf<Question>()
        var round = 0
        while (picked.size < limit) {
            var takenThisRound = false
            for (pool in pools) {
                if (picked.size >= limit) break
                if (round < pool.size) {
                    picked.add(pool[round])
                    takenThisRound = true
                }
            }
            if (!takenThisRound) break
            round++
        }
        return picked
    }

    private fun matchesScope(
        question: Question,
        bankId: String,
        stats: StudyStats,
        favorites: Set<QuestionKey>,
        scope: PracticeScope,
    ): Boolean {
        val key = QuestionKey(bankId, question.questionId)
        val state = stats.of(key)
        return when (scope) {
            PracticeScope.ALL -> true
            PracticeScope.UNPRACTICED -> state == null || !state.isPracticed
            PracticeScope.CURRENT_WRONG -> state?.isCurrentWrong == true
            PracticeScope.HISTORY_WRONG -> state?.everWrong == true
            PracticeScope.CONSOLIDATED -> state?.isConsolidated == true
            PracticeScope.FAVORITES -> key in favorites
        }
    }
}
