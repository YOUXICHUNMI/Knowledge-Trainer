package morefish.knowledge.trainer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvenanceTest {

    @Test
    fun originTakesTheFourDeclaredValues() {
        assertEquals(ProvenanceOrigin.AUTHORED, ProvenanceOrigin.fromWire("authored"))
        assertEquals(ProvenanceOrigin.ADAPTED, ProvenanceOrigin.fromWire("adapted"))
        assertEquals(ProvenanceOrigin.IMPORTED, ProvenanceOrigin.fromWire("imported"))
        assertEquals(ProvenanceOrigin.AI_ASSISTED, ProvenanceOrigin.fromWire("ai-assisted"))
        // 下划线写法是作者手写时的常见变体，与连字符同义
        assertEquals(ProvenanceOrigin.AI_ASSISTED, ProvenanceOrigin.fromWire("ai_assisted"))
        assertEquals(ProvenanceOrigin.AUTHORED, ProvenanceOrigin.fromWire(" AUTHored "))
    }

    @Test
    fun unknownOriginIsUnspecifiedNotTheClosestGuess() {
        // 关键不变量：拼错不能"猜一个最接近的"。猜错等于替作者声明了他没说过的事，
        // 而这种错误事后无法从数据里看出来。校验器会另报 ORIGIN_INVALID。
        for (bad in listOf("copied", "ai", "original", "0", "true")) {
            assertEquals(ProvenanceOrigin.UNSPECIFIED, ProvenanceOrigin.fromWire(bad), bad)
        }
    }

    @Test
    fun missingOriginIsUnspecified() {
        assertEquals(ProvenanceOrigin.UNSPECIFIED, ProvenanceOrigin.fromWire(null))
        assertEquals(ProvenanceOrigin.UNSPECIFIED, ProvenanceOrigin.fromWire(""))
        assertEquals(ProvenanceOrigin.UNSPECIFIED, ProvenanceOrigin.fromWire("   "))
    }

    @Test
    fun unspecifiedIsNotRenderedAsAuthored() {
        // 这条守的是文案本身：把"没说"写成"原创"是这个项目最不愿意犯的错
        assertEquals("来源未标注", ProvenanceOrigin.UNSPECIFIED.label)
        assertEquals("作者原创", ProvenanceOrigin.AUTHORED.label)
        assertTrue(ProvenanceOrigin.UNSPECIFIED.wireName.isEmpty(), "未声明必须序列化成空串，不能写成 unspec")
    }

    @Test
    fun contentFingerprintIgnoresProvenance() {
        val base = question("q1")
        val withSource = base.copy(
            origin = ProvenanceOrigin.ADAPTED,
            sourceId = "marx",
            sourceLocator = "第一章",
        )
        // 出处是"关于这道题的说明"，不是"这道题是什么"。
        // 算进指纹的后果：作者补一个漏写的出处，补丁的逐题锚点全部失效。
        assertEquals(base.contentFingerprint(), withSource.contentFingerprint())
    }

    @Test
    fun translatingKeepsProvenance() {
        val source = question("q1").copy(
            origin = ProvenanceOrigin.IMPORTED,
            sourceId = "s1",
            sourceLocator = "p.42",
            translations = mapOf(
                "en" to QuestionText(
                    locale = "en",
                    promptMarkdown = "What is practice?",
                    optionTexts = mapOf("o1" to "a", "o2" to "b"),
                ),
            ),
        )
        val localized = source.localized("en", "zh-Hans") ?: error("应当有英文")
        assertEquals("What is practice?", localized.promptMarkdown)
        // 翻译不会让一道题的来源变掉
        assertEquals(ProvenanceOrigin.IMPORTED, localized.origin)
        assertEquals("s1", localized.sourceId)
        assertEquals("p.42", localized.sourceLocator)
    }

    @Test
    fun summarySeparatesUndeclaredFromSourced() {
        val questions = listOf(
            question("q1").copy(origin = ProvenanceOrigin.AUTHORED),
            question("q2").copy(origin = ProvenanceOrigin.ADAPTED, sourceId = "s1", sourceLocator = "p1"),
            question("q3").copy(origin = ProvenanceOrigin.AI_ASSISTED),
            question("q4"), // 什么都没说
        )
        val summary = ProvenanceSummary.of(questions)
        assertEquals(4, summary.total)
        assertEquals(1, summary.withSource)
        // q4 完全没说。q3 说了"AI 参与生成"但没给具体出处 —— 那是另一种缺失：
        // 它至少告诉了读者该警惕什么，所以不算"来源未标注"。
        assertEquals(1, summary.undeclared)
        assertEquals(1, summary.byOrigin[ProvenanceOrigin.AUTHORED])
        assertEquals(1, summary.aiAssisted)
    }

    @Test
    fun sourcedPercentUsesAllQuestionsAsDenominator() {
        // 只报"有几道有出处"会让人把剩下的默认成"作者原创"
        val questions = listOf(
            question("q1").copy(sourceId = "s1", sourceLocator = "p1"),
            question("q2"),
            question("q3"),
            question("q4"),
        )
        assertEquals(25, ProvenanceSummary.of(questions).sourcedPercent)
        assertEquals(0, ProvenanceSummary.of(emptyList()).sourcedPercent)
    }

    @Test
    fun sourceLookupTreatsBlankAsMissing() {
        val provenance = BankProvenance(
            sources = listOf(PackageSource("s1", "甲教材", edition = "2020", author = "某某")),
        )
        assertNull(provenance.source(null))
        assertNull(provenance.source(""))
        assertNull(provenance.source("  "))
        assertNull(provenance.source("s9"))
        assertEquals("甲教材 · 2020", provenance.source("s1")?.shortLabel)
    }

    @Test
    fun shortLabelSkipsEmptyParts() {
        assertEquals("只有标题", PackageSource("s1", "只有标题").shortLabel)
        // 不能出现"甲 ·  · "这种由空字段拼出来的分隔符
        assertEquals("甲 · 版次", PackageSource("s1", "甲", edition = "版次").shortLabel)
    }

    private fun question(id: String) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "题干 $id",
        options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
        correctOptionIds = setOf("o2"),
    )
}
