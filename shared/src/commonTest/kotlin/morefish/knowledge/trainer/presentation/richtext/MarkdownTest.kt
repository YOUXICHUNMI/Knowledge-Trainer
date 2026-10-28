package morefish.knowledge.trainer.presentation.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTest {

    @Test
    fun plainTextStaysPlain() {
        val samples = listOf(
            "我国专门针对无线电管理的行政法规及其制定机构是：",
            "在 144 MHz 频段，最大发射功率为 25 W。",
            "A/B/C 三个选项都成立",
            "使用 3.5mm 插头（不平衡）",
            "",
        )
        samples.forEach { assertTrue(Markdown.isPlain(it), "应判为纯文本：$it") }
    }

    @Test
    fun markdownMarkersDisableFastPath() {
        listOf("# 标题", "**加粗**", "`code`", "\$x^2\$", "- 列表", "1. 有序", "> 引用", "---").forEach {
            assertFalse(Markdown.isPlain(it), "应判为含标记：$it")
        }
    }

    @Test
    fun parsesHeadingsAndParagraphs() {
        val blocks = Markdown.parse("# 一、选择题\n\n这是正文第一行\n第二行")
        assertEquals(2, blocks.size)
        val heading = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals(1, heading.level)
        val paragraph = assertIs<MdBlock.Paragraph>(blocks[1])
        val text = assertIs<MdInline.Text>(paragraph.inlines.single())
        assertEquals("这是正文第一行\n第二行", text.text)
    }

    @Test
    fun parsesBoldItalicAndCode() {
        val blocks = Markdown.parse("**必须**做到 *可以* 做到 `dBm` 换算")
        val inlines = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
        assertTrue(inlines.any { it is MdInline.Text && it.bold && it.text == "必须" })
        assertTrue(inlines.any { it is MdInline.Text && it.italic && it.text == "可以" })
        assertTrue(inlines.any { it is MdInline.Code && it.text == "dBm" })
    }

    @Test
    fun underscoreInsideIdentifierIsNotItalic() {
        val blocks = Markdown.parse("变量 max_output_power 保持不变")
        val inlines = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
        val text = inlines.joinToString("") { (it as? MdInline.Text)?.text ?: "" }
        assertEquals("变量 max_output_power 保持不变", text)
        assertTrue(inlines.none { it is MdInline.Text && it.italic })
    }

    @Test
    fun parsesInlineMathAndKeepsAsteriskInside() {
        val blocks = Markdown.parse("功率 \$P = U \\times I\$ 的单位是 W")
        val inlines = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
        val math = inlines.filterIsInstance<MdInline.Math>().single()
        assertEquals("P = U \\times I", math.tex)
        assertEquals("功率 ", assertIs<MdInline.Text>(inlines.first()).text)
    }

    @Test
    fun unclosedMathFallsBackToLiteral() {
        val blocks = Markdown.parse("价格是 \$100 元")
        val inlines = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
        assertTrue(inlines.none { it is MdInline.Math })
        assertEquals("价格是 \$100 元", inlines.filterIsInstance<MdInline.Text>().joinToString("") { it.text })
    }

    @Test
    fun parsesListsAndQuote() {
        val blocks = Markdown.parse("- 第一项\n- 第二项\n\n1. 甲\n2. 乙\n\n> 引用一句")
        assertEquals(3, blocks.size)
        assertEquals(2, assertIs<MdBlock.BulletList>(blocks[0]).items.size)
        val ordered = assertIs<MdBlock.OrderedList>(blocks[1])
        assertEquals(1, ordered.start)
        assertEquals(2, ordered.items.size)
        assertIs<MdBlock.Quote>(blocks[2])
    }

    @Test
    fun parsesFencedCodeAndMathBlock() {
        val blocks = Markdown.parse("```kotlin\nval a = 1\n```\n\n\$\$\nf = \\frac{1}{2\\pi\\sqrt{LC}}\n\$\$")
        assertEquals(2, blocks.size)
        val code = assertIs<MdBlock.Code>(blocks[0])
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1", code.text)
        val math = assertIs<MdBlock.MathBlock>(blocks[1])
        assertTrue(math.tex.contains("frac"))
    }

    @Test
    fun keepsEscapedCharacters() {
        val blocks = Markdown.parse("使用 \\*星号\\* 表示脚注")
        val text = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
            .filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals("使用 *星号* 表示脚注", text)
    }

    @Test
    fun linkIsParsedButShownAsText() {
        val blocks = Markdown.parse("见 [官方条例](https://example.org/a)")
        val link = assertIs<MdBlock.Paragraph>(blocks.single()).inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("官方条例", link.text)
        assertEquals("https://example.org/a", link.url)
    }

    @Test
    fun excerptStripsMarkersForListRows() {
        val excerpt = Markdown.plainExcerpt("## 谐振频率\n\n已知 **\$f_0\$** 与 \$L\$ 的关系：\n\n- 第一条\n- 第二条", 60)
        assertFalse(excerpt.contains("**"))
        assertFalse(excerpt.contains("#"))
        assertFalse(excerpt.contains("\$"))
        assertTrue(excerpt.contains("谐振频率"))
        assertTrue(excerpt.contains("第一条"))
    }

    @Test
    fun excerptReplacesFormulasWithPlaceholder() {
        val excerpt = Markdown.plainExcerpt(
            "自感电动势 \$e = -L\\dfrac{\\Delta I}{\\Delta t}\$ 的方向由楞次定律判断",
            60,
        )
        assertTrue(excerpt.contains("公式"), "列表行里不该出现 TeX 源码：$excerpt")
        assertFalse(excerpt.contains("dfrac"))
        assertFalse(excerpt.contains("\$"))
    }

    @Test
    fun excerptKeepsPlainTextAsIs() {
        assertEquals("在 144 MHz 频段，最大发射功率为 25 W。", Markdown.plainExcerpt("在 144 MHz 频段，最大发射功率为 25 W。", 40))
        assertEquals(10, Markdown.plainExcerpt("一二三四五六七八九十十一", 10).length)
    }

    @Test
    fun mathInsideEmphasisIsStillMath() {
        val blocks = Markdown.parse("**\$E = mc^2\$**")
        val inlines = assertIs<MdBlock.Paragraph>(blocks.single()).inlines
        assertNull(inlines.firstOrNull { it is MdInline.Text && it.text.contains("mc^2") })
    }
}

