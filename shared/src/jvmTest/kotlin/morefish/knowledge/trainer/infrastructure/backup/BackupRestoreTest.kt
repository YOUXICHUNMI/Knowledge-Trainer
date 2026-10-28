package morefish.knowledge.trainer.infrastructure.backup

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.DataVault
import morefish.knowledge.trainer.application.port.UnsupportedDataVault
import morefish.knowledge.trainer.application.port.VaultException
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.BackupExportOutcome
import morefish.knowledge.trainer.application.usecase.CreateBackupUseCase
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.RestoreBackupUseCase
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.infrastructure.storage.DirectoryVault
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.presentation.AppDependencies
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 备份 → 换一台机器 → 恢复。这是这一块唯一真正的验收标准：
 * **题库、历史、笔记都还在，而且身份没变**（bankId、作答时间戳）。
 *
 * 全程用真实文件（临时目录 + [FileTextStorage] + [FileMediaStore]），
 * 不用内存替身 —— 备份这件事的全部难点都在磁盘上。
 */
class BackupRestoreTest {

    private val clock = WallClock { 1_800_000_000_000L }
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempRoot(): File {
        val dir = Files.createTempDirectory("kt-backup-test").toFile()
        tempDirs += dir
        return dir
    }

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples")
    }

    private fun starterBytes(): ByteArray = File(examples, "starter.qbank").readBytes()

    private fun depsAt(root: File): AppDependencies = AppDependencies(
        FileTextStorage(root),
        clock = clock,
        mediaStore = FileMediaStore(root),
        vault = DirectoryVault(root, deviceLabel = "测试机"),
    )

    private fun attemptRecord(bankId: String) = AttemptRecord(
        attemptId = "a-1",
        sessionId = "s1",
        bankId = bankId,
        questionId = "q002",
        questionType = QuestionType.MULTIPLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = listOf("o1", "o2", "o3", "o4"),
        selectedOptionIds = setOf("o1", "o2", "o3"),
        textAnswer = null,
        verdict = Verdict.CORRECT,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "2cf5290769b52058",
        submittedAtMillis = 1_799_999_000_000L,
        elapsedMillis = 8_000,
        explanationSeen = false,
    )

    /** 造一台"用过一阵子的机器"：一个题库、一条历史、一条笔记。 */
    private suspend fun seededRoot(): File {
        val root = tempRoot()
        val deps = depsAt(root)
        val outcome = ImportBankUseCase(deps.bankRepository, clock = clock)
            .importQbank(starterBytes(), "示例", "test")
        val imported = assertIs<morefish.knowledge.trainer.application.usecase.ImportOutcome.Imported>(outcome)
        deps.attemptRepository.append(attemptRecord(imported.bank.bankId))
        deps.noteRepository.upsert(
            Note(
                noteId = "n1",
                scope = NoteScope.DRAFT,
                title = "换设备也要带走的笔记",
                bodyMarkdown = "**重点**：这一行必须活着到另一台机器。",
                createdAtMillis = 1_799_999_500_000L,
                updatedAtMillis = 1_799_999_500_000L,
            ),
        )
        return root
    }

    // ---------------------------------------------------------------- 迁移

    @Test
    fun backupOnOneMachineRestoresOnAnother() = runTest {
        val rootA = seededRoot()
        val vaultA = DirectoryVault(rootA, deviceLabel = "机器 A")
        val created = CreateBackupUseCase(vaultA, clock = clock).create()
        val bundle = assertIs<BackupExportOutcome.Ok>(created, "导出应当成功").bundle

        assertEquals(1, bundle.manifest.counts.banks)
        assertEquals(3, bundle.manifest.counts.questions)
        assertEquals(1, bundle.manifest.counts.attempts)
        assertEquals(1, bundle.manifest.counts.notes)
        assertEquals("机器 A", bundle.manifest.deviceLabel)
        assertTrue(bundle.fileName.endsWith(".qbk"), "文件名应当带 .qbk：${bundle.fileName}")

        // —— 换一台空机器
        val rootB = tempRoot()
        val depsB = depsAt(rootB)
        val outcome = RestoreBackupUseCase(depsB.vault).restore(bundle.bytes)
        assertIs<RestoreBackupUseCase.Outcome.Ok>(outcome, "恢复应当成功：$outcome")

        val banks = depsB.bankRepository.list()
        assertEquals(1, banks.size, "题库没搬过去")
        assertEquals(3, banks.first().questions.size)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", banks.first().bankId, "bankId 必须原样保留")

        val attempts = depsB.attemptRepository.all()
        assertEquals(1, attempts.size)
        assertEquals(1_799_999_000_000L, attempts.first().submittedAtMillis, "作答时间不能被改写")
        assertEquals("a-1", attempts.first().attemptId)

        val notes = depsB.noteRepository.all()
        assertEquals(1, notes.size)
        assertTrue(notes.first().bodyMarkdown.contains("**重点**"), "笔记正文要原样搬过去")
    }

    @Test
    fun restoringOntoAMachineWithDataKeepsTheOldDataAside() = runTest {
        val root = seededRoot()
        val vault = DirectoryVault(root, deviceLabel = "机器 A")
        val bundle = assertIs<BackupExportOutcome.Ok>(CreateBackupUseCase(vault, clock = clock).create()).bundle

        // 再来一次：这次本机已经有数据了
        val outcome = assertIs<RestoreBackupUseCase.Outcome.Ok>(
            RestoreBackupUseCase(vault).restore(bundle.bytes),
        )
        val previous = outcome.outcome.previousLocation
        assertNotNull(previous, "覆盖前必须把旧数据挪到旁边的目录，并告诉用户在哪")
        assertTrue(File(previous).isDirectory, "旧数据目录应当真的存在：$previous")
        assertTrue(File(previous).listFiles()!!.isNotEmpty(), "旧数据目录不该是空的")
    }

    @Test
    fun corruptBackupIsRejectedAndLocalDataUntouched() = runTest {
        val root = seededRoot()
        val deps = depsAt(root)
        val vault = DirectoryVault(root, deviceLabel = "机器 A")
        val bundle = assertIs<BackupExportOutcome.Ok>(CreateBackupUseCase(vault, clock = clock).create()).bundle

        val broken = bundle.bytes.copyOf()
        broken[broken.size / 2] = (broken[broken.size / 2].toInt() xor 0xFF).toByte()

        val outcome = RestoreBackupUseCase(deps.vault).restore(broken)
        assertIs<RestoreBackupUseCase.Outcome.Rejected>(outcome, "坏备份必须被拒绝")

        // 拒绝之后本机数据一个字节都不该动
        assertEquals(1, deps.bankRepository.list().size)
        assertEquals(3, deps.bankRepository.list().first().questions.size)
        assertEquals(1, deps.attemptRepository.all().size)
    }

    // ---------------------------------------------------------------- 加密迁移

    @Test
    fun encryptedBackupMigratesToAnotherMachine() = runTest {
        val passphrase = "换机器用的口令-海豚毛线"
        val rootA = seededRoot()
        val vaultA = DirectoryVault(rootA, deviceLabel = "机器 A")
        val bundle = assertIs<BackupExportOutcome.Ok>(
            CreateBackupUseCase(vaultA, clock = clock).create(passphrase),
        ).bundle
        assertTrue(bundle.encrypted, "给了口令就应当导出加密备份")
        assertEquals(2, bundle.manifest.backupVersion)

        val rootB = tempRoot()
        val depsB = depsAt(rootB)

        // ① 不给口令：连"里面有什么文件"都看不到，但统计还看得见
        val locked = assertIs<RestoreBackupUseCase.Outcome.Rejected>(
            RestoreBackupUseCase(depsB.vault).restore(bundle.bytes),
        )
        assertTrue(locked.problems.any { it.code == "BACKUP_PASSPHRASE_REQUIRED" })
        assertEquals(0, depsB.bankRepository.list().size, "没解锁就不该写任何东西")

        // ② 口令错：拒绝，本机仍然一个字节没动
        val wrong = assertIs<RestoreBackupUseCase.Outcome.Rejected>(
            RestoreBackupUseCase(depsB.vault).restore(bundle.bytes, "记错的口令-海豚毛线"),
        )
        assertTrue(wrong.problems.any { it.code == "BACKUP_DECRYPT_FAILED" })
        assertEquals(0, depsB.bankRepository.list().size)

        // ③ 口令对：原样搬过去，身份不变
        assertIs<RestoreBackupUseCase.Outcome.Ok>(
            RestoreBackupUseCase(depsB.vault).restore(bundle.bytes, passphrase),
            "口令正确却恢复失败",
        )
        val banks = depsB.bankRepository.list()
        assertEquals(1, banks.size)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", banks.first().bankId)
        assertEquals(3, banks.first().questions.size)
        val attempts = depsB.attemptRepository.all()
        assertEquals(1, attempts.size)
        assertEquals(1_799_999_000_000L, attempts.first().submittedAtMillis, "加密不该改动任何时间戳")
        assertEquals("a-1", attempts.first().attemptId)
        assertEquals(1, depsB.noteRepository.all().size)
        assertTrue(depsB.noteRepository.all().first().bodyMarkdown.contains("**重点**"))
    }

    @Test
    fun wrongPassphraseOnMachineWithDataLeavesItIntact() = runTest {
        val passphrase = "本机自己的口令-毛线九月"
        val root = seededRoot()
        val deps = depsAt(root)
        val vault = DirectoryVault(root, deviceLabel = "机器 A")
        val bundle = assertIs<BackupExportOutcome.Ok>(
            CreateBackupUseCase(vault, clock = clock).create(passphrase),
        ).bundle

        val outcome = assertIs<RestoreBackupUseCase.Outcome.Rejected>(
            RestoreBackupUseCase(deps.vault).restore(bundle.bytes, "别的口令-毛线九月"),
        )
        assertTrue(outcome.problems.any { it.code == "BACKUP_DECRYPT_FAILED" })

        // 本机数据必须原封不动：题库还在、历史还在、笔记还在、没有被挪到 *.pre-restore-*
        assertEquals(1, deps.bankRepository.list().size)
        assertEquals(1, deps.attemptRepository.all().size)
        assertEquals(1, deps.noteRepository.all().size)
        assertTrue(
            root.listFiles()!!.none { it.name.contains("pre-restore") },
            "解锁失败不该触发任何目录切换",
        )
    }

    @Test
    fun encryptedBackupKeepsImagesAndMediaIntact() = runTest {
        // 图片是二进制，最容易在"抄字节 → 加密 → 解密 → 写回"这条路上被改坏。
        val root = tempRoot()
        val deps = depsAt(root)
        File(root, "banks-v1.json").writeText("""{"schemaVersion":2,"banks":[]}""")
        File(root, "media").mkdirs()
        val png = ByteArray(64) { (it * 3).toByte() }
        File(root, "media/pic.png").writeBytes(png)

        val exported = CreateBackupUseCase(deps.vault, clock = clock).create("带图片的口令-海豚九月")
        val bundle = assertIs<BackupExportOutcome.Ok>(
            exported,
            (exported as? BackupExportOutcome.Failed)?.message ?: "导出失败但没给原因",
        ).bundle

        val target = tempRoot()
        assertIs<RestoreBackupUseCase.Outcome.Ok>(
            RestoreBackupUseCase(DirectoryVault(target)).restore(bundle.bytes, "带图片的口令-海豚九月"),
        )
        val restored = File(target, "media/pic.png")
        assertTrue(restored.isFile, "图片没搬过去")
        assertTrue(png.contentEquals(restored.readBytes()), "图片字节被改动了")
    }

    // ---------------------------------------------------------------- 目录级行为

    @Test
    fun snapshotSkipsTempFiles() = runTest {
        val root = tempRoot()
        File(root, "banks-v1.json").writeText("""{"schemaVersion":2,"banks":[]}""")
        File(root, "banks-v1.json.tmp").writeText("半截内容")
        File(root, "media").mkdirs()
        File(root, "media/pic.png").writeBytes(ByteArray(4))

        val files = DirectoryVault(root).snapshot().files
        assertEquals(setOf("banks-v1.json", "media/pic.png"), files.keys)
    }

    @Test
    fun unsafePathIsRefusedAndDataStaysIntact() = runTest {
        val root = tempRoot()
        File(root, "banks-v1.json").writeText("""{"schemaVersion":2,"banks":[]}""")

        val error = runCatching {
            DirectoryVault(root).replaceAll(mapOf("../逃出去.json" to "x".encodeToByteArray()))
        }.exceptionOrNull()
        assertIs<VaultException>(error, "越界路径必须被拒绝")
        assertEquals("VAULT_PATH_UNSAFE", error.code)
        assertTrue(File(root, "banks-v1.json").isFile, "被拒绝后本机文件应当还在")
        assertFalse(File(root.parentFile, "逃出去.json").exists(), "不该在数据目录之外写出任何东西")
    }

    @Test
    fun unsupportedVaultSaysWhyInsteadOfPretending() = runTest {
        val outcome = CreateBackupUseCase(UnsupportedDataVault, clock = clock).create()
        val failed = assertIs<BackupExportOutcome.Failed>(outcome)
        assertTrue(failed.message.contains("内存"), "应当说清原因：${failed.message}")

        val restored = RestoreBackupUseCase(UnsupportedDataVault).restore(ByteArray(4))
        assertIs<RestoreBackupUseCase.Outcome.Rejected>(restored)
    }

    @Test
    fun emptyDataDirectoryIsNotABackup() = runTest {
        val root = tempRoot()
        val outcome = CreateBackupUseCase(DirectoryVault(root), clock = clock).create()
        val failed = assertIs<BackupExportOutcome.Failed>(outcome, "没有数据时不该打出一份空备份")
        assertTrue(failed.message.contains("还没有任何数据"), failed.message)
        assertNull(root.listFiles()?.firstOrNull { it.name.endsWith(".qbk") })
    }

    @Test
    fun vaultReportsItsLocation() {
        val root = tempRoot()
        val vault: DataVault = DirectoryVault(root)
        assertTrue(vault.isSupported)
        assertEquals(root.absolutePath, vault.locationLabel)
        assertNull(vault.unsupportedReason)
    }
}
