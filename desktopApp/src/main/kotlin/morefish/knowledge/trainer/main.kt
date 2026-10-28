@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package morefish.knowledge.trainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.infrastructure.ai.JvmAiHttpClient
import morefish.knowledge.trainer.infrastructure.picking.DesktopFilePicker
import morefish.knowledge.trainer.infrastructure.storage.DirectoryVault
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.presentation.App
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.AppDependencies
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() {
    val deps = createDependencies()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Knowledge Trainer",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            val window = LocalAwtWindow.current
            var controller by remember { mutableStateOf<AppController?>(null) }

            // 把 .qbank 拖进窗口任意位置即可导入：AWT DropTarget 比 Compose 内部
            // 拖放 API 更直接，也不会随着窗口装饰变化失效。
            DisposableEffect(window, controller) {
                val adapter = object : DropTargetAdapter() {
                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val dropped = runCatching {
                            (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                                ?.filterIsInstance<File>()
                                ?.firstOrNull()
                        }.getOrNull()
                        event.dropComplete(dropped != null)
                        dropped?.let { file -> controller?.importDroppedFile(file.name, file.readBytes()) }
                    }
                }
                val target = DropTarget(window, adapter)
                onDispose { target.removeDropTargetListener(adapter); target.setActive(false) }
            }

            App(deps, onControllerReady = { controller = it })
        }
    }
}

/**
 * 桌面端组合根：数据写在 ~/.knowledge-trainer。
 * 目录不可写时退化为内存存储，并在界面上明确提示"重启会丢失"，
 * 而不是假装保存成功。
 */
private fun createDependencies(): AppDependencies {
    val root = File(System.getProperty("user.home"), ".knowledge-trainer")
    return runCatching {
        val vault = DirectoryVault(root)
        // 上一次恢复如果被打断（断电 / 被杀 / 盘满），必须在**读任何数据之前**收拾现场：
        // 否则仓储会先读到空的目录，用户看见的是"东西全没了"，然后才变回来。
        // 这个方法自己不抛异常，最坏也是返回一句"什么都没敢动"。
        val recovery = runBlocking { vault.recoverInterrupted() }
        AppDependencies(
            FileTextStorage(root),
            filePicker = DesktopFilePicker(),
            mediaStore = FileMediaStore(root),
            // 只有桌面端接了联网 AI；其他平台传 null，界面上如实显示"本平台不支持"
            aiHttpClient = JvmAiHttpClient(),
            // 备份/恢复同样只在数据真的落盘的平台上提供
            vault = vault,
            // 启动自愈的结果交给界面说一次，用户点过「知道了」就不再提
            restoreRecovery = recovery,
        )
    }
        .getOrElse { AppDependencies(InMemoryTextStorage(), filePicker = DesktopFilePicker()) }
}
