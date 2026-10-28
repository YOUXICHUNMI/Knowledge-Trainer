package morefish.knowledge.trainer.domain.i18n

/**
 * BCP 47 语言标签的**受限**实现：只认 `language[-Script][-REGION]` 三段。
 *
 * 为什么不用全量 BCP 47：这个项目的多语言只用于"题库文案有哪几种语言"，
 * 变体（`-1996`）、扩展（`-u-ca-gregory`）与私有段（`-x-...`）没有任何语义位置。
 * 与其接收下来再悄悄丢掉后半截（那会让 `zh-Hans-1996` 和 `zh-Hans` 撞成同一个），
 * 不如明确拒绝，并让报错写清楚只支持三段。
 *
 * 大小写按 BCP 47 的惯例归一：语言小写、文字首字母大写、地区大写。
 * `zh-hans-cn` 与 `zh-Hans-CN` 是同一个标签，不能因为它们写法不同就当成两种语言。
 */
data class LocaleTag(
    val language: String,
    val script: String? = null,
    val region: String? = null,
) {
    /** 归一化后的规范写法，落盘与比较都用它。 */
    val value: String
        get() = buildString {
            append(language)
            script?.let { append('-').append(it) }
            region?.let { append('-').append(it) }
        }

    override fun toString(): String = value

    companion object {
        private val LANGUAGE = Regex("^[A-Za-z]{2,3}$")
        private val SCRIPT = Regex("^[A-Za-z]{4}$")
        private val REGION_ALPHA = Regex("^[A-Za-z]{2}$")
        private val REGION_DIGIT = Regex("^[0-9]{3}$")

        /** 解析失败返回 null；调用方负责把"为什么失败"写成人话。 */
        fun parse(raw: String): LocaleTag? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            // 兼容 Java Locale.toString() 这类下划线写法：归一后再校验，不直接拒绝
            val parts = text.replace('_', '-').split('-')
            if (parts.isEmpty() || parts.size > 3) return null
            if (!LANGUAGE.matches(parts[0])) return null

            var script: String? = null
            var region: String? = null
            for (part in parts.drop(1)) {
                when {
                    SCRIPT.matches(part) && script == null && region == null ->
                        script = part.lowercase().replaceFirstChar { it.uppercase() }
                    (REGION_ALPHA.matches(part) || REGION_DIGIT.matches(part)) && region == null ->
                        region = part.uppercase()
                    else -> return null
                }
            }
            return LocaleTag(parts[0].lowercase(), script, region)
        }

        fun isValid(raw: String): Boolean = parse(raw) != null
    }
}

/** 一次语言匹配的结果类型。区分它，是为了让界面能说清"这是同一种语言"还是"退到了同语种"。 */
enum class LocaleMatch(val label: String) {
    /** 标签完全一致（归一化后）。 */
    EXACT("完全匹配"),

    /** 只有主语言相同（如请求 `zh` 命中 `zh-Hans`）。 */
    LANGUAGE("同语种"),

    NONE("不匹配"),
}

data class LocalePick(val tag: LocaleTag, val match: LocaleMatch)

/**
 * 在候选语言里找最合适的一个。
 *
 * 关键规则：**同语种回退只在候选唯一时发生**。
 * 库里同时有 `zh-Hans` 和 `zh-Hant` 时，请求 `zh` 不做任何猜测 ——
 * 猜错就是把简体给繁体读者，或者反过来，比"找不到"更糟。
 */
fun pickLocale(request: String, available: Collection<String>): LocalePick? {
    val want = LocaleTag.parse(request) ?: return null
    val parsed = available.mapNotNull { raw -> LocaleTag.parse(raw)?.let { raw to it } }
    parsed.firstOrNull { it.second == want }?.let { return LocalePick(it.second, LocaleMatch.EXACT) }
    val sameLanguage = parsed.filter { it.second.language == want.language }
    return if (sameLanguage.size == 1) LocalePick(sameLanguage.single().second, LocaleMatch.LANGUAGE) else null
}

/** 语言的中文名。只收录题库与界面里真的会出现的；其余直接显示标签，不硬造译名。 */
private val LOCALE_NAMES: Map<String, String> = mapOf(
    "zh" to "中文",
    "zh-Hans" to "简体中文",
    "zh-Hant" to "繁體中文",
    "en" to "英语",
    "ja" to "日语",
    "ko" to "韩语",
    "fr" to "法语",
    "de" to "德语",
    "es" to "西班牙语",
    "ru" to "俄语",
    "pt" to "葡萄牙语",
    "it" to "意大利语",
    "ar" to "阿拉伯语",
)

fun localeName(tag: String): String {
    val parsed = LocaleTag.parse(tag) ?: return tag
    return LOCALE_NAMES[parsed.value]
        ?: LOCALE_NAMES[parsed.language]
        ?: parsed.value
}

/**
 * 界面上的语言显示：`简体中文 · zh-Hans`。
 *
 * 名字给人看，标签给机器核对 —— 只写"简体中文"时，读者没法确认到底是 `zh-Hans` 还是 `zh-CN`。
 */
fun localeLabel(tag: String): String {
    val parsed = LocaleTag.parse(tag) ?: return tag
    val name = localeName(parsed.value)
    return if (name == parsed.value) parsed.value else "$name · ${parsed.value}"
}

/**
 * 译文缺失时怎么办。
 *
 * 默认 [SKIP]：没有译文的题直接不抽。把另一种语言当译文塞给用户，
 * 在对错判断上是有害的 —— 一道英文题配着中文答案，判题与阅读都会错位。
 */
enum class LocaleFallback(val wireName: String, val label: String, val hint: String) {
    SKIP(
        "skip",
        "只练有译文的题",
        "没有该语言译文的题目会被跳过，本题库不会出现另一种语言",
    ),
    ORIGINAL(
        "original",
        "缺译文时看原文",
        "译文缺失的题显示原文并标注原语言 —— 看得懂再答，判题用的是原文答案",
    ),
}

/** 某语言在某个题库里的覆盖情况。 */
data class LocaleCoverage(
    val locale: String,
    val covered: Int,
    val total: Int,
) {
    val missing: Int get() = (total - covered).coerceAtLeast(0)
    val complete: Boolean get() = covered >= total
    val ratio: Double get() = if (total <= 0) 0.0 else (covered.toDouble() / total).coerceIn(0.0, 1.0)

    /** 列表里的简短写法：完整就写"完整"，否则写 `2/6`。 */
    val summary: String get() = if (complete) "完整" else "$covered/$total"

    companion object {
        fun of(locale: String, covered: Int, total: Int) = LocaleCoverage(locale, covered.coerceAtLeast(0), total)
    }
}

/** 把一组覆盖记录里缺翻译最多的那一种挑出来，用于首页提示。 */
fun List<LocaleCoverage>.worstCoverage(): LocaleCoverage? =
    filter { !it.complete }.minByOrNull { it.ratio }
