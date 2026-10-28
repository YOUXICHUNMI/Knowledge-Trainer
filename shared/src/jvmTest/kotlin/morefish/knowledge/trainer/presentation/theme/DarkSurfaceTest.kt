package morefish.knowledge.trainer.presentation.theme

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.ThemeMode
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.presentation.AppContent
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.AppDependencies
import morefish.knowledge.trainer.presentation.SampleBank
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 深色下"某块还是浅色的"这条，用像素兜住。
 *
 * [PaletteContrastTest] 核对的是令牌本身，`AccentInkUsageTest` 核对的是几种用错方式，
 * 两者都管不到"这一页根本没用令牌"。而那一类错误的表现是**一片浅色**，
 * 正好是像素能看出来的东西 —— 判据写在 [assertNoLightBlocksOnDark]。
 *
 * 与 `ScreenshotTest` 里那条的区别：这个**不需要 `KT_SHOTS=1`**，
 * 每次 `:shared:jvmTest` 都会跑，所以 CI 上也有。代价是只挑三页（1–2 秒），
 * 全量 20 页仍靠截图那一轮。
 */
@OptIn(InternalComposeUiApi::class)
class DarkSurfaceTest {

    @Test
    fun darkPagesHaveNoLightPanel() {
        val deps = AppDependencies(InMemoryTextStorage())
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        val bank = runBlocking {
            val outcome = ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "dark-surface")
            (outcome as? ImportOutcome.Imported)?.bank ?: error("示例题库导入失败：$outcome")
        }
        controller.refresh()
        controller.theme.set(ThemeMode.DARK)
        try {
            // 练习：选项卡片、判定色、提交条 —— 语义色最密的一页
            controller.startPractice(bank, questionCount = 3)
            assertDarkPageIsClean("练习页", controller)

            // 导入：输入框、预览、说明卡 —— 表单控件最多的一页
            controller.openImport(SampleBank.json)
            assertDarkPageIsClean("导入页", controller)

            // 历史：列表行 + 判定角标
            controller.openHistory()
            assertDarkPageIsClean("历史页", controller)
        } finally {
            // 全局状态不留债：别的用例按浅色写断言
            controller.theme.set(ThemeMode.LIGHT)
            Palette.use(LightScheme)
        }
    }

    /**
     * 首页不在清单里：Hero 上那颗主按钮是**刻意**的白底，是这一页的层次。
     * 它需要的是"放行"，不是"改掉" —— 截图那一轮用 `allowLightBlocks` 显式放行。
     */
    private fun assertDarkPageIsClean(name: String, controller: AppController) {
        val scene = ImageComposeScene(width = 1180, height = 900, density = Density(1f)) {
            AppContent(controller)
        }
        try {
            var nanos = 0L
            repeat(4) {
                scene.render(nanos)
                nanos += 33_000_000L
            }
            val bitmap = scene.render(nanos).toComposeImageBitmap()
            // 配色是在组合时切过去的，要等这一帧渲染完才看得到
            assertTrue(Palette.current === DarkScheme, "$name 应当处于深色方案")
            assertNoLightBlocksOnDark(name, bitmap)
        } finally {
            scene.close()
        }
    }
}
