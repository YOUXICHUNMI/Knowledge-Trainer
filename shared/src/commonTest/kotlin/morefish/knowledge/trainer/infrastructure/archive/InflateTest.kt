package morefish.knowledge.trainer.infrastructure.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯 Kotlin inflate 的跨平台一致性测试。
 *
 * 用 zlib 压出来的真实字节流（见 [InflateVectors]）做金标准：
 * 如果实现对 fixed / dynamic Huffman 或 stored 块的理解有偏差，
 * 这里会立刻在**所有平台**（JVM / JS / Wasm / iOS）上暴露。
 */
class InflateTest {

    /** 手写 base64 解码：不想为测试引入实验性 API，也不想依赖平台实现。 */
    private fun decodeBase64(text: String): ByteArray {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ArrayList<Byte>(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=' || ch == '\n' || ch == '\r') continue
            val v = table.indexOf(ch)
            require(v >= 0) { "非法 base64 字符: $ch" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    @Test
    fun matchesZlibForAllVectors() {
        InflateVectors.all.forEach { vector ->
            val compressed = decodeBase64(vector.deflateBase64)
            val plain = Inflate.raw(compressed, 0, compressed.size, vector.plainSize)
            assertNotNull(plain, "解压失败：${vector.name}（块类型 ${vector.blockType}）")
            assertEquals(vector.plainSize, plain.size, "长度不符：${vector.name}")
            assertEquals(
                vector.plainSha256,
                Sha256.hex(plain),
                "内容与 zlib 原文不一致：${vector.name}",
            )
        }
    }

    @Test
    fun coversAllThreeBlockTypes() {
        val types = InflateVectors.all.map { it.blockType }.toSet()
        assertEquals(setOf(0, 1, 2), types, "测试向量必须同时覆盖 stored / fixed / dynamic 三类块")
    }

    @Test
    fun rejectsWrongExpectedSize() {
        // 期望大小写小了：必须失败，不能截断后当成功（否则哈希校验会被绕过）
        val stream = decodeBase64(InflateVectors.all.first { it.name == "long_distance_repeat" }.deflateBase64)
        val real = InflateVectors.all.first { it.name == "long_distance_repeat" }.plainSize
        assertNull(Inflate.raw(stream, 0, stream.size, real - 1))
        // 期望大小写大了：同样必须失败
        assertNull(Inflate.raw(stream, 0, stream.size, real + 1))
    }

    @Test
    fun rejectsTruncatedStream() {
        val vector = InflateVectors.all.first { it.name == "long_distance_repeat" }
        val stream = decodeBase64(vector.deflateBase64)
        assertNull(
            Inflate.raw(stream, 0, stream.size / 2, vector.plainSize),
            "截断的压缩流必须被拒绝",
        )
    }

    @Test
    fun rejectsGarbage() {
        // 全 0xFF 是最常见的"随机坏数据"形态：块类型 3 非法
        val garbage = ByteArray(64) { 0xFF.toByte() }
        assertNull(Inflate.raw(garbage, 0, garbage.size, 32))
    }

    @Test
    fun rejectsNegativeOrOversizedRequest() {
        val stream = decodeBase64(InflateVectors.all.first { it.name == "empty" }.deflateBase64)
        assertNull(Inflate.raw(stream, 0, stream.size, -1))
        // 单次请求超过上限（64 MiB）：直接拒绝，不尝试分配
        assertNull(Inflate.raw(stream, 0, stream.size, 65 * 1024 * 1024))
    }

    @Test
    fun handlesOffsetIntoBuffer() {
        // ZIP 条目数据是"大字节数组里的一段"，偏移处理必须正确
        val vector = InflateVectors.all.first { it.name == "chinese_utf8" }
        val payload = decodeBase64(vector.deflateBase64)
        val padded = ByteArray(payload.size + 10) { 0x7F }
        payload.copyInto(padded, 5)
        val plain = Inflate.raw(padded, 5, payload.size, vector.plainSize)
        assertNotNull(plain)
        assertEquals(vector.plainSha256, Sha256.hex(plain))
    }

    @Test
    fun throwsNothingOnRandomNoise() {
        // 解压器面对任意噪声只能返回 null，绝不能抛异常把导入流程炸掉
        val rnd = RandomLike(12345)
        repeat(200) {
            val noise = ByteArray(64) { rnd.next().toByte() }
            val result = Inflate.raw(noise, 0, noise.size, 128)
            if (result != null) {
                assertTrue(result.size == 128)
            }
        }
    }

    /** 不依赖 kotlin.random 的具体算法，只要一个能产字节的伪随机即可。 */
    private class RandomLike(seed: Int) {
        private var state = if (seed == 0) 1 else seed
        fun next(): Int {
            state = state * 1103515245 + 12345
            return (state ushr 16) and 0xFF
        }
    }
}
