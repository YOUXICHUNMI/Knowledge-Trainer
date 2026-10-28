package morefish.knowledge.trainer.infrastructure.backup

import kotlinx.serialization.json.Json
import morefish.knowledge.trainer.domain.backup.BackupEntry
import morefish.knowledge.trainer.domain.backup.BackupManifest
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipWriteEntry
import morefish.knowledge.trainer.infrastructure.archive.ZipWriter
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.qbk` 容器格式。
 *
 * 这一套测试的重心不是"能读回来"，而是**读不回来的时候说得清为什么**：
 * 备份是最后一道防线，它出问题时用户手里已经没有别的副本了。
 * 所以每种拒绝都必须有稳定错误码，且拒绝时 `canRestore = false`、payload 为空。
 *
 * 另一个重点是"我们写的 ZIP 别人能不能读"：手写的 ZIP 结构如果 CRC 或中央目录偏了一位，
 * 自己的读取器可能照样读得出来，但用户拿去用 7-Zip 打开就是"文件已损坏"。
 * 所以这里用 `java.util.zip.ZipFile` 复核一遍 —— 它是另一套实现，会校验 CRC。
 */
class BackupArchiveTest {

    private val archive = BackupArchive()
    private val json = Json { encodeDefaults = true }

    private val files = mapOf(
        "banks-v1.json" to """{"schemaVersion":2,"banks":[{"bankId":"b1","title":"T","questionCount":3}]}"""
            .encodeToByteArray(),
        "attempts-v1.ndjson" to "{\"attemptId\":\"a1\"}\n{\"attemptId\":\"a2\"}\n".encodeToByteArray(),
        "notes-v1.json" to """{"notes":[{"noteId":"n1"}]}""".encodeToByteArray(),
        "media/b1/pic.png" to ByteArray(64) { it.toByte() },
    )

    private fun bundleOf(source: Map<String, ByteArray> = files) =
        archive.create(source, deviceLabel = "测试机", appVersion = "0.9.0", nowMillis = 1_800_000_000_000L)

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun roundTripKeepsEveryByte() {
        val bundle = bundleOf()
        val read = archive.read(bundle.bytes)

        assertTrue(read.canRestore, "应当可以恢复：${read.problems.map { it.message }}")
        assertEquals(files.keys.sorted(), read.payload.keys.sorted())
        files.forEach { (path, bytes) ->
            assertTrue(bytes.contentEquals(read.payload.getValue(path)), "$path 的内容变了")
        }
        assertEquals(files.size, read.preview?.manifest?.files?.size)
        assertEquals(0, read.preview?.warnings?.size ?: -1)
    }

    @Test
    fun otherZipToolsCanReadWhatWeWrite() {
        val bundle = bundleOf()
        val temp = File.createTempFile("kt-backup", ".qbk")
        try {
            temp.writeBytes(bundle.bytes)
            // ZipFile 会在读完条目时校验 CRC：CRC 写错这里就会抛 ZipException
            ZipFile(temp).use { zip ->
                val names = zip.entries().toList().map { it.name }.sorted()
                assertEquals(
                    listOf(
                        BackupManifest.ENTRY_NAME,
                        "data/attempts-v1.ndjson",
                        "data/banks-v1.json",
                        "data/media/b1/pic.png",
                        "data/notes-v1.json",
                    ),
                    names,
                )
                val media = zip.getInputStream(zip.getEntry("data/media/b1/pic.png")).readBytes()
                assertTrue(files.getValue("media/b1/pic.png").contentEquals(media))
            }
            // 另一条独立路径：ZipInputStream 逐条流式读
            val streamed = mutableListOf<String>()
            ZipInputStream(bundle.bytes.inputStream()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    streamed += entry.name
                    input.readBytes()
                    entry = input.nextEntry
                }
            }
            assertEquals(5, streamed.size)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun sameDataTwiceProducesIdenticalBytes() {
        // ZIP 时间戳固定 + 条目排序固定 → 同内容字节相同，摘要也就相同。
        // 这不是洁癖：用户要能拿两台的摘要核对"是不是同一份数据"。
        assertTrue(bundleOf().bytes.contentEquals(bundleOf().bytes))
    }

    @Test
    fun countsAreReadFromRealFiles() {
        val read = archive.read(bundleOf().bytes)
        val counts = read.preview!!.manifest.counts
        assertEquals(1, counts.banks)
        assertEquals(3, counts.questions)
        assertEquals(2, counts.attempts)
        assertEquals(1, counts.notes)
        assertEquals(1, counts.mediaFiles)
        assertEquals(64, counts.mediaBytes.toInt())
        assertEquals(4, counts.totalFiles)
    }

    // ---------------------------------------------------------------- 拒绝

    @Test
    fun tamperedByteIsRejected() {
        val bytes = bundleOf(mapOf("a.txt" to "hello-world".encodeToByteArray())).bytes
        val marker = "hello-world".encodeToByteArray()
        val at = indexOf(bytes, marker)
        assertTrue(at > 0, "测试前提：明文内容应当能在包里找到")
        bytes[at] = 'H'.code.toByte()

        val read = archive.read(bytes)
        assertFalse(read.canRestore)
        assertTrue(read.payload.isEmpty(), "拒绝时不应交出任何字节")
        assertHasCode(read, BackupProblem.HASH_MISMATCH)
    }

    @Test
    fun missingDataFileIsRejected() {
        val manifest = manifestText(entries = listOf(entry("a.txt", "a".encodeToByteArray())))
        val read = archive.read(craftZip(manifest, emptyMap()))
        assertHasCode(read, BackupProblem.FILE_MISSING)
    }

    @Test
    fun unlistedDataFileIsRejected() {
        val manifest = manifestText(entries = listOf(entry("a.txt", "a".encodeToByteArray())))
        val read = archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray(), "b.txt" to "b".encodeToByteArray())))
        assertHasCode(read, BackupProblem.FILE_UNLISTED)
    }

    @Test
    fun zipWithoutManifestIsRejected() {
        val read = archive.read(ZipWriter.write(listOf(ZipWriteEntry("data/a.txt", "a".encodeToByteArray()))))
        assertHasCode(read, BackupProblem.MANIFEST_MISSING)
    }

    @Test
    fun nonZipIsRejected() {
        val read = archive.read("这不是备份".encodeToByteArray())
        assertHasCode(read, BackupProblem.NOT_ZIP)
    }

    @Test
    fun newerBackupVersionIsRejected() {
        val manifest = manifestText(backupVersion = BackupManifest.BACKUP_VERSION + 1)
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.VERSION_UNSUPPORTED)
    }

    @Test
    fun newerLayoutVersionIsRejected() {
        val manifest = manifestText(layoutVersion = BackupManifest.LAYOUT_VERSION + 1)
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.LAYOUT_UNSUPPORTED)
    }

    @Test
    fun encryptedManifestWithoutCryptParamsIsRejected() {
        // 说"我加密了"却不写用什么算法、多少轮、哪个盐：这种清单没法解，只能拒绝。
        val manifest = manifestText(encrypted = true)
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.CRYPT_PARAMS)
    }

    @Test
    fun wrongFormatIsRejected() {
        val manifest = manifestText(format = "knowledge-trainer-question-bank")
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.FORMAT_UNKNOWN)
    }

    @Test
    fun pathTraversalIsRejected() {
        val manifest = manifestText(entries = listOf(entry("../逃出去.json", "x".encodeToByteArray())))
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.PATH_UNSAFE)
    }

    @Test
    fun digestMismatchIsRejected() {
        val manifest = manifestText(contentDigest = "0".repeat(64))
        assertHasCode(archive.read(craftZip(manifest, mapOf("a.txt" to "a".encodeToByteArray()))), BackupProblem.DIGEST_MISMATCH)
    }

    @Test
    fun emptyFileListIsRejected() {
        val manifest = manifestText(entries = emptyList())
        assertHasCode(archive.read(craftZip(manifest, emptyMap())), BackupProblem.EMPTY)
    }

    @Test
    fun creatingEmptyBackupFails() {
        val error = runCatching { archive.create(emptyMap(), "测试机", "0.9.0", 1L) }.exceptionOrNull()
        assertTrue(error is BackupArchiveException, "空数据不该打出一份空备份")
        assertEquals(BackupProblem.EMPTY, (error as BackupArchiveException).code)
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertHasCode(read: BackupReadResult, code: String) {
        assertFalse(read.canRestore, "应当被拒绝，实际通过了校验")
        assertTrue(
            read.problems.any { it.code == code },
            "缺少错误码 $code，实际是 ${read.problems.map { it.code }}",
        )
    }

    private fun entry(path: String, bytes: ByteArray) =
        BackupEntry(path = path, bytes = bytes.size.toLong(), sha256 = Sha256.hex(bytes))

    private fun manifestText(
        format: String = BackupManifest.BACKUP_FORMAT,
        backupVersion: Int = BackupManifest.BACKUP_VERSION,
        layoutVersion: Int = BackupManifest.LAYOUT_VERSION,
        encrypted: Boolean = false,
        entries: List<BackupEntry> = listOf(entry("a.txt", "a".encodeToByteArray())),
        contentDigest: String = "",
    ): String {
        val manifest = BackupManifest(
            format = format,
            backupVersion = backupVersion,
            layoutVersion = layoutVersion,
            createdAtMillis = 1_800_000_000_000L,
            deviceLabel = "测试机",
            appVersion = "0.9.0",
            encrypted = encrypted,
            files = entries,
            contentDigest = contentDigest,
        )
        return json.encodeToString(BackupManifest.serializer(), manifest)
    }

    /** 用我们自己的写入器拼一份"清单说一套、数据是另一套"的包。 */
    private fun craftZip(manifest: String, data: Map<String, ByteArray>): ByteArray = ZipWriter.write(
        buildList {
            add(ZipWriteEntry(BackupManifest.ENTRY_NAME, manifest.encodeToByteArray()))
            data.forEach { (path, bytes) -> add(ZipWriteEntry(BackupManifest.DATA_PREFIX + path, bytes)) }
        },
    )

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
