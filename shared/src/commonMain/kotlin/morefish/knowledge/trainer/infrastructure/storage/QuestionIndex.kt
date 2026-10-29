package morefish.knowledge.trainer.infrastructure.storage

/**
 * 一个题库的「题号 → 这一行的字节位置」索引。
 *
 * 为什么需要它：题目文件一行一题（`questions-v1.ndjson`），而「取一道题」过去
 * 只能整份读回来再逐行扫 —— 十万题是 50MB、370ms，只为其中的 500 字节。
 * 索引把这件事变成「读 500 字节」。
 *
 * 索引是**派生数据**：任何时候都能从题目文件重建，所以它撒谎不会毁掉数据，
 * 但必须能被发现。三道锁：
 *
 * 1. **结构锁**：最后一条记录的结束位置必须正好等于题目文件的字节长度。
 *    文件被追加过而索引没跟上（崩在两次写之间）立刻露馅；
 * 2. **条数锁**：解析出的条数必须等于目录里写的题数 —— 中间被删掉一行也露馅；
 * 3. **内容锁**：按索引读回来的那一行，`questionId` 必须就是要找的那个。
 *    偏移算错了会在这里露馅，而不是把别人的题当成这一道。
 *
 * 三者任一不成立，就重建（从题目文件重新扫一遍），而不是硬着头皮用。
 */
internal class QuestionIndex private constructor(
    private var offsets: LongArray,
    private var lengths: IntArray,
    private val positions: HashMap<String, Int>,
) {

    /** 一条记录的落点：从第 [offset] 个字节起，往后 [length] 个字节（含换行）。 */
    class Entry(val offset: Long, val length: Int)

    /** 索引里有多少条（= 认得的题号个数）。 */
    var count: Int = positions.size
        private set

    /** 最后一条记录的结束位置 —— 正常情况下正好等于题目文件的字节长度。 */
    var endOffset: Long = 0
        private set

    fun entry(questionId: String): Entry? {
        val i = positions[questionId] ?: return null
        return Entry(offsets[i], lengths[i])
    }

    fun contains(questionId: String): Boolean = positions.containsKey(questionId)

    /**
     * 按**槽位号**取一行。槽位号就是"这是第几道读得出题号的题"（坏行不占槽），
     * 倒排索引里记的行号用的也是这套编号 —— 前提是两边同一次扫描、同一套判定。
     */
    fun at(row: Int): Entry? {
        if (row < 0 || row >= count) return null
        return Entry(offsets[row], lengths[row])
    }

    /**
     * 能不能拿这张表去回答查询：它得**正好铺到文件末尾**，且条目数不超过目录里的题数。
     *
     * 第二条用的是"不超过"而不是"等于"：文件里如果有读不懂的行，重建出来的条目天然少几条，
     * 要求相等会让这种库每次定位都重建一遍 —— 那是把一个数据问题变成了性能悬崖。
     * 反过来"多出条目"不能被接受：那说明文件里有目录还不知道的题，该重建。
     */
    fun covers(fileBytes: Long, catalogCount: Int): Boolean =
        endOffset == fileBytes && count <= catalogCount

    /** 追加一条：位置接着 [endOffset] 往下排，顺序必须与题目文件一致。 */
    fun add(questionId: String, length: Int) {
        if (length <= 0 || positions.containsKey(questionId)) return
        if (count == offsets.size) {
            offsets = offsets.copyOf(count * 2)
            lengths = lengths.copyOf(count * 2)
        }
        offsets[count] = endOffset
        lengths[count] = length
        positions[questionId] = count
        count++
        endOffset += length
    }

    /**
     * 跳过一段（这一行读不懂、没有题号）。
     *
     * 必须跳：不跳的话，后面每一行记下的偏移都会少算这一行的字节数，
     * 于是"内容锁"会在读取时露馅 —— 但那已经是把错数据摆到人眼前之后了。
     */
    fun skip(length: Int) {
        if (length > 0) endOffset += length
    }

    /** 落盘用的文本：每行「题号 \t 起始字节 \t 长度」。 */
    fun render(): String {
        val ids = arrayOfNulls<String>(count)
        for ((id, i) in positions) ids[i] = id
        val text = StringBuilder(count * 24)
        for (i in 0 until count) {
            text.append(ids[i]).append('\t').append(offsets[i]).append('\t').append(lengths[i]).append('\n')
        }
        return text.toString()
    }

    companion object {

        fun empty(): QuestionIndex = QuestionIndex(LongArray(16), IntArray(16), HashMap())

        /**
         * 从索引文本解析。读不懂就整体作废（返回 null）——
         * 半份索引比没有索引更危险：它会让结构锁"看起来"通过。
         */
        fun parse(raw: String): QuestionIndex? {
            val index = empty()
            if (raw.isBlank()) return index
            for (line in raw.lineSequence()) {
                if (line.isEmpty()) continue
                val first = line.indexOf('\t')
                val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
                if (first <= 0 || second <= first) return null
                val offset = line.substring(first + 1, second).toLongOrNull() ?: return null
                val length = line.substring(second + 1).toIntOrNull() ?: return null
                if (offset < 0 || length <= 0) return null
                // 索引里的题号也要落进表里 —— 顺序与 endOffset 由记录自带的位置决定
                index.restore(line.substring(0, first), offset, length)
            }
            return index
        }
    }

    /** 解析时按记录里写下的位置还原（不重新排）。 */
    private fun restore(questionId: String, offset: Long, length: Int) {
        if (count == offsets.size) {
            offsets = offsets.copyOf(count * 2)
            lengths = lengths.copyOf(count * 2)
        }
        offsets[count] = offset
        lengths[count] = length
        positions[questionId] = count
        count++
        endOffset = maxOf(endOffset, offset + length)
    }
}

/**
 * 逐行累加地写一份索引：每行「题号 \t 起始字节 \t 长度」，长度含行尾换行。
 *
 * 长度用**字节**而不是字符：文件是 UTF-8，一个汉字三字节；按字符算出的偏移
 * 拿去 `RandomAccessFile.seek` 会读到半个字，解码出来全是乱码。
 */
internal class QuestionIndexBuilder {

    private val text = StringBuilder()

    /** 已经排到第几个字节（= 下一个题目的起始位置）。 */
    var endOffset: Long = 0
        private set

    private var count = 0

    fun append(questionId: String, lineByteLength: Int) {
        text.append(questionId).append('\t').append(endOffset).append('\t').append(lineByteLength).append('\n')
        endOffset += lineByteLength
        count++
    }

    fun build(): String = text.toString()

    val size: Int get() = count
}

/** 一行文本在文件里占多少字节（含行尾换行）。 */
internal fun lineByteLength(line: String): Int = line.encodeToByteArray().size + 1
