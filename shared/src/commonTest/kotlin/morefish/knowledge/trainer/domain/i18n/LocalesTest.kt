package morefish.knowledge.trainer.domain.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalesTest {

    @Test
    fun normalizesCaseAndSeparators() {
        // 语言小写、文字首字母大写、地区大写：同一个标签只能有一种写法，
        // 否则 zh-hans 与 zh-Hans 会被当成两种语言各存一份
        assertEquals("zh-Hans-CN", LocaleTag.parse("zh-hans-cn")?.value)
        assertEquals("zh-Hans", LocaleTag.parse("zh_Hans")?.value)
        assertEquals("en-US", LocaleTag.parse("EN-us")?.value)
        assertEquals("es-419", LocaleTag.parse("es-419")?.value)
        assertEquals("zh-Hans", LocaleTag.parse("  zh-Hans  ")?.value)
    }

    @Test
    fun rejectsWhatItCannotRepresent() {
        // 变体/扩展/私有段一律拒绝：收下来再悄悄丢掉后半截，会把两种语言撞成一个
        listOf("", "   ", "zh-Hans-CN-1996", "x-private", "12", "zh-", "-zh", "zh-Hans-Hans", "zh-Hans-")
            .forEach { assertNull(LocaleTag.parse(it), "应拒绝「$it」") }
    }

    @Test
    fun exactMatchWins() {
        val pick = pickLocale("en", listOf("zh-Hans", "en"))
        assertEquals("en", pick?.tag?.value)
        assertEquals(LocaleMatch.EXACT, pick?.match)
    }

    @Test
    fun languageOnlyFallbackNeedsUniqueCandidate() {
        // 只有一种中文时，请求 zh 落到 zh-Hans 是合理的
        assertEquals("zh-Hans", pickLocale("zh", listOf("zh-Hans", "en"))?.tag?.value)
        assertEquals(LocaleMatch.LANGUAGE, pickLocale("zh", listOf("zh-Hans"))?.match)
        // 简繁都在时不做任何猜测：猜错就是给繁体读者看简体，比"找不到"更糟
        assertNull(pickLocale("zh", listOf("zh-Hans", "zh-Hant")))
    }

    @Test
    fun unknownLanguageHasNoPick() {
        assertNull(pickLocale("ja", listOf("zh-Hans", "en")))
        assertNull(pickLocale("not a tag", listOf("en")))
    }

    @Test
    fun labelsCarryTheTag() {
        assertTrue(localeLabel("zh-Hans").contains("简体中文"))
        // 名字给人看，标签给机器核对：只写"简体中文"没法确认到底是 zh-Hans 还是 zh-CN
        assertTrue(localeLabel("zh-Hans").contains("zh-Hans"))
        // 没收录的语言不硬造译名
        assertEquals("sw", localeLabel("sw"))
    }

    @Test
    fun coverageTellsTheTruth() {
        val partial = LocaleCoverage("en", 2, 6)
        assertEquals(4, partial.missing)
        assertFalse(partial.complete)
        assertEquals("2/6", partial.summary)

        val full = LocaleCoverage("zh-Hans", 6, 6)
        assertTrue(full.complete)
        assertEquals("完整", full.summary)

        // 空库不该算出 NaN 或 100%
        assertEquals(0.0, LocaleCoverage("en", 0, 0).ratio)
    }
}
