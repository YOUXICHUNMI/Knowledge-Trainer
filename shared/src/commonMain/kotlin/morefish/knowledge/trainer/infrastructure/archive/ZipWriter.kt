package morefish.knowledge.trainer.infrastructure.archive

/**
 * 一个待写入的条目。路径用 `/` 分隔，必须是相对路径。
 */
class ZipWriteEntry(val path: String, val bytes: ByteArray)

/**
 * 最小 ZIP 写入器：**只写 STORED（不压缩）**条目。
 *
 * 三件事是刻意的：
 *
 * 1. **不压缩**。压缩需要一个 deflate 实现，而 commonMain 里只有解压器（[Inflate]）。
 *    为了备份引入一套压缩器，换来的是"体积小一点"和"多一处可能出错的代码"。
 *    代价如实写在界面上：备份文件大小 ≈ 数据本身大小，用户想更小可以自己压。
 *    格式本身留了余地 —— 每条目独立记录压缩方法，[ZipReader] 两种都能读，
 *    以后加压缩不会让旧备份失效。
 * 2. **时间戳固定**（1980-01-01 00:00）。同样的数据打两次，字节完全相同，
 *    便于"两台机器各打一份，比一下摘要就知道是不是同一份"。真正的备份时间写在清单里。
 * 3. **文件名标记 UTF-8**（flag 0x0800）。路径里有中文时，不标这个位的解压工具会按 GBK 猜。
 *
 * 不支持 ZIP64 与分卷：[ZipReader] 也不支持，且备份体量有上限（见 MAX_TOTAL_BYTES）。
 */
object ZipWriter {

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_EOCD = 0x06054b50L
    private const val FLAG_UTF8 = 0x0800
    private const val METHOD_STORED = 0
    private const val VERSION = 20 // 2.0：够用且最保守
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x0021 // 1980-01-01

    /** 条目数上限。与 [ZipReader.MAX_ENTRIES] 对齐，保证自己写的包自己一定读得回来。 */
    const val MAX_ENTRIES = 20000

    /** 总字节上限。与 [ZipReader.MAX_TOTAL_BYTES] 对齐。 */
    const val MAX_TOTAL_BYTES: Long = 500L * 1024 * 1024

