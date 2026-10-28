package morefish.knowledge.trainer.domain.grading

import morefish.knowledge.trainer.domain.model.DiffSegment
import morefish.knowledge.trainer.domain.model.SegmentKind

/**
 * 严格文本比较的差异计算。
 *
 * 只做"共同前缀 + 共同后缀 + 中间差异块"的定位，
 * 目标是让用户一眼看到差在哪个字符，而不是给出一个相似度分数。
 * 注意：相似度永远不能参与判定，只能作为提示。
 */
object TextDiffCalculator {

    fun diff(expected: String, actual: String): List<DiffSegment> {
        if (expected == actual) {
            return if (expected.isEmpty()) emptyList() else listOf(DiffSegment(expected, SegmentKind.SAME))
        }

        val maxPrefix = minOf(expected.length, actual.length)
        var prefix = 0
        while (prefix < maxPrefix && expected[prefix] == actual[prefix]) prefix++

        val maxSuffix = minOf(expected.length, actual.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix &&
            expected[expected.length - 1 - suffix] == actual[actual.length - 1 - suffix]
        ) {
            suffix++
        }

        val expectedMiddle = expected.substring(prefix, expected.length - suffix)
        val actualMiddle = actual.substring(prefix, actual.length - suffix)

        return buildList {
            if (prefix > 0) add(DiffSegment(expected.substring(0, prefix), SegmentKind.SAME))
            if (expectedMiddle.isNotEmpty()) add(DiffSegment(expectedMiddle, SegmentKind.DELETED))
            if (actualMiddle.isNotEmpty()) add(DiffSegment(actualMiddle, SegmentKind.INSERTED))
            if (suffix > 0) add(DiffSegment(expected.substring(expected.length - suffix), SegmentKind.SAME))
        }
    }

    /** 给出"差在什么维度"的可读说明，便于用户定位不可见字符差异。 */
    fun notes(expected: String, actual: String): List<String> {
        if (expected == actual) return emptyList()
        val notes = mutableListOf<String>()

        if (expected.trim() == actual.trim()) {
            notes += "仅首尾空白不同"
        }
        if (expected.replace("\r\n", "\n") == actual.replace("\r\n", "\n")) {
            notes += "仅换行符形式不同（CRLF / LF）"
        }
        if (stripWhitespace(expected) == stripWhitespace(actual)) {
            notes += "仅空白字符（空格 / 换行 / 制表符）不同"
        }
        if (normalizeWidth(expected) == normalizeWidth(actual)) {
            notes += "仅全角半角字符不同"
        }
        if (normalizeWidth(stripWhitespace(expected)) == normalizeWidth(stripWhitespace(actual))) {
            notes += "空白与全半角的组合差异"
        }
        if (notes.isEmpty()) {
            notes += "存在实际文字内容差异"
        }
        return notes.distinct()
    }

    private fun stripWhitespace(text: String): String = text.filterNot { it.isWhitespace() }

    /** 全角 ASCII 与全角空格折半，仅用于生成提示，不参与判定。 */
    private fun normalizeWidth(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '\u3000' -> append(' ')
                ch in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }
}
