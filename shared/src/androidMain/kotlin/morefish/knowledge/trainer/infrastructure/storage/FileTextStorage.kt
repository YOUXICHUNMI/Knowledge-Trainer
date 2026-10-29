package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextStorage
import java.io.File
import java.io.RandomAccessFile

/** 基于应用私有目录的文本存储（Android）。与桌面端实现同构，只是根目录来源不同。 */
class FileTextStorage(private val root: File) : TextStorage, RandomAccessText {

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

    /** 与桌面端同一套语义：字节长度问文件系统，不把文件读进内存再量。 */
    override fun byteLength(name: String): Long? {
        val target = File(root, name)
        return if (target.isFile) target.length() else null
    }

    /** 与桌面端同一套语义：按 UTF-8 字节区间读一段，越界返回 null。 */
    override fun readRange(name: String, offset: Long, length: Int): String? {
        val target = File(root, name)
        if (!target.isFile || offset < 0 || length <= 0) return null
        return runCatching {
            RandomAccessFile(target, "r").use { raf ->
                if (offset + length > raf.length()) return@use null
                val bytes = ByteArray(length)
                raf.seek(offset)
                raf.readFully(bytes)
                bytes.decodeToString()
            }
        }.getOrNull()
    }
}
