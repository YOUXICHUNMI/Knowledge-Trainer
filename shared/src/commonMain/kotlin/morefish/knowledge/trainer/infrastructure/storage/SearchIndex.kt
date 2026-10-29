package morefish.knowledge.trainer.infrastructure.storage

/**
 * 一个题库的「词 → 哪几行」倒排索引。
 *
 * 为什么需要它：`searchQuestions` 过去是「读整份文件 + 逐行 contains」，
 * 十万题的库上 1.26s —— 而且这发生在"图谱里填一个端点"这种交互里。
 *
 * 中文没有空格分词，所以按**二元组**切：`以下说法` → `以下`、`下说`、`说法`。
 * 查询时把关键词同样切，取所有二元组的**交集**，得到候选行号；再逐行读回来
 * 用完整子串复核（二元组交集会有假阳性：`以下`…`说法` 都在，却不连在一起）。
 *
 * 索引是派生数据，能重建，所以撒谎不致命 —— 但必须能被发现。两道锁：
 *
 * 1. **结构锁**：索引头里记着生成时题目文件的**字节数**，与当前文件对不上就重建。
 *    这是最硬的一条：只要文件变了一个字节，行号就可能整体错位，
 *    而错位的行号会让我们"验证"到另一道题上去 —— 漏掉真命中比慢严重得多。
 * 2. **内容锁**：候选行读回来之后，仍要用真实文本复核（见 [matchesQuery]）。
 *    索引只负责"哪几行可能有关"，不负责"这几行就是答案"。
 *
 * 行号用的是 [QuestionIndex] 的槽位号：同一次扫描里，跳过同样的"读不懂的行"，
 * 两边编号才一致。所以这份索引只能和同源的偏移表一起用 —— 两者都由
 * [JsonBankRepository] 在同一处、用同一套判定生成。
 */
internal class SearchIndex private constructor(
    private val postings: HashMap<String, IntArray>,
    /** 生成时认得的行数（行号上界）。 */
    val slots: Int,
    /** 生成时题目文件的字节数 —— 结构锁。 */
    val fileBytes: Long,
) {

    /** 索引里有多少个不同的词。 */
    val termCount: Int get() = postings.size

    /**
     * 结构锁：只在字节数完全相等时才认这张表。
     *
     * 不用"文件更大就重建"这种宽容判据：文件被改写（不是追加）时长度可能正好不变，
     * 而内容已经全变了。宁可多重建一次，也不要在错位的行号上"验证"出别人的题。
     */
    fun covers(fileBytes: Long, slots: Int): Boolean =
        this.fileBytes == fileBytes && this.slots == slots

    /**
     * 求交集：返回同时含全部 [terms] 的行号（升序）。
     *
     * 任一词不在索引里就直接是空集 —— 那说明没有一行同时含这些词。
     * 但空集不等于"真的没有"：追加的行还躺在尾巴里（见 [SearchTail]），
     * 调用方要把那份补上，所以这里的返回值只代表"主索引里没有"。
     */
    fun candidates(terms: List<String>): IntArray {
        if (terms.isEmpty()) return EMPTY
        var result: IntArray? = null
        for (term in terms) {
            val rows = postings[term] ?: return EMPTY
            result = if (result == null) rows else intersect(result, rows)
            if (result.isEmpty()) return EMPTY
        }
        return result ?: EMPTY
    }

    companion object {
        private val EMPTY = IntArray(0)

        fun empty(): SearchIndex = SearchIndex(HashMap(), 0, 0)

        /** 排好索引直接构造，不必先渲染成文本再解析回来（十万题那一次是白白多花两百毫秒）。 */
        fun of(postings: HashMap<String, IntArray>, slots: Int, fileBytes: Long): SearchIndex =
            SearchIndex(postings, slots, fileBytes)

        /**
         * 从索引文本解析。读不懂就整体作废（返回 null）——
         * 半份索引会让"交集"少一个词，于是真命中被悄悄漏掉，这比没有索引更难发现。
         */
        fun parse(raw: String): SearchIndex? {
            if (raw.isBlank()) return null
            val lines = raw.lineSequence().iterator()
            if (!lines.hasNext()) return null
            val header = parseHeader(lines.next()) ?: return null
            val postings = HashMap<String, IntArray>()
            var maxRow = -1
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) return null
                val term = line.substring(0, tab)
                val rows = parseDeltas(line.substring(tab + 1)) ?: return null
                if (rows.isEmpty()) return null
                if (rows.last() > maxRow) maxRow = rows.last()
                postings[term] = rows
            }
            // 行号越界说明表与头对不上：宁可重建
            if (maxRow >= header.slots) return null
            return SearchIndex(postings, header.slots, header.fileBytes)
        }

        private class Header(val fileBytes: Long, val slots: Int)

        private fun parseHeader(line: String): Header? {
            val parts = line.split(' ')
            if (parts.size != 4 || parts[0] != MAGIC) return null
            if (parts[1].toIntOrNull() != VERSION) return null
            val bytes = parts[2].toLongOrNull() ?: return null
            val slots = parts[3].toIntOrNull() ?: return null
            if (bytes < 0 || slots < 0) return null
            return Header(bytes, slots)
        }

        private fun parseDeltas(text: String): IntArray? {
            if (text.isEmpty()) return null
            var out = IntArray(16)
            var size = 0
            // 与 render 对称：delta 是"距上一行的距离"，上一条从 -1 算起
            var row = -1
            var start = 0
            while (true) {
                val comma = text.indexOf(',', start)
                val end = if (comma < 0) text.length else comma
                val delta = text.substring(start, end).toIntOrNull() ?: return null
                if (delta <= 0) return null
                row += delta
                if (size == out.size) out = out.copyOf(size * 2)
                out[size++] = row
                if (comma < 0) break
                start = comma + 1
            }
            return out.copyOf(size)
        }

        /** 二路归并求交，两边都是升序。 */
        private fun intersect(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(minOf(a.size, b.size))
            var i = 0
            var j = 0
            var k = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] < b[j] -> i++
                    a[i] > b[j] -> j++
                    else -> {
                        out[k++] = a[i]
                        i++
                        j++
                    }
                }
            }
            return out.copyOf(k)
        }

        internal const val MAGIC = "#ktsearch"
        internal const val VERSION = 1
    }
}

