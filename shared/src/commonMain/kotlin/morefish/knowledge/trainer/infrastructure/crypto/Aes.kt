package morefish.knowledge.trainer.infrastructure.crypto

/**
 * AES 分组密码（只实现加密方向）。
 *
 * 为什么自己写：`commonMain` 里调不到 `javax.crypto`，而"每个平台各写一份"正是
 * [morefish.knowledge.trainer.infrastructure.archive.Sha256] 当初被手写的原因 ——
 * 备份文件的字节必须到处一样，不能这里是 AES 那里是别的。
 *
 * 只做加密方向是刻意的：GCM 的 CTR 模式加密与解密都用 E(K, ·)，
 * 多写一个解密方向只会多一份可能写错、且没人验证的代码。
 *
 * **S 盒不是抄来的表**，是用 GF(2⁸) 上的乘法逆元 + 仿射变换算出来的：
 * 256 个魔数手抄错一位，症状是"某些数据能通、某些数据错"，比直接算难查得多。
 *
 * 免责：这是纯 Kotlin 的**查表实现**，不是抗侧信道（缓存计时）的实现。
 * 它防的是"别人拿到你的备份文件"，不防"别人在你机器上跑计时攻击"。
 */
internal object Aes {

    /** 轮数由密钥长度决定：128→10、192→12、256→14。 */
    fun roundsFor(keyBytes: Int): Int = when (keyBytes) {
        16 -> 10
        24 -> 12
        32 -> 14
        else -> throw IllegalArgumentException("AES 密钥只能是 16 / 24 / 32 字节，收到 $keyBytes")
    }

    /**
     * 密钥扩展，返回 `4 * (rounds + 1)` 个字（每个字 32 位，按大端读）。
     * AES-256 是 Nk=8 / Nr=14，所以是 60 个字。
     */
    fun expandKey(key: ByteArray): IntArray {
        val nk = key.size / 4
        val rounds = roundsFor(key.size)
        val total = 4 * (rounds + 1)
        val w = IntArray(total)
        for (i in 0 until nk) {
            w[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[i * 4 + 3].toInt() and 0xFF)
        }
        var rcon = 1
        for (i in nk until total) {
            var temp = w[i - 1]
            when {
                i % nk == 0 -> {
                    temp = subWord(rotWord(temp)) xor (rcon shl 24)
                    rcon = xtime(rcon)
                }
                // AES-256 专有的一步：i ≡ 4 (mod 8) 时多过一次 SubWord
                nk > 6 && i % nk == 4 -> temp = subWord(temp)
            }
            w[i] = w[i - nk] xor temp
        }
        return w
    }

    /**
     * 加密一个 16 字节分组。[out] 可以与 [input] 是同一个数组（加密时状态先读进 IntArray）。
     */
    @Suppress("NAME_SHADOWING")
    fun encryptBlock(roundKeys: IntArray, input: ByteArray, inOffset: Int, out: ByteArray, outOffset: Int) {
        val rounds = roundKeys.size / 4 - 1
        // 状态按列主序：s[r + 4c] = 第 c 列第 r 行
        val s = IntArray(16) { input[inOffset + it].toInt() and 0xFF }

        addRoundKey(s, roundKeys, 0)
        for (round in 1 until rounds) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, roundKeys, round * 4)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, roundKeys, rounds * 4)

        for (i in 0 until 16) out[outOffset + i] = s[i].toByte()
    }

    // ---------- 轮变换 ----------

    private fun addRoundKey(s: IntArray, w: IntArray, offset: Int) {
        for (c in 0 until 4) {
            val word = w[offset + c]
            s[c * 4] = s[c * 4] xor ((word ushr 24) and 0xFF)
            s[c * 4 + 1] = s[c * 4 + 1] xor ((word ushr 16) and 0xFF)
            s[c * 4 + 2] = s[c * 4 + 2] xor ((word ushr 8) and 0xFF)
            s[c * 4 + 3] = s[c * 4 + 3] xor (word and 0xFF)
        }
    }

    private fun subBytes(s: IntArray) {
        for (i in 0 until 16) s[i] = S_BOX[s[i]]
    }

    /** 第 r 行左移 r 位。列主序下就是按行取 4 个数重排。 */
    private fun shiftRows(s: IntArray) {
        // 第 1 行
        val r1 = s[1]
        s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = r1
        // 第 2 行
        val r2a = s[2]; val r2b = s[6]
        s[2] = s[10]; s[6] = s[14]; s[10] = r2a; s[14] = r2b
        // 第 3 行
        val r3 = s[15]
        s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = r3
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val i = c * 4
            val a0 = s[i]; val a1 = s[i + 1]; val a2 = s[i + 2]; val a3 = s[i + 3]
            val all = a0 xor a1 xor a2 xor a3
            s[i] = a0 xor all xor xtime(a0 xor a1)
            s[i + 1] = a1 xor all xor xtime(a1 xor a2)
            s[i + 2] = a2 xor all xor xtime(a2 xor a3)
            s[i + 3] = a3 xor all xor xtime(a3 xor a0)
        }
    }

    private fun subWord(w: Int): Int =
        (S_BOX[(w ushr 24) and 0xFF] shl 24) or
            (S_BOX[(w ushr 16) and 0xFF] shl 16) or
            (S_BOX[(w ushr 8) and 0xFF] shl 8) or
            S_BOX[w and 0xFF]

    private fun rotWord(w: Int): Int = (w shl 8) or (w ushr 24)

    /** 乘 2（GF(2⁸)，模 x⁸+x⁴+x³+x+1 = 0x11B）。 */
    private fun xtime(a: Int): Int {
        val shifted = (a shl 1) and 0xFF
        return if (a and 0x80 != 0) shifted xor 0x1B else shifted
    }

    // ---------- S 盒：算出来，不抄 ----------

    private val S_BOX: IntArray = IntArray(256) { i -> affine(inverse(i)) }

    /** GF(2⁸) 上的乘法逆元：a^254（a=0 时约定为 0）。 */
    private fun inverse(a: Int): Int {
        if (a == 0) return 0
        var result = 1
        var base = a
        var exp = 254
        while (exp > 0) {
            if (exp and 1 == 1) result = multiply(result, base)
            base = multiply(base, base)
            exp = exp shr 1
        }
        return result
    }

    /** GF(2⁸) 乘法：俄国农民法，模 0x11B。 */
    private fun multiply(a0: Int, b0: Int): Int {
        var a = a0
        var b = b0
        var product = 0
        repeat(8) {
            if (b and 1 == 1) product = product xor a
            val high = a and 0x80
            a = (a shl 1) and 0xFF
            if (high != 0) a = a xor 0x1B
            b = b shr 1
        }
        return product
    }

    /** 仿射变换：b xor rotl(b,1) xor rotl(b,2) xor rotl(b,3) xor rotl(b,4) xor 0x63。 */
    private fun affine(b: Int): Int =
        (b xor rotl8(b, 1) xor rotl8(b, 2) xor rotl8(b, 3) xor rotl8(b, 4) xor 0x63) and 0xFF

    private fun rotl8(x: Int, n: Int): Int = ((x shl n) or (x ushr (8 - n))) and 0xFF

    /** 只给测试用：确认算出来的 S 盒与标准一致（首项 0x63、末项 0x16）。 */
    internal fun sBoxForTest(): IntArray = S_BOX.copyOf()
}
