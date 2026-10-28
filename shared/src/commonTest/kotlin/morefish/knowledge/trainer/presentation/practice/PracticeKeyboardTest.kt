package morefish.knowledge.trainer.presentation.practice

import morefish.knowledge.trainer.domain.model.QuestionType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 键盘作答的规则。
 *
 * 这些断言盯的都不是"能不能按"，而是**什么时候不许管**：简答题里按 2 是打字、
 * 回车是换行、Ctrl+C 是复制。抢错一个，用户就会觉得输入框坏了 ——
 * 而这种坏法在界面上点一遍是发现不了的。
 */
class PracticeKeyboardTest {

    private fun choice(
        type: QuestionType = QuestionType.SINGLE_CHOICE,
        optionCount: Int = 4,
        revealed: Boolean = false,
        canSubmit: Boolean = false,
    ) = PracticeKeyContext(type = type, optionCount = optionCount, revealed = revealed, canSubmit = canSubmit)

    private fun letter(index: Int) = Keystroke(letterIndex = index)
    private fun digit(index: Int) = Keystroke(digitIndex = index)
    private val enter = Keystroke(isConfirm = true)

    @Test
    fun letterKeysPickByDisplayedOrder() {
        val context = choice()
        assertEquals(PracticeCommand.PickOption(0), PracticeKeyboard.translate(letter(0), context))
        assertEquals(PracticeCommand.PickOption(2), PracticeKeyboard.translate(letter(2), context))
    }

    @Test
    fun digitKeysMeanTheSameAsLetters() {
        val context = choice()
        assertEquals(PracticeCommand.PickOption(0), PracticeKeyboard.translate(digit(0), context))
        assertEquals(PracticeCommand.PickOption(3), PracticeKeyboard.translate(digit(3), context))
    }

    @Test
    fun aKeyPastTheLastOptionIsIgnoredInsteadOfPickingTheLastOne() {
        val context = choice(optionCount = 3)
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(letter(3), context))
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(digit(5), context))
    }

    @Test
    fun typingInAShortAnswerIsNeverMistakenForPickingAnOption() {
        val context = choice(type = QuestionType.SHORT_ANSWER, canSubmit = true)
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(letter(0), context))
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(digit(1), context))
    }

    @Test
    fun bareEnterKeepsItsNewlineInAShortAnswer() {
        val context = choice(type = QuestionType.SHORT_ANSWER, canSubmit = true)
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(enter, context))
    }

    @Test
    fun ctrlEnterSubmitsAShortAnswerOnlyWhenThereIsSomethingToSubmit() {
        val armed = choice(type = QuestionType.SHORT_ANSWER, canSubmit = true)
        assertEquals(
            PracticeCommand.Submit,
            PracticeKeyboard.translate(Keystroke(isConfirm = true, hasCommandModifier = true), armed),
        )

        val empty = choice(type = QuestionType.SHORT_ANSWER, canSubmit = false)
        assertEquals(
            PracticeCommand.Ignored,
            PracticeKeyboard.translate(Keystroke(isConfirm = true, hasCommandModifier = true), empty),
        )
    }

    @Test
    fun enterSubmitsAChoiceOnlyAfterSomethingIsSelected() {
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(enter, choice(canSubmit = false)))
        assertEquals(PracticeCommand.Submit, PracticeKeyboard.translate(enter, choice(canSubmit = true)))
    }

    @Test
    fun enterAdvancesOnceTheAnswerIsGraded() {
        val revealed = choice(revealed = true, canSubmit = true)
        assertEquals(PracticeCommand.Advance, PracticeKeyboard.translate(enter, revealed))
    }

    @Test
    fun aGradedAnswerCannotBeChangedByPressingAKey() {
        val revealed = choice(revealed = true)
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(letter(1), revealed))
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(digit(1), revealed))
    }

    @Test
    fun shortcutsWithAModifierAreNeverTurnedIntoAnswers() {
        val context = choice()
        assertEquals(
            PracticeCommand.Ignored,
            PracticeKeyboard.translate(Keystroke(letterIndex = 0, hasCommandModifier = true), context),
        )
        assertEquals(
            PracticeCommand.Ignored,
            PracticeKeyboard.translate(Keystroke(digitIndex = 0, hasCommandModifier = true), context),
        )
    }

    @Test
    fun anOrderingQuestionHasNoNumberedOptionsToPick() {
        val context = choice(type = QuestionType.ORDERING, optionCount = 4)
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(letter(0), context))
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(digit(0), context))
    }

    @Test
    fun trueFalseTakesEitherOfItsTwoKeys() {
        val context = choice(type = QuestionType.TRUE_FALSE, optionCount = 2)
        assertEquals(PracticeCommand.PickOption(0), PracticeKeyboard.translate(letter(0), context))
        assertEquals(PracticeCommand.PickOption(1), PracticeKeyboard.translate(letter(1), context))
        assertEquals(PracticeCommand.Ignored, PracticeKeyboard.translate(letter(2), context))
    }
}