/**
 * 逐题累加地排一份倒排索引。
 *
 * 内部用 `HashMap<String, IntList>` 而不是 `HashMap<String, List<Int>>`：
 * 十万题约两百万个（词，行号）对，装箱的 `Int` 光对象头就是几十 MB，
 * 排一次索引能把手机端撑爆。
 */
internal class SearchIndexBuilder {

    private val postings = HashMap<String, IntList>()
    private var rows = 0

    /** 每一行内用来去重的小集合，反复清空复用 —— 十万题各建一个就是十万次分配。 */
    private val seenInRow = HashSet<String>()

    /** 加一行：行号由调用方按扫描顺序给，必须与偏移表同源。 */
    fun add(row: Int, prompt: String) {
        rows = maxOf(rows, row + 1)
        seenInRow.clear()
        forEachSearchTerm(prompt) { term ->
            // 同一行里同一个词只记一次：重复的行号会让 delta 算出 0，
            // 而 delta 为 0 的行是解析器判"表坏了"的依据 —— 自己写坏自己。
            if (seenInRow.add(term)) postings.getOrPut(term) { IntList() }.add(row)
        }
    }

    /** 排好的索引（查询用），与 [render] 出的文本内容一致。 */
    fun build(fileBytes: Long, slots: Int): SearchIndex {
        val map = HashMap<String, IntArray>(postings.size)
        for ((term, list) in postings) map[term] = list.toIntArray()
        return SearchIndex.of(map, slots, fileBytes)
    }

    fun render(fileBytes: Long, slots: Int): String {
        val text = StringBuilder(postings.size * 24)
        text.append(SearchIndex.MAGIC).append(' ').append(SearchIndex.VERSION)
            .append(' ').append(fileBytes).append(' ').append(slots).append('\n')
        // 按词排序：同一份内容每次排出来的字节完全一样（可对照、可进 golden）
        for (term in postings.keys.sorted()) {
            text.append(term).append('\t')
            val rowsOfTerm = postings.getValue(term)
            // 起点是 -1 而不是 0：行号 0 编码成 1，否则首条 delta 是 0，
            // 而 0 在解析侧是"这张表坏了"的信号（见 parseDeltas）
            var previous = -1
            for (i in 0 until rowsOfTerm.size) {
                val row = rowsOfTerm[i]
                text.append(row - previous)
                if (i != rowsOfTerm.size - 1) text.append(',')
                previous = row
            }
            text.append('\n')
        }
        return text.toString()
    }

