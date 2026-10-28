package morefish.knowledge.trainer.infrastructure.backup

import morefish.knowledge.trainer.domain.backup.BackupManifest
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.infrastructure.archive.ZipWriteEntry
import morefish.knowledge.trainer.infrastructure.archive.ZipWriter
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 加密备份（容器 v2）。
 *
 * 这一套测试要证明的是**具体的东西**，不是"感觉安全"：
 *
 * 1. 口令对就能原样拿回每个字节；口令错就一个字节都拿不到。
 * 2. 改任何一个位置（密文、盐、迭代次数、时间、设备名）都会被拒绝 —— 不是"读出错"，是认证失败。
 * 3. **明文部分不含文件名、不含文件内容的片段**：这是"加密了"和"看起来加密了"的区别。
 * 4. 每次导出的字节都不同（盐与 nonce 随机），但内容摘要相同（摘要只依赖逻辑内容）。
 * 5. 老的非加密备份照样能读 —— 加密不能以"旧文件读不了"为代价。
 */
class BackupEncryptionTest {

    private val archive = BackupArchive()
    private val passphrase = "正确口令-自行车海豚毛线"
    private val files = mapOf(
        "banks-v1.json" to """{"banks":[{"bankId":"b1","questionCount":2}]}""".encodeToByteArray(),
        "banks/b1/questions-v1.ndjson" to "{\"questionId\":\"q1\"}\n{\"questionId\":\"q2\"}\n".encodeToByteArray(),
        "attempts-v1.ndjson" to "{\"attemptId\":\"a1\"}\n".encodeToByteArray(),
        "notes-v1.json" to """{"notes":[{"noteId":"n1"}]}""".encodeToByteArray(),
    )

    private fun bundle(pass: String = passphrase) =
        archive.create(files, "机器 A", "0.9.0", 1_800_000_000_000L, pass)

    // ---------- 正常路径 ----------

    @Test
    fun encryptedRoundTripReturnsEveryByte() {
        val created = bundle()
        assertTrue(created.encrypted)
        assertEquals(2, created.manifest.backupVersion)

        val read = archive.read(created.bytes, passphrase)
        assertTrue(read.canRestore, "口令正确却恢复不了：${read.problems.map { it.code }}")
        assertEquals(files.keys, read.payload.keys)
        files.forEach { (path, bytes) ->
            assertTrue(bytes.contentEquals(read.payload[path]), "$path 的内容不一致")
        }
    }

    @Test
    fun decryptedBackupReportsSameContentDigest() {
        // 两台机器同一份数据：盐与 nonce 不同 → 文件字节不同；但摘要只依赖内容，必须相同。
        val a = archive.create(files, "机器 A", "0.9.0", 1L, passphrase)
        val b = archive.create(files, "机器 B", "0.9.0", 2L, passphrase)
        assertEquals(a.contentDigest, b.contentDigest)
        assertFalse(a.bytes.contentEquals(b.bytes), "两次加密的字节不该相同（盐与 nonce 是随机的）")

        val read = archive.read(a.bytes, passphrase)
        assertEquals(a.contentDigest, read.preview?.manifest?.contentDigest)
    }

