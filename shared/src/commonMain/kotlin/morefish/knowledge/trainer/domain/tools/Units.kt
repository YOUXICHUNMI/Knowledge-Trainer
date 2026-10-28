package morefish.knowledge.trainer.domain.tools

import kotlin.math.PI
import kotlin.math.abs

/**
 * 换算的物理维度。跨维度一律拒绝 —— "1 米等于多少千克"没有答案，
 * 与其返回一个看起来像结果的数字，不如直接说做不到。
 */
enum class Dimension(val label: String, val baseSymbol: String) {
    LENGTH("长度", "m"),
    AREA("面积", "m²"),
    VOLUME("体积", "m³"),
    MASS("质量", "kg"),
    TIME("时间", "s"),
    SPEED("速度", "m/s"),
    TEMPERATURE("温度", "K"),
    PRESSURE("压强", "Pa"),
    ENERGY("能量", "J"),
    POWER("功率", "W"),
    ANGLE("角度", "rad"),
    DATA("数据量", "B"),
}

/**
 * 换算系数的可信度。三个等级而非"精确/不精确"两档，是因为
 * "法定市制值"和"查表得到的近似值"性质完全不同：前者不准是设计，后者不准是无奈。
 */
enum class UnitPrecision {
    /** 由定义确定，例如 1 in = 0.0254 m 精确成立。 */
    EXACT,

    /** 法定约定值，例如 1 市斤 = 0.5 kg（中国大陆）。 */
    CONVENTION,

    /** 存在多种取值，只能取常见近似，例如 BTU。 */
    APPROXIMATE,
}

/**
 * 一个单位。
 *
 * 换算走基准单位：[factor] / [offset] 表达 `base = (value + offset) × factor`。
 * 温度必须这么表达：`°C → K` 是 `(t + 273.15) × 1`，`°F → K` 是 `(t + 459.67) × 5/9`，
 * 单纯乘一个系数是很多单位换算器出错的地方。
 */
data class UnitDef(
    val id: String,
    val symbol: String,
    val name: String,
    val dimension: Dimension,
    val factor: Double,
    val offset: Double = 0.0,
    val precision: UnitPrecision = UnitPrecision.EXACT,
    val note: String = "",
    val aliases: List<String> = emptyList(),
) {
    /** 相对基准单位的比例，用于在选择列表里提示"这个单位有多大"。 */
    val scaleHint: String
        get() = when {
            offset != 0.0 -> note.ifBlank { "带偏移，不能按比例换算" }
            factor == 1.0 -> "基准单位"
            factor >= 1.0 -> "1 $symbol = ${formatNumber(factor)} ${dimension.baseSymbol}"
            else -> "1 ${dimension.baseSymbol} = ${formatNumber(1.0 / factor)} $symbol"
        }
}

sealed interface Conversion {
    data class Ok(val value: Double, val display: String, val note: String?) : Conversion
    data class DimensionMismatch(val from: Dimension, val to: Dimension) : Conversion
    data class Invalid(val reason: String) : Conversion
}

/**
 * 离线单位换算目录。
 *
 * 只收录学习场景真会遇到的单位，并把"这个系数有多可信"一并写出来：
 * 换算器给出一个 12 位小数的数字，却不肯说其中后 8 位是估算，是不诚实的。
 */
object Units {

