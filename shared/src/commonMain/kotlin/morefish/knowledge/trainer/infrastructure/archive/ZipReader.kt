package morefish.knowledge.trainer.infrastructure.archive

/**
 * 最小 ZIP 读取器：只解析中央目录 + 逐条解压，用于读 .qbank。
 *
 * 安全策略与 tools/qbank 的 Python 实现保持一致（两处都要能拒绝同一批坏包）：
 * - 路径穿越、绝对路径、盘符、反斜杠、控制字符、冒号 → 整体拒绝
 * - 符号链接 / 非普通文件 → 拒绝
 * - 加密条目 → 拒绝
 * - 条目数、单条目大小、总解压量、压缩比 → 超限拒绝
 *
 * 这里不追求支持 ZIP64 与分卷（题库包体量远达不到），遇到则明确报错而不是静默截断。
 */
class ZipFormatException(message: String) : Exception(message)

class ZipEntry(
    val name: String,
    val compressionMethod: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val offset: Long,
    val encrypted: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

object ZipReader {

    private const val SIG_EOCD = 0x06054b50
    private const val SIG_CEN = 0x02014b50
    private const val SIG_LOC = 0x04034b50

    const val MAX_ENTRIES = 20000
    const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    const val MAX_RATIO = 200L

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    fun entries(bytes: ByteArray): List<ZipEntry> {
        if (!isZip(bytes)) throw ZipFormatException("不是 ZIP 文件（是否只改了扩展名？）")
        val eocd = findEocd(bytes) ?: throw ZipFormatException("找不到 ZIP 中央目录结尾记录，文件可能被截断")

        val entryCount = u16(bytes, eocd + 10)
        val cenSize = u32(bytes, eocd + 12)
        val cenOffset = u32(bytes, eocd + 16)

        if (entryCount == 0xFFFF || cenSize == 0xFFFFFFFFL || cenOffset == 0xFFFFFFFFL) {
            throw ZipFormatException("该包使用了 ZIP64 或分卷格式，题库包不应出现；请用规范工具重新打包")
        }
        if (entryCount > MAX_ENTRIES) {
            throw ZipFormatException("条目数 $entryCount 超过上限 $MAX_ENTRIES")
        }
        if (cenOffset + cenSize > bytes.size) {
            throw ZipFormatException("中央目录越界，文件可能已损坏")
        }

        val out = ArrayList<ZipEntry>(entryCount)
        var p = cenOffset.toInt()
        var seenNames = HashSet<String>()
        var index = 0
        while (index < entryCount) {
            if (p + 46 > bytes.size || u32(bytes, p) != SIG_CEN.toLong()) {
                throw ZipFormatException("中央目录第 ${index + 1} 条记录损坏")
            }
            val flags = u16(bytes, p + 8)
            val method = u16(bytes, p + 10)
            val compSize = u32(bytes, p + 20)
            val rawSize = u32(bytes, p + 24)
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val extAttr = u32(bytes, p + 38)
            val localOffset = u32(bytes, p + 42)

            if (p + 46 + nameLen > bytes.size) throw ZipFormatException("中央目录文件名越界")
            val name = decodeName(bytes, p + 46, nameLen, utf8Flag = (flags and 0x800) != 0)

            if ((flags and 0x1) != 0) throw ZipFormatException("条目 $name 被加密，题库包不允许加密")
            checkEntryName(name)

            // 高 16 位是 Unix 模式；0xA000 = 符号链接
            val unixMode = ((extAttr shr 16) and 0xFFFF).toInt()
            if (unixMode and 0xF000 == 0xA000) throw ZipFormatException("条目 $name 是符号链接，拒绝读取")
            if (unixMode and 0xF000 != 0 && unixMode and 0xF000 != 0x8000 && unixMode and 0xF000 != 0x4000) {
                throw ZipFormatException("条目 $name 不是普通文件或目录")
            }

            if (compSize > 0 && rawSize > compSize * MAX_RATIO && rawSize > 1024 * 1024) {
                throw ZipFormatException("条目 $name 压缩比过高，疑似 ZIP 炸弹")
            }
            if (rawSize > MAX_ENTRY_BYTES) throw ZipFormatException("条目 $name 超过单文件上限 50 MiB")
            if (!seenNames.add(name)) throw ZipFormatException("ZIP 中出现重复条目 $name")

            out += ZipEntry(
                name = name,
                compressionMethod = method,
                compressedSize = compSize,
                uncompressedSize = rawSize,
                offset = localOffset,
                encrypted = false,
            )
            p += 46 + nameLen + extraLen + commentLen
            index++
        }

        val total = out.sumOf { it.uncompressedSize }
        if (total > MAX_TOTAL_BYTES) throw ZipFormatException("解压总量超过 500 MiB")
        return out
    }

    /** 读取单个条目的原始解压字节。directory 条目返回空数组。 */
    fun read(bytes: ByteArray, entry: ZipEntry, codec: DeflateCodec): ByteArray {
        if (entry.isDirectory) return ByteArray(0)
        val lo = entry.offset.toInt()
        if (lo + 30 > bytes.size || u32(bytes, lo) != SIG_LOC.toLong()) {
            throw ZipFormatException("条目 ${entry.name} 的本地文件头损坏")
        }
        val nameLen = u16(bytes, lo + 26)
        val extraLen = u16(bytes, lo + 28)
        val dataStart = lo + 30 + nameLen + extraLen
        val dataEnd = dataStart + entry.compressedSize.toInt()
        if (dataEnd > bytes.size) throw ZipFormatException("条目 ${entry.name} 数据越界")

        return when (entry.compressionMethod) {
            0 -> bytes.copyOfRange(dataStart, dataEnd)
            8 -> {
                val limit = entry.uncompressedSize.toInt()
                codec.inflateRaw(bytes, dataStart, entry.compressedSize.toInt(), limit)
                    ?: throw ZipFormatException("该运行环境不支持解压 .qbank（缺少 inflate 实现）")
            }
            else -> throw ZipFormatException("条目 ${entry.name} 使用了不支持的压缩方法 ${entry.compressionMethod}")
        }
    }

    // ---- 内部工具 ----

    private fun findEocd(bytes: ByteArray): Int? {
        // EOCD 至少 22 字节，注释最长 65535
        val minPos = maxOf(0, bytes.size - 22 - 65535)
        var i = bytes.size - 22
        while (i >= minPos) {
            if (u32(bytes, i) == SIG_EOCD.toLong()) return i
            i--
        }
        return null
    }

    private fun checkEntryName(name: String) {
        if (name.isEmpty()) throw ZipFormatException("条目名为空")
        if (name.startsWith("/")) throw ZipFormatException("条目名为绝对路径：$name")
        if (name.length >= 2 && name[1] == ':') throw ZipFormatException("条目名带盘符：$name")
        if (name.contains('\\')) throw ZipFormatException("条目名含反斜杠：$name")
        if (name.split('/').any { it == ".." }) throw ZipFormatException("条目名含上级目录引用：$name")
        if (name.any { it.code < 0x20 || it == ':' }) throw ZipFormatException("条目名含非法字符：$name")
    }

    private fun decodeName(bytes: ByteArray, offset: Int, length: Int, utf8Flag: Boolean): String {
        // 未置 UTF-8 标志时规范要求按 CP437 解释；题库包的条目名一律 ASCII，
        // 只有作者手工添加的中文文件名会走到这里，此时按 UTF-8 解码更接近真实意图。
        return decodeUtf8(bytes, offset, length)
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        var i = offset
        val end = offset + length
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> {
                    sb.append(b0.toChar())
                    i += 1
                }
                b0 and 0xE0 == 0xC0 && i + 1 < end -> {
                    sb.append((((b0 and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar())
                    i += 2
                }
                b0 and 0xF0 == 0xE0 && i + 2 < end -> {
                    sb.append(
                        (((b0 and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                            (bytes[i + 2].toInt() and 0x3F)).toChar(),
                    )
                    i += 3
                }
                b0 and 0xF8 == 0xF0 && i + 3 < end -> {
                    val cp = ((b0 and 0x07) shl 18) or ((bytes[i + 1].toInt() and 0x3F) shl 12) or
                        ((bytes[i + 2].toInt() and 0x3F) shl 6) or (bytes[i + 3].toInt() and 0x3F)
                    if (cp in 0x10000..0x10FFFF) {
                        // 手写代理对：StringBuilder.appendCodePoint 是 JVM 专属 API，JS/Wasm 上不存在
                        val v = cp - 0x10000
                        sb.append((0xD800 + (v shr 10)).toChar())
                        sb.append((0xDC00 + (v and 0x3FF)).toChar())
                    } else {
                        sb.append('\uFFFD')
                    }
                    i += 4
                }
                else -> {
                    sb.append('\uFFFD')
                    i += 1
                }
            }
        }
        return sb.toString()
    }

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toInt() and 0xFF).toLong()) or
            ((b[i + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[i + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[i + 3].toInt() and 0xFF).toLong() shl 24)
}
