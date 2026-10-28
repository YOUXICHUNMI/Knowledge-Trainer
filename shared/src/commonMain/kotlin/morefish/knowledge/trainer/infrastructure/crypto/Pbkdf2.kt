package morefish.knowledge.trainer.infrastructure.crypto

/**
 * PBKDF2-HMAC-SHA256（RFC 8018）。
 *
 * **为什么不是 Argon2id**：产品总纲建议"成熟 KDF（如 Argon2id）"。Argon2id 需要 BLAKE2b
 * 加一块内存硬的填充逻辑，几十行核心 + 一个 1 KiB 的 BLAKE2b 初始化向量；在这里手写一份
 * 没有第三方验证过的 Argon2id，比写 PBKDF2 更容易出现"看起来在工作"的错。
 * 所以本版用 PBKDF2-HMAC-SHA256，并**把 KDF 名称与参数写进备份文件**：
 * 将来接上 Argon2id 只是多一个 `kdf` 取值，旧备份照样能开，不需要换格式版本。
 *
 * 代价必须说清（界面上也写）：PBKDF2 **不是内存硬的**，用显卡批量猜比 Argon2id 便宜得多。
 * 迭代次数只能抬高单次成本，真正决定安全的是口令本身够不够长。
 */
internal object Pbkdf2 {

    /** 单次派生允许的最小迭代次数。读别人的文件时低于这个数一律拒绝（防止把参数改小来提速爆破）。 */
    const val MIN_ITERATIONS = 50_000

    /** 允许的最大迭代次数：防止一个恶意文件让应用算上几分钟（拒绝服务）。 */
    const val MAX_ITERATIONS = 4_000_000

    fun deriveHmacSha256(passphrase: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(iterations > 0) { "迭代次数必须大于 0" }
        require(keyBytes > 0) { "密钥长度必须大于 0" }
        val mac = HmacSha256.context(passphrase)
        val hashBytes = 32
        val blocks = (keyBytes + hashBytes - 1) / hashBytes
        val out = ByteArray(blocks * hashBytes)
        val acc = ByteArray(hashBytes)

        for (block in 1..blocks) {
            // U1 = PRF(P, S || INT_BE(block))
            val seed = ByteArray(salt.size + 4)
            salt.copyInto(seed)
            seed[salt.size] = (block ushr 24).toByte()
            seed[salt.size + 1] = (block ushr 16).toByte()
            seed[salt.size + 2] = (block ushr 8).toByte()
            seed[salt.size + 3] = block.toByte()

            var u = mac.run(seed)
            u.copyInto(acc)
            for (i in 2..iterations) {
                u = mac.run(u)
                for (j in 0 until hashBytes) acc[j] = (acc[j].toInt() xor u[j].toInt()).toByte()
            }
            acc.copyInto(out, (block - 1) * hashBytes)
        }
        return out.copyOf(keyBytes)
    }
}
