package morefish.knowledge.trainer.domain.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** 三角函数按角度还是按弧度解释。科学计算器上这是一个必须显式可见的开关。 */
enum class AngleMode(val wireName: String, val label: String, val hint: String) {
    DEG("deg", "角度", "sin(90) = 1"),
    RAD("rad", "弧度", "sin(π/2) = 1"),
}

/**
 * 求值失败的原因。带位置而不是只给一句话：
 * 用户写了一大串式子时，"第 7 个字符附近少了一个右括号"比"语法错误"有用得多。
 */
sealed interface CalcError {
    /** 出错位置（0 起）；-1 表示不指向具体字符。 */
    val position: Int
    val message: String

    data object EmptyInput : CalcError {
        override val position: Int get() = -1
        override val message: String get() = "还没有输入算式"
    }

    data class UnexpectedEnd(override val position: Int) : CalcError {
        override val message: String get() = "算式在这里结束了，后面还缺少内容"
    }

    data class UnexpectedChar(val char: Char, override val position: Int) : CalcError {
        override val message: String get() = "不认识的字符「$char」"
    }

    data class UnexpectedToken(val text: String, override val position: Int) : CalcError {
        override val message: String get() = "多余的内容「$text」"
    }

    data class MissingParen(override val position: Int) : CalcError {
        override val message: String get() = "括号没有配对，这里少一个「)」"
    }

    data class UnknownName(val name: String, override val position: Int) : CalcError {
        override val message: String get() = "不认识的函数或常数「$name」"
    }

    data class WrongArgCount(
        val name: String,
        val expected: String,
        val actual: Int,
        override val position: Int,
    ) : CalcError {
        override val message: String get() = "$name 需要 $expected 个参数，实际给了 $actual 个"
    }

    data class MissingCallParenthesis(val name: String, override val position: Int) : CalcError {
        override val message: String get() = "函数 $name 后面要跟括号，例如 $name(1)"
    }

    data class DivideByZero(override val position: Int) : CalcError {
        override val message: String get() = "除数不能为 0"
    }

    data class Domain(val reason: String, override val position: Int) : CalcError {
        override val message: String get() = reason
    }

    data object Overflow : CalcError {
        override val position: Int get() = -1
        override val message: String get() = "结果超出可表示范围"
    }
}

sealed interface CalcOutcome {
    data class Value(val value: Double, val display: String) : CalcOutcome
    data class Failure(val error: CalcError) : CalcOutcome
}

/**
 * 离线科学计算器：表达式字符串 → 数值。
 *
 * 只做**数值求值**，不碰符号化简、不做等号变形 —— 它的用途是"考试时算一个数"，
 * 而不是替代草稿纸上的推导。语法是本项目自定的一个很小的子集：
 *
 * - 四则与幂：`+ - * / ^`（`^` 右结合，`-2^2 = -4`），阶乘 `!`，百分号 `%`（后置，`50% = 0.5`）
 * - 隐式乘法：`2π`、`3sin(30)`、`2(3+4)`、`(1+2)(3+4)`
 * - 函数：sin cos tan asin acos atan ln lg sqrt cbrt abs exp floor ceil round sign
 *   min max mod pow hypot atan2 log（`log(x)` 为常用对数，`log(b, x)` 为以 b 为底）
 * - 常数：pi / π / e
 *
 * 计算过程与显示严格分离：`value` 是精确到 Double 的结果，`display` 只是它的可读形式。
 */
object Calculator {

    private const val MAX_FACTORIAL = 170.0

    fun evaluate(expression: String, angleMode: AngleMode = AngleMode.DEG): CalcOutcome {
        val source = normalizeExpression(expression)
        if (source.isBlank()) return CalcOutcome.Failure(CalcError.EmptyInput)

        val tokens = when (val result = tokenize(source)) {
            is TokenizeResult.Failed -> return CalcOutcome.Failure(result.error)
            is TokenizeResult.Ok -> result.tokens
        }

        val parser = Parser(tokens, angleMode)
        val value = parser.parse() ?: return CalcOutcome.Failure(parser.error ?: CalcError.UnexpectedEnd(source.length))
        if (!value.isFinite()) return CalcOutcome.Failure(CalcError.Overflow)
        return CalcOutcome.Value(value, formatNumber(value))
    }

