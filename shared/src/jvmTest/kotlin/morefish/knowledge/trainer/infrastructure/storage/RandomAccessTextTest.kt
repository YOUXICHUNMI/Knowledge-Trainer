package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.TextSpan
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「按字节读一段」的真实文件实现。
 *
 * 这里只盯两件事，都是集成测试测不出来的：
 *
 * 1. **批量读的结果必须与逐个读逐字节相同** —— 它内部会把挨得近的几段并成一次读、
 *    再按字节切片解码。切错一个字节，段边界就落在半个汉字上，读出来的是替换符：
 *    看起来"读到了"，内容却是坏的。
 * 2. **越界的那一段是 null，其余照读** —— 批里有一段读不到，不该让整批落空。
 */
class RandomAccessTextTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "kt-raf-${System.nanoTime()}")

    /** 三行：中文（一字三字节）、纯 ASCII、emoji（四字节）。 */
    private val lines = listOf(
        """{"q":"第一题：以下说法正确的是？"}""",
        """{"q":"第二题：ASCII only padding padding padding"}""",
        """{"q":"第三题：😀 emoji 也要能切开"}""",
    )

    @AfterTest
    fun cleanup() {
        runCatching { root.deleteRecursively() }
    }

    /** 按行边界的字节偏移切段 —— 与索引记下来的形态一致。 */
    private fun spansOf(storage: FileTextStorage): List<TextSpan> {
        var offset = 0L
        return lines.map { line ->
            val length = line.encodeToByteArray().size + 1
            TextSpan(offset, length).also { offset += length }
        }
    }

    private fun FileTextStorage.writeFixture() {
        write("bank.ndjson", lines.joinToString("") { it + "\n" })
    }

    @Test
    fun batchReadMatchesReadingEachSpanOnItsOwn() {
        val storage = FileTextStorage(root)
        storage.writeFixture()
        val spans = spansOf(storage)
        assertEquals(storage.byteLength("bank.ndjson"), spans.last().offset + spans.last().length, "夹具要铺满整个文件")

        val batch = storage.readSpans("bank.ndjson", spans)
        assertEquals(spans.size, batch.size)
        spans.forEachIndexed { i, span ->
            assertEquals(storage.readRange("bank.ndjson", span.offset, span.length), batch[i], "第 $i 段批量读与单读不一致")
            assertEquals(lines[i], batch[i]!!.removeSuffix("\n"), "第 $i 段切错了（多字节字符被切开）")
        }
    }

    @Test
    fun anOutOfRangeSpanIsNullWithoutSinkingTheRestOfTheBatch() {
        val storage = FileTextStorage(root)
        storage.writeFixture()
        val fileBytes = storage.byteLength("bank.ndjson")!!

        val batch = storage.readSpans(
            "bank.ndjson",
            listOf(
                TextSpan(0, 10),
                TextSpan(fileBytes - 4, 100), // 越过末尾
                TextSpan(-1, 10), // 负偏移
                TextSpan(0, 0), // 零长度
            ),
        )
        assertEquals(4, batch.size, "结果必须与请求一一对应，不能少几段")
        assertEquals(storage.readRange("bank.ndjson", 0, 10), batch[0])
        assertNull(batch[1])
        assertNull(batch[2])
        assertNull(batch[3])

        // 文件不在：整批都是 null，而不是抛异常
        assertTrue(storage.readSpans("没有这个文件", listOf(TextSpan(0, 4))).all { it == null })
        assertTrue(storage.readSpans("bank.ndjson", emptyList()).isEmpty())
    }

    @Test
    fun nearbySpansAreReadTogetherWithoutLosingTheirBoundaries() {
        val storage = FileTextStorage(root)
        val line = """{"q":"反复出现的同一行，用来验证相邻段会被合并读"}"""
        storage.write("bank.ndjson", (1..300).joinToString("") { line + "\n" })
        val length = line.encodeToByteArray().size + 1

        // 连续 200 行：实现会把它们并成很少几次读，但每一段仍必须精确落在行首
        val spans = (0 until 200).map { TextSpan(it.toLong() * length, length) }
        val batch = storage.readSpans("bank.ndjson", spans)
        assertEquals(200, batch.size)
        batch.forEachIndexed { i, text -> assertEquals(line, text!!.removeSuffix("\n"), "第 $i 段错位") }

        // 批里出现重复偏移时也不该串位
        val repeated = storage.readSpans("bank.ndjson", listOf(TextSpan(0, length), TextSpan(0, length)))
        assertEquals(repeated[0], repeated[1])
        assertEquals(line, repeated[0]!!.removeSuffix("\n"))
    }

    @Test
    fun aSpanCuttingThroughAMultiByteCharacterDecodesToReplacementInsteadOfThrowing() {
        // 端口约定调用方只读整行。真有人切在汉字中间，实现也不该抛异常 ——
        // 解出来是替换符，那一行随后会被 JSON 解析挡掉，界面上看不到坏数据
        val storage = FileTextStorage(root)
        storage.write("bank.ndjson", "中文\n")
        val batch = storage.readSpans("bank.ndjson", listOf(TextSpan(1, 3)))
        val text = assertNotNull(batch.single(), "切在半个字上也该返回一段文本")
        assertTrue(text.contains('\uFFFD'), "半个汉字该解成替换符，实际是「$text」")
    }
}
