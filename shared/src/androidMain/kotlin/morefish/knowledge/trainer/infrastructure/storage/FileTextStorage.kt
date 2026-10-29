package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextSpan
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

    /**
     * 与桌面端同一套语义：整批共用一个文件句柄，挨得近的几段并成一次读。
     * 手机上 open/close 比读几百字节贵得多 —— 这正是搜索复核候选时的主要开销。
     * 阈值与桌面端同值（4KB），改动时两边要一起改。
     */
    override fun readSpans(name: String, spans: List<TextSpan>): List<String?> {
        if (spans.isEmpty()) return emptyList()
        val target = File(root, name)
        if (!target.isFile) return List(spans.size) { null }
        val out = runCatching {
            RandomAccessFile(target, "r").use { raf ->
                val fileBytes = raf.length()
                val texts = arrayOfNulls<String>(spans.size)
                var i = 0
                while (i < spans.size) {
                    val head = spans[i]
                    if (!fitsIn(head, fileBytes)) {
                        i++
                        continue
                    }
                    val blockStart = head.offset
                    var blockEnd = head.offset + head.length
                    var last = i
                    var next = i + 1
                    while (next < spans.size) {
                        val span = spans[next]
                        if (!fitsIn(span, fileBytes) || span.offset > blockEnd + SPAN_MERGE_GAP) break
                        if (span.offset + span.length > blockEnd) blockEnd = span.offset + span.length
                        last = next
                        next++
                    }
                    val block = ByteArray((blockEnd - blockStart).toInt())
                    raf.seek(blockStart)
                    raf.readFully(block)
                    for (k in i..last) {
                        val span = spans[k]
                        if (!fitsIn(span, fileBytes)) continue
                        val from = (span.offset - blockStart).toInt()
                        texts[k] = block.decodeToString(from, from + span.length)
                    }
                    i = last + 1
                }
                texts.toList()
            }
        }.getOrNull()
        return out ?: List(spans.size) { null }
    }

    private fun fitsIn(span: TextSpan, fileBytes: Long): Boolean =
        span.offset >= 0 && span.length > 0 && span.offset + span.length <= fileBytes

    private companion object {
        /** 与桌面端同值：两段之间隔不超过 4KB 就并成一次读。 */
        const val SPAN_MERGE_GAP = 4 * 1024
    }
}
