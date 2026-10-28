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

    private fun file(name: String): File = File(root, name)
}