    val all: List<UnitDef> = buildList {
        // ---- 长度（基准 米）----
        add(UnitDef("m", "m", "米", Dimension.LENGTH, 1.0))
        add(UnitDef("km", "km", "千米", Dimension.LENGTH, 1000.0, aliases = listOf("公里")))
        add(UnitDef("dm", "dm", "分米", Dimension.LENGTH, 0.1))
        add(UnitDef("cm", "cm", "厘米", Dimension.LENGTH, 0.01, aliases = listOf("公分")))
        add(UnitDef("mm", "mm", "毫米", Dimension.LENGTH, 0.001))
        add(UnitDef("um", "μm", "微米", Dimension.LENGTH, 1e-6))
        add(UnitDef("nm", "nm", "纳米", Dimension.LENGTH, 1e-9))
        add(UnitDef("in", "in", "英寸", Dimension.LENGTH, 0.0254, aliases = listOf("inch")))
        add(UnitDef("ft", "ft", "英尺", Dimension.LENGTH, 0.3048, aliases = listOf("foot", "feet")))
        add(UnitDef("yd", "yd", "码", Dimension.LENGTH, 0.9144, aliases = listOf("yard")))
        add(UnitDef("mi", "mi", "英里", Dimension.LENGTH, 1609.344, aliases = listOf("mile")))
        add(UnitDef("nmi", "nmi", "海里", Dimension.LENGTH, 1852.0))
        add(
            UnitDef(
                "li", "里", "市里", Dimension.LENGTH, 500.0,
                precision = UnitPrecision.CONVENTION,
                note = "1 市里 = 500 m（中国大陆法定值）",
                aliases = listOf("市里"),
            ),
        )
        add(
            UnitDef(
                "chi", "尺", "市尺", Dimension.LENGTH, 1.0 / 3.0,
                precision = UnitPrecision.CONVENTION,
                note = "1 市尺 = 1/3 m（中国大陆法定值）",
                aliases = listOf("市尺"),
            ),
        )
        add(
            UnitDef(
                "cun", "寸", "市寸", Dimension.LENGTH, 1.0 / 30.0,
                precision = UnitPrecision.CONVENTION,
                note = "1 市寸 = 1/30 m（中国大陆法定值）",
                aliases = listOf("市寸"),
            ),
        )

        // ---- 面积（基准 平方米）----
        add(UnitDef("m2", "m²", "平方米", Dimension.AREA, 1.0))
        add(UnitDef("km2", "km²", "平方千米", Dimension.AREA, 1e6, aliases = listOf("平方公里")))
        add(UnitDef("cm2", "cm²", "平方厘米", Dimension.AREA, 1e-4))
        add(UnitDef("mm2", "mm²", "平方毫米", Dimension.AREA, 1e-6))
        add(UnitDef("ha", "ha", "公顷", Dimension.AREA, 1e4))
        add(
            UnitDef(
                "mu", "亩", "亩", Dimension.AREA, 10_000.0 / 15.0,
                precision = UnitPrecision.CONVENTION,
                note = "1 亩 = 10000/15 m²（中国大陆法定值）",
            ),
        )
        add(UnitDef("acre", "acre", "英亩", Dimension.AREA, 4046.8564224, aliases = listOf("ac")))
        add(UnitDef("ft2", "ft²", "平方英尺", Dimension.AREA, 0.09290304))
        add(UnitDef("in2", "in²", "平方英寸", Dimension.AREA, 0.00064516))

        // ---- 体积（基准 立方米）----
        add(UnitDef("m3", "m³", "立方米", Dimension.VOLUME, 1.0))
        add(UnitDef("L", "L", "升", Dimension.VOLUME, 1e-3, aliases = listOf("l", "liter", "litre")))
        add(UnitDef("mL", "mL", "毫升", Dimension.VOLUME, 1e-6, aliases = listOf("ml")))
        add(UnitDef("cm3", "cm³", "立方厘米", Dimension.VOLUME, 1e-6))
        add(UnitDef("gal_us", "gal(US)", "美制加仑", Dimension.VOLUME, 0.003785411784))
        add(UnitDef("gal_uk", "gal(UK)", "英制加仑", Dimension.VOLUME, 0.00454609))
        add(UnitDef("ft3", "ft³", "立方英尺", Dimension.VOLUME, 0.028316846592))
        add(UnitDef("in3", "in³", "立方英寸", Dimension.VOLUME, 1.6387064e-5))

        // ---- 质量（基准 千克）----
        add(UnitDef("kg", "kg", "千克", Dimension.MASS, 1.0, aliases = listOf("公斤")))
        add(UnitDef("g", "g", "克", Dimension.MASS, 1e-3))
        add(UnitDef("mg", "mg", "毫克", Dimension.MASS, 1e-6))
        add(UnitDef("t", "t", "吨", Dimension.MASS, 1000.0, aliases = listOf("tonne")))
        add(UnitDef("lb", "lb", "磅", Dimension.MASS, 0.45359237, aliases = listOf("pound")))
        add(UnitDef("oz", "oz", "盎司", Dimension.MASS, 0.028349523125, aliases = listOf("ounce")))
        add(
            UnitDef(
                "jin", "斤", "市斤", Dimension.MASS, 0.5,
                precision = UnitPrecision.CONVENTION,
                note = "1 市斤 = 0.5 kg（中国大陆法定值）",
                aliases = listOf("市斤"),
            ),
        )
        add(
            UnitDef(
                "liang", "两", "市两", Dimension.MASS, 0.05,
                precision = UnitPrecision.CONVENTION,
                note = "1 市两 = 0.05 kg（中国大陆法定值）",
                aliases = listOf("市两"),
            ),
        )
        add(UnitDef("ct", "ct", "克拉", Dimension.MASS, 0.0002, aliases = listOf("carat")))

        // ---- 时间（基准 秒）----
        add(UnitDef("s", "s", "秒", Dimension.TIME, 1.0, aliases = listOf("sec")))
        add(UnitDef("ms", "ms", "毫秒", Dimension.TIME, 1e-3))
        add(UnitDef("us", "μs", "微秒", Dimension.TIME, 1e-6))
        add(UnitDef("min", "min", "分钟", Dimension.TIME, 60.0))
        add(UnitDef("h", "h", "小时", Dimension.TIME, 3600.0, aliases = listOf("hour")))
        add(UnitDef("d", "d", "天", Dimension.TIME, 86_400.0, aliases = listOf("day")))
        add(UnitDef("wk", "wk", "周", Dimension.TIME, 604_800.0, aliases = listOf("week")))
        add(
            UnitDef(
                "yr", "yr", "年", Dimension.TIME, 31_536_000.0,
                precision = UnitPrecision.APPROXIMATE,
                note = "按 365 天计，闰年与回归年长度不同",
                aliases = listOf("year"),
            ),
        )

        // ---- 速度（基准 米每秒）----
        add(UnitDef("mps", "m/s", "米每秒", Dimension.SPEED, 1.0))
        add(UnitDef("kph", "km/h", "千米每小时", Dimension.SPEED, 1.0 / 3.6))
        add(UnitDef("mph", "mph", "英里每小时", Dimension.SPEED, 0.44704))
        add(UnitDef("kn", "kn", "节", Dimension.SPEED, 1852.0 / 3600.0, aliases = listOf("knot")))
        add(UnitDef("fps", "ft/s", "英尺每秒", Dimension.SPEED, 0.3048))

        // ---- 温度（基准 开尔文；带偏移，不能按比例换算）----
        // 顺序按使用频率排：默认选中"摄氏度 → 华氏度"，而不是物理上的基准单位开尔文。
        add(
            UnitDef(
                "degC", "°C", "摄氏度", Dimension.TEMPERATURE, 1.0,
                offset = 273.15,
                note = "K = °C + 273.15",
            ),
        )
        add(
            UnitDef(
                "degF", "°F", "华氏度", Dimension.TEMPERATURE, 5.0 / 9.0,
                offset = 459.67,
                note = "K = (°F + 459.67) × 5/9",
            ),
        )
        add(UnitDef("K", "K", "开尔文", Dimension.TEMPERATURE, 1.0, note = "基准单位，无偏移"))
        add(
            UnitDef(
                "degR", "°R", "兰氏度", Dimension.TEMPERATURE, 5.0 / 9.0,
                note = "K = °R × 5/9",
            ),
        )

        // ---- 压强（基准 帕斯卡）----
        add(UnitDef("Pa", "Pa", "帕斯卡", Dimension.PRESSURE, 1.0))
        add(UnitDef("kPa", "kPa", "千帕", Dimension.PRESSURE, 1e3))
        add(UnitDef("MPa", "MPa", "兆帕", Dimension.PRESSURE, 1e6))
        add(UnitDef("bar", "bar", "巴", Dimension.PRESSURE, 1e5))
        add(UnitDef("atm", "atm", "标准大气压", Dimension.PRESSURE, 101_325.0))
        add(UnitDef("mmHg", "mmHg", "毫米汞柱", Dimension.PRESSURE, 133.322387415))
        add(UnitDef("psi", "psi", "磅力每平方英寸", Dimension.PRESSURE, 6894.757293168361))
        add(UnitDef("kgfcm2", "kgf/cm²", "千克力每平方厘米", Dimension.PRESSURE, 98_066.5))

        // ---- 能量（基准 焦耳）----
        add(UnitDef("J", "J", "焦耳", Dimension.ENERGY, 1.0))
        add(UnitDef("kJ", "kJ", "千焦", Dimension.ENERGY, 1e3))
        add(UnitDef("cal", "cal", "卡", Dimension.ENERGY, 4.184))
        add(UnitDef("kcal", "kcal", "千卡", Dimension.ENERGY, 4184.0, aliases = listOf("大卡")))
        add(UnitDef("Wh", "Wh", "瓦时", Dimension.ENERGY, 3600.0))
        add(UnitDef("kWh", "kWh", "千瓦时", Dimension.ENERGY, 3.6e6, aliases = listOf("度")))
        add(UnitDef("eV", "eV", "电子伏", Dimension.ENERGY, 1.602176634e-19))
        add(
            UnitDef(
                "BTU", "BTU", "英热单位", Dimension.ENERGY, 1055.056,
                precision = UnitPrecision.APPROXIMATE,
                note = "BTU 有 1055.06 / 1059.67 等多种定义，这里取 IT 表常用值",
            ),
        )

        // ---- 功率（基准 瓦特）----
        add(UnitDef("W", "W", "瓦特", Dimension.POWER, 1.0))
        add(UnitDef("kW", "kW", "千瓦", Dimension.POWER, 1e3))
        add(UnitDef("MW", "MW", "兆瓦", Dimension.POWER, 1e6))
        add(
            UnitDef(
                "hp", "hp", "米制马力", Dimension.POWER, 735.49875,
                note = "米制马力 735.49875 W；英制马力 745.6999 W",
                aliases = listOf("马力"),
            ),
        )
        add(
            UnitDef(
                "BTUh", "BTU/h", "英热单位每小时", Dimension.POWER, 0.29307107,
                precision = UnitPrecision.APPROXIMATE,
                note = "由 BTU 的近似值推出",
            ),
        )

        // ---- 角度（基准 弧度）----
        add(UnitDef("rad", "rad", "弧度", Dimension.ANGLE, 1.0))
        add(UnitDef("deg", "°", "度", Dimension.ANGLE, PI / 180.0))
        add(UnitDef("grad", "grad", "百分度", Dimension.ANGLE, PI / 200.0))
        add(UnitDef("arcmin", "′", "角分", Dimension.ANGLE, PI / 10_800.0))
        add(UnitDef("arcsec", "″", "角秒", Dimension.ANGLE, PI / 648_000.0))
        add(UnitDef("turn", "turn", "转", Dimension.ANGLE, 2 * PI))

        // ---- 数据量（基准 字节；十进制与二进制前缀分列，不混用）----
        add(UnitDef("B", "B", "字节", Dimension.DATA, 1.0))
        add(UnitDef("bit", "bit", "位", Dimension.DATA, 0.125, aliases = listOf("b")))
        add(UnitDef("KB", "KB", "千字节", Dimension.DATA, 1e3))
        add(UnitDef("MB", "MB", "兆字节", Dimension.DATA, 1e6))
        add(UnitDef("GB", "GB", "吉字节", Dimension.DATA, 1e9))
        add(UnitDef("TB", "TB", "太字节", Dimension.DATA, 1e12))
        add(UnitDef("KiB", "KiB", "KiB", Dimension.DATA, 1024.0))
        add(UnitDef("MiB", "MiB", "MiB", Dimension.DATA, 1024.0 * 1024))
        add(UnitDef("GiB", "GiB", "GiB", Dimension.DATA, 1024.0 * 1024 * 1024))
        add(UnitDef("TiB", "TiB", "TiB", Dimension.DATA, 1024.0 * 1024 * 1024 * 1024))
    }