    // ---------------------------------------------------------------- 词法

    private sealed interface Token {
        val position: Int

        data class Number(val value: Double, override val position: Int) : Token
        data class Name(val text: String, override val position: Int) : Token
        data class Operator(val symbol: Char, override val position: Int) : Token
    }

    private sealed interface TokenizeResult {
        data class Ok(val tokens: List<Token>) : TokenizeResult
        data class Failed(val error: CalcError) : TokenizeResult
    }

    private fun tokenize(source: String): TokenizeResult {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                ch.isWhitespace() -> index++

                ch.isDigit() || (ch == '.' && index + 1 < source.length && source[index + 1].isDigit()) -> {
                    val start = index
                    while (index < source.length && source[index].isDigit()) index++
                    if (index < source.length && source[index] == '.') {
                        index++
                        while (index < source.length && source[index].isDigit()) index++
                    }
                    // 只有 e 后面确实跟着数字才当作指数，否则 `2e` 里的 e 应该是常数
                    if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                        var probe = index + 1
                        if (probe < source.length && (source[probe] == '+' || source[probe] == '-')) probe++
                        if (probe < source.length && source[probe].isDigit()) {
                            index = probe
                            while (index < source.length && source[index].isDigit()) index++
                        }
                    }
                    val literal = source.substring(start, index)
                    val value = literal.toDoubleOrNull()
                        ?: return TokenizeResult.Failed(CalcError.UnexpectedToken(literal, start))
                    tokens += Token.Number(value, start)
                }

                ch.isLetter() || ch == 'π' -> {
                    val start = index
                    while (index < source.length && (source[index].isLetter() || source[index] == 'π')) index++
                    tokens += Token.Name(source.substring(start, index), start)
                }

                ch in "+-*/^!%()," -> {
                    tokens += Token.Operator(ch, index)
                    index++
                }

