package morefish.knowledge.trainer.infrastructure.crypto

/**
 * 网页端没有数据保险库（数据在内存里），走不到这里。
 *
 * 将来要支持，用 `crypto.getRandomValues(new Uint8Array(n))`。
 */
actual fun secureRandomBytes(count: Int): ByteArray = throw UnsupportedOperationException(
    "网页端还没有备份功能，也没有接入安全随机源（secureRandomBytes）",
)
