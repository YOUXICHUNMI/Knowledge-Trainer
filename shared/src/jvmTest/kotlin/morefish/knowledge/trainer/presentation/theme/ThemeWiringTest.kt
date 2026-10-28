package morefish.knowledge.trainer.presentation.theme

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.ThemeMode
import morefish.knowledge.trainer.presentation.AppContent
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.AppDependencies
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 主题的**接线**测试：真的把整棵树渲染出来，真的换一次主题，再真的读屏幕像素。
 *
 * 为什么非要读像素：[Palette] 是一个全局，只有 `KtTheme` 在组合开始时把它切过去，
 * 一千多个使用点都是"组合时读一次"。这一层关系——"用户点了按钮 → KtTheme 真的被重新执行
 * → 屏幕真的换了颜色"——编译看不出来，普通单元测试也看不出来（控制器状态是对的）。
 *
 * 它抓的是这几类接线错误：读的是别的状态导致 mode 改了但没重组、KtTheme 没拿到 dark、
 * 某个页面自己写死了颜色因而纹丝不动。实测（把 `key(dark)` 拿掉再跑）这条用例仍然通过，
 * 说明 Compose 的失效传播比预期更彻底 —— 那么 `key` 就是保险而不是必需，注释里已如实写明。
 *
 * 采样点选在右边距上：它落在 980dp 内容列之外，只会是画布或顶栏的纸面，不会撞到文字。
 */
@OptIn(InternalComposeUiApi::class)
class ThemeWiringTest {

    @Test
    fun switchingTheModeRepaintsTheWholeScreen() {
        val deps = AppDependencies(InMemoryTextStorage())
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        controller.theme.set(ThemeMode.DARK)

        val scene = ImageComposeScene(width = 1200, height = 900, density = Density(1f)) {
            AppContent(controller)
        }
        try {
            val darkSamples = scene.samplePoints()
            // 全局是在组合时切过去的，所以要等这一帧渲染完才看得到
            assertTrue(Palette.current === DarkScheme, "深色模式下 KtTheme 应当把全局切到深色方案")
            for ((name, color) in darkSamples) {
                assertTrue(
                    color.luminance() < 0.06f,
                    "深色模式下 $name 仍是亮的（相对亮度 ${color.luminance()}），说明有子树没跟着重建",
                )
            }

            // 同一个场景里换回浅色：整棵树必须重建，而不是只换掉顶栏
            controller.theme.set(ThemeMode.LIGHT)
            val lightSamples = scene.samplePoints()
            assertTrue(Palette.current === LightScheme, "换回浅色后全局应当跟着换回来")
            for ((name, color) in lightSamples) {
                assertTrue(
                    color.luminance() > 0.5f,
                    "浅色模式下 $name 仍是暗的（相对亮度 ${color.luminance()}），说明有子树留着深色",
                )
            }
        } finally {
            scene.close()
            // 全局状态不能留给后面的用例：它们是按浅色写的断言
            Palette.use(LightScheme)
        }
    }

    /**
     * 帧时间必须一路往前走：离屏场景不会替你接管时钟，
     * 倒着给时间（换主题后又从 0 开始）会让重组永远等不到下一个时钟帧。
     */
    private var frameNanos = 0L

    /** 顶栏纸面、页面中段、页面底部：三处都是"整页背景"，与内容无关。 */
    private fun ImageComposeScene.samplePoints(): List<Pair<String, Color>> {
        repeat(3) {
            render(frameNanos)
            frameNanos += 33_000_000
        }
        val pixels = render(frameNanos).toComposeImageBitmap().toPixelMap()
        frameNanos += 33_000_000
        return listOf(
            "顶栏右侧" to pixels[1195, 40],
            "页面中段右侧" to pixels[1195, 600],
            "页面底部右侧" to pixels[1195, 890],
        )
    }
}
