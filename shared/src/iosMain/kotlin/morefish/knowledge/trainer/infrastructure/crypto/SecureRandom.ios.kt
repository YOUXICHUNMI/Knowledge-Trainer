package morefish.knowledge.trainer.infrastructure.crypto

/**
 * iOS 目前没有数据保险库（数据在内存里，没有可备份的东西），所以走不到这里。
 *
 * 将来要支持 iOS 备份，把这里换成
 * `SecRandomCopyBytes(kSecRandomDefault, count, buf)`（`platform.Security`），
 * **不要**退化成 `Random.Default`：nonce 可预测 = GCM 直接失效。
 */
actual fun secureRandomBytes(count: Int): ByteArray = throw UnsupportedOperationException(
    "iOS 端还没有备份功能，也没有接入安全随机源（secureRandomBytes）",
)