    fun of(dimension: Dimension): List<UnitDef> = all.filter { it.dimension == dimension }

    fun byId(id: String): UnitDef? = all.firstOrNull { it.id == id }

    /** 从 id / 符号 / 中文名 / 别名里找一个单位，大小写不敏感。 */
    fun resolve(text: String): UnitDef? {
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return null
        return all.firstOrNull {
            it.id.lowercase() == needle ||
                it.symbol.lowercase() == needle ||
                it.name.lowercase() == needle ||
                it.aliases.any { alias -> alias.lowercase() == needle }
        }
    }

    /**
     * 换算主逻辑。
     *
     * 输入不是有限数时返回 [Conversion.Invalid] 而不是 NaN ——
     * 界面上"NaN"只会让人以为软件崩了。
     */
    fun convert(value: Double, from: UnitDef, to: UnitDef): Conversion {
        if (!value.isFinite()) return Conversion.Invalid("请输入一个有限数值")
        if (from.dimension != to.dimension) return Conversion.DimensionMismatch(from.dimension, to.dimension)

        val base = toBase(value, from)
        val result = fromBase(base, to)
        if (!result.isFinite()) return Conversion.Invalid("结果超出可表示范围")
        return Conversion.Ok(result, formatNumber(result), noteFor(from, to))
    }

