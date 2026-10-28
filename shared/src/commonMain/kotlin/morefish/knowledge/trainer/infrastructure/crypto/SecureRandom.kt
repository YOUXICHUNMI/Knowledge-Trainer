package morefish.knowledge.trainer.infrastructure.crypto

/**
 * 密码学安全的随机字节（salt 与 nonce 用）。
 *
 * **不能用 `kotlin.random.Random`**：它是可预测的 PRNG，盐和 nonce 一旦可预测，
 * GCM 的 nonce 复用就变成现实威胁 —— 同一个密钥下 nonce 撞一次，攻击者能直接
 * 从两条密文里异或出明文，而界面上一切照旧"看起来正常"。所以这里必须是平台的 CSPRNG，
 * 且由编译器强制每个平台回答（`expect`/`actual`），谁都不能偷偷留一个默认实现。
 *
 * 目前只有 JVM / Android 真的有实现：其余三个平台没有数据保险库（数据在内存里、
 * 没有可备份的东西），走不到这里。将来要是给 iOS 或 Web 加保险库，
 * **第一件事是在这里接上 `SecRandomCopyBytes` / `crypto.getRandomValues`**，
 * 而不是回来改这里的默认值。
 */
expect fun secureRandomBytes(count: Int): ByteArray