                else -> return TokenizeResult.Failed(CalcError.UnexpectedChar(ch, index))
            }
        }
        return TokenizeResult.Ok(tokens)
    }

    // ---------------------------------------------------------------- 语法

    /**
     * 优先级（低 → 高）：
     * 加减 < 乘除与隐式乘 < 一元正负 < 幂（右结合） < 后缀 `!` `%` < 括号与函数调用
     */
    private class Parser(
        private val tokens: List<Token>,
        private val angleMode: AngleMode,
    ) {
        private var index = 0
        var error: CalcError? = null
            private set

        fun parse(): Double? {
            val value = expression() ?: return null
            val extra = tokens.getOrNull(index)
            if (extra != null) {
                fail(describeExtra(extra))
                return null
            }
            return value
        }

        private fun describeExtra(token: Token): CalcError = when (token) {
            is Token.Operator -> if (token.symbol == ')') {
                CalcError.UnexpectedToken(")", token.position)
            } else {
                CalcError.UnexpectedToken(token.symbol.toString(), token.position)
            }

            is Token.Number -> CalcError.UnexpectedToken(formatNumber(token.value), token.position)
            is Token.Name -> CalcError.UnexpectedToken(token.text, token.position)
        }

        private fun fail(e: CalcError): Nothing? {
            if (error == null) error = e
            return null
        }

        private fun peek(): Token? = tokens.getOrNull(index)

        private fun peekOperator(symbol: Char): Boolean {
            val token = peek()
            return token is Token.Operator && token.symbol == symbol
        }

        private fun endPosition(): Int = tokens.lastOrNull()?.position ?: 0

        private fun expression(): Double? {
            var left = term() ?: return null
            while (true) {
                val token = peek()
                if (token !is Token.Operator) break
                if (token.symbol != '+' && token.symbol != '-') break
                index++
                val right = term() ?: return null
                left = if (token.symbol == '+') left + right else left - right
            }
            return left
        }

        private fun term(): Double? {
            var left = unary() ?: return null
            while (true) {
                val token = peek()
                when {
                    token is Token.Operator && (token.symbol == '*' || token.symbol == '/') -> {
                        index++
                        val right = unary() ?: return null
                        if (token.symbol == '*') {
                            left *= right
                        } else {
                            if (right == 0.0) return fail(CalcError.DivideByZero(token.position))
                            left /= right
                        }
                    }

                    // 隐式乘法：`2π`、`3sin(30)`、`2(3+4)`。只认 '(' 和函数名开头，
                    // 否则 `2 3` 会被悄悄算成 6 —— 那属于输入错误，应当报出来。
                    token is Token.Operator && token.symbol == '(' || token is Token.Name -> {
                        val right = unary() ?: return null
                        left *= right
                    }

                    else -> return left
                }
            }
        }

        private fun unary(): Double? {
            val token = peek()
            if (token is Token.Operator && (token.symbol == '-' || token.symbol == '+')) {
                index++
                val value = unary() ?: return null
                return if (token.symbol == '-') -value else value
            }
            return power()
        }

        private fun power(): Double? {
            val base = postfix() ?: return null
            val token = peek()
            if (token is Token.Operator && token.symbol == '^') {
                index++
                val exponent = unary() ?: return null
                if (base < 0 && exponent != floor(exponent)) {
                    return fail(CalcError.Domain("负数的非整数次幂在实数范围内没有定义", token.position))
                }
                val value = base.pow(exponent)
                if (!value.isFinite()) return fail(CalcError.Overflow)
                return value
            }
            return base
        }

        private fun postfix(): Double? {
            var value = primary() ?: return null
            while (true) {
                val token = peek()
                if (token !is Token.Operator) return value
                when (token.symbol) {
                    '!' -> {
                        index++
                        if (value < 0 || value != floor(value)) {
                            return fail(CalcError.Domain("阶乘只对非负整数有定义", token.position))
                        }
                        if (value > MAX_FACTORIAL) {
                            return fail(CalcError.Domain("阶乘结果超出可表示范围", token.position))
                        }
                        value = factorial(value.toInt())
                    }

                    '%' -> {
                        index++
                        value /= 100.0
                    }

                    else -> return value
                }
            }
        }

        private fun primary(): Double? {
            val token = peek() ?: return fail(CalcError.UnexpectedEnd(endPosition()))

            when (token) {
                is Token.Number -> {
                    index++
                    return token.value
                }

                is Token.Operator -> {
                    if (token.symbol != '(') return fail(CalcError.UnexpectedToken(token.symbol.toString(), token.position))
                    index++
                    val inner = expression() ?: return null
                    if (!peekOperator(')')) return fail(CalcError.MissingParen(token.position))
                    index++
                    return inner
                }

                is Token.Name -> {
                    index++
                    val name = token.text.lowercase()
                    CONSTANTS[name]?.let { return it }
                    if (name !in FUNCTION_ARITY) return fail(CalcError.UnknownName(token.text, token.position))
                    if (!peekOperator('(')) return fail(CalcError.MissingCallParenthesis(token.text, token.position))
                    index++
                    val args = mutableListOf<Double>()
                    if (!peekOperator(')')) {
                        while (true) {
                            val arg = expression() ?: return null
                            args += arg
                            if (peekOperator(',')) {
                                index++
                                continue
                            }
                            break
                        }
                    }
                    if (!peekOperator(')')) return fail(CalcError.MissingParen(token.position))
                    index++
                    return applyFunction(name, args, token.position)
                }
            }
        }

        private fun applyFunction(name: String, args: List<Double>, position: Int): Double? {
            val arity = FUNCTION_ARITY[name] ?: return fail(CalcError.UnknownName(name, position))
            if (args.size !in arity) {
                return fail(CalcError.WrongArgCount(name, describeArity(arity), args.size, position))
            }
            fun a(i: Int): Double = args[i]

            return when (name) {
                "sin" -> snapTrig(sin(toRadians(a(0))))
                "cos" -> snapTrig(cos(toRadians(a(0))))
                "tan" -> snapTrig(tan(toRadians(a(0))))
                "asin" -> if (a(0) < -1 || a(0) > 1) {
                    fail(CalcError.Domain("反正弦的取值只能在 -1 到 1 之间", position))
                } else {
                    fromRadians(asin(a(0)))
                }

                "acos" -> if (a(0) < -1 || a(0) > 1) {
                    fail(CalcError.Domain("反余弦的取值只能在 -1 到 1 之间", position))
                } else {
                    fromRadians(acos(a(0)))
                }

                "atan" -> fromRadians(atan(a(0)))
                "sinh" -> sinh(a(0))
                "cosh" -> cosh(a(0))
                "tanh" -> tanh(a(0))

                "ln" -> if (a(0) <= 0) {
                    fail(CalcError.Domain("自然对数只在正数上有定义", position))
                } else {
                    ln(a(0))
                }

                "lg", "log10" -> if (a(0) <= 0) {
                    fail(CalcError.Domain("常用对数只在正数上有定义", position))
                } else {
                    log10(a(0))
                }

                // log(x) 按常用对数，log(b, x) 才是以 b 为底 —— 与国内教材习惯一致
                "log" -> when (args.size) {
                    1 -> if (a(0) <= 0) {
                        fail(CalcError.Domain("对数只在正数上有定义", position))
                    } else {
                        log10(a(0))
                    }

                    else -> if (a(0) <= 0 || a(0) == 1.0 || a(1) <= 0) {
                        fail(CalcError.Domain("log(b, x) 要求 b > 0、b ≠ 1、x > 0", position))
                    } else {
                        ln(a(1)) / ln(a(0))
                    }
                }

                "sqrt" -> if (a(0) < 0) {
                    fail(CalcError.Domain("负数没有实数平方根", position))
                } else {
                    sqrt(a(0))
                }

                "cbrt" -> cbrt(a(0))
                "abs" -> abs(a(0))
                "exp" -> exp(a(0))
                "floor" -> floor(a(0))
                "ceil" -> ceil(a(0))
                "round" -> round(a(0))
                "sign" -> when {
                    a(0) > 0 -> 1.0
                    a(0) < 0 -> -1.0
                    else -> 0.0
                }

                "min" -> args.min()
                "max" -> args.max()
                "hypot" -> hypot(a(0), a(1))
                "atan2" -> fromRadians(atan2(a(0), a(1)))
                "pow" -> a(0).pow(a(1))
                "mod" -> if (a(1) == 0.0) {
                    fail(CalcError.DivideByZero(position))
                } else {
                    a(0) - a(1) * floor(a(0) / a(1))
                }

                else -> fail(CalcError.UnknownName(name, position))
            }
        }

        private fun describeArity(arity: IntRange): String =
            if (arity.first == arity.last) "${arity.first}" else "${arity.first}~${arity.last}"

        private fun toRadians(value: Double): Double =
            if (angleMode == AngleMode.DEG) value * PI / 180.0 else value

        private fun fromRadians(value: Double): Double =
            if (angleMode == AngleMode.DEG) value * 180.0 / PI else value

        /**
         * 角度模式下 `sin(180)` 在 Double 里是 1.22e-16。
         * 计算器显示 `1.224646799×10^-16` 只会让人怀疑自己按错了，
         * 因此把"数学上应为 0"的极小值归零 —— 只对三角函数结果做，不外溢到别处。
         */
        private fun snapTrig(value: Double): Double =
            if (angleMode == AngleMode.DEG && abs(value) < 1e-12) 0.0 else value
    }

    // ---------------------------------------------------------------- 函数表

    private val CONSTANTS: Map<String, Double> = mapOf(
        "pi" to PI,
        "π" to PI,
        "e" to kotlin.math.E,
        "tau" to 2 * PI,
    )

    private val FUNCTION_ARITY: Map<String, IntRange> = mapOf(
        "sin" to 1..1,
        "cos" to 1..1,
        "tan" to 1..1,
        "asin" to 1..1,
        "acos" to 1..1,
        "atan" to 1..1,
        "sinh" to 1..1,
        "cosh" to 1..1,
        "tanh" to 1..1,
        "ln" to 1..1,
        "lg" to 1..1,
        "log10" to 1..1,
        "log" to 1..2,
        "sqrt" to 1..1,
        "cbrt" to 1..1,
        "abs" to 1..1,
        "exp" to 1..1,
        "floor" to 1..1,
        "ceil" to 1..1,
        "round" to 1..1,
        "sign" to 1..1,
        "min" to 2..8,
        "max" to 2..8,
        "hypot" to 2..2,
        "atan2" to 2..2,
        "pow" to 2..2,
        "mod" to 2..2,
    )

    private fun factorial(n: Int): Double {
        var result = 1.0
        for (i in 2..n) result *= i
        return result
    }
}