    @Test
    fun encryptedBackupCanBeWalkedByForeignZipReader() {
        // 手写 ZIP 的条目名与 CRC 也要经得起别的实现来读。
        val created = bundle()
        val names = mutableListOf<String>()
        ZipInputStream(created.bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.readBytes()
            }
        }
        assertEquals(listOf(BackupManifest.ENTRY_NAME, BackupManifest.PAYLOAD_ENTRY), names)
    }

    @Test
    fun plaintextPartLeaksNeitherNamesNorContent() {
        val created = bundle()
        val haystack = created.bytes
        // 文件名、目录名、内容里的特征串，一个都不该出现在文件里
        listOf(
            "banks/b1/questions-v1.ndjson",
            "attempts-v1.ndjson",
            "notes-v1.json",
            "bankId",
            "attemptId",
            "\"q1\"",
            "自行车",
        ).forEach { needle ->
            assertEquals(-1, indexOf(haystack, needle.encodeToByteArray()), "备份文件里出现了明文「$needle」")
        }
    }

    @Test
    fun envelopeStillShowsCountsSoUserCanDecideBeforeTypingPassphrase() {
        val created = bundle()
        val locked = archive.read(created.bytes)
        assertTrue(locked.requiresPassphrase, "没给口令时应当要求口令")
        assertFalse(locked.canRestore)
        // 统计留在信封上（这是"要不要这份"的判断依据），但文件明细没有
        assertEquals(1, locked.preview?.manifest?.counts?.banks)
        assertEquals(2, locked.preview?.manifest?.counts?.questions)
        assertEquals(1, locked.preview?.manifest?.counts?.attempts)
        assertEquals(4, locked.preview?.manifest?.counts?.totalFiles)
        assertTrue(locked.preview?.manifest?.files.isNullOrEmpty(), "加密备份的明文清单里不该有文件明细")
    }

    // ---------- 拒绝路径 ----------

    @Test
    fun wrongPassphraseFailsWithSingleCode() {
        val read = archive.read(bundle().bytes, "错误口令-自行车海豚毛线")
        assertFalse(read.canRestore)
        assertEquals(listOf(BackupProblem.DECRYPT_FAILED), read.problems.map { it.code })
        assertTrue(read.payload.isEmpty(), "解不开时不该有任何内容泄漏出去")
        // 不区分"口令错"与"文件被改"
        assertTrue(read.problems.first().message.contains("分不开"))
    }

    @Test
    fun emptyPassphraseIsTreatedAsNoPassphrase() {
        val read = archive.read(bundle().bytes, "")
        assertTrue(read.requiresPassphrase)
        assertFalse(read.canRestore)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val created = bundle()
        // 改的是**密文本体**（重新打包，CRC 由我们自己的写入器算对）：
        // 这样拒绝只可能来自 GCM 的标签校验，而不是 ZIP 的 CRC。
        val payload = payloadOf(created.bytes)
        payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 0x01).toByte()
        val rebuilt = ZipWriter.write(
            listOf(
                ZipWriteEntry(BackupManifest.ENTRY_NAME, manifestTextOf(created.bytes).encodeToByteArray()),
                ZipWriteEntry(BackupManifest.PAYLOAD_ENTRY, payload),
            ),
        )
        val read = archive.read(rebuilt, passphrase)
        assertFalse(read.canRestore)
        assertEquals(listOf(BackupProblem.DECRYPT_FAILED), read.problems.map { it.code })
        assertTrue(read.payload.isEmpty())
    }

    @Test
    fun anyByteFlipInZipIsRejected() {
        val created = bundle()
        val tampered = created.bytes.copyOf()
        val target = created.bytes.size / 2
        tampered[target] = (tampered[target].toInt() xor 0x01).toByte()
        val read = archive.read(tampered, passphrase)
        assertFalse(read.canRestore, "改坏一个字节还能恢复")
        assertTrue(read.payload.isEmpty())
    }

    @Test
    fun tamperedIterationsAreRejected() {
        val created = bundle()
        val original = manifestTextOf(created.bytes)
        // 只改 1 轮：既在允许范围内（不会先被策略拦），又足以让派生出来的密钥完全不同
        val patched = original.replace(
            "\"iterations\": ${BackupCrypto.ITERATIONS}",
            "\"iterations\": ${BackupCrypto.ITERATIONS + 1}",
        )
        assertTrue(patched != original, "没有找到 iterations 字段，测试本身失效了")
        val read = archive.read(rebuild(patched, created), passphrase)
        assertFalse(read.canRestore, "改了迭代次数还能解开")
        assertTrue(read.problems.any { it.code == BackupProblem.DECRYPT_FAILED })
    }

    @Test
    fun tamperedDeviceLabelIsRejected() {
        val created = bundle()
        val patched = manifestTextOf(created.bytes).replace("机器 A", "机器 B")
        val read = archive.read(rebuild(patched, created), passphrase)
        assertFalse(read.canRestore, "改了设备名还能解开 —— 说明 AAD 没有覆盖它")
        assertTrue(read.problems.any { it.code == BackupProblem.DECRYPT_FAILED })
    }

    @Test
    fun encryptedManifestListingFilesIsRejected() {
        val created = bundle()
        val patched = manifestTextOf(created.bytes).replace(
            "\"files\": [],",
            "\"files\": [{\"path\":\"a.txt\",\"bytes\":1,\"sha256\":\"00\"}],",
        )
        assertTrue(patched != manifestTextOf(created.bytes), "没有找到 files 字段，测试本身失效了")
        val read = archive.read(rebuild(patched, created), passphrase)
        assertFalse(read.canRestore)
        assertTrue(read.problems.any { it.code == BackupProblem.MANIFEST_INVALID })
    }

    @Test
    fun tooLowIterationsAreRejected() {
        val created = bundle()
        val patched = manifestTextOf(created.bytes).replace(
            "\"iterations\": ${BackupCrypto.ITERATIONS}",
            "\"iterations\": 1",
        )
        val read = archive.read(rebuild(patched, created), passphrase)
        assertFalse(read.canRestore)
        assertTrue(
            read.problems.any { it.code == BackupProblem.CRYPT_PARAMS },
            "迭代次数 1 应当被直接拒绝，实际 ${read.problems.map { it.code }}",
        )
    }

    @Test
    fun unlistedExtraEntryIsRejected() {
        val created = bundle()
        val manifestText = manifestTextOf(created.bytes)
        val payload = payloadOf(created.bytes)
        val packed = ZipWriter.write(
            listOf(
                ZipWriteEntry(BackupManifest.ENTRY_NAME, manifestText.encodeToByteArray()),
                ZipWriteEntry(BackupManifest.PAYLOAD_ENTRY, payload),
                ZipWriteEntry("偷塞进来的.txt", "x".encodeToByteArray()),
            ),
        )
        val read = archive.read(packed, passphrase)
        assertFalse(read.canRestore)
        assertTrue(read.problems.any { it.code == BackupProblem.FILE_UNLISTED })
    }

    @Test
    fun shortPassphraseIsRefusedAtCreation() {
        val error = runCatching { bundle(pass = "short") }.exceptionOrNull()
        assertTrue(error is BackupArchiveException)
        assertEquals(BackupProblem.PASSPHRASE_TOO_SHORT, (error as BackupArchiveException).code)
    }

    @Test
    fun unencryptedBackupStillReadable() {
        // 老格式（v1）必须一直能读：加密不能以"旧备份打不开"为代价。
        val plain = archive.create(files, "机器 A", "0.9.0", 1L)
        assertFalse(plain.encrypted)
        assertEquals(1, plain.manifest.backupVersion)
        val read = archive.read(plain.bytes)
        assertTrue(read.canRestore)
        assertFalse(read.requiresPassphrase)
        assertEquals(files.keys, read.payload.keys)
    }

    @Test
    fun encryptedBundleHasNoDataPrefixEntries() {
        val created = bundle()
        val names = mutableListOf<String>()
        ZipInputStream(created.bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.readBytes()
            }
        }
        assertTrue(names.none { it.startsWith(BackupManifest.DATA_PREFIX) }, "加密备份里不该再有 data/ 条目")
    }

    // ---------- 辅助 ----------

    private fun manifestTextOf(bundleBytes: ByteArray): String {
        val entry = morefish.knowledge.trainer.infrastructure.archive.ZipReader
            .entries(bundleBytes)
            .first { it.name == BackupManifest.ENTRY_NAME }
        return morefish.knowledge.trainer.infrastructure.archive.ZipReader
            .read(bundleBytes, entry, morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec)
            .decodeToString()
    }

    private fun payloadOf(bundleBytes: ByteArray): ByteArray {
        val entry = morefish.knowledge.trainer.infrastructure.archive.ZipReader
            .entries(bundleBytes)
            .first { it.name == BackupManifest.PAYLOAD_ENTRY }
        return morefish.knowledge.trainer.infrastructure.archive.ZipReader
            .read(bundleBytes, entry, morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec)
    }

    private fun rebuild(manifestText: String, original: BackupBundle): ByteArray = ZipWriter.write(
        listOf(
            ZipWriteEntry(BackupManifest.ENTRY_NAME, manifestText.encodeToByteArray()),
            ZipWriteEntry(BackupManifest.PAYLOAD_ENTRY, payloadOf(original.bytes)),
        ),
    )

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
