package morefish.knowledge.trainer.infrastructure.backup

import morefish.knowledge.trainer.domain.backup.BackupCrypt
import morefish.knowledge.trainer.domain.backup.BackupCryptoIds
import morefish.knowledge.trainer.domain.backup.BackupManifest
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.crypto.AesGcm
import morefish.knowledge.trainer.infrastructure.crypto.Pbkdf2
import morefish.knowledge.trainer.infrastructure.crypto.secureRandomBytes

/**
 * 加密备份的密码学部分：口令 → 密钥 → 加解密。
 *
 * 三件事都在这里说清楚：
 *
 * 1. **KDF 是 PBKDF2-HMAC-SHA256，600000 轮**。产品总纲建议 Argon2id，本版没做，
 *    理由写在 [Pbkdf2] 顶部的注释里：手写一份没人验证过的 Argon2id，比用成熟的 PBKDF2 更危险。
 *    参数（含算法名）全部写在文件里，将来加 Argon2id 是**多一个 kdf 取值**，不是换格式版本。
 * 2. **AAD 绑定信封**。迭代次数、盐、nonce、时间、设备名都会参与认证：
 *    把 `iterations` 从 600000 改成 1 来加速爆破，标签会直接校验失败。
 * 3. **不区分"口令错"与"文件被改"**。GCM 只给"标签对不对"这一个结论，
 *    再往下细分就得靠猜 —— 而猜错会把用户推进"一直重试口令"的死胡同。
 */
internal object BackupCrypto {

    /** 600000 轮：本机实测约 0.6~1.0 秒（纯 Kotlin，已 JIT 预热）。低于这个量级就不叫加密了。 */
    const val ITERATIONS = 600_000

    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    /**
     * 口令最短 10 个字符（常量在 [BackupCryptoIds.MIN_PASSPHRASE_CHARS]，界面用的是同一个）。
     *
     * 这个下限**防不住弱口令**（"1234567890" 一样该被拒绝），它的作用是拦住
     * "随手四个字母"。真正的强度只能靠引导：界面上直说"四个不相关的词比一个复杂词更牢"，
     * 而不是给一个假装客观的强度条。
     */
    const val MIN_PASSPHRASE_CHARS = BackupCryptoIds.MIN_PASSPHRASE_CHARS

    fun newSalt(): ByteArray = secureRandomBytes(SALT_BYTES)

    fun newNonce(): ByteArray = secureRandomBytes(NONCE_BYTES)

    fun encryptPassword(passphrase: String): ByteArray = passphrase.encodeToByteArray()

    fun deriveKey(passphrase: String, crypt: BackupCrypt): ByteArray =
        Pbkdf2.deriveHmacSha256(encryptPassword(passphrase), hexToBytes(crypt.saltHex), crypt.iterations, KEY_BYTES)

    /** 加密：返回 `密文 || 标签`。 */
    fun seal(passphrase: String, plain: ByteArray, crypt: BackupCrypt, aad: ByteArray): ByteArray =
        AesGcm.seal(deriveKey(passphrase, crypt), hexToBytes(crypt.nonceHex), plain, aad)

    /** 解密失败（口令错 / 被改过 / 不是这份文件）一律返回 null。 */
    fun open(passphrase: String, sealed: ByteArray, crypt: BackupCrypt, aad: ByteArray): ByteArray? =
        AesGcm.open(deriveKey(passphrase, crypt), hexToBytes(crypt.nonceHex), sealed, aad)

    /**
     * 参与认证的信封字段。
     *
     * 用固定的行格式而不是把清单 JSON 整体丢进去：JSON 的字段顺序、空格、转义都可能变，
     * 一旦某次序列化输出不同，旧备份就再也解不开了。这里每一行都是"我们自己的约定"。
     */
    fun aadOf(manifest: BackupManifest, crypt: BackupCrypt): ByteArray = buildString {
        append(AAD_VERSION).append('\n')
        append("backupVersion=").append(manifest.backupVersion).append('\n')
        append("layoutVersion=").append(manifest.layoutVersion).append('\n')
        append("createdAtMillis=").append(manifest.createdAtMillis).append('\n')
        append("deviceLabel=").append(escape(manifest.deviceLabel)).append('\n')
        append("appVersion=").append(escape(manifest.appVersion)).append('\n')
        append("kdf=").append(crypt.kdf).append('\n')
        append("iterations=").append(crypt.iterations).append('\n')
        append("salt=").append(crypt.saltHex).append('\n')
        append("cipher=").append(crypt.cipher).append('\n')
        append("nonce=").append(crypt.nonceHex).append('\n')
    }.encodeToByteArray()

    /** 行格式里的字段必须转义换行，否则"设备名里带一个换行"就能让两行撞成一行。 */
    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    // ---------- 十六进制 ----------

    fun hexOf(bytes: ByteArray): String = Sha256.hexOf(bytes)

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制长度必须是偶数" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = digit(hex[i * 2])
            val low = digit(hex[i * 2 + 1])
            require(high >= 0 && low >= 0) { "十六进制里有非法字符" }
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    fun isHex(hex: String): Boolean = hex.length % 2 == 0 && hex.all { digit(it) >= 0 }

    private fun digit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    /** AAD 的版本标签。哪天信封字段变了，靠它区分"旧格式"而不是悄悄解错。 */
    const val AAD_VERSION = "kt-qbk-aad-1"

    /** 检查清单里的加密参数能不能接受。返回 null = 通过。 */
    fun checkParams(crypt: BackupCrypt?): String? {
        if (crypt == null) return "清单说这份备份加密了，但没有写加密参数"
        if (crypt.kdf != BackupCryptoIds.KDF_PBKDF2_SHA256) {
            return "这份备份用「${crypt.kdf}」派生密钥，本机只认 ${BackupCryptoIds.KDF_PBKDF2_SHA256}"
        }
        if (crypt.cipher != BackupCryptoIds.CIPHER_AES_256_GCM) {
            return "这份备份用「${crypt.cipher}」加密，本机只认 ${BackupCryptoIds.CIPHER_AES_256_GCM}"
        }
        if (crypt.iterations < Pbkdf2.MIN_ITERATIONS) {
            return "迭代次数只有 ${crypt.iterations}（本机要求不少于 ${Pbkdf2.MIN_ITERATIONS}）—— 这么低的次数等于没加密"
        }
        if (crypt.iterations > Pbkdf2.MAX_ITERATIONS) {
            return "迭代次数 ${crypt.iterations} 高到不合理，解开它要等上几分钟"
        }
        if (!isHex(crypt.saltHex) || crypt.saltHex.length != SALT_BYTES * 2) {
            return "盐的长度不对（应为 $SALT_BYTES 字节）"
        }
        if (!isHex(crypt.nonceHex) || crypt.nonceHex.length != NONCE_BYTES * 2) {
            return "nonce 的长度不对（应为 $NONCE_BYTES 字节）"
        }
        if (crypt.tagBytes != AesGcm.TAG_BYTES) {
            return "认证标签长度 ${crypt.tagBytes} 字节，本机只认 ${AesGcm.TAG_BYTES} 字节"
        }
        return null
    }
}
