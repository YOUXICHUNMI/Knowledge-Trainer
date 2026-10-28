package morefish.knowledge.trainer.infrastructure.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯 Kotlin 密码学原语的**对拍**测试。
 *
 * 这里一条"期望值"都不是我自己算的：AES、GCM、HMAC、PBKDF2 全部拿 `javax.crypto`
 * 当独立实现，同一组随机输入各算一遍再比字节。
 *
 * 为什么不在测试里写死几个十六进制常量：常量是我抄的，抄错就永远不会报错；
 * 而对拍是两套独立实现互相盯着，其中一套（JDK）已经被全世界用了二十年。
 * 唯一手写的常量是 AES S 盒的首尾两项 —— 那只是"算出来的表没被写成一团乱码"的快速体检。
 */
class CryptoSelfTest {

    private val random = SecureRandom()

    // ---------- AES 分组 ----------

    @Test
    fun `AES 分组加密与 JDK 逐字节一致`() {
        for (keyBytes in listOf(16, 24, 32)) {
            repeat(20) {
                val key = ByteArray(keyBytes).also { random.nextBytes(it) }
                val plain = ByteArray(16).also { random.nextBytes(it) }
                val roundKeys = Aes.expandKey(key)
                val ours = ByteArray(16)
                Aes.encryptBlock(roundKeys, plain, 0, ours, 0)

                val jdk = Cipher.getInstance("AES/ECB/NoPadding")
                jdk.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                assertContentEquals(jdk.doFinal(plain), ours, "AES-${keyBytes * 8} 不一致")
            }
        }
    }

    @Test
    fun `算出来的 S 盒首尾与标准一致`() {
        val box = Aes.sBoxForTest()
        assertEquals(0x63, box[0x00])
        assertEquals(0x7c, box[0x01])
        assertEquals(0x16, box[0xFF])
        // 双射：256 个值互不相同（抄错一位的典型症状就是有重复）
        assertEquals(256, box.toSet().size)
    }

    // ---------- GCM ----------

    @Test
    fun `GCM 加密结果与 JDK 逐字节一致（含 AAD 与各种长度）`() {
        for (keyBytes in listOf(16, 32)) {
            for (plainSize in listOf(0, 1, 15, 16, 17, 64, 1000)) {
                for (aadSize in listOf(0, 1, 16, 37)) {
                    val key = ByteArray(keyBytes).also { random.nextBytes(it) }
                    val nonce = ByteArray(12).also { random.nextBytes(it) }
                    val plain = ByteArray(plainSize).also { random.nextBytes(it) }
                    val aad = ByteArray(aadSize).also { random.nextBytes(it) }

                    val ours = AesGcm.seal(key, nonce, plain, aad)

                    val jdk = Cipher.getInstance("AES/GCM/NoPadding")
                    jdk.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    jdk.updateAAD(aad)
                    val expected = jdk.doFinal(plain)

                    assertContentEquals(
                        expected, ours,
                        "GCM key=$keyBytes plain=$plainSize aad=$aadSize 不一致",
                    )
                }
            }
        }
    }

    @Test
    fun `GCM 解密回原文，改一个字节就解不开`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val plain = "题库备份的明文内容".encodeToByteArray()
        val aad = "header".encodeToByteArray()
        val sealed = AesGcm.seal(key, nonce, plain, aad)

        assertContentEquals(plain, assertNotNull(AesGcm.open(key, nonce, sealed, aad)))

        // 改密文
        for (position in listOf(0, 5, plain.size, sealed.size - 1)) {
            val tampered = sealed.copyOf()
            tampered[position] = (tampered[position].toInt() xor 0x01).toByte()
            assertNull(AesGcm.open(key, nonce, tampered, aad), "第 $position 字节被改后仍解开了")
        }
        // 改 nonce / AAD / 密钥
        assertNull(AesGcm.open(key, ByteArray(12) { 1 }, sealed, aad))
        assertNull(AesGcm.open(key, nonce, sealed, "headEr".encodeToByteArray()))
        assertNull(AesGcm.open(ByteArray(32) { 9 }, nonce, sealed, aad))
    }

    @Test
    fun `GCM 空明文也有标签，且与 JDK 一致`() {
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val ours = AesGcm.seal(key, nonce, ByteArray(0), ByteArray(0))
        assertEquals(16, ours.size)

        val jdk = Cipher.getInstance("AES/GCM/NoPadding")
        jdk.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        assertContentEquals(jdk.doFinal(ByteArray(0)), ours)
    }

    // ---------- HMAC ----------

    @Test
    fun `HMAC-SHA256 与 JDK 一致（含超长密钥）`() {
        // 空密钥不进这个循环：JDK 的 SecretKeySpec 直接抛 "Empty key"，那是它的限制，
        // 不是我们的。空密钥在我们自己的实现里能算，但备份里不会出现（口令另有下限）。
        for (keySize in listOf(1, 8, 32, 64, 65, 100)) {
            for (msgSize in listOf(0, 1, 55, 64, 200)) {
                val key = ByteArray(keySize).also { random.nextBytes(it) }
                val msg = ByteArray(msgSize).also { random.nextBytes(it) }
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key, "HmacSHA256"))
                assertContentEquals(mac.doFinal(msg), HmacSha256.digest(key, msg), "key=$keySize msg=$msgSize")
            }
        }
    }

    // ---------- PBKDF2 ----------

    @Test
    fun `PBKDF2-HMAC-SHA256 与 JDK 一致`() {
        val combos = listOf(
            Triple(1, 32, "短"),
            Triple(1000, 32, "correct horse battery staple"),
            Triple(4096, 48, ""),
            Triple(2048, 64, "带中文口令 · 还有空格"),
        )
        for ((iterations, keyBytes, passphrase) in combos) {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyBytes * 8))
                .encoded
            val ours = Pbkdf2.deriveHmacSha256(passphrase.encodeToByteArray(), salt, iterations, keyBytes)
            assertContentEquals(expected, ours, "iter=$iterations key=$keyBytes 不一致")
        }
    }

    @Test
    fun `PBKDF2 不同盐得到不同密钥，同输入可重复`() {
        val pass = "六个字的密码".encodeToByteArray()
        val saltA = ByteArray(16) { 1 }
        val saltB = ByteArray(16) { 2 }
        val a1 = Pbkdf2.deriveHmacSha256(pass, saltA, 1000, 32)
        val a2 = Pbkdf2.deriveHmacSha256(pass, saltA, 1000, 32)
        val b = Pbkdf2.deriveHmacSha256(pass, saltB, 1000, 32)
        assertContentEquals(a1, a2)
        assertTrue(a1.toList() != b.toList())
    }

    @Test
    fun `迭代次数越高越慢——这是刻意的`() {
        // 不测具体耗时（机器不同），只断言"确实按次数做了事"：1000 次与 2000 次的结果必须不同。
        val pass = "x".encodeToByteArray()
        val salt = ByteArray(16)
        val low = Pbkdf2.deriveHmacSha256(pass, salt, 1000, 32)
        val high = Pbkdf2.deriveHmacSha256(pass, salt, 2000, 32)
        assertTrue(low.toList() != high.toList())
    }
}
