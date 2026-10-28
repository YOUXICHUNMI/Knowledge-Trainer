package morefish.knowledge.trainer.domain.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * 计算器是"考试时用来算一个数"的工具，所以测试的重点不是炫技，
 * 而是**优先级的数学惯例**、**常见的输入习惯**和**出错时说不说得清**。
 */
class CalculatorTest {

    private fun value(expression: String, mode: AngleMode = AngleMode.DEG): Double =
        when (val outcome = Calculator.evaluate(expression, mode)) {
            is CalcOutcome.Value -> outcome.value
            is CalcOutcome.Failure -> fail("「$expression」应当能算出结果，实际：${outcome.error.message}")
        }

    private fun display(expression: String, mode: AngleMode = AngleMode.DEG): String =
        when (val outcome = Calculator.evaluate(expression, mode)) {
            is CalcOutcome.Value -> outcome.display
            is CalcOutcome.Failure -> fail("「$expression」应当能算出结果，实际：${outcome.error.message}")
        }

    private fun failure(expression: String, mode: AngleMode = AngleMode.DEG): CalcError =
        when (val outcome = Calculator.evaluate(expression, mode)) {
            is CalcOutcome.Value -> fail("「$expression」应当报错，实际算出 ${outcome.display}")
            is CalcOutcome.Failure -> outcome.error
        }

    private fun assertValue(expected: Double, expression: String, mode: AngleMode = AngleMode.DEG) {
        val actual = value(expression, mode)
        assertTrue(
            abs(actual - expected) < 1e-9,
            "「$expression」应为 $expected，实际 $actual",
        )
    }

    // ------------------------------------------------------------ 优先级与结合性

    @Test
    fun multiplicationBindsTighterThanAddition() {
        assertValue(7.0, "1+2*3")
        assertValue(9.0, "(1+2)*3")
        assertValue(19.0, "1+2*3^2")
        assertValue(5.0, "10-3-2")   // 减法左结合
        assertValue(1.0, "8/4/2")    // 除法左结合
    }

    @Test
    fun powerIsRightAssociativeAndBindsTighterThanUnaryMinus() {
        assertValue(512.0, "2^3^2")
        // 数学惯例：-2^2 是 -(2^2)，不是 (-2)^2
        assertValue(-4.0, "-2^2")
        assertValue(4.0, "(-2)^2")
        assertValue(0.5, "2^-1")
    }

    @Test
    fun implicitMultiplicationCoversTheUsualHandwriting() {
        assertValue(14.0, "2(3+4)")
        assertValue(21.0, "(1+2)(3+4)")
        assertValue(1.5, "3sin(30)")
        assertValue(12.0, "4sqrt(9)")
        assertTrue(abs(value("2π") - 2 * PI) < 1e-12)
    }

    // ------------------------------------------------------------ 后缀运算

    @Test
    fun percentMeansOneHundredth() {
        assertValue(0.5, "50%")
        assertValue(20.0, "200*10%")
        assertValue(1.05, "100%+5%")
    }

    @Test
    fun factorialOnlyAcceptsNonNegativeIntegers() {
        assertValue(120.0, "5!")
        assertValue(1.0, "0!")
        assertValue(7.0, "3!+1")
        assertTrue(failure("(-1)!") is CalcError.Domain)
        assertTrue(failure("5.5!") is CalcError.Domain)
    }

    // ------------------------------------------------------------ 函数

    @Test
    fun commonFunctionsComputeWhatTheTextbookSays() {
        assertValue(4.0, "sqrt(16)")
        assertValue(3.0, "cbrt(27)")
        assertValue(1.0, "ln(e)")
        assertValue(3.0, "lg(1000)")
        assertValue(3.0, "log(2, 8)")
        assertValue(3.0, "abs(-3)")
        assertValue(3.0, "max(1, 2, 3)")
        assertValue(1.0, "min(1, 2, 3)")
        assertValue(1.0, "mod(7, 3)")
        assertValue(-2.0, "floor(-1.5)")
        assertValue(-1.0, "ceil(-1.5)")
        assertValue(2.0, "round(2.4)")
        assertValue(5.0, "hypot(3, 4)")
        assertValue(1024.0, "pow(2, 10)")
        assertValue(-1.0, "sign(-9)")
    }

    @Test
    fun degreeModeIsTheDefaultForTrigonometry() {
        assertValue(1.0, "sin(90)")
        assertValue(-1.0, "cos(180)")
        assertValue(1.0, "tan(45)")
        assertValue(30.0, "asin(0.5)")
        assertValue(45.0, "atan(1)")
    }