    fun toBase(value: Double, unit: UnitDef): Double = (value + unit.offset) * unit.factor

    fun fromBase(base: Double, unit: UnitDef): Double = base / unit.factor - unit.offset

    /**
     * 往返误差（相对值）：`a → b → a` 后偏离原值多少。
     * 这是"换算没有写错"最直接的可测量证据，测试里对全部单位两两跑一遍。
     */
    fun roundTripRelativeError(from: UnitDef, to: UnitDef, probe: Double = 1.0): Double {
        val there = convert(probe, from, to)
        if (there !is Conversion.Ok) return Double.NaN
        val back = convert(there.value, to, from)
        if (back !is Conversion.Ok) return Double.NaN
        return abs(back.value - probe) / abs(probe)
    }

    private fun noteFor(from: UnitDef, to: UnitDef): String? {
        val parts = mutableListOf<String>()
        listOf(from, to).forEach { unit ->
            when (unit.precision) {
                UnitPrecision.APPROXIMATE -> parts += "「${unit.symbol}」的系数按常见近似值取，结果用于估算"
                UnitPrecision.CONVENTION -> parts += "「${unit.symbol}」使用中国大陆法定市制值"
                UnitPrecision.EXACT -> Unit
            }
        }
        if (from.offset != 0.0 || to.offset != 0.0) {
            parts += "温度按 (数值 + 偏移) × 系数 换算，不是简单比例"
        }
        return parts.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("；")
    }

