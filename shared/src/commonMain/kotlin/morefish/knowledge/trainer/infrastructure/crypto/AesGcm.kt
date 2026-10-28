package morefish.knowledge.trainer.infrastructure.crypto

/**
 * AES-GCM（NIST SP 800-38D），128 位认证标签、96 位 nonce。
 *
 * 选 GCM 而不是 "AES-CBC + 单独算 HMAC"，理由只有一个：**只有一种失败方式**。
 * 自己拼 encrypt-then-MAC 要回答"MAC 覆盖哪些字节、nonce 进不进 MAC、比较是否短路"，
 * 每个问题答错都能悄悄毁掉认证；GCM 里这些都定死了，答错就等于解不开。
 *
 * [seal] 出去的是 `密文 || 标签`（标签 16 字节在末尾）。
 * [open] 用**恒定时间**比较标签 —— 早退比较会泄漏"前 N 个字节对了"，
 * 对本地备份不算致命，但没有理由写错的版本。
 *
 * GHASH 用逐位乘法（128 步/块），没做 4 位查表优化：慢一点，换"能在纸上验算清楚"。
 * 单次上限见 [MAX_BYTES]。
 */
internal object AesGcm {

    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    /**
     * 单次加解密的上限。逐位 GHASH 是纯 Kotlin，再大就不是"等一下"而是"卡死"了；
     * 到这里明确拒绝，好过让用户对着一个假死的窗口等十分钟。
     */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 返回 `密文 || 16 字节标签`。 */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "GCM nonce 必须是 $NONCE_BYTES 字节" }
        require(plaintext.size.toLong() <= MAX_BYTES) { "单次加密上限 $MAX_BYTES 字节" }
        val roundKeys = Aes.expandKey(key)
        val h = ghashKey(roundKeys)
        val j0 = j0Of(nonce)

        val cipher = ByteArray(plaintext.size)
        ctrXor(roundKeys, j0, plaintext, cipher, plaintext.size)
        val s = ghash(h, aad, cipher)
        val mask = ByteArray(16)
        Aes.encryptBlock(roundKeys, j0, 0, mask, 0)

        val out = ByteArray(plaintext.size + TAG_BYTES)
        cipher.copyInto(out)
        for (i in 0 until TAG_BYTES) out[plaintext.size + i] = (s[i].toInt() xor mask[i].toInt()).toByte()
        return out
    }

    /** 认证失败返回 null —— **不区分"口令错"与"文件被改"**，密码学上这两件事本来就分不开。 */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (nonce.size != NONCE_BYTES) return null
        if (sealed.size < TAG_BYTES) return null
        val bodySize = sealed.size - TAG_BYTES
        if (bodySize.toLong() > MAX_BYTES) return null
        val roundKeys = Aes.expandKey(key)
        val h = ghashKey(roundKeys)
        val j0 = j0Of(nonce)

        val cipher = sealed.copyOfRange(0, bodySize)
        val s = ghash(h, aad, cipher)
        val mask = ByteArray(16)
        Aes.encryptBlock(roundKeys, j0, 0, mask, 0)

        // 恒定时间比较：每个字节都参与，不提前 return
        var diff = 0
        for (i in 0 until TAG_BYTES) {
            diff = diff or (s[i].toInt() xor mask[i].toInt() xor sealed[bodySize + i].toInt())
        }
        if (diff != 0) return null

        val plain = ByteArray(bodySize)
        ctrXor(roundKeys, j0, cipher, plain, bodySize)
        return plain
    }

    // ---------- 内部 ----------

    /** H = E(K, 0^128) */
    private fun ghashKey(roundKeys: IntArray): LongArray {
        val zero = ByteArray(16)
        val h = ByteArray(16)
        Aes.encryptBlock(roundKeys, zero, 0, h, 0)
        return longArrayOf(beLong(h, 0), beLong(h, 8))
    }

    /** 96 位 IV：J0 = IV || 0^31 || 1。 */
    private fun j0Of(nonce: ByteArray): ByteArray = ByteArray(16).also {
        nonce.copyInto(it)
        it[15] = 1
    }

    /** CTR 加解密：counter 从 J0+1 开始逐块加一。加解密同一个函数 —— GCM 只用 AES 的加密方向。 */
    private fun ctrXor(roundKeys: IntArray, j0: ByteArray, input: ByteArray, output: ByteArray, length: Int) {
        val counter = j0.copyOf()
        val stream = ByteArray(16)
        var done = 0
        while (done < length) {
            increment32(counter)
            Aes.encryptBlock(roundKeys, counter, 0, stream, 0)
            val n = minOf(16, length - done)
            for (i in 0 until n) {
                output[done + i] = (input[done + i].toInt() xor stream[i].toInt()).toByte()
            }
            done += n
        }
    }

    /** 只加最后一个 32 位字（GCM 的 inc32），进位向高位传。 */
    private fun increment32(block: ByteArray) {
        for (i in 15 downTo 12) {
            val v = (block[i].toInt() and 0xFF) + 1
            block[i] = v.toByte()
            if (v <= 0xFF) return
        }
    }

    /**
     * S = GHASH(AAD || pad || C || pad || len(AAD)*8 || len(C)*8)。
     * 逐块：Y = (Y xor block) · H，初值为 0；AAD 与密文都按 16 字节补零。
     */
    private fun ghash(h: LongArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        val y = LongArray(2)
        val block = ByteArray(16)

        var i = 0
        while (i < aad.size) {
            block.fill(0)
            val n = minOf(16, aad.size - i)
            aad.copyInto(block, 0, i, i + n)
            absorb(y, beLong(block, 0), beLong(block, 8), h)
            i += 16
        }

        i = 0
        while (i < cipher.size) {
            block.fill(0)
            val n = minOf(16, cipher.size - i)
            cipher.copyInto(block, 0, i, i + n)
            absorb(y, beLong(block, 0), beLong(block, 8), h)
            i += 16
        }

        val lens = ByteArray(16)
        putBeLong(lens, 0, aad.size.toLong() * 8)
        putBeLong(lens, 8, cipher.size.toLong() * 8)
        absorb(y, beLong(lens, 0), beLong(lens, 8), h)

        val s = ByteArray(16)
        putBeLong(s, 0, y[0])
        putBeLong(s, 8, y[1])
        return s
    }

    /** y ← (y xor block) · H，结果就地写回 [y]。 */
    private fun absorb(y: LongArray, hi: Long, lo: Long, h: LongArray) {
        val xh = y[0] xor hi
        val xl = y[1] xor lo
        var vh = h[0]
        var vl = h[1]
        var zh = 0L
        var zl = 0L
        for (bit in 0 until 128) {
            val set = if (bit < 64) (xh ushr (63 - bit)) and 1L else (xl ushr (63 - (bit - 64))) and 1L
            if (set == 1L) {
                zh = zh xor vh
                zl = zl xor vl
            }
            val lsb = vl and 1L
            vl = (vl ushr 1) or (vh shl 63)
            vh = vh ushr 1
            if (lsb == 1L) vh = vh xor REDUCTION
        }
        y[0] = zh
        y[1] = zl
    }

    /** GF(2¹²⁸) 的规约常量：x¹²⁸ + x⁷ + x² + x + 1 的高位项。 */
    private val REDUCTION: Long = 0xE100000000000000uL.toLong()

    private fun beLong(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun putBeLong(b: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) b[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
