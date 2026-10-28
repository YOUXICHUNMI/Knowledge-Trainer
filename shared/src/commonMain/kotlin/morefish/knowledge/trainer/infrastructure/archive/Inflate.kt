package morefish.knowledge.trainer.infrastructure.archive

/**
 * 纯 Kotlin 的 raw DEFLATE 解压（RFC 1951）。
 *
 * 为什么自己写而不用平台实现：
 * - JVM/Android 有 `java.util.zip.Inflater`，但它是平台专属；
 * - JS 只有异步的 `DecompressionStream`，而 ZIP 结构解析是同步的；
 * - iOS 要走 Compression framework，又是另外一套。
 *
 * 一份实现覆盖四个平台，行为一致、可单测、无异步、无额外依赖。
 * 性能大约是原生 zlib 的三分之一，对几十 MB 以内的题库完全够用。
 *
 * **安全语义**：任何越界、超长、非法码表一律返回 `null`，不做"尽力解一点"。
 * 解压器是 ZIP 炸弹的最后一道闸门，因此输出长度严格按预期大小封顶。
 */
internal object Inflate {

    private const val MAX_BITS = 15
    private const val MAX_OUTPUT = 64 * 1024 * 1024

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193,
        12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
    private val CODE_LENGTH_ORDER =
        intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /**
     * @param expectedSize 期望输出字节数（来自 ZIP 中央目录）。输出一旦超过它立即失败。
     * @return 恰好 expectedSize 字节的结果；任何异常情况返回 null。
     */
    fun raw(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray? {
        if (expectedSize < 0 || expectedSize > MAX_OUTPUT) return null
        if (offset < 0 || length < 0 || offset + length > data.size) return null

        val out = Output(ByteArray(expectedSize))
        val br = BitReader(data, offset, offset + length)

        var finalBlock = false
        while (!finalBlock) {
            val isFinal = br.bits(1)
            if (isFinal < 0) return null
            finalBlock = isFinal == 1
            when (val type = br.bits(2)) {
                -1 -> return null

                0 -> {
                    // 非压缩块：对齐到字节边界，读 LEN / NLEN 并互校验
                    br.alignToByte()
                    val len = br.bits(16)
                    val nlen = br.bits(16)
                    if (len < 0 || nlen < 0) return null
                    if ((len xor 0xFFFF) != nlen) return null
                    if (!out.copyFrom(br, len)) return null
                }

                1 -> if (!inflateBlock(br, FIXED_LIT, FIXED_DIST, out)) return null

                2 -> {
                    val tables = readDynamicTables(br) ?: return null
                    if (!inflateBlock(br, tables.first, tables.second, out)) return null
                }

                else -> return null // 类型 3 保留，非法
            }
        }
        return if (out.pos == expectedSize) out.buf else null
    }

    // ------------------------------------------------------------ 输出缓冲

    /** 固定容量的输出缓冲：越界即失败，绝不扩容。 */
    private class Output(val buf: ByteArray) {
        var pos = 0

        fun put(b: Byte): Boolean {
            if (pos >= buf.size) return false
            buf[pos] = b
            pos += 1
            return true
        }

        fun copyFrom(br: BitReader, count: Int): Boolean {
            if (count < 0 || pos + count > buf.size) return false
            if (!br.copyRaw(buf, pos, count)) return false
            pos += count
            return true
        }
    }

    // ------------------------------------------------------------ 块解码

    private fun inflateBlock(br: BitReader, lit: Huffman, dist: Huffman, out: Output): Boolean {
        while (true) {
            val sym = lit.decode(br)
            if (sym < 0) return false
            if (sym < 256) {
                if (!out.put(sym.toByte())) return false
                continue
            }
            if (sym == 256) return true

            val li = sym - 257
            if (li < 0 || li >= LENGTH_BASE.size) return false
            val extra = br.bits(LENGTH_EXTRA[li])
            if (extra < 0) return false
            val len = LENGTH_BASE[li] + extra

            val dsym = dist.decode(br)
            if (dsym < 0 || dsym >= DIST_BASE.size) return false
            val dextra = br.bits(DIST_EXTRA[dsym])
            if (dextra < 0) return false
            val d = DIST_BASE[dsym] + dextra
            if (d > out.pos) return false
            if (out.pos + len > out.buf.size) return false

            var from = out.pos - d
            repeat(len) {
                // 距离可能小于长度，必须逐字节按顺序搬 —— 这正是 LZ77 的语义
                out.buf[out.pos] = out.buf[from]
                out.pos += 1
                from += 1
            }
        }
    }

    private fun readDynamicTables(br: BitReader): Pair<Huffman, Huffman>? {
        val hlit = br.bits(5)
        val hdist = br.bits(5)
        val hclen = br.bits(4)
        if (hlit < 0 || hdist < 0 || hclen < 0) return null
        val nlen = hlit + 257
        val ndist = hdist + 1
        val ncode = hclen + 4
        if (nlen > 286 || ndist > 30) return null

        val codeLengths = IntArray(19)
        for (i in 0 until ncode) {
            val v = br.bits(3)
            if (v < 0) return null
            codeLengths[CODE_LENGTH_ORDER[i]] = v
        }
        val codeHuffman = Huffman.build(codeLengths, 19) ?: return null

        val lengths = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val sym = codeHuffman.decode(br)
            if (sym < 0) return null
            when {
                sym < 16 -> {
                    lengths[i] = sym
                    i += 1
                }

                sym == 16 -> {
                    if (i == 0) return null
                    val rep = br.bits(2).checked(3) ?: return null
                    if (i + rep > lengths.size) return null
                    val prev = lengths[i - 1]
                    repeat(rep) { lengths[i++] = prev }
                }

                sym == 17 -> {
                    val rep = br.bits(3).checked(3) ?: return null
                    if (i + rep > lengths.size) return null
                    repeat(rep) { lengths[i++] = 0 }
                }

                else -> {
                    val rep = br.bits(7).checked(11) ?: return null
                    if (i + rep > lengths.size) return null
                    repeat(rep) { lengths[i++] = 0 }
                }
            }
        }
        if (lengths[256] == 0) return null // 缺少块结束符

        val lit = Huffman.build(lengths.copyOfRange(0, nlen), nlen) ?: return null
        val dist = Huffman.build(lengths.copyOfRange(nlen, nlen + ndist), ndist) ?: return null
        return lit to dist
    }

