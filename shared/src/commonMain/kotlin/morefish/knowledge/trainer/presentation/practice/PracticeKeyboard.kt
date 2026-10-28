package morefish.knowledge.trainer.presentation.practice

import morefish.knowledge.trainer.domain.model.QuestionType

/**
 * 一次按键里练习页关心的那部分信息。
 *
 * 界面负责把 Compose 的按键事件拆成这个数据类，判断全在 [PracticeKeyboard.translate] ——
 * 那里不碰 Compose，所以规则能被单测钉住。"什么题型吃数字键""回车是提交还是下一题"
 * 这类事情写在界面里，只能靠人手点一遍才知道有没有改坏。
 */
data class Keystroke(
    /** 字母键的序号（A = 0）；不是字母键就是 null。 */
    val letterIndex: Int? = null,
    /** 数字键 1–9 的序号（数字 1 = 0）；不是数字键就是 null。 */
    val digitIndex: Int? = null,
    /** 回车 / 小键盘回车：一个"确认"动作。 */
    val isConfirm: Boolean = false,
    /** 按着 Ctrl / Alt / Cmd —— 那是系统或浏览器的快捷键，不是作答。 */
    val hasCommandModifier: Boolean = false,
)

/** 练习页当前的状态里，决定"这一下按键算什么的"那几项。 */
data class PracticeKeyContext(
    val type: QuestionType,
    val optionCount: Int,
    /** 这题已经提交、正在看反馈。 */
    val revealed: Boolean,
    /** 现在按提交有没有东西可交（与提交按钮同一条判定）。 */
    val canSubmit: Boolean,
)

sealed interface PracticeCommand {
    /** 选中第 index 个选项（0 起，按屏幕上的显示顺序）。 */
    data class PickOption(val index: Int) : PracticeCommand

    data object Submit : PracticeCommand

    /** 翻到下一题（最后一题时是看总结）。 */
    data object Advance : PracticeCommand

    /** 不管这一下，让它照常往下传。 */
    data object Ignored : PracticeCommand
}

/**
 * 键盘作答。
 *
 * 三条取舍写在这里，不是因为它们显然，而是因为每条都有代价：
 *
 * 1. **只有选择题吃字母/数字键。** 简答题要打字，重组题是"点片段"，
 *    在它们身上抢按键只会让人打不出字、点不出东西。
 * 2. **简答题的裸回车留给换行。** 逐字符判定的题，答案本来就可能有多行；
 *    回车要是被拿去提交，那种题就再也写不完整。要提交用 Ctrl/Cmd + 回车。
 * 3. **带修饰键的一律不管。** Ctrl+C、Alt+Tab、Cmd+Q 不该在练习页里变成"选了 A"。
 */
object PracticeKeyboard {

    /** 点一下就选一个选项的题型。重组题是"按次序追加"，数字键对它没有意义。 */
    private val pickable = setOf(
        QuestionType.SINGLE_CHOICE,
        QuestionType.MULTIPLE_CHOICE,
        QuestionType.TRUE_FALSE,
    )

    fun translate(keystroke: Keystroke, context: PracticeKeyContext): PracticeCommand {
        if (keystroke.hasCommandModifier) {
            // 简答题唯一能靠键盘提交的机会：答案里有换行，裸回车必须留给输入框
            val confirmInShortAnswer = keystroke.isConfirm &&
                context.type == QuestionType.SHORT_ANSWER &&
                !context.revealed &&
                context.canSubmit
            return if (confirmInShortAnswer) PracticeCommand.Submit else PracticeCommand.Ignored
        }

        if (keystroke.isConfirm) {
            return when {
                // 已经判过：此时回车就是"继续往下走"，与界面上的主按钮一致
                context.revealed -> PracticeCommand.Advance
                context.type == QuestionType.SHORT_ANSWER -> PracticeCommand.Ignored
                context.canSubmit -> PracticeCommand.Submit
                // 什么都没选就回车：不提交，也不提示 —— 提示会和"还没作答"的说明打架
                else -> PracticeCommand.Ignored
            }
        }

        // 判完之后答案已经定了，再按数字键不该改动它
        if (context.revealed) return PracticeCommand.Ignored
        if (context.type !in pickable) return PracticeCommand.Ignored

        val index = keystroke.digitIndex ?: keystroke.letterIndex ?: return PracticeCommand.Ignored
        return if (index in 0 until context.optionCount) {
            PracticeCommand.PickOption(index)
        } else {
            // 只有 3 个选项时按 D：不理它，而不是顺手选最后一项
            PracticeCommand.Ignored
        }
    }
}
