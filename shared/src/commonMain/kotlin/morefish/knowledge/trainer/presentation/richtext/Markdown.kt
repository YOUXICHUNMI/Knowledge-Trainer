package morefish.knowledge.trainer.presentation.richtext

/**
 * 题库里真正会出现的 Markdown 子集。
 *
 * 刻意不支持完整 CommonMark：题库文本是"题干/选项/解析"，不是网页。
 * 解析不了的语法一律按原文显示，绝不猜、绝不吞字。
 */
sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Code(val language: String?, val text: String) : MdBlock
    data class MathBlock(val tex: String) : MdBlock
    data class BulletList(val items: List<List<MdInline>>) : MdBlock
    data class OrderedList(val start: Int, val items: List<List<MdInline>>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdInline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdInline

    data class Code(val text: String) : MdInline
    data class Math(val tex: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

/** 块内是否含数学公式（决定走普通 Text 还是自绘的混排布局）。 */
fun List<MdInline>.hasMath(): Boolean = any { it is MdInline.Math }

/**
 * Markdown 解析器：行级状态机 + 行内递归。
 *
 * 设计要点：
 * - 纯文本走极速通道（[isPlain]），保证普通题库的排版与旧版逐像素一致；
 * - 行内公式 `$...$` 先于强调解析，`$a*b$` 里的星号不会被当成斜体；
 * - 找不到配对标记时按字面输出，不做半截解析。
 */
object Markdown {

    private val BULLET = Regex("^[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
    private val UNDERSCORE_EMPHASIS = Regex("(?<![\\p{L}\\p{N}])_[^_\\s][^_]*_(?![\\p{L}\\p{N}])")
    private val STAR_EMPHASIS = Regex("\\*\\*[^*\\n]+\\*\\*|(?<!\\*)\\*[^*\\s][^*\\n]*\\*(?!\\*)")

    /** 没有任何 Markdown/LaTeX 标记时返回 true —— 直接交给 Text，排版最稳。 */
    fun isPlain(source: String): Boolean {
        if (source.isEmpty()) return true
        if ('$' in source || '`' in source) return false
        if ("](" in source || "~~" in source) return false
        if (STAR_EMPHASIS.containsMatchIn(source)) return false
        if (UNDERSCORE_EMPHASIS.containsMatchIn(source)) return false
        for (raw in source.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(">")) return false
            if (line.startsWith("```") || line.startsWith("$$")) return false
            if (line == "---" || line == "***" || line == "___") return false
            if (BULLET.matches(line) || ORDERED.matches(line)) return false
            // 反斜杠转义（\* \{ 等）也算有语法，走解析器才能正确还原
            if (line.contains("\\*") || line.contains("\\_") || line.contains("\\$")) return false
        }
        return true
    }

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                line.isEmpty() -> i++

                line.startsWith("```") -> {
                    val language = line.removePrefix("```").trim().ifEmpty { null }
                    val buffer = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        buffer.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += MdBlock.Code(language, buffer.toString().trimEnd('\n'))
                }

                line.startsWith("$$") -> {
                    val rest = line.removePrefix("$$")
                    if (rest.trimEnd().endsWith("$$") && rest.trim().length >= 2) {
                        blocks += MdBlock.MathBlock(rest.trim().removeSuffix("$$").trim())
                        i++
                    } else {
                        val buffer = StringBuilder(rest)
                        i++
                        while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                            buffer.append('\n').append(lines[i])
                            i++
                        }
                        if (i < lines.size) {
                            buffer.append('\n').append(lines[i].trim().removeSuffix("$$"))
                            i++
                        }
                        blocks += MdBlock.MathBlock(buffer.toString().trim())
                    }
                }

                line.startsWith("#") -> {
                    var level = 0
                    while (level < line.length && line[level] == '#') level++
                    if (level in 1..6 && (level == line.length || line[level] == ' ')) {
                        blocks += MdBlock.Heading(level, inlineOf(line.substring(level).trim()))
                    } else {
                        blocks += MdBlock.Paragraph(inlineOf(line))
                    }
                    i++
                }

                line == "---" || line == "***" || line == "___" -> {
                    blocks += MdBlock.Rule
                    i++
                }

                line.startsWith(">") -> {
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quoted += lines[i].trim().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    blocks += MdBlock.Quote(parse(quoted.joinToString("\n")))
                }

                BULLET.matches(line) -> {
                    val items = mutableListOf<List<MdInline>>()
                    val current = StringBuilder()
                    var started = false
                    while (i < lines.size) {
                        val probe = lines[i]
                        val probeTrim = probe.trim()
                        val match = BULLET.find(probeTrim)
                        when {
                            match != null && (probe == probeTrim || !started) -> {
                                if (started) items += inlineOf(current.toString().trim())
                                current.setLength(0)
                                current.append(match.groupValues[1])
                                started = true
                                i++
                            }
                            probeTrim.isEmpty() -> break
                            probe.startsWith("  ") && started -> {
                                current.append('\n').append(probeTrim)
                                i++
                            }
                            else -> break
                        }
                    }
                    if (started) items += inlineOf(current.toString().trim())
                    blocks += MdBlock.BulletList(items)
                }

                ORDERED.matches(line) -> {
                    val start = ORDERED.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size) {
                        val probeTrim = lines[i].trim()
                        if (probeTrim.isEmpty()) break
                        val match = ORDERED.find(probeTrim)
                        if (match == null) break
                        items += inlineOf(match.groupValues[2].trim())
                        i++
                    }
                    blocks += MdBlock.OrderedList(start, items)
                }

                else -> {
                    val buffer = StringBuilder(line)
                    i++
                    while (i < lines.size) {
                        val probeTrim = lines[i].trim()
                        if (probeTrim.isEmpty()) break
                        if (probeTrim.startsWith("#") || probeTrim.startsWith("```") ||
                            probeTrim.startsWith(">") || probeTrim.startsWith("$$") ||
                            probeTrim == "---" || BULLET.matches(probeTrim) || ORDERED.matches(probeTrim)
                        ) break
                        buffer.append('\n').append(probeTrim)
                        i++
                    }
                    blocks += MdBlock.Paragraph(inlineOf(buffer.toString()))
                }
            }
        }
        return blocks
    }

    /**
     * 列表里的一行摘要：去掉标记、公式换成 [mathPlaceholder]、折叠空白。
     *
     * 故意不保留 TeX 源：一行 `\dfrac{\Delta I}{\Delta t}` 糊在标题里，
     * 读的人只会以为界面坏了。要看公式的地方（题干、解析、笔记预览）用 RichText。
     */
    fun plainExcerpt(source: String, limit: Int, mathPlaceholder: String = "公式"): String {
        if (source.isBlank()) return ""
        if (isPlain(source)) return source.replace(Regex("\\s+"), " ").trim().take(limit)
        val flattened = buildString {
            parse(source).forEach { block ->
                when (block) {
                    is MdBlock.Paragraph -> append(inlineText(block.inlines, mathPlaceholder))
                    is MdBlock.Heading -> append(inlineText(block.inlines, mathPlaceholder))
                    is MdBlock.MathBlock -> append(mathPlaceholder)
                    is MdBlock.Code -> append(block.text)
                    is MdBlock.BulletList -> block.items.forEach { append(inlineText(it, mathPlaceholder)).append(' ') }
                    is MdBlock.OrderedList -> block.items.forEach { append(inlineText(it, mathPlaceholder)).append(' ') }
                    is MdBlock.Quote -> block.blocks.forEach { inner ->
                        if (inner is MdBlock.Paragraph) append(inlineText(inner.inlines, mathPlaceholder)).append(' ')
                    }
                    is MdBlock.Rule -> Unit
                }
                append(' ')
            }
        }.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= limit) flattened else flattened.take(limit) + "…"
    }

    private fun inlineText(inlines: List<MdInline>, mathPlaceholder: String = "公式"): String = inlines.joinToString("") { inline ->
        when (inline) {
            is MdInline.Text -> inline.text
            is MdInline.Code -> inline.text
            is MdInline.Math -> mathPlaceholder
            is MdInline.Link -> inline.text
        }
    }

    // ------------------------------------------------------------- 行内

    fun inlineOf(source: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        parseInline(source, 0, source.length, Style(), out)
        return merge(out)
    }

    private data class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false)

    private fun parseInline(s: String, start: Int, end: Int, em: Style, out: MutableList<MdInline>) {
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdInline.Text(buffer.toString(), em.bold, em.italic, em.strike)
                buffer.clear()
            }
        }
        var i = start
        while (i < end) {
            val c = s[i]

            if (c == '\\' && i + 1 < end) {
                val next = s[i + 1]
                if (!next.isLetterOrDigit()) {
                    buffer.append(next)
                    i += 2
                    continue
                }
            }

            if (c == '$' && i + 1 < end && s[i + 1] != '$') {
                val close = findMathClose(s, i + 1, end)
                if (close > i + 1) {
                    flush()
                    out += MdInline.Math(s.substring(i + 1, close).trim())
                    i = close + 1
                    continue
                }
            }

            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close in (i + 1) until end) {
                    flush()
                    out += MdInline.Code(s.substring(i + 1, close))
                    i = close + 1
                    continue
                }
            }

            if (c == '[') {
                val link = matchLink(s, i, end)
                if (link != null) {
                    flush()
                    out += MdInline.Link(link.first, link.second)
                    i = link.third
                    continue
                }
            }

            if (c == '~' && i + 1 < end && s[i + 1] == '~' && !em.strike) {
                val close = s.indexOf("~~", i + 2)
                if (close in (i + 2) until end) {
                    flush()
                    parseInline(s, i + 2, close, em.copy(strike = true), out)
                    i = close + 2
                    continue
                }
            }

            if (c == '*') {
                val strong = i + 1 < end && s[i + 1] == '*'
                val marker = if (strong) "**" else "*"
                val innerStart = i + marker.length
                if (!(strong && em.bold) && !(!strong && em.italic) && canOpen(s, i, end, c, strong)) {
                    val close = findMarkerClose(s, innerStart, end, marker, c)
                    if (close >= 0) {
                        flush()
                        parseInline(s, innerStart, close, if (strong) em.copy(bold = true) else em.copy(italic = true), out)
                        i = close + marker.length
                        continue
                    }
                }
            }

            if (c == '_' && !em.italic && canOpen(s, i, end, c, strong = false)) {
                val close = findMarkerClose(s, i + 1, end, "_", c)
                if (close >= 0) {
                    flush()
                    parseInline(s, i + 1, close, em.copy(italic = true), out)
                    i = close + 1
                    continue
                }
            }

            buffer.append(c)
            i++
        }
        flush()
    }

    private fun findMathClose(s: String, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '$') return i
            i++
        }
        return -1
    }

    private fun canOpen(s: String, index: Int, end: Int, marker: Char, strong: Boolean): Boolean {
        val nextIndex = index + if (strong) 2 else 1
        if (nextIndex >= end) return false
        val next = s[nextIndex]
        if (next.isWhitespace()) return false
        if (next == marker) return false
        if (marker == '_') {
            val prev = if (index > 0) s[index - 1] else ' '
            if (prev.isLetterOrDigit()) return false
        }
        return true
    }

    private fun findMarkerClose(s: String, from: Int, end: Int, marker: String, markerChar: Char): Int {
        var i = from
        while (i < end) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == markerChar && s.startsWith(marker, i)) {
                if (i > from) {
                    if (markerChar == '_') {
                        val after = if (i + 1 < end) s[i + 1] else ' '
                        if (after.isLetterOrDigit()) {
                            i++
                            continue
                        }
                    }
                    return i
                }
            }
            i++
        }
        return -1
    }

    /** 返回 (文字, 链接, 结束下标)。 */
    private fun matchLink(s: String, start: Int, end: Int): Triple<String, String, Int>? {
        var i = start + 1
        var depth = 1
        while (i < end) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (i + 1 >= end || s[i + 1] != '(') return null
        val textEnd = i
        val urlStart = i + 2
        var j = urlStart
        var paren = 1
        while (j < end) {
            when (s[j]) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren == 0) break
                }
            }
            j++
        }
        if (j >= end) return null
        val text = s.substring(start + 1, textEnd)
        val url = s.substring(urlStart, j).trim()
        if (url.isEmpty()) return null
        return Triple(text, url, j + 1)
    }

    private fun merge(items: List<MdInline>): List<MdInline> {
        val out = mutableListOf<MdInline>()
        for (item in items) {
            val last = out.lastOrNull()
            if (item is MdInline.Text && last is MdInline.Text &&
                last.bold == item.bold && last.italic == item.italic && last.strike == item.strike
            ) {
                out[out.size - 1] = last.copy(text = last.text + item.text)
            } else {
                out += item
            }
        }
        return out
    }
}
