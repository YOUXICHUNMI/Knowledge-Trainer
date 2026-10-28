package morefish.knowledge.trainer.presentation.practice

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.AppDependencies
import morefish.knowledge.trainer.presentation.Screen
import morefish.knowledge.trainer.presentation.screens.PracticeScreen
import morefish.knowledge.trainer.presentation.theme.KtTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 键盘作答的**接线**测试：真的把练习页渲染出来，真的往里发按键。
 *
 * [PracticeKeyboardTest] 管的是"这一下算不算作答"的规则，这里管的是另一半 ——
 * 焦点有没有落在能收到按键的那一层、`onPreviewKeyEvent` 有没有真的接上控制器。
 * 这一半只靠编译是看不出来的：修饰符少一个 `focusable()`，整页看着完全正常，
 * 只是键盘悄悄地什么都不做。
 *
 * 用的是离屏场景（与 `ScreenshotTest` 同一套 Skia 栈），不需要显示器。
 */
@OptIn(InternalComposeUiApi::class)
class PracticeKeyboardGestureTest {

    @Test
    fun aLetterThenEnterAnswersAndMovesOn() {
        val controller = practiceOnChoiceBank(questionCount = 1)
        val scene = sceneFor(controller)

        // 题库里只有单选，但选项会打乱 —— 字母键指的是屏幕上第几个，不是原题的顺序
        val options = controller.practice.session!!.current!!.orderedOptions()
        assertTrue(options.size >= 2, "这场应当落在有多个选项的题型上，实际 ${controller.practice.currentQuestion?.type}")
        assertTrue(scene.press(Key.A), "字母键应当被练习页吃掉")
        assertEquals(1, controller.practice.selectedOptionIds.size, "按 A 等于选屏幕上第一个选项")
        assertEquals(null, controller.practice.feedback)

        assertTrue(scene.press(Key.Enter), "有作答时回车就是提交")
        assertNotNull(controller.practice.feedback, "回车应当真的提交了")

        // 判完之后回车 = 继续：只有一题，于是直接落到总结页
        assertTrue(scene.press(Key.Enter))
        assertEquals(Screen.SUMMARY, controller.screen)

        scene.close()
    }

    @Test
    fun aDigitKeyPicksTheSameOptionAsItsLetter() {
        val controller = practiceOnChoiceBank(questionCount = 1)
        val scene = sceneFor(controller)

        assertTrue(scene.press(Key.Two))
        val picked = controller.practice.selectedOptionIds
        assertEquals(1, picked.size)
        assertEquals(
            controller.practice.session!!.current!!.orderedOptions()[1].optionId,
            picked.single(),
            "数字 2 应当选中第二个选项",
        )

        scene.close()
    }

    @Test
    fun typingInAShortAnswerKeepsItsLetters() {
        val controller = practiceOnShortAnswerBank()
        val scene = sceneFor(controller)

        assertFalse(scene.press(Key.A), "简答题要打字，字母键不能被抢走")
        assertTrue(controller.practice.selectedOptionIds.isEmpty())
        assertEquals("", controller.practice.textAnswer, "按键不该被当成答案写进输入框以外的任何地方")

        scene.close()
    }

    // ---------------------------------------------------------------- 脚手架

    private fun sceneFor(controller: AppController): ImageComposeScene =
        ImageComposeScene(width = 1200, height = 900, density = Density(1f)) {
            KtTheme { PracticeScreen(controller) }
        }.also {
            // 首帧之后 LaunchedEffect 才跑完，焦点是在那一帧里落到页面上的
            it.render(0)
            it.render(16)
        }

    private fun ImageComposeScene.press(key: Key): Boolean =
        sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))

    /**
     * 三道单选，确定性的题库。
     *
     * 原来是从 `SampleBank` 里抽（里面混着简答题），抽到简答时"按 A 选中第一个选项"这件事
     * 根本无从谈起 —— 跑全量时偶发红过一次，报的是"这题应当有多个选项，实际 SHORT_ANSWER"。
     * 抽题是随机的，测试不能把对错押在运气上。
     */
    private fun practiceOnChoiceBank(questionCount: Int = 1): AppController {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        runBlocking {
            deps.bankRepository.save(
                QuestionBank(
                    bankId = "choice-bank",
                    title = "选择题",
                    sourceLocale = "zh",
                    questions = (1..3).map { i ->
                        Question(
                            questionId = "choice-$i",
                            type = QuestionType.SINGLE_CHOICE,
                            promptMarkdown = "第 $i 题：选甲",
                            options = listOf(
                                QuestionOption("a", "甲"),
                                QuestionOption("b", "乙"),
                                QuestionOption("c", "丙"),
                            ),
                            correctOptionIds = setOf("a"),
                        )
                    },
                    importedAtMillis = 0L,
                    sourceLabel = "test",
                ),
            )
        }
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        controller.refresh()
        controller.startPractice(controller.banks.single(), questionCount = questionCount)
        return controller
    }

    /** 只放一道简答题的题库：用来确认"打字"没有被键盘作答抢走。 */
    private fun practiceOnShortAnswerBank(): AppController {
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val question = Question(
            questionId = "short-1",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "默写一句话",
            answerText = "标准答案",
            grading = GradingSpec.StrictText,
        )
        runBlocking {
            deps.bankRepository.save(
                QuestionBank(
                    bankId = "short-bank",
                    title = "简答题",
                    sourceLocale = "zh",
                    questions = listOf(question),
                    importedAtMillis = 0L,
                    sourceLabel = "test",
                ),
            )
        }
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        controller.refresh()
        controller.startPractice(controller.banks.single(), questionCount = 1)
        return controller
    }
}