    @Test
    fun degreeModeSnapsTheTinyResidueOfExactAnglesToZero() {
        // sin(180°) 在 Double 里是 1.22e-16，显示成那个数只会让人怀疑自己按错
        assertEquals(0.0, value("sin(180)"))
        assertEquals(0.0, value("tan(180)"))
    }

    @Test
    fun radianModeChangesBothDirections() {
        assertValue(1.0, "sin(pi/2)", AngleMode.RAD)
        assertValue(PI / 4, "atan(1)", AngleMode.RAD)
        assertValue(PI / 2, "asin(1)", AngleMode.RAD)
        // 同一个式子在不同角度制下结果必须不同，否则开关是假的
        assertTrue(abs(value("sin(90)", AngleMode.RAD) - 0.8939966636) < 1e-9)
    }

    // ------------------------------------------------------------ 输入习惯

    @Test
    fun fullWidthInputFromAChineseImeIsAccepted() {
        assertValue(9.0, "（1＋2）×3")
        assertValue(124.0, "１２３．５＋０．５")
        assertValue(0.5, "1÷2")
        assertValue(-1.0, "－1")
    }

    @Test
    fun scientificNotationIsUnderstood() {
        assertValue(1500.0, "1.5e3")
        assertValue(0.002, "2e-3")
        // `2e` 里的 e 没有跟数字，应当当成自然常数而不是指数
        assertTrue(abs(value("2e") - 2 * kotlin.math.E) < 1e-12)
    }

    // ------------------------------------------------------------ 显示

    @Test
    fun displayHidesBinaryFloatingNoise() {
        assertEquals("0.3", display("0.1+0.2"))
        assertEquals("3", display("6/2"))
        assertEquals("0.125", display("1/8"))
        assertEquals("100", display("10^2"))
    }

    @Test
    fun displayKeepsAtMostTenSignificantDigits() {
        assertEquals("0.3333333333", display("1/3"))
        assertEquals("1.414213562", display("sqrt(2)"))
    }

    @Test
    fun veryLargeAndVerySmallNumbersSwitchToScientificNotation() {
        assertEquals("1×10^20", display("1e20"))
        assertEquals("1×10^-18", display("1e-18"))
        // 不太大不太小的仍然用普通写法
        assertEquals("1000000000", display("1e9"))
        assertEquals("0.000001", display("1e-6"))
    }

    // ------------------------------------------------------------ 出错

    @Test
    fun emptyInputSaysSoInsteadOfShowingZero() {
        assertEquals(CalcError.EmptyInput, failure(""))
        assertEquals(CalcError.EmptyInput, failure("   "))
    }

    @Test
    fun arithmeticErrorsAreNamedAtTheirPosition() {
        val divide = failure("1/0")
        assertTrue(divide is CalcError.DivideByZero)
        assertEquals(1, divide.position)

        assertTrue(failure("sqrt(-1)") is CalcError.Domain)
        assertTrue(failure("ln(0)") is CalcError.Domain)
        assertTrue(failure("lg(-5)") is CalcError.Domain)
        assertTrue(failure("asin(2)") is CalcError.Domain)
        assertEquals(CalcError.Overflow, failure("2^10000"))
    }

    @Test
    fun syntaxErrorsKeepTheOffendingPosition() {
        val missingEnd = failure("1+")
        assertTrue(missingEnd is CalcError.UnexpectedEnd)

        val missingParen = failure("(1+2")
        assertTrue(missingParen is CalcError.MissingParen)
        assertEquals(0, missingParen.position)

        val extraParen = failure("1+2)")
        assertTrue(extraParen is CalcError.UnexpectedToken)

        // 两个数字挨在一起不是"隐式乘法"，是漏了运算符
        assertTrue(failure("2 3") is CalcError.UnexpectedToken)
        assertTrue(failure("1+@") is CalcError.UnexpectedChar)
        assertTrue(failure("foo(1)") is CalcError.UnknownName)
        assertTrue(failure("sin") is CalcError.MissingCallParenthesis)
        assertTrue(failure("min(1)") is CalcError.WrongArgCount)
        assertTrue(failure("max(1,)") is CalcError.UnexpectedToken)
    }

    @Test
    fun unknownFunctionMessageNamesTheFunction() {
        assertEquals("不认识的函数或常数「foo」", failure("foo(1)").message)
    }
}
