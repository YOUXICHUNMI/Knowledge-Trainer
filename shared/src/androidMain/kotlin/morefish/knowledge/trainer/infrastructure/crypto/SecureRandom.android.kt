package morefish.knowledge.trainer.infrastructure.crypto

import java.security.SecureRandom

/** Android：同样走 `SecureRandom`（由系统 provider 提供）。 */
private val androidRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray {
    require(count >= 0) { "要取的字节数不能是负数" }
    val out = ByteArray(count)
    androidRandom.nextBytes(out)
    return out
}
