package morefish.knowledge.trainer.infrastructure.crypto

/**
 * Wasm 端没有数据保险库（数据在内存里），走不到这里。
 *
 * 将来要支持，用 `crypto.getRandomValues`（Wasm 侧要经 JS interop）。
 */
actual fun secureRandomBytes(count: Int): ByteArray = throw UnsupportedOperationException(
    "Wasm 端还没有备份功能，也没有接入安全随机源（secureRandomBytes）",
)
