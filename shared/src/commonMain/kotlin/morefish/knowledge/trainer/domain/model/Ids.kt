package morefish.knowledge.trainer.domain.model

import kotlin.random.Random

/**
 * 标识符生成。
 *
 * 题库、题目、选项的 ID 必须在跨设备、跨导入之间保持稳定语义，
 * 因此生成后一律视为不透明字符串，不允许用"当前显示序号"充当身份。
 */
object Ids {

    private const val HEX = "0123456789abcdef"

    /** RFC 4122 version 4 UUID。 */
    fun uuid(): String {
        val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(HEX[(bytes[i].toInt() shr 4) and 0x0F])
            sb.append(HEX[bytes[i].toInt() and 0x0F])
        }
        return sb.toString()
    }

    fun prefixed(prefix: String, length: Int = 12): String = buildString {
        append(prefix).append('-')
        repeat(length) { append(HEX[Random.nextInt(16)]) }
    }
}

/** 内容指纹：用于判断"同一份材料是否已导入过"，不是加密哈希。 */
object Fingerprint {

    /**
     * 目录里那串指纹是按哪一版算法算出来的。
     *
     * 1 = 首版：把每题指纹**排序**后拼成一串再哈希。好处是天然与顺序无关，
     *     坏处是只能整表重算 —— 十万题的库加一道题要重算 200ms，
     *     而这次真正新增的内容只有 512 字节。
     * 2 = 现在：把每题指纹**逐项相加**（模 2^64）。同样是顺序无关的多重集指纹，
     *     但可以增量更新：加一道题就是"旧值 + 新题指纹"，与库多大无关。
     *
     * 换算法的代价是老值不再可比，所以目录里记着版本，比对时按版本决定要不要重算
     * （见 `JsonBankRepository.findByFingerprint` 与 `ImportBankUseCase`）。
     */
    const val CURRENT_VERSION = 2

    /** 首版算法。只用于**认出**升级之前写下的值，不再产生新值。 */
    const val LEGACY_VERSION = 1

    /** FNV-1a 64 位，稳定、跨平台一致、不依赖 JVM 实现。 */
    fun of(text: String): String = hex(rawOf(text))

    /** 同一套 FNV-1a 的数值形式，供多重集指纹逐项累加。 */
    fun rawOf(text: String): ULong {
        var hash = -3750763034362895579L // FNV offset basis 0xcbf29ce484222325
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash.toULong()
    }

    fun hex(value: ULong): String = value.toString(16).padStart(16, '0')

    /**
     * 与语言、显示顺序无关的题目集合指纹。
     *
     * 用"逐项相加"而不是"排序后拼接"，换来的是可增量（见 [Accumulator]）；
     * 代价是抗构造碰撞比整串哈希弱 —— 它只是查重启发式，从来不是安全边界。
     */
    fun ofQuestions(parts: List<String>): String =
        Accumulator().also { acc -> parts.forEach(acc::add) }.value()

    /** 在已有集合指纹上追加一项。要求 [existing] 是本版算法写下的值。 */
    fun plus(existing: String, part: String): String =
        hex((existing.toULongOrNull(16) ?: 0uL) + rawOf(part))

    /**
     * 首版算法：排序后拼接。**只用于比对升级前写下的值**。
     *
     * 留在这里而不是删掉，是因为用户设备上可能还躺着旧版本算出的指纹：
     * 按 id 找到库之后拿它判断"内容是否相同"，直接比会把同一个包判成"不同版本"。
     */
    fun legacyOfQuestions(parts: List<String>): String = of(parts.sorted().joinToString("\u0000"))

    /**
     * 多重集指纹的累加器：加一项是 O(1)，且与 [ofQuestions] 的整表结果逐位相同。
     *
     * 写库时边序列化边累加，不必先把十万题的指纹攒成一个列表再排序。
     */
    class Accumulator {
        private var sum: ULong = 0uL

        fun add(part: String) {
            sum += rawOf(part)
        }

        fun value(): String = hex(sum)
    }
}
