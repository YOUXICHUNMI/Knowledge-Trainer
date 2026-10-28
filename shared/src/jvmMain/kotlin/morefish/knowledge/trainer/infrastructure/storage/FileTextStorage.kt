package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.TextStorage
import java.io.File

/**
 * 基于文件系统的文本存储（桌面端）。
 *
 * 写入先落到 .tmp 再重命名，避免进程被杀时留下半截 JSON
 * 导致下次启动读不出题库。历史文件是追加写，天然只增不改。
 */
class FileTextStorage(private val root: File) : TextStorage {

    override val isPersistent: Boolean = true

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun read(name: String): String? {
        val file = file(name)
        return if (file.isFile) file.readText() else null
    }

    override fun write(name: String, content: String) {
        val target = file(name)
        // 名字里可以带子目录（banks/<bankId>/questions-v1.ndjson），先建出来
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "无法写入 ${target.absolutePath}" }
        }
    }

    override fun appendLine(name: String, line: String) {
        file(name).apply { parentFile?.mkdirs() }.appendText(line + "\n")
    }

    /**
     * 删文件；如果删完它的父目录空了，把父目录也收掉。
     *
     * 题库的题目文件是 `banks/<id>/questions-v1.ndjson` —— 移除一个题库后留下一堆
     * 空目录，下次有人打开数据目录看，会以为"里面还有东西"。
     * 只收**紧邻的一层**，且绝不碰根目录：根目录里还有别的文件。
     */
    override fun delete(name: String): Boolean {
        val target = file(name)
        if (!target.isFile) return false
        val removed = target.delete()
        val parent = target.parentFile
        if (removed && parent != null && parent.absoluteFile != root.absoluteFile && parent.isDirectory) {
            if (parent.list()?.isEmpty() == true) parent.delete()
        }
        return removed
    }

    private fun file(name: String): File = File(root, name)
}
