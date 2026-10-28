package morefish.knowledge.trainer.domain.tools

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * 数字输入与显示的工具函数。
 *
 * 刻意不依赖 `BigDecimal` / `String.format`：前者在 KMP common 里没有，
 * 后者只有 JVM 有。这里只用 `Double.toString()` 的"最短往返表示"再按有效位数截断，
 * 因此同一个算式在桌面、Android、Web 上显示完全一致。
 */

/**
 * 把用户可能输入的全角字符换成解析器认识的半角。
 *
 * 中文输入法下 `（）＋－×÷` 是默认状态，如果直接报"不认识的字符"，
 * 用户会以为是软件坏了 —— 这属于必须容忍的输入习惯，不是错误。
 */
fun normalizeExpression(text: String): String = buildString(text.length) {
    for (ch in text) {
        append(
            when (ch) {
                '＋' -> '+'
                '－', '−', '–', '—' -> '-'
                '×', '✕', '✖', '＊', '·' -> '*'
                '÷', '／' -> '/'
                '（' -> '('
                '）' -> ')'
                '，', '、' -> ','
                '．', '。' -> '.'
                '％' -> '%'
                '　', '\u00A0' -> ' '
                in '０'..'９' -> '0' + (ch - '０')
                else -> ch
            },
        )
    }
}

/** 单位换算输入框用：允许前后空格，只接受有限实数。 */
fun parseNumber(text: String): Double? {
    val cleaned = normalizeExpression(text).filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/**
 * 按有效数字格式化，去掉二进制浮点噪声。
 *
 * 例：`0.1 + 0.2` 在 Double 里是 `0.30000000000000004`，
 * 计算器显示成那样等于把实现细节甩给用户。这里先按有效位数取整，
 * 再走最短往返表示，`0.3` 就是 `0.3`。
 */
fun formatNumber(value: Double, significantDigits: Int = 10): String {
    if (value.isNaN()) return "无定义"
    if (value == Double.POSITIVE_INFINITY) return "溢出"
    if (value == Double.NEGATIVE_INFINITY) return "溢出"
    if (value == 0.0) return "0"

    val rounded = roundToSignificant(value, significantDigits.coerceIn(1, 17))
    return plainText(rounded)
}

private fun roundToSignificant(value: Double, sig: Int): Double {
    val magnitude = floor(log10(abs(value))).toInt()
    val decimals = sig - 1 - magnitude
    if (decimals <= 0) {
        val step = 10.0.pow(-decimals)
        return round(value / step) * step
    }
    val scale = 10.0.pow(decimals)
    return round(value * scale) / scale
}

/**
 * `Double.toString()` 在 |x| ≥ 1e7 或 < 1e-3 时会给出 `1.0E10` 这类写法。
 * 这里展开成普通十进制（数学习惯），只有位数实在太多时才保留科学记数法。
 */
private fun plainText(value: Double): String {
    val raw = value.toString()
    val eIndex = raw.indexOf('E')
    if (eIndex < 0) return trimDecimal(raw)

    val negative = raw.startsWith('-')
    val mantissa = if (negative) raw.substring(1, eIndex) else raw.substring(0, eIndex)
    val exponent = raw.substring(eIndex + 1).toIntOrNull() ?: 0
    val dot = mantissa.indexOf('.')
    val digits = if (dot < 0) mantissa else mantissa.replace(".", "")
    val decimalPos = (if (dot < 0) mantissa.length else dot) + exponent

    // 位数太多时不硬展开：`0.000000000012345` 读起来比 `1.2345×10^-11` 更费劲
    if (decimalPos >= 16 || decimalPos <= -8) {
        val head = digits.first().toString()
        val tail = digits.drop(1).trimEnd('0')
        val body = if (tail.isEmpty()) head else "$head.$tail"
        val sign = if (negative) "-" else ""
        return "$sign$body×10^${decimalPos - 1}"
    }

    val body = when {
        decimalPos <= 0 -> "0." + "0".repeat(-decimalPos) + digits
        decimalPos >= digits.length -> digits + "0".repeat(decimalPos - digits.length)
        else -> digits.substring(0, decimalPos) + "." + digits.substring(decimalPos)
    }
    return (if (negative) "-" else "") + trimDecimal(body)
}

/** 去掉多余的尾零：`1.500` → `1.5`，`1.0` → `1`。 */
private fun trimDecimal(text: String): String {
    if (!text.contains('.')) return text
    var end = text.length
    while (end > 1 && text[end - 1] == '0') end--
    if (end > 1 && text[end - 1] == '.') end--
    return text.substring(0, end)
}
