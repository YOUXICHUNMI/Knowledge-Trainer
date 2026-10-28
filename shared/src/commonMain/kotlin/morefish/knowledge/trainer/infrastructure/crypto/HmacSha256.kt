package morefish.knowledge.trainer.infrastructure.crypto

import morefish.knowledge.trainer.infrastructure.archive.Sha256

/**
 * HMAC-SHA256（RFC 2104）。
 *
 * 摊出来的 ipad/opad 可以复用（[context]）：PBKDF2 要拿同一个口令算几十万次，
 * 每次都重新摊一遍纯属浪费 —— 这直接决定"输完口令是等 1 秒还是等 10 秒"。
 */
internal object HmacSha256 {

    private const val BLOCK = 64

    fun digest(key: ByteArray, message: ByteArray): ByteArray = context(key).run(message)

    fun context(key: ByteArray): Context {
        // 超过 64 字节的密钥先哈希一次（RFC 2104 规定），否则右侧补零到 64
        val k = if (key.size > BLOCK) Sha256.digest(key) else key
        val pad = ByteArray(BLOCK)
        k.copyInto(pad)
        val inner = ByteArray(BLOCK) { (pad[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (pad[it].toInt() xor 0x5C).toByte() }
        return Context(inner, outer)
    }

    class Context(private val inner: ByteArray, private val outer: ByteArray) {

        /** H((K ⊕ opad) || H((K ⊕ ipad) || message)) */
        fun run(message: ByteArray): ByteArray {
            val first = ByteArray(inner.size + message.size)
            inner.copyInto(first)
            message.copyInto(first, inner.size)
            val innerHash = Sha256.digest(first)

            val second = ByteArray(outer.size + innerHash.size)
            outer.copyInto(second)
            innerHash.copyInto(second, outer.size)
            return Sha256.digest(second)
        }
    }
}
