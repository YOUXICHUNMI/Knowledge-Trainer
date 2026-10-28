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

    /** FNV-1a 64 位，稳定、跨平台一致、不依赖 JVM 实现。 */
    fun of(text: String): String {
        var hash = -3750763034362895579L // FNV offset basis 0xcbf29ce484222325
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    /** 与语言、显示顺序无关的题目集合指纹。 */
    fun ofQuestions(parts: List<String>): String = of(parts.sorted().joinToString("\u0000"))
}
