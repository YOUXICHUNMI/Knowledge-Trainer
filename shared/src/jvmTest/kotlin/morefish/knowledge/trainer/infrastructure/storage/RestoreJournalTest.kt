package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.RestoreRecoveryKind
import morefish.knowledge.trainer.application.port.VaultException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 恢复被打断之后，启动时能不能把现场收拾干净。
 *
 * 这里**不去真的杀进程**（测试里杀不掉自己的 finally，杀出来的现场也不可控），
 * 而是直接构造"崩溃那一刻磁盘上应该是什么样"，再让 [DirectoryVault.recoverInterrupted] 去收拾。
 * 这正是崩溃恢复该被测试的方式：核心是"从任意中间状态出发，结果都必须是
 * 要么是原来那份数据、要么是备份里那份数据，绝不允许是第三样东西"。
 */
class RestoreJournalTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    /** 每个测试一个独立父目录，数据目录是 `父目录/kt`，日志与旧数据都在父目录里。 */
    private fun parent(): File {
        val dir = Files.createTempDirectory("kt-restore-journal").toFile()
        tempDirs += dir
        return dir
    }

    private fun rootOf(parent: File): File = File(parent, "kt")

    private fun seed(dir: File, marker: String) {
        dir.mkdirs()
        File(dir, "banks-v1.json").writeText("""{"marker":"$marker"}""")
        File(dir, "banks/b1/questions-v1.ndjson").apply { parentFile.mkdirs() }
            .writeText("q-$marker")
    }

    private fun readAll(dir: File): Map<String, String> =
        dir.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    private fun stagingFiles(): Map<String, ByteArray> = mapOf(
        "banks-v1.json" to """{"marker":"new"}""".toByteArray(),
        "banks/b9/questions-v1.ndjson" to "q-new".toByteArray(),
    )

    private fun writeStaging(staging: File, files: Map<String, ByteArray>): Long {
        staging.mkdirs()
        var bytes = 0L
        files.forEach { (path, content) ->
            File(staging, path).apply { parentFile.mkdirs() }.writeBytes(content)
            bytes += content.size
        }
        return bytes
    }

    private fun writeJournal(
        root: File,
        phase: String,
        hadRoot: Boolean,
        stagingName: String,
        previousName: String,
        files: Int,
        bytes: Long,
    ) {
        RestoreJournalFile.write(
            root,
            RestoreJournal(
                stamp = 1_800_000_000_000L,
                phase = phase,
                hadRoot = hadRoot,
                stagingName = stagingName,
                previousName = previousName,
                files = files,
                bytes = bytes,
            ),
        )
    }

    // ---- 干净状态 ----

    @Test
    fun cleanStateNeedsNoRecovery() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val vault = DirectoryVault(root)

        assertNull(vault.recoverInterrupted(), "没有日志就不该报'上次被打断'")
        assertFalse(RestoreJournalFile.locate(root).exists(), "不该凭空造一份日志出来")
        assertEquals("old", File(root, "banks-v1.json").readText().let { extractMarker(it) })
    }

    @Test
    fun successfulRestoreLeavesNoJournal() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val vault = DirectoryVault(root)

        vault.replaceAll(stagingFiles())

        assertFalse(RestoreJournalFile.locate(root).exists(), "恢复成功必须把日志删掉")
        assertNull(vault.recoverInterrupted(), "成功之后启动不该有任何'被打断'的说法")
        val now = readAll(root)
        assertEquals("""{"marker":"new"}""", now["banks-v1.json"])
        assertTrue(now.containsKey("banks/b9/questions-v1.ndjson"))
        // 旧数据必须还在（那是唯一的退路）
        val previous = parent.listFiles()!!.single { it.name.startsWith("kt.pre-restore-") }
        assertEquals("""{"marker":"old"}""", File(previous, "banks-v1.json").readText())
        assertTrue(parent.listFiles()!!.none { it.name.startsWith("kt.restore-staging-") }, "半成品要清掉")
    }

    // ---- STAGING：数据目录还没被碰过 ----

    @Test
    fun stagingPhaseRollsBackWithoutTouchingData() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val before = readAll(root)

        val staging = File(parent, "kt.restore-staging-1")
        writeStaging(staging, mapOf("banks-v1.json" to """{"marker":"new"}""".toByteArray()))
        writeJournal(root, RestoreJournal.PHASE_STAGING, true, staging.name, "kt.pre-restore-1", 2, 0)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_BACK, recovery.kind)
        assertTrue(recovery.stagingRemoved)
        assertEquals(before, readAll(root), "这个阶段数据目录不该被动过")
        assertFalse(staging.exists(), "半成品要清掉")
        assertFalse(RestoreJournalFile.locate(root).exists(), "处理完日志要删掉")
    }

    @Test
    fun stagingPhaseOnFreshMachineReportsNothingToRestore() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        val staging = File(parent, "kt.restore-staging-1")
        writeStaging(staging, stagingFiles())
        writeJournal(root, RestoreJournal.PHASE_STAGING, false, staging.name, "kt.pre-restore-1", 2, 0)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_BACK, recovery.kind)
        assertFalse(root.exists(), "本来就没有数据，收拾完也不该凭空造一个")
        assertFalse(staging.exists())
        assertNotNull(recovery.detail)
    }

    // ---- SWAPPING：数据目录可能短暂不存在 ----

    @Test
    fun swappingWithCompleteStagingRollsForward() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        val previous = File(parent, "kt.pre-restore-1")
        seed(previous, "old")
        val staging = File(parent, "kt.restore-staging-1")
        val bytes = writeStaging(staging, stagingFiles())
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, staging.name, previous.name, 2, bytes)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_FORWARD, recovery.kind)
        assertEquals("""{"marker":"new"}""", File(root, "banks-v1.json").readText(), "新数据要接上")
        assertTrue(previous.exists(), "旧数据是退路，不能被自愈删掉")
        assertEquals("old", extractMarker(File(previous, "banks-v1.json").readText()))
        assertFalse(RestoreJournalFile.locate(root).exists())
        assertEquals(2, recovery.filesInPlace)
    }

    @Test
    fun swappingWithIncompleteStagingRollsBackToOldData() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        val previous = File(parent, "kt.pre-restore-1")
        seed(previous, "old")
        val before = readAll(previous)
        val staging = File(parent, "kt.restore-staging-1")
        // 只写了半个文件：日志说 2 个文件 45 字节，对不上就不许前滚
        writeStaging(staging, mapOf("banks-v1.json" to """{"marker":"new"}""".toByteArray()))
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, staging.name, previous.name, 2, 999)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP, recovery.kind)
        assertEquals(before, readAll(root), "残缺的新数据不能生效")
        assertFalse(staging.exists())
    }

    @Test
    fun swappingWithoutStagingRollsBackToPrevious() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        val previous = File(parent, "kt.pre-restore-1")
        seed(previous, "old")
        val before = readAll(previous)
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, "kt.restore-staging-1", previous.name, 2, 45)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP, recovery.kind)
        assertEquals(before, readAll(root))
        assertEquals(2, recovery.filesInPlace)
    }

    @Test
    fun swappingWithBothSidesPresentMeansItAlreadyWorked() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "new")
        val previous = File(parent, "kt.pre-restore-1")
        seed(previous, "old")
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, "kt.restore-staging-1", previous.name, 2, 45)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ALREADY_DONE, recovery.kind)
        assertEquals("new", extractMarker(File(root, "banks-v1.json").readText()), "新数据已经在位就不能动它")
        assertEquals(previous.absolutePath, recovery.previousLocation)
        assertFalse(RestoreJournalFile.locate(root).exists())
    }

    @Test
    fun swappingBeforeOldDirMovedRollsBack() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val before = readAll(root)
        val staging = File(parent, "kt.restore-staging-1")
        val bytes = writeStaging(staging, stagingFiles())
        // 旧目录还没被移走（previous 不存在）—— 这一步还没发生就断了
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, staging.name, "kt.pre-restore-1", 2, bytes)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.ROLLED_BACK, recovery.kind)
        assertEquals(before, readAll(root))
        assertFalse(staging.exists())
    }

    @Test
    fun nothingRecoverableIsReportedAsDataMissing() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        writeJournal(root, RestoreJournal.PHASE_SWAPPING, true, "kt.restore-staging-1", "kt.pre-restore-1", 2, 45)

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.DATA_MISSING, recovery.kind)
        assertFalse(root.exists(), "什么都不该造出来")
        assertNotNull(recovery.detail)
        // 日志留着：这还需要人来处理，不能悄悄删掉
        assertTrue(RestoreJournalFile.locate(root).exists())
    }

    // ---- 日志本身 ----

    @Test
    fun unreadableJournalIsQuarantinedNotDeleted() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val before = readAll(root)
        RestoreJournalFile.locate(root).writeText("这不是日志\nphase=??\n")

        val recovery = assertNotNull(DirectoryVault(root).recoverInterrupted())
        assertEquals(RestoreRecoveryKind.JOURNAL_UNREADABLE, recovery.kind)
        assertEquals(before, readAll(root), "看不懂就什么都别动")
        assertFalse(RestoreJournalFile.locate(root).exists(), "原位置的日志要移走，否则每次启动都报")
        val kept = parent.listFiles()!!.filter { it.name.contains(".unreadable-") }
        assertEquals(1, kept.size, "读不懂的日志要改名留档，不能删")
    }

    @Test
    fun journalRoundTripsAndRejectsGarbage() {
        val journal = RestoreJournal(
            stamp = 1_800_000_000_000L,
            phase = RestoreJournal.PHASE_SWAPPING,
            hadRoot = true,
            stagingName = "kt.restore-staging-123",
            previousName = "kt.pre-restore-123",
            files = 42,
            bytes = 123_456L,
        )
        assertEquals(journal, RestoreJournal.decode(journal.encode()), "自己写的自己要能读回来")

        assertNull(RestoreJournal.decode(""), "空文件不是日志")
        assertNull(RestoreJournal.decode("kt-qbk-restore-journal 2\nstamp=1\n"), "版本不认识就拒绝")
        assertNull(
            RestoreJournal.decode("kt-qbk-restore-journal 1\nstamp=1\nphase=SWAPPING\n"),
            "少字段就是残的，不能猜默认值",
        )
        assertNull(
            RestoreJournal.decode(journal.encode().replace("phase=SWAPPING", "phase=ALMOST")),
            "没见过的阶段名一律拒绝",
        )
    }

    // ---- 正常失败不能留下"被中断"的假象 ----

    @Test
    fun failedRestoreCleansItsOwnJournal() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        val before = readAll(root)
        val vault = DirectoryVault(root)

        assertFailsWith<VaultException> {
            vault.replaceAll(mapOf("../escape.txt" to "x".toByteArray()))
        }
        assertEquals(before, readAll(root), "失败要一个字节不动")
        assertNull(vault.recoverInterrupted(), "正常失败自己收尾了，下次启动不该报'上次被打断'")
        assertTrue(parent.listFiles()!!.none { it.name.startsWith("kt.restore-staging-") })
    }

    // ---- 日志不许进备份 ----

    @Test
    fun journalNeverEntersTheBackup() = runTest {
        val parent = parent()
        val root = rootOf(parent)
        seed(root, "old")
        // 模拟"恢复做到一半停在 STAGING"：日志在磁盘上
        writeJournal(root, RestoreJournal.PHASE_STAGING, true, "kt.restore-staging-1", "kt.pre-restore-1", 2, 0)

        val snapshot = DirectoryVault(root).snapshot()
        assertEquals(
            setOf("banks-v1.json", "banks/b1/questions-v1.ndjson"),
            snapshot.files.keys,
            "日志要是进了备份，装到别的机器上会被当成'那边也在恢复'",
        )
    }
}

/** 从测试自己写的那种 JSON 里取回标记（内容格式是本文件定的，用不着正则）。 */
private fun extractMarker(json: String): String =
    json.substringAfter("\"marker\":\"", "").substringBefore("\"")
