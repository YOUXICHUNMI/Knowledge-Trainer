package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.PickedFile
import morefish.knowledge.trainer.application.port.RestoreRecovery
import morefish.knowledge.trainer.application.port.RestoreRecoveryKind
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.infrastructure.storage.DirectoryVault
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 备份页的接线：导出 → 选文件 → 预览 → 确认 → 重新载入。
 *
 * 这里用一个假的文件选择器（真机上它是系统对话框），验证的是"控制器有没有把
 * 五个动作串对"，以及**恢复之后界面上的数据真的换了** ——
 * 单纯的用例测试证明不了这一点，因为仓储缓存没刷新时，界面会继续显示旧题库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupFlowTest {

    private val clock = WallClock { 1_800_000_000_000L }
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempRoot(): File {
        val dir = Files.createTempDirectory("kt-backup-flow").toFile()
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

    /** 假的选择器：记住"上次保存到哪"和"下次选中哪个文件"。 */
    private class FakePicker : FilePicker {
        override val isAvailable: Boolean = true
        override val unavailableReason: String? = null
        override val canSave: Boolean = true
        var savedTo: File? = null
        var nextBackup: PickedFile? = null
        var cancelled = false

        override suspend fun pickPackage(): PickedFile? = null
        override suspend fun pickBackup(): PickedFile? = nextBackup
        override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
            if (cancelled) return false
            val target = File.createTempFile("kt-saved", ".qbk")
            target.writeBytes(bytes)
            savedTo = target
            return true
        }
    }

    private suspend fun controllerOn(
        root: File,
        scheduler: TestCoroutineScheduler,
        picker: FakePicker,
        recovery: RestoreRecovery? = null,
    ): AppController {
        val deps = AppDependencies(
            FileTextStorage(root),
            clock = clock,
            filePicker = picker,
            mediaStore = FileMediaStore(root),
            vault = DirectoryVault(root, deviceLabel = "测试机"),
            restoreRecovery = recovery,
        )
        ImportBankUseCase(deps.bankRepository, clock = clock)
            .importQbank(File(examples, "starter.qbank").readBytes(), "示例", "test")
        val controller = AppController(deps, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        controller.refresh()
        scheduler.advanceUntilIdle()
        return controller
    }

    @Test
    fun exportThenRestoreOnAFreshMachine() = runTest {
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker()
        val rootA = tempRoot()
        val a = controllerOn(rootA, scheduler, picker)

        a.openBackup()
        assertEquals(Screen.BACKUP, a.screen)
        assertEquals(1, a.backup.summary.banks)
        assertEquals(3, a.backup.summary.questions)

        a.exportBackup()
        scheduler.advanceUntilIdle()
        val exported = assertNotNull(a.backup.exported, "导出成功后应当留下可核对的凭据")
        assertTrue(exported.digest.length == 64, "摘要应当是 64 位十六进制")
        val qbk = assertNotNull(picker.savedTo, "应当写到文件").also { tempDirs += it }
        assertTrue(qbk.isFile && qbk.length() > 0)

        // —— 换一台空机器：选中刚才那份文件
        val rootB = tempRoot()
        val pickerB = FakePicker().apply { nextBackup = PickedFile("bak.qbk", qbk.readBytes()) }
        val b = controllerOn(rootB, scheduler, pickerB)
        // controllerOn 已经导入了一个库；先清掉，模拟"全新的机器"
        File(rootB, "banks-v1.json").delete()
        b.refresh()
        scheduler.advanceUntilIdle()
        assertEquals(0, b.banks.size)

        b.openBackup()
        b.pickBackupFile()
        scheduler.advanceUntilIdle()
        val intake = assertNotNull(b.backup.intake, "应当读出一份待确认的备份")
        assertTrue(intake.canRestore, "这份备份应当可以恢复：${intake.problems.map { it.message }}")
        assertEquals(1, intake.preview?.manifest?.counts?.banks)

        b.confirmRestore()
        scheduler.advanceUntilIdle()
        assertEquals(Screen.HOME, b.screen, "恢复完应当回首页而不是停在备份页")
        assertEquals(1, b.banks.size, "恢复之后界面上的题库必须换成备份里的")
        assertEquals(3, b.banks.first().questions.size)
        assertNull(b.backup.intake, "确认之后不该还挂着待确认状态")
        assertFalse(b.banner?.isError ?: true, "成功横幅不该是错误样式：${b.banner?.text}")
    }

    @Test
    fun cancelledSaveIsNotAnError() = runTest {
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker().apply { cancelled = true }
        val controller = controllerOn(tempRoot(), scheduler, picker)

        controller.openBackup()
        controller.exportBackup()
        scheduler.advanceUntilIdle()

        assertNull(controller.backup.exported, "取消保存不该产生一份'已导出'")
        assertFalse(controller.banner?.isError ?: true, "取消不是失败")
    }

    @Test
    fun encryptedExportUnlocksOnAnotherMachine() = runTest {
        val passphrase = "备份口令-海豚毛线九月"
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker()
        val a = controllerOn(tempRoot(), scheduler, picker)

        a.openBackup()
        // 与界面完全同一套动作：打开开关 → 输两次 → 勾确认 → 点导出
        a.backup.toggleEncrypt()
        a.backup.updateExportPassphrase(passphrase)
        a.backup.updateExportConfirm(passphrase)
        assertNotNull(a.backup.exportBlockReason, "还没勾「忘记口令就打不开」就不该能导出")
        a.backup.updateExportAck(true)
        assertNull(a.backup.exportBlockReason)

        a.exportBackup()
        scheduler.advanceUntilIdle()
        val exported = assertNotNull(a.backup.exported, "加密导出应当成功")
        assertTrue(exported.encrypted, "给了口令就该是加密备份")
        assertEquals(64, exported.digest.length, "加密备份也要能显示内容摘要")
        // 加密清单里没有文件明细，文件数得从统计里来：starter 导入后是
        // banks-v1.json + banks/<bankId>/questions-v1.ndjson 两个文件。
        assertEquals(2, exported.fileCount, "加密导出也要报对文件数")
        assertEquals("", a.backup.exportPassphrase, "导出之后口令必须从界面状态里抹掉")
        val qbk = assertNotNull(picker.savedTo).also { tempDirs += it }

        // —— 另一台空机器
        val rootB = tempRoot()
        val pickerB = FakePicker().apply { nextBackup = PickedFile("bak.qbk", qbk.readBytes()) }
        val b = controllerOn(rootB, scheduler, pickerB)
        File(rootB, "banks-v1.json").delete()
        b.refresh()
        scheduler.advanceUntilIdle()
        assertEquals(0, b.banks.size)

        b.openBackup()
        b.pickBackupFile()
        scheduler.advanceUntilIdle()

        val locked = assertNotNull(b.backup.intake)
        assertTrue(locked.requiresPassphrase, "加密备份应当先要口令")
        assertFalse(locked.canRestore)
        assertEquals(1, locked.preview?.manifest?.counts?.banks, "没解锁也要能看到统计，方便判断要不要这份")
        assertFalse(b.banner?.isError ?: true, "「要口令」不是错误，不该打红条")
        assertEquals("", b.backup.unlockPassphrase)

        // 口令错：拒绝，而且界面停在"可以重试"的状态
        b.unlockBackup("记错的口令-海豚毛线九月")
        scheduler.advanceUntilIdle()
        val afterWrong = assertNotNull(b.backup.intake)
        assertFalse(afterWrong.canRestore)
        assertEquals("", b.backup.unlockPassphrase, "口令错时不该把口令留在内存里")
        assertTrue(b.banner?.isError ?: false)

        // 口令对：解锁 → 确认 → 数据换掉
        b.unlockBackup(passphrase)
        scheduler.advanceUntilIdle()
        assertTrue(assertNotNull(b.backup.intake).canRestore, "口令对了却没解锁：${b.banner?.text}")
        assertEquals(passphrase, b.backup.unlockPassphrase)

        b.confirmRestore()
        scheduler.advanceUntilIdle()
        assertEquals(Screen.HOME, b.screen)
        assertEquals(1, b.banks.size, "加密备份恢复后界面上的题库必须换成备份里的")
        assertEquals(3, b.banks.first().questions.size)
        assertEquals("", b.backup.unlockPassphrase, "恢复完成后口令必须立刻清掉")
    }

    /**
     * 解锁之后不恢复、直接返回：口令与待确认的备份都必须跟着消失。
     *
     * 这一条钉的是"这一屏的状态"与"整库的状态"的分界。待确认的备份、解锁口令、
     * 加密开关都属于备份页；而「离开」这个动作在组合根、清理在备份控制器 ——
     * 两边都做对，才真的抹得掉。少清一处，一份已经解开的备份会一直留在内存里，
     * 下次进页面还挂着"可以恢复"，而用户以为那已经是很久以前的事。
     */
    @Test
    fun leavingTheBackupPageForgetsTheUnlockedPassphrase() = runTest {
        val passphrase = "备份口令-海豚毛线九月"
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker()
        val controller = controllerOn(tempRoot(), scheduler, picker)

        controller.openBackup()
        controller.backup.toggleEncrypt()
        controller.backup.updateExportPassphrase(passphrase)
        controller.backup.updateExportConfirm(passphrase)
        controller.backup.updateExportAck(true)
        controller.exportBackup()
        scheduler.advanceUntilIdle()
        val qbk = assertNotNull(picker.savedTo).also { tempDirs += it }

        controller.intakeBackupBytes("bak.qbk", qbk.readBytes())
        controller.unlockBackup(passphrase)
        scheduler.advanceUntilIdle()
        assertEquals(
            passphrase,
            controller.backup.unlockPassphrase,
            "解锁之后要留住口令，否则每点一次都要重新派生一遍",
        )
        assertTrue(assertNotNull(controller.backup.intake).canRestore)

        controller.closeBackup()
        assertEquals(Screen.HOME, controller.screen)
        assertEquals("", controller.backup.unlockPassphrase, "离开页面不能把钥匙留在锁孔上")
        assertNull(controller.backup.intake, "待确认的备份不该挂到下一次进来")
        assertFalse(controller.backup.encrypt, "加密开关回到默认关，下次进来重新决定")
    }

    @Test
    fun shortPassphraseIsRefusedBeforeAnythingIsWritten() = runTest {
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker()
        val controller = controllerOn(tempRoot(), scheduler, picker)

        controller.openBackup()
        controller.backup.toggleEncrypt()
        controller.backup.updateExportPassphrase("short")
        controller.backup.updateExportConfirm("short")
        controller.backup.updateExportAck(true)

        // 按钮本身就该是灰的，并且说明原因
        assertEquals("口令至少 10 个字符", controller.backup.exportBlockReason)

        controller.exportBackup()
        scheduler.advanceUntilIdle()

        assertNull(controller.backup.exported, "口令太短不该产出备份")
        assertNull(picker.savedTo, "更不该弹出保存对话框")
        assertTrue(controller.banner?.isError ?: false)
        assertTrue(controller.banner?.text?.contains("至少") ?: false, "要说清为什么不行：${controller.banner?.text}")
    }

    @Test
    fun unsupportedPlatformHidesExportAndSaysWhy() = runTest {
        val scheduler = TestCoroutineScheduler()
        val controller = AppController(AppDependencies.inMemory(), CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        controller.refresh()
        scheduler.advanceUntilIdle()

        assertFalse(controller.backup.supported)
        assertFalse(controller.backup.canExport)
        assertNotNull(controller.backup.unavailableReason)

        controller.openBackup()
        controller.exportBackup()
        scheduler.advanceUntilIdle()
        assertNull(controller.backup.exported, "不支持的平台不该导出任何东西")
        assertTrue(controller.banner?.isError ?: false)
    }

    // ---- 上一次恢复被打断 ----

    @Test
    fun interruptedRestoreIsAnnouncedOnceThenStaysOnTheBackupPage() = runTest {
        val scheduler = TestCoroutineScheduler()
        val root = tempRoot()
        val notice = RestoreRecovery(
            kind = RestoreRecoveryKind.ROLLED_FORWARD,
            dataLocation = root.absolutePath,
            filesInPlace = 2,
        )
        val controller = controllerOn(root, scheduler, FakePicker(), recovery = notice)

        // 首页横幅说一次，而且不是错误色（事情已经处理好了）
        val first = assertNotNull(controller.banner, "被打断过就要在首页说一句")
        assertFalse(first.isError, "已经接上了，不该用报错的口吻：${first.text}")
        assertTrue(first.text.contains("接上"), "要说清现在生效的是哪一份：${first.text}")

        // 备份页上有固定卡片；点「知道了」之后两处都清掉
        controller.openBackup()
        assertNotNull(controller.backup.restoreRecovery)
        assertFalse(controller.backup.recoveryIsError)
        controller.backup.dismissRecovery()
        assertNull(controller.backup.restoreRecovery)

        // 再刷新一次也不该再提（否则每次进应用都像出过事）
        controller.dismissBanner()
        controller.refresh()
        scheduler.advanceUntilIdle()
        assertNull(controller.banner, "同一件事只说一次")
    }

    @Test
    fun missingDataDuringRecoveryIsReportedAsAnError() = runTest {
        val scheduler = TestCoroutineScheduler()
        val root = tempRoot()
        val notice = RestoreRecovery(
            kind = RestoreRecoveryKind.DATA_MISSING,
            dataLocation = root.absolutePath,
            detail = "旧数据在 /tmp/kt.pre-restore-1",
        )
        val controller = controllerOn(root, scheduler, FakePicker(), recovery = notice)

        val banner = assertNotNull(controller.banner)
        assertTrue(banner.isError, "数据不见了必须用错误色，不能混在普通提示里")
        assertTrue(controller.backup.recoveryIsError)
        // 横幅只给一句结论，具体路径与"别自己删东西"的说明在备份页的卡片里
        assertNotNull(controller.backup.restoreRecovery?.detail)
    }

    // ---- 引用自检 ----

    @Test
    fun openingThePageAuditsAndTheExportBannerCarriesTheConclusion() = runTest {
        val scheduler = TestCoroutineScheduler()
        val controller = controllerOn(tempRoot(), scheduler, FakePicker())

        controller.openBackup()
        scheduler.advanceUntilIdle()

        // 进页面就核对：这一页每个数字都必须是"现在"的，自检也一样
        val onEntry = assertNotNull(controller.backup.audit, "进页面之后应当有一份针对当前数据的自检")
        assertEquals(1, onEntry.banks)
        assertEquals(3, onEntry.questions)
        assertTrue(onEntry.mediaChecked, "桌面端给得出文件清单，图片必须真的核对过，不能自称不知道")
        assertTrue(onEntry.isClean, "starter 里没有任何指向别处的引用：${onEntry.summary}")
        assertNotNull(controller.backup.auditAtMillis, "界面要能说出这份结论是什么时候的")

        controller.exportBackup()
        scheduler.advanceUntilIdle()

        val afterExport = assertNotNull(controller.backup.audit, "导出之后应当重新核对一遍")
        assertEquals(onEntry.banks, afterExport.banks)
        // 结论要进横幅：用户点完导出就该看到，而不是自己去下面找
        val banner = assertNotNull(controller.banner)
        assertTrue(banner.text.contains("引用自检"), banner.text)
    }

    @Test
    fun restoreAuditsWhatWasJustWritten() = runTest {
        val scheduler = TestCoroutineScheduler()
        val picker = FakePicker()
        val a = controllerOn(tempRoot(), scheduler, picker)
        a.openBackup()
        a.exportBackup()
        scheduler.advanceUntilIdle()
        val qbk = assertNotNull(picker.savedTo).also { tempDirs += it }

        val rootB = tempRoot()
        val pickerB = FakePicker().apply { nextBackup = PickedFile("bak.qbk", qbk.readBytes()) }
        val b = controllerOn(rootB, scheduler, pickerB)
        File(rootB, "banks-v1.json").delete()
        b.refresh()
        scheduler.advanceUntilIdle()

        b.openBackup()
        b.pickBackupFile()
        scheduler.advanceUntilIdle()
        b.confirmRestore()
        scheduler.advanceUntilIdle()

        // 数据落地之后拿"刚写进去的那份清单"核对一遍 —— 总纲要求的"最后解析引用"
        val audit = assertNotNull(b.backup.audit, "恢复之后必须再核对一次，且核对的是恢复后的数据")
        assertEquals(1, audit.banks)
        assertEquals(3, audit.questions)
        assertTrue(audit.mediaChecked)
        assertTrue(audit.isClean)
        val banner = assertNotNull(b.banner)
        assertTrue(banner.text.contains("引用自检"), banner.text)
        assertFalse(banner.isError)
    }

    @Test
    fun dismissedAuditCanBeRecomputedFromThePageButton() = runTest {
        val scheduler = TestCoroutineScheduler()
        val controller = controllerOn(tempRoot(), scheduler, FakePicker())
        controller.openBackup()
        controller.exportBackup()
        scheduler.advanceUntilIdle()
        assertNotNull(controller.backup.audit)

        controller.backup.dismissAudit()
        assertNull(controller.backup.audit, "「收起」之后面板应当回到没跑过的样子")
        assertNull(controller.backup.auditAtMillis, "时间也要一起清：留着一个孤立的时间戳只会让人困惑")

        controller.recheckBackupAudit()
        scheduler.advanceUntilIdle()
        val audit = assertNotNull(controller.backup.audit, "页面上的「核对一次」要能重新跑")
        assertEquals(1, audit.banks)
        assertFalse(controller.backup.busy)
    }
}
