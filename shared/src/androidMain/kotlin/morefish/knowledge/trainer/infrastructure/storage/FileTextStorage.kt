package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.TextStorage
import java.io.File

/** 基于应用私有目录的文本存储（Android）。与桌面端实现同构，只是根目录来源不同。 */
class FileTextStorage(private val root: File) : TextStorage {

    override val isPersistent: Boolean = true

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun read(name: String): String? {
        val file = File(root, name)
        return if (file.isFile) file.readText() else null
    }

    override fun write(name: String, content: String) {
        val target = File(root, name)
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
        File(root, name).apply { parentFile?.mkdirs() }.appendText(line + "\n")
    }

    /** 与桌面端同一套语义：删文件，删空了就把紧邻的一层目录也收掉，不动根目录。 */
    override fun delete(name: String): Boolean {
        val target = File(root, name)
        if (!target.isFile) return false
        val removed = target.delete()
        val parent = target.parentFile
        if (removed && parent != null && parent.absolutePath != root.absolutePath && parent.isDirectory) {
            if (parent.list()?.isEmpty() == true) parent.delete()
        }
        return removed
    }
}