    /** bits() 返回 -1 表示越界；这里把它变成"失败"，同时加上基值。 */
    private fun Int.checked(base: Int): Int? = if (this < 0) null else base + this

    // ------------------------------------------------------------ 位读取

    private class BitReader(private val data: ByteArray, private val start: Int, private val end: Int) {
        private var pos = start
        private var bitBuf = 0
        private var bitCnt = 0

        fun bits(n: Int): Int {
            if (n == 0) return 0
            while (bitCnt < n) {
                if (pos >= end) return -1
                bitBuf = bitBuf or ((data[pos].toInt() and 0xFF) shl bitCnt)
                pos += 1
                bitCnt += 8
            }
            val value = bitBuf and ((1 shl n) - 1)
            bitBuf = bitBuf ushr n
            bitCnt -= n
            return value
        }

        /**
         * 对齐到字节边界。
         *
         * 关键点：缓冲区里可能还存着整个未消费的字节（bits() 一次读 8 位，可能读多了）。
         * 对齐时必须把这些整字节"退回"给输入流，否则后续 [copyRaw] 会从错误的偏移开始读 ——
         * 非压缩块（stored）正是靠这个顺序才能正确取出原始字节。
         */
        fun alignToByte() {
            val pad = bitCnt and 7          // 丢弃到字节边界的零头
            val whole = (bitCnt - pad) shr 3 // 剩余整字节数，全部退回
            pos -= whole
            bitBuf = 0
            bitCnt = 0
        }

        fun copyRaw(dst: ByteArray, at: Int, count: Int): Boolean {
            if (pos + count > end) return false
            data.copyInto(dst, at, pos, pos + count)
            pos += count
            return true
        }
    }

    // ------------------------------------------------------------ Huffman

    private class Huffman(private val counts: IntArray, private val symbols: IntArray) {

        /** 解一个符号；数据非法或越界返回 -1。 */
        fun decode(br: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..MAX_BITS) {
                val bit = br.bits(1)
                if (bit < 0) return -1
                code = code or bit
                val count = counts[len]
                if (code - count < first) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            return -1
        }

        companion object {
            /** 由码长表构建 canonical Huffman；超订或全空返回 null。 */
            fun build(lengths: IntArray, n: Int): Huffman? {
                val counts = IntArray(MAX_BITS + 1)
                var total = 0
                for (i in 0 until n) {
                    val l = lengths[i]
                    if (l < 0 || l > MAX_BITS) return null
                    if (l != 0) {
                        counts[l] += 1
                        total += 1
                    }
                }
                if (total == 0) return null

                // 超订检查：left 是这一层还剩多少码位
                var left = 1
                for (len in 1..MAX_BITS) {
                    left = left shl 1
                    left -= counts[len]
                    if (left < 0) return null
                }

                val offsets = IntArray(MAX_BITS + 2)
                for (len in 1..MAX_BITS) {
                    offsets[len + 1] = offsets[len] + counts[len]
                }
                val symbols = IntArray(total)
                for (i in 0 until n) {
                    val l = lengths[i]
                    if (l != 0) {
                        symbols[offsets[l]] = i
                        offsets[l] += 1
                    }
                }
                return Huffman(counts, symbols)
            }
        }
    }

    // ------------------------------------------------------------ 固定码表

    private val FIXED_LIT: Huffman = run {
        val lengths = IntArray(288)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        requireNotNull(Huffman.build(lengths, 288))
    }

    private val FIXED_DIST: Huffman = run {
        val lengths = IntArray(32) { 5 }
        requireNotNull(Huffman.build(lengths, 32))
    }
}
