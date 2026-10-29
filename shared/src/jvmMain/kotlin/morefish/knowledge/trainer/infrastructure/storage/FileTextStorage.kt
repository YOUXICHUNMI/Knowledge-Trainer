package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextSpan
import morefish.knowledge.trainer.application.port.TextStorage
import java.io.File
import java.io.RandomAccessFile

/**
 * 基于文件系统的文本存储（桌面端）。
 *
 * 写入先落到 .tmp 再重命名，避免进程被杀时留下半截 JSON
 * 导致下次启动读不出题库。历史文件是追加写，天然只增不改。
 */
class FileTextStorage(private val root: File) : TextStorage, RandomAccessText {

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

    /**
     * 字节长度。用 `File.length()` 而不是"读出来量字符串长度"：后者为了知道
     * 一份 50MB 文件有多长，要把它整个读进内存 —— 那正是这个方法要避免的事。
     */
    override fun byteLength(name: String): Long? {
        val target = file(name)
        return if (target.isFile) target.length() else null
    }

    /**
     * 读一段。每次开一次文件句柄：一次图谱刷新只取两三道题，
     * 为此常驻一个未关闭的句柄（还要处理并发与关闭时机）不划算。
     *
     * 要读**很多**段时用 [readSpans]，别循环调这个。
     */
    override fun readRange(name: String, offset: Long, length: Int): String? {
        val target = file(name)
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
     * 一次读多段：整批共用**一个**文件句柄。
     *
     * 搜索复核候选时一段就是一行。几百个候选逐个 [readRange] 就是几百次
     * `open/close` —— 这两个系统调用比读 500 字节本身贵得多。
     *
     * 挨得近的几段会被并成一次读，判据是下一段的起点不超过当前末尾 [SPAN_MERGE_GAP] 字节。
     * 阈值刻意小：并进来的字节也要解码，"省一次 seek"的收益抵不过多读几十 KB。
     * 切出来的每一段都从整行的行首开始（调用方给的偏移来自行边界），
     * 所以各段单独解码不会切在半个汉字上。
     */
    override fun readSpans(name: String, spans: List<TextSpan>): List<String?> {
        if (spans.isEmpty()) return emptyList()
        val target = file(name)
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
        /** 两段之间隔不超过这么多字节就并成一次读 —— 只为了省一次 seek，不是预读。 */
        const val SPAN_MERGE_GAP = 4 * 1024
    }
}
