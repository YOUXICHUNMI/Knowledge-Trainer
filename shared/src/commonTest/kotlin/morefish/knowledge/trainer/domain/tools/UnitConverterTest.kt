package morefish.knowledge.trainer.domain.tools

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 单位换算的测试围绕三件事：**维度不许串**、**温度带偏移**、**系数有多准要说清楚**。
 */
class UnitConverterTest {

    private fun convert(value: Double, from: String, to: String): Conversion.Ok {
        val fromUnit = requireNotNull(Units.resolve(from)) { "找不到单位 $from" }
        val toUnit = requireNotNull(Units.resolve(to)) { "找不到单位 $to" }
        val outcome = Units.convert(value, fromUnit, toUnit)
        assertTrue(outcome is Conversion.Ok, "$from → $to 应当能换算，实际 $outcome")
        return outcome
    }

    private fun assertConvert(expected: Double, value: Double, from: String, to: String, tolerance: Double = 1e-9) {
        val actual = convert(value, from, to).value
        assertTrue(abs(actual - expected) <= tolerance, "$value $from 应为 $expected $to，实际 $actual")
    }

    // ------------------------------------------------------------ 目录自检

    @Test
    fun catalogPassesItsOwnIntegrityCheck() {
        val problems = Units.integrityProblems()
        assertTrue(problems.isEmpty(), "单位表自检未通过：${problems.joinToString("；")}")
        assertTrue(Units.all.size >= 70, "单位数量偏少：${Units.all.size}")
    }

    @Test
    fun resolveAcceptsSymbolChineseNameAndAlias() {
        assertEquals("km", Units.resolve("km")?.id)
        assertEquals("km", Units.resolve("千米")?.id)
        assertEquals("km", Units.resolve("公里")?.id)
        assertEquals("kg", Units.resolve("公斤")?.id)
        assertEquals("degC", Units.resolve("°C")?.id)
        assertEquals("mps", Units.resolve("m/s")?.id)
        assertNull(Units.resolve("光年"))
        assertNull(Units.resolve(""))
    }

    // ------------------------------------------------------------ 常用换算

    @Test
    fun lengthConversionsMatchTheDefinitions() {
        assertConvert(100.0, 1.0, "m", "cm")
        assertConvert(0.0254, 1.0, "in", "m", tolerance = 0.0)
        assertConvert(2.54, 1.0, "in", "cm")
        assertConvert(1609.344, 1.0, "mi", "m", tolerance = 0.0)
        assertConvert(1852.0, 1.0, "nmi", "m", tolerance = 0.0)
        assertConvert(1000.0, 1.0, "km", "m")
        assertConvert(3.280839895, 1.0, "m", "ft")
    }

    @Test
    fun massAndAreaAndEnergyConversionsAreRight() {
        assertConvert(0.5, 1.0, "斤", "kg")
        assertConvert(500.0, 1.0, "斤", "g")
        assertConvert(666.6666667, 1.0, "亩", "m2", tolerance = 1e-6)
        assertConvert(10_000.0, 1.0, "ha", "m2")
        assertConvert(4184.0, 1.0, "kcal", "J", tolerance = 1e-6)
        assertConvert(3.6e6, 1.0, "kWh", "J", tolerance = 1e-3)
        assertConvert(101_325.0, 1.0, "atm", "Pa", tolerance = 1e-6)
        assertConvert(1073741824.0, 1.0, "GiB", "B", tolerance = 0.0)
        assertConvert(0.5144444444, 1.0, "kn", "mps")
    }

    // ------------------------------------------------------------ 温度：偏移不是比例

    @Test
    fun temperatureOffsetsAreAppliedNotJustScaled() {
        assertConvert(273.15, 0.0, "°C", "K")
        assertConvert(373.15, 100.0, "°C", "K")
        assertConvert(212.0, 100.0, "°C", "°F")
        assertConvert(0.0, 32.0, "°F", "°C")
        assertConvert(-40.0, -40.0, "°C", "°F")
        assertConvert(-273.15, 0.0, "K", "°C")
        assertConvert(37.0, 98.6, "°F", "°C", tolerance = 1e-6)
        // 单纯的"乘以系数"会在这里露出破绽：0°C 换算成 °F 是 32，不是 0
        assertTrue(convert(0.0, "°C", "°F").value != 0.0)
    }

    @Test
    fun temperatureResultCarriesAnOffsetWarning() {
        val note = convert(0.0, "°C", "K").note
        assertTrue(note != null && note.contains("偏移"), "温度换算必须提示偏移，实际：$note")
    }

    // ------------------------------------------------------------ 维度与精度

    @Test
    fun crossDimensionConversionIsRefusedWithBothDimensionNames() {
        val meter = requireNotNull(Units.resolve("m"))
        val kilogram = requireNotNull(Units.resolve("kg"))
        val outcome = Units.convert(1.0, meter, kilogram)
        assertTrue(outcome is Conversion.DimensionMismatch)
        assertEquals(Dimension.LENGTH, outcome.from)
        assertEquals(Dimension.MASS, outcome.to)
    }

    @Test
    fun nonFiniteInputIsRejectedInsteadOfProducingNaN() {
        val meter = requireNotNull(Units.resolve("m"))
        val cm = requireNotNull(Units.resolve("cm"))
        assertTrue(Units.convert(Double.NaN, meter, cm) is Conversion.Invalid)
        assertTrue(Units.convert(Double.POSITIVE_INFINITY, meter, cm) is Conversion.Invalid)
    }

    @Test
    fun precisionNotesAppearOnlyWhereTheCoefficientIsNotExact() {
        assertNull(convert(1.0, "m", "cm").note, "精确换算不该加提示")
        assertTrue(convert(1.0, "尺", "m").note?.contains("市制") == true)
        assertTrue(convert(1.0, "BTU", "J").note?.contains("近似") == true)
        assertTrue(convert(1.0, "yr", "d").note?.contains("近似") == true)
    }

    @Test
    fun displayHidesFloatingNoise() {
        assertEquals("100", convert(1.0, "m", "cm").display)
        assertEquals("2.54", convert(1.0, "in", "cm").display)
        assertEquals("32", convert(0.0, "°C", "°F").display)
    }

    // ------------------------------------------------------------ 往返

    @Test
    fun everyPairOfSameDimensionUnitsRoundTrips() {
        Dimension.entries.forEach { dimension ->
            val units = Units.of(dimension)
            units.forEach { from ->
                units.forEach { to ->
                    val error = Units.roundTripRelativeError(from, to)
                    // 带偏移的温度单位会损失一点有效数字，其余必须非常干净
                    val tolerance = if (dimension == Dimension.TEMPERATURE) 1e-9 else 1e-12
                    assertTrue(
                        error < tolerance,
                        "${from.id} ↔ ${to.id} 往返误差 $error 超过 $tolerance",
                    )
                }
            }
        }
    }

    @Test
    fun baseUnitIsIdentity() {
        Dimension.entries.forEach { dimension ->
            val base = Units.of(dimension).firstOrNull { it.factor == 1.0 && it.offset == 0.0 }
            assertTrue(base != null, "维度 ${dimension.label} 缺少基准单位")
            assertEquals(7.5, Units.toBase(7.5, requireNotNull(base)))
        }
    }
}
