package morefish.knowledge.trainer.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import morefish.knowledge.trainer.presentation.screens.AiScreen
import morefish.knowledge.trainer.presentation.screens.BackupScreen
import morefish.knowledge.trainer.presentation.screens.BatchScreen
import morefish.knowledge.trainer.presentation.screens.PatchScreen
import morefish.knowledge.trainer.presentation.screens.ExtensionsScreen
import morefish.knowledge.trainer.presentation.screens.GraphEditorScreen
import morefish.knowledge.trainer.presentation.screens.GraphScreen
import morefish.knowledge.trainer.presentation.screens.HistoryScreen
import morefish.knowledge.trainer.presentation.screens.HomeScreen
import morefish.knowledge.trainer.presentation.screens.ImportScreen
import morefish.knowledge.trainer.presentation.screens.NoteEditorScreen
import morefish.knowledge.trainer.presentation.screens.NotesScreen
import morefish.knowledge.trainer.presentation.screens.PracticeScreen
import morefish.knowledge.trainer.presentation.screens.PracticeSetupScreen
import morefish.knowledge.trainer.presentation.screens.QuestionEditorScreen
import morefish.knowledge.trainer.presentation.screens.ReviewScreen
import morefish.knowledge.trainer.presentation.screens.StandardScreen
import morefish.knowledge.trainer.presentation.screens.SummaryScreen
import morefish.knowledge.trainer.presentation.screens.ToolboxScreen
import morefish.knowledge.trainer.presentation.screens.WrongQuestionsScreen
import morefish.knowledge.trainer.presentation.theme.KtTheme
import morefish.knowledge.trainer.presentation.theme.isDark
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.Motion

/**
 * 应用唯一入口。所有平台（Android / Desktop / Web / iOS）共用这一棵 Compose 树，
 * 差异只在 platform 入口注入的 [AppDependencies]。
 */
@Composable
@Preview
fun App(deps: AppDependencies = AppDependencies.inMemory(), onControllerReady: ((AppController) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val controller = remember(deps) { AppController(deps, scope) }

    LaunchedEffect(controller) {
        onControllerReady?.invoke(controller)
        controller.refresh()
    }

    AppContent(controller)
}

/**
 * 由外部提供控制器的应用外壳。
 *
 * [App] 自己创建控制器；截图测试与需要预置状态的场景可以先用控制器准备好数据，
 * 再直接调这里 —— 两者渲染的是同一棵树，不会出现"预览好看、真机不一样"。
 */
@Composable
fun AppContent(controller: AppController) {
    // 用户的三种选择在这里解算成"这一帧到底是深是浅"：跟随系统要问一次系统。
    val dark = controller.theme.mode.isDark()
    // 颜色是全局令牌（Palette.current），而 Compose 并不知道谁读过它。
    // 实测（ThemeWiringTest）里不加这个 key 也会整屏重绘 —— KtTheme 换了色方案之后，
    // Material 的 CompositionLocal 把失效一路传了下去。但那是运行时内部的传播方式，
    // 不是写在契约里的东西；key 让"换主题必须整屏跟着换"由结构保证，而不是碰巧成立。
    // 代价只有一个：切换那一下子树重建，滚动位置与局部展开状态会重置。
    key(dark) {
        KtTheme(dark) {
            Box(modifier = Modifier.fillMaxSize().background(Palette.Canvas)) {
                AnimatedContent(
                    targetState = controller.screen,
                    transitionSpec = {
                        // 进入更深的页面从右侧推入，返回则从左侧，方向本身就是导航语义。
                        val forward = depthOf(targetState) >= depthOf(initialState)
                        val enter = fadeIn(tween(Motion.StandardMillis)) +
                            slideInHorizontally(tween(Motion.EmphasisMillis)) { full ->
                                if (forward) full / 12 else -full / 12
                            }
                        val exit = fadeOut(tween(Motion.QuickMillis)) +
                            slideOutHorizontally(tween(Motion.StandardMillis)) { full ->
                                if (forward) -full / 24 else full / 24
                            }
                        (enter togetherWith exit).using(SizeTransform(clip = false))
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(controller)
                        Screen.IMPORT -> ImportScreen(controller, SampleBank.json)
                        Screen.QUESTION_EDITOR -> QuestionEditorScreen(controller)
                        Screen.PRACTICE -> PracticeScreen(controller)
                        Screen.PRACTICE_SETUP -> PracticeSetupScreen(controller)
                        Screen.SUMMARY -> SummaryScreen(controller)
                        Screen.HISTORY -> HistoryScreen(controller)
                        Screen.WRONG -> WrongQuestionsScreen(controller)
                        Screen.NOTES -> NotesScreen(controller)
                        Screen.NOTE_EDITOR -> NoteEditorScreen(controller)
                        Screen.TOOLBOX -> ToolboxScreen(controller)
                        Screen.GRAPH -> GraphScreen(controller)
                        Screen.GRAPH_EDITOR -> GraphEditorScreen(controller)
                        Screen.EXTENSIONS -> ExtensionsScreen(controller)
                        Screen.AI -> AiScreen(controller)
                        Screen.AI_BATCH -> BatchScreen(controller)
                        Screen.PATCH -> PatchScreen(controller)
                        Screen.REVIEW -> ReviewScreen(controller)
                        Screen.BACKUP -> BackupScreen(controller)
                        // 说明页从导入页进入；这里传的是转场中的这一页，而不是 controller 的当前值，
                        // 否则退场动画里会突然显示成另一页的内容。
                        Screen.STANDARD, Screen.SKILL, Screen.TOOLS ->
                            StandardScreen(screen, controller::closeStandard)
                    }
                }
            }
        }
    }
}

/** 用"层级"而不是枚举序号判断前进/后退：枚举里工具页排最后，序号会误判成前进。 */
private fun depthOf(screen: Screen): Int = when (screen) {
    Screen.HOME -> 0
    Screen.IMPORT, Screen.PRACTICE_SETUP, Screen.HISTORY, Screen.WRONG, Screen.QUESTION_EDITOR, Screen.NOTES,
    Screen.GRAPH, Screen.EXTENSIONS, Screen.AI, Screen.AI_BATCH, Screen.PATCH, Screen.REVIEW, Screen.BACKUP,
    -> 1
    Screen.STANDARD, Screen.SKILL, Screen.TOOLS, Screen.PRACTICE, Screen.SUMMARY, Screen.NOTE_EDITOR,
    Screen.TOOLBOX, Screen.GRAPH_EDITOR,
    -> 2
}
