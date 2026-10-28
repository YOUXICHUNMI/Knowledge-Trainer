package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.DataVault
import morefish.knowledge.trainer.application.port.RestoreOutcome
import morefish.knowledge.trainer.application.port.VaultException
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.backup.BackupPreview
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.infrastructure.backup.BackupArchive
import morefish.knowledge.trainer.infrastructure.backup.BackupArchiveException
import morefish.knowledge.trainer.infrastructure.backup.BackupBundle
import morefish.knowledge.trainer.infrastructure.backup.BackupReadResult

/** 备份导出的结果。失败时给出人话，不返回半个文件。 */
sealed interface BackupExportOutcome {
    class Ok(val bundle: BackupBundle) : BackupExportOutcome

    class Failed(val message: String) : BackupExportOutcome
}

/**
 * 把本机数据打成一份 `.qbk` 备份。
 *
 * 只做三件事：取快照 → 算清单 → 压成 ZIP（要加密时再多一步 AES-GCM）。
 * **不压缩**（见 [morefish.knowledge.trainer.infrastructure.archive.ZipWriter]），
 * 所以这里也不需要判断"哪些文件值得压"。整个过程在内存里完成，
 * 落盘交给界面层的文件选择器 —— 用例不碰用户选的路径。
 *
 * [passphrase] 为空 = 明文备份；非空 = 加密备份。**口令不落盘、不进清单、不进日志**：
 * 它只在这一次调用里活，用完就没有了。忘记口令 = 那份备份打不开，这是加密的定义，
 * 不是缺陷（页面上必须把这句话说在按钮旁边）。
 */
class CreateBackupUseCase(
    private val vault: DataVault,
    private val archive: BackupArchive = BackupArchive(),
    private val appVersion: String = APP_VERSION,
    private val clock: WallClock = WallClock.System,
) {

    suspend fun create(passphrase: String? = null): BackupExportOutcome {
        if (!vault.isSupported) {
            return BackupExportOutcome.Failed(vault.unsupportedReason ?: "这个平台不支持备份")
        }
        return try {
            val snapshot = vault.snapshot()
            if (snapshot.files.isEmpty()) {
                return BackupExportOutcome.Failed("本机还没有任何数据 —— 先导入一个题库，再来备份")
            }
            val bundle = archive.create(
                files = snapshot.files,
                // 没有设备名时退回数据目录 —— 宁可写路径，也不要在清单里留一句"未知设备"。
                deviceLabel = vault.deviceLabel.ifBlank { vault.locationLabel },
                appVersion = appVersion,
                nowMillis = clock.nowMillis(),
                passphrase = passphrase?.takeIf { it.isNotEmpty() },
            )
            BackupExportOutcome.Ok(bundle)
        } catch (e: VaultException) {
            BackupExportOutcome.Failed(e.message ?: "读取本机数据失败")
        } catch (e: BackupArchiveException) {
            BackupExportOutcome.Failed(e.message ?: "打包失败")
        }
    }

    companion object {
        /** 写进清单的应用版本。没有构建信息时用这个占位，比编一个版本号好。 */
        const val APP_VERSION = "0.9.0"
    }
}

/**
 * 读一份备份，回答"这是什么、能不能装"。
 *
 * **一个字节都不写**。界面拿到结果后先摊开清单让人看，用户点头才调 [RestoreBackupUseCase]。
 * 这样"选错文件"和"选对了但不想装"都不会有任何后果。
 *
 * 加密备份分两步：先不带口令读一次（只拿到信封 + [BackupReadResult.requiresPassphrase]），
 * 用户输口令后再带口令读第二次。分两步是为了让"要口令"这件事在界面上是一个正常状态，
 * 而不是一个错误。
 */
class InspectBackupUseCase(
    private val archive: BackupArchive = BackupArchive(),
) {

    fun inspect(bytes: ByteArray, passphrase: String? = null): BackupReadResult =
        archive.read(bytes, passphrase)
}

/**
 * 用一份备份整体替换本机数据。
 *
 * 两道门都是**先校验再落地**：
 * 1. 结构 + 逐文件 sha256（[BackupArchive.read]）；
 * 2. 交给自己平台的 [DataVault] 做"staging → 复核 → 原子切换"，失败回滚。
 *
 * 不做"部分恢复"、不做"合并恢复"：合并会造出一个既不是备份、也不是本机的第三种状态，
 * 而用户以为它是其中一个。真要合并，应该是先恢复再导入。
 */
class RestoreBackupUseCase(
    private val vault: DataVault,
    private val archive: BackupArchive = BackupArchive(),
) {

    sealed interface Outcome {
        /**
         * 恢复成功。[previousLocation] 非空 = 旧数据被保存在那里，可以人工退回去。
         *
         * [payloadPaths] 是这次真正写进去的文件集合 —— 自检要拿它回答
         * "题目引用的图在不在"，而不是去问"此刻磁盘上有什么"（那是同一件事，
         * 但只有前者说得清"就是这份备份"）。
         */
        class Ok(
            val outcome: RestoreOutcome,
            val preview: BackupPreview,
            val payloadPaths: Set<String>,
        ) : Outcome

        /** 校验没过，本机数据一个字节都没动。 */
        class Rejected(val problems: List<BackupProblem>, val preview: BackupPreview?) : Outcome
    }

    suspend fun restore(bytes: ByteArray, passphrase: String? = null): Outcome {
        if (!vault.isSupported) {
            return Outcome.Rejected(
                listOf(BackupProblem("VAULT_UNSUPPORTED", vault.unsupportedReason ?: "这个平台不支持恢复")),
                null,
            )
        }
        val read = archive.read(bytes, passphrase)
        val preview = read.preview
        if (!read.canRestore || preview == null) {
            return Outcome.Rejected(
                problems = read.problems.ifEmpty {
                    listOf(BackupProblem(BackupProblem.EMPTY, "这份备份没有可恢复的内容"))
                },
                preview = preview,
            )
        }
        return try {
            val outcome = vault.replaceAll(read.payload)
            Outcome.Ok(outcome, preview, read.payload.keys)
        } catch (e: VaultException) {
            Outcome.Rejected(
                listOf(BackupProblem(e.code, e.message ?: "写入失败")),
                preview,
            )
        }
    }
}
