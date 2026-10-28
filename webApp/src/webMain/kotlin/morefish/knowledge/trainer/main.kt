package morefish.knowledge.trainer

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import morefish.knowledge.trainer.presentation.App
import morefish.knowledge.trainer.presentation.AppDependencies

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Web 端暂为内存存储：刷新页面后题库与历史会丢失，界面会明确提示。
    // 浏览器端持久化（IndexedDB / localStorage）与 iOS 文件存储属于后续阶段。
    val deps = AppDependencies.inMemory()

    ComposeViewport {
        WithFontResourcesLoaded {
            App(deps)
        }
    }
}