    /**
     * 目录自检。单位表是手写的，手写表就会写错 ——
     * 让它在测试里自己检查，比等用户算出 1 公斤 = 2.2 磅再回头查要好。
     */
    fun integrityProblems(): List<String> {
        val problems = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        val symbols = mutableSetOf<String>()

        all.forEach { unit ->
            if (unit.id.isBlank()) problems += "存在空 id 的单位：${unit.name}"
            if (!ids.add(unit.id.lowercase())) problems += "id 重复：${unit.id}"
            if (unit.symbol.isBlank() || unit.name.isBlank()) problems += "符号或名称为空：${unit.id}"
            if (!unit.factor.isFinite() || unit.factor <= 0.0) problems += "换算系数必须为正：${unit.id}"
            if (!unit.offset.isFinite()) problems += "偏移量非法：${unit.id}"
            if (unit.offset != 0.0 && unit.dimension != Dimension.TEMPERATURE) {
                problems += "只有温度允许偏移量，${unit.id} 却是 ${unit.dimension.label}"
            }
            if (!symbols.add("${unit.dimension.name}/${unit.symbol.lowercase()}")) {
                problems += "同一维度内符号重复：${unit.symbol}"
            }
        }

        Dimension.entries.forEach { dimension ->
            val units = of(dimension)
            if (units.isEmpty()) {
                problems += "维度 ${dimension.label} 没有任何单位"
                return@forEach
            }
            val hasBase = units.any { it.factor == 1.0 && it.offset == 0.0 }
            if (!hasBase) problems += "维度 ${dimension.label} 缺少系数为 1 的基准单位"
        }
        return problems
    }
}
