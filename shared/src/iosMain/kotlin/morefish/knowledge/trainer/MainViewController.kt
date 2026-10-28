package morefish.knowledge.trainer

import androidx.compose.ui.window.ComposeUIViewController
import morefish.knowledge.trainer.presentation.App
import morefish.knowledge.trainer.presentation.AppDependencies

/**
 * iOS 入口。当前使用内存存储（重启丢失），
 * 文件存储与备份属于后续阶段，界面会显示相应提示。
 */
fun MainViewController() = ComposeUIViewController { App(AppDependencies.inMemory()) }
