package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType

/**
 * 把一道题组装成一次讲解请求。
 *
 * 单题生成与批量生成**共用这一份**：`只发这一道题`（题干、选项、正确答案、你的答案，
 * 不带笔记 / 历史 / 其他题库）这条规则如果写两份，迟早会有一份多发了别的东西。
 */
object AiPromptAssembler {

    /**
     * 这道题当前该用哪种语言。
     *
     * 顺序：题目自带的语言 → 会话语言 → 题库源语言。
     * 都取不到时返回空串，让缓存键落在一个"未声明语言"的分支上，而不是猜一个 zh-Hans。
     */
    fun localeOf(question: Question, sessionLocale: String?, bank: QuestionBank?): String {
        question.locale?.takeIf { it.isNotBlank() }?.let { return normal(it) }
        sessionLocale?.takeIf { it.isNotBlank() }?.let { return normal(it) }
        return bank?.sourceLocale?.takeIf { it.isNotBlank() }?.let { normal(it) }.orEmpty()
    }

    private fun normal(raw: String): String = LocaleTag.parse(raw)?.value ?: raw

    fun inputOf(
        bankId: String,
        question: Question,
        locale: String,
        attempt: AttemptRecord? = null,
    ): AiPromptInput {
        // 简答没有选项，正确答案就在 answerText 里；把 answerText 同时当"正确选项"发会更啰嗦
        val correct = if (question.type == QuestionType.SHORT_ANSWER) {
            emptyList()
        } else {
            question.options
                .filter { it.optionId in question.correctOptionIds }
                .map { it.contentMarkdown }
        }
        return AiPromptInput(
            bankId = bankId,
            questionId = question.questionId,
            locale = locale,
            questionRevision = question.contentRevision,
            contentFingerprint = question.contentFingerprint(),
            typeLabel = question.type.label,
            promptMarkdown = question.promptMarkdown,
            options = question.options.map { it.optionId to it.contentMarkdown },
            correctOptions = correct,
            answerText = question.answerText.orEmpty(),
            userAnswer = attempt?.takeIf { it.questionId == question.questionId }?.let { describe(question, it) },
            language = locale,
        )
    }

    /**
     * 用户当时怎么答的。
     *
     * 只描述"选了哪些 / 写了什么"，不带对错判断 —— 对错由判题器给，
     * 让模型自己判断"当时答得对不对"等于把判题权交出去。
     */
    fun describe(question: Question, attempt: AttemptRecord): String? = when (question.type) {
        QuestionType.SHORT_ANSWER -> attempt.textAnswer?.takeIf { it.isNotBlank() }
        QuestionType.ORDERING -> attempt.orderedOptionIds
            .mapNotNull { id -> question.options.firstOrNull { it.optionId == id }?.contentMarkdown }
            .joinToString(" → ")
            .takeIf { it.isNotBlank() }
        else -> question.options
            .filter { it.optionId in attempt.selectedOptionIds }
            .joinToString("；") { it.contentMarkdown }
            .takeIf { it.isNotBlank() }
    }
}
