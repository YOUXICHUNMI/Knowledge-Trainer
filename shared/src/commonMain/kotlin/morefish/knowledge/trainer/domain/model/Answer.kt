package morefish.knowledge.trainer.domain.model

/**
 * 用户的一次作答输入，与题型、判题策略相互独立。
 * 同一个 [AnswerInput.Choices] 既可以来自单选，也可以来自多选。
 */
sealed interface AnswerInput {
    val isEmpty: Boolean

    data class Choices(val selectedOptionIds: Set<String>) : AnswerInput {
        override val isEmpty: Boolean get() = selectedOptionIds.isEmpty()
    }

    data class Text(val text: String) : AnswerInput {
        override val isEmpty: Boolean get() = text.isBlank()
    }

    /** 语义重组：用户排出的片段次序。 */
    data class Ordering(val orderedOptionIds: List<String>) : AnswerInput {
        override val isEmpty: Boolean get() = orderedOptionIds.isEmpty()
    }

    companion object {
        val Empty: AnswerInput = Choices(emptySet())
    }
}
