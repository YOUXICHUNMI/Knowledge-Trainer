package morefish.knowledge.trainer.infrastructure.picking

import morefish.knowledge.trainer.application.port.FilePickException
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.MAX_BACKUP_FILE_BYTES
import morefish.knowledge.trainer.application.port.MAX_BANK_FILE_BYTES
import morefish.knowledge.trainer.application.port.PickedFile
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面端文件选择器：Swing 的 JFileChooser。
 *
 * 两个细节：
 * 1. 放到 [Dispatchers.IO] 上跑 —— 对话框是阻塞调用，放在 UI 线程会把 Compose 冻住。
 * 2. 选之前先看文件大小，超限直接拒绝；不要"读进来再说"。
 * 也允许选"所有文件"，这样用户选错时能得到清楚的错误提示，而不是看到一个空对话框。
 */
class DesktopFilePicker : FilePicker {

    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null

    override suspend fun pickPackage(): PickedFile? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择 .qbank 题库、.qbext 扩展包或 .qbpatch 更新补丁"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(
                "Knowledge Trainer 包 (*.qbank, *.qbext, *.qbpatch)",
                "qbank",
                "qbext",
                "qbpatch",
            )
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val file: File = chooser.selectedFile ?: return@withContext null
        if (!file.isFile) throw FilePickException("选择的不是一个文件：${file.name}")
        if (file.length() > MAX_BANK_FILE_BYTES) {
            throw FilePickException(
                "文件过大（${file.length() / 1024 / 1024} MB），上限 ${MAX_BANK_FILE_BYTES / 1024 / 1024} MB",
            )
        }
        PickedFile(name = file.name, bytes = file.readBytes())
    }

    override val canSave: Boolean = true

    override suspend fun pickBackup(): PickedFile? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择 .qbk 备份文件"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Knowledge Trainer 备份 (*.qbk)", "qbk")
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val file: File = chooser.selectedFile ?: return@withContext null
        if (!file.isFile) throw FilePickException("选择的不是一个文件：${file.name}")
        if (file.length() > MAX_BACKUP_FILE_BYTES) {
            throw FilePickException(
                "备份过大（${file.length() / 1024 / 1024} MB），上限 ${MAX_BACKUP_FILE_BYTES / 1024 / 1024} MB",
            )
        }
        PickedFile(name = file.name, bytes = file.readBytes())
    }

    /**
     * 保存对话框。
     *
     * 三个细节：
     * 1. 用户没写扩展名就自动补上 `.qbk`（很多人会直接改文件名）；
     * 2. 目标已存在时**再确认一次** —— 用 `showConfirmDialog` 而不是静默覆盖，
     *    这是用户自己的数据，覆盖掉就没有第二份了；
     * 3. 先写 `.tmp` 再改名：写到一半断电不会留下一个看起来正常、其实半个的备份。
     */
    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "保存备份"
            fileSelectionMode = JFileChooser.FILES_ONLY
            selectedFile = File(suggestedName)
            fileFilter = FileNameExtensionFilter("Knowledge Trainer 备份 (*.qbk)", "qbk")
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext false
        var target: File = chooser.selectedFile ?: return@withContext false
        if (!target.name.contains('.')) target = File(target.parentFile, target.name + ".qbk")
        if (target.exists()) {
            val answer = JOptionPane.showConfirmDialog(
                null,
                "「${target.name}」已经存在，要覆盖它吗？",
                "确认覆盖",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
            if (answer != JOptionPane.YES_OPTION) return@withContext false
        }
        try {
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw FilePickException("无法写入 ${target.absolutePath}")
            }
            true
        } catch (e: FilePickException) {
            throw e
        } catch (e: Exception) {
            throw FilePickException("写入失败：${e.message ?: e::class.simpleName}")
        }
    }
}
