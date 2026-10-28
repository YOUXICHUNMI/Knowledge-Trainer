package morefish.knowledge.trainer.infrastructure.crypto

import java.security.SecureRandom

/** 桌面端：JDK 的 `SecureRandom`（Windows 上走 `Windows-PRNG`）。 */
private val jvmRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray {
    require(count >= 0) { "要取的字节数不能是负数" }
    val out = ByteArray(count)
    jvmRandom.nextBytes(out)
    return out
}