class MathSourceParserTest {

    private fun render(tex: String): MathNode = MathSourceParser(tex).parse()

    private fun row(tex: String): List<MathNode> = assertIs<MathNode.Row>(render(tex)).items

    /** 单个原子的分组会被解析器解包，取唯一子节点比较稳妥。 */
    private fun only(node: MathNode): MathNode = if (node is MathNode.Row) node.items.single() else node

    @Test
    fun parsesFraction() {
        val items = row("\\frac{a}{b}")
        val frac = assertIs<MathNode.Frac>(items.single())
        assertEquals("a", assertIs<MathNode.Sym>(only(frac.num)).text)
    }

    @Test
    fun bindsScriptsToBaseOnly() {
        val items = row("x^2 + y")
        val script = assertIs<MathNode.Script>(items[0])
        assertEquals("x", assertIs<MathNode.Sym>(script.base).text)
        assertEquals("2", assertIs<MathNode.Sym>(script.sup!!).text)
        assertEquals(3, items.size)
    }

    @Test
    fun parsesSqrtWithIndex() {
        val items = row("\\sqrt[3]{x+1}")
        val radical = assertIs<MathNode.Radical>(items.single())
        assertEquals("3", assertIs<MathNode.Sym>(only(radical.index!!)).text)
        assertIs<MathNode.Row>(radical.body)
    }

    @Test
    fun parsesLeftRight() {
        val items = row("\\left(\\frac{1}{2}\\right)^2")
        val script = assertIs<MathNode.Script>(items.single())
        val fenced = assertIs<MathNode.Fenced>(script.base)
        assertEquals("(", fenced.open)
        assertEquals(")", fenced.close)
    }

    @Test
    fun mapsCommandsToUnicode() {
        val items = row("\\alpha \\le \\infty \\times")
        assertEquals("\u03B1", assertIs<MathNode.Sym>(items[0]).text)
        assertEquals("\u2264", assertIs<MathNode.Sym>(items[1]).text)
        assertEquals("\u221E", assertIs<MathNode.Sym>(items[2]).text)
        assertEquals("\u00D7", assertIs<MathNode.Sym>(items[3]).text)
    }

    @Test
    fun bigOpKeepsLimits() {
        val node = assertIs<MathNode.BigOp>(row("\\sum_{i=1}^{n} i").first())
        assertEquals("\u2211", node.text)
        assertTrue(node.sub != null)
        assertTrue(node.sup != null)
    }

    @Test
    fun textCommandKeepsSpaces() {
        val words = assertIs<MathNode.Words>(row("\\text{当且仅当 }").single())
        assertEquals("当且仅当 ", words.text)
    }

    @Test
    fun unknownCommandIsShownNotDropped() {
        val unsupported = assertIs<MathNode.Unsupported>(row("\\foo{x}").first())
        assertEquals("\\foo", unsupported.text)
    }

    @Test
    fun parsesMatrix() {
        val grid = assertIs<MathNode.Grid>(row("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}").single())
        assertEquals("pmatrix", grid.env)
        assertEquals(2, grid.rows.size)
        assertEquals(2, grid.rows[0].size)
    }

    @Test
    fun parsesCases() {
        val grid = assertIs<MathNode.Grid>(row("\\begin{cases} x & x>0 \\\\ 0 & \\text{否则} \\end{cases}").single())
        assertEquals("cases", grid.env)
        assertEquals(2, grid.rows.size)
    }

    @Test
    fun doesNotLoopOnUnbalancedBraces() {
        val items = row("\\frac{a}{b")
        assertTrue(items.isNotEmpty())
        val items2 = row("{{{")
        assertTrue(items2.isEmpty() || items2.size <= 1)
    }
}