    /**
     * 写出完整 ZIP 字节。
     *
     * @throws ZipFormatException 条目路径不合法、条目太多或总量超限。
     */
    fun write(entries: List<ZipWriteEntry>): ByteArray {
        if (entries.isEmpty()) throw ZipFormatException("没有任何条目可写")
        if (entries.size > MAX_ENTRIES) {
            throw ZipFormatException("条目太多（${entries.size} > $MAX_ENTRIES）")
        }

        val names = ArrayList<ByteArray>(entries.size)
        var total = 0L
        var localSize = 0L
        var centralSize = 0L
        val seen = HashSet<String>(entries.size * 2)
        for (entry in entries) {
            checkPath(entry.path)
            if (!seen.add(entry.path)) throw ZipFormatException("条目重复：${entry.path}")
            val name = encodeName(entry.path)
            names += name
            total += entry.bytes.size
            localSize += 30L + name.size + entry.bytes.size
            centralSize += 46L + name.size
        }
        if (total > MAX_TOTAL_BYTES) {
            throw ZipFormatException("总量超限（$total > $MAX_TOTAL_BYTES 字节）")
        }
        val buffer = ByteArray((localSize + centralSize + 22L).toInt())
        var pos = 0
        val offsets = IntArray(entries.size)

        entries.forEachIndexed { i, entry ->
            val name = names[i]
            val bytes = entry.bytes
            val crc = Crc32.of(bytes)
            offsets[i] = pos

            putInt(buffer, pos, SIG_LOCAL)
            putShort(buffer, pos + 4, VERSION)
            putShort(buffer, pos + 6, FLAG_UTF8)
            putShort(buffer, pos + 8, METHOD_STORED)
            putShort(buffer, pos + 10, DOS_TIME)
            putShort(buffer, pos + 12, DOS_DATE)
            putInt(buffer, pos + 14, crc)
            putInt(buffer, pos + 18, bytes.size.toLong())
            putInt(buffer, pos + 22, bytes.size.toLong())
            putShort(buffer, pos + 26, name.size)
            putShort(buffer, pos + 28, 0)
            name.copyInto(buffer, pos + 30)
            pos += 30 + name.size
            bytes.copyInto(buffer, pos)
            pos += bytes.size
        }

        val centralStart = pos
        entries.forEachIndexed { i, entry ->
            val name = names[i]
            val bytes = entry.bytes
            putInt(buffer, pos, SIG_CENTRAL)
            putShort(buffer, pos + 4, VERSION) // version made by
            putShort(buffer, pos + 6, VERSION)
            putShort(buffer, pos + 8, FLAG_UTF8)
            putShort(buffer, pos + 10, METHOD_STORED)
            putShort(buffer, pos + 12, DOS_TIME)
            putShort(buffer, pos + 14, DOS_DATE)
            putInt(buffer, pos + 16, Crc32.of(bytes))
            putInt(buffer, pos + 20, bytes.size.toLong())
            putInt(buffer, pos + 24, bytes.size.toLong())
            putShort(buffer, pos + 28, name.size)
            putShort(buffer, pos + 30, 0) // extra
            putShort(buffer, pos + 32, 0) // comment
            putShort(buffer, pos + 34, 0) // disk
            putShort(buffer, pos + 36, 0) // internal attr
            putInt(buffer, pos + 38, 0) // external attr
            putInt(buffer, pos + 42, offsets[i].toLong())
            name.copyInto(buffer, pos + 46)
            pos += 46 + name.size
        }

        putInt(buffer, pos, SIG_EOCD)
        putShort(buffer, pos + 4, 0)
        putShort(buffer, pos + 6, 0)
        putShort(buffer, pos + 8, entries.size)
        putShort(buffer, pos + 10, entries.size)
        putInt(buffer, pos + 12, (pos - centralStart).toLong())
        putInt(buffer, pos + 16, centralStart.toLong())
        putShort(buffer, pos + 20, 0)
        return buffer
    }

    /**
     * 路径检查与 [ZipReader] 的读取规则对齐：读得回来的才允许写出去。
     * 这些文件名全部由应用自己生成，走到这里报错说明代码有问题，不是用户的错。
     */
    private fun checkPath(path: String) {
        if (path.isEmpty()) throw ZipFormatException("条目路径为空")
        if (path.startsWith("/")) throw ZipFormatException("条目路径是绝对路径：$path")
        if (path.contains('\\')) throw ZipFormatException("条目路径含反斜杠：$path")
        if (path.contains(':')) throw ZipFormatException("条目路径含冒号：$path")
        if (path.any { it.code < 0x20 }) throw ZipFormatException("条目路径含控制字符：$path")
        if (path.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            throw ZipFormatException("条目路径含非法片段：$path")
        }
        if (path.length > 400) throw ZipFormatException("条目路径过长：$path")
    }

    /** ZIP 文件名用 UTF-8 字节，长度字段是 16 位。 */
    private fun encodeName(path: String): ByteArray {
        val bytes = path.encodeToByteArray()
        if (bytes.size > 0xFFFF) throw ZipFormatException("条目名过长：$path")
        return bytes
    }

    private fun putShort(buffer: ByteArray, pos: Int, value: Int) {
        buffer[pos] = (value and 0xFF).toByte()
        buffer[pos + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putInt(buffer: ByteArray, pos: Int, value: Long) {
        buffer[pos] = (value and 0xFF).toByte()
        buffer[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[pos + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[pos + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

/** ZIP 的 CRC-32（多项式 0xEDB88320），STORED 条目也必须写对，否则解压工具会报"文件损坏"。 */
internal object Crc32 {

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Long {
        var crc = -1
        for (b in data) {
            crc = (crc ushr 8) xor table[(crc xor b.toInt()) and 0xFF]
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