    val rowCount: Int get() = rows
}

/** 极简的 int 列表：倒排表的 postings 是几十万个，`MutableList<Int>` 装不下。 */
internal class IntList {

    private var values = IntArray(4)

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = values[index]

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

/**
 * 规范化：小写、**只有空白**才算词边界，其余标点一律丢掉。
 *
 * 标点为什么是丢掉而不是变空格：中文题干里"以下，说法"和"以下说法"是同一个意思，
 * 变成空格就会切出不同的二元组，于是用户搜"以下说法"找不到那一行 ——
 * 一个逗号就决定了命中与否，这不合理。丢掉之后两边落成同一个串。
 *
 * 换行、制表符、空格仍保留为边界：英文按词搜索要靠它。
 */
internal fun normalizeSearchText(text: String): String {
    val out = StringBuilder(text.length)
    var pendingSpace = false
    for (ch in text) {
        when {
            ch.isWhitespace() -> if (out.isNotEmpty()) pendingSpace = true
            isSearchable(ch) -> {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(ch.lowercaseChar())
            }
            // 其它标点：直接丢，不产生边界
            else -> Unit
        }
    }
    return out.toString()
}

/** 字母、数字、汉字才算"词里的字"；其余都当噪声。 */
private fun isSearchable(ch: Char): Boolean =
    ch.isLetterOrDigit()

/**
 * 把一段文字切成索引项：先规范化分句，再对每句取二元组。
 *
 * 段内取二元组、不跨段，是刻意的：跨段二元组（"题以"）既不会出现在索引里，
 * 也不会被查询命中 —— 两边同一套规则，比"看起来能搜到"更重要。
 *
 * **一个字的段不产生索引项**。它曾经产生过（1-gram），想让"第 1 题"里的"1"能搜到；
 * 但 1-gram 的条目数跟题量成正比，几个高频字（"题""的"）的 postings 就有十万条，
 * 索引体积直接翻倍。而这类段在查询里没有任何筛选力 —— 它们仍会参与**验证**
 * （见 [matchesQuery]），只是不参与"从十万行里挑出候选"。
 *
 * 代价说清楚：全部由单字组成的查询（"题"）拿不到候选，退回全表扫描。
 * 那是老路径的速度，结果一样。
 *
 * 排索引时用 [forEachSearchTerm]（不分配列表）；查询侧要的是集合语义，才用 [searchTermsOf]。
 */
internal fun forEachSearchTerm(text: String, action: (String) -> Unit) {
    val normalized = normalizeSearchText(text)
    if (normalized.isEmpty()) return
    var start = 0
    while (start < normalized.length) {
        var end = normalized.indexOf(' ', start)
        if (end < 0) end = normalized.length
        for (i in start until end - 1) action(normalized.substring(i, i + 2))
        start = end + 1
    }
}

internal fun searchTermsOf(text: String): List<String> {
    val terms = ArrayList<String>(text.length)
    forEachSearchTerm(text) { terms += it }
    return terms
}

/**
 * 查询的分句：与 [searchTermsOf] 同一套规范化，但保留"段"的身份 ——
 * 验证时要求**每个查询段**都出现在题干的某一段里（词与词之间是「且」，
 * 不要求相邻、也不要求同序：关键词搜索本来就该这样）。
 */
internal fun searchQuerySegments(query: String): List<String> =
    normalizeSearchText(query).split(' ').filter { it.isNotEmpty() }

/**
 * 这一行的题干是否真的命中查询：按段做完整子串匹配，而不是只看二元组交集。
 *
 * 这里**每个段都要**命中，包括只有一个字的段 —— 它们不参与挑候选，
 * 但必须参与"这一行到底算不算命中"，否则"第 37 题"会把第 3 题也搜出来。
 */
internal fun matchesQuery(prompt: String, segments: List<String>): Boolean {
    if (segments.isEmpty()) return false
    val haystack = normalizeSearchText(prompt)
    if (haystack.isEmpty()) return false
    if (segments.size == 1) return haystack.contains(segments[0])
    val parts = haystack.split(' ')
    return segments.all { needle -> parts.any { it.contains(needle) } }
}
