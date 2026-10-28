package morefish.knowledge.trainer.presentation.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import morefish.knowledge.trainer.application.port.DataVault
import morefish.knowledge.trainer.application.port.FilePickException
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.RestoreRecovery
import morefish.knowledge.trainer.application.port.RestoreRecoveryKind
import morefish.knowledge.trainer.domain.backup.BackupCryptoIds
import morefish.knowledge.trainer.domain.backup.BackupPreview
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.domain.backup.ReferenceAudit
import morefish.knowledge.trainer.domain.model.QuestionBank

/**
 * 本机数据概况。数字来自已经载入的内存状态，**不读文件、不打快照** ——
 * 只为了在用户按下"导出"之前告诉他"这次要备份多少东西"。
 */
data class BackupSummary(
    val banks: Int,
    val questions: Int,
    val attempts: Int,
    val notes: Int,
    val reviews: Int,
) {
    val isEmpty: Boolean get() = banks == 0 && attempts == 0 && notes == 0
}

/** 刚导出的那份备份。留在界面上供核对（文件名、大小、摘要）。 */
data class BackupExportInfo(
    val fileName: String,
    val bytes: Long,
    val digest: String,
    val createdAtMillis: Long,
    val fileCount: Int,
    val encrypted: Boolean,
)

/**
 * 已读入、待确认的备份。
 *
 * `bytes` 留着是因为确认时要恢复的就是这一份：如果确认时重新去读文件，
 * 用户中途换了文件，就会恢复成另一份东西。
 *
 * [requiresPassphrase] 与 [problems] 分开：加密备份"还没解锁"不是一个错误，
 * 界面要换成一个口令输入框，而不是打出"这份备份不能用"。
 */
data class BackupIntake(
    val name: String,
    val bytes: ByteArray,
    val preview: BackupPreview?,
    val problems: List<BackupProblem>,
    val canRestore: Boolean,
    val requiresPassphrase: Boolean = false,
) {
    // ByteArray 是引用类型，data class 默认的 equals 会误导；按引用比较更诚实（与 PickedFile 一致）
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = bytes.contentHashCode() * 31 + name.hashCode()
}

/**
 * 打好的那份备份包。
 *
 * 组合根把 `infrastructure` 的 `BackupBundle` 摘成这几个字段再递进来 —— 备份容器还没抽象成
 * 端口（见架构测试里登记的债），而子控制器不许认识 `infrastructure`。摘字段这一层本来
 * 也只有组合根能做：它是唯一允许挑具体实现的地方。
 */
class PackedBackup(
    val fileName: String,
    val bytes: ByteArray,
    val digest: String,
    val createdAtMillis: Long,
    val fileCount: Int,
    val encrypted: Boolean,
    /** 真正打进包里的文件清单：引用自检要对着它说，而不是对着"此刻磁盘上有什么"。 */
    val payloadPaths: Set<String>,
)

/** 打包这一步的结果。失败时给出人话，不返回半个文件。 */
sealed interface PackResult {
    class Ok(val packed: PackedBackup) : PackResult

    data class Failed(val message: String) : PackResult
}

/** 读一份备份的结果（一个字节都没写）。同样是摘过的字段，不带 `infrastructure` 的类型。 */
class BackupRead(
    val preview: BackupPreview?,
    val problems: List<BackupProblem>,
    val canRestore: Boolean,
    val requiresPassphrase: Boolean,
)

/** 导出之后该说哪句话。[Blocked] 是"按钮本来该是灰的"：表单没填全或平台不支持。 */
sealed interface BackupExportVerdict {
    class Saved(val info: BackupExportInfo, val payloadPaths: Set<String>) : BackupExportVerdict

    /** 用户取消了保存。不是失败，但也不该显示"已导出"。 */
    data object Cancelled : BackupExportVerdict

    data class Failed(val message: String) : BackupExportVerdict

    data class Blocked(val message: String) : BackupExportVerdict
}

/** 读入一份备份之后的状态。加密备份"还差口令"不是错误，所以单独一档。 */
sealed interface BackupIntakeVerdict {
    /** 清单已经摊开，可以看了。 */
    data object Ready : BackupIntakeVerdict

    /** 加密备份：下一步是输口令。 */
    data object NeedsPassphrase : BackupIntakeVerdict

    /** 读不懂 / 校验不过。 */
    data class Unusable(val message: String) : BackupIntakeVerdict

    /** 用户取消了文件选择。 */
    data object Cancelled : BackupIntakeVerdict

    /** 文件读不出来。 */
    data class Failed(val message: String) : BackupIntakeVerdict
}

sealed interface BackupUnlockVerdict {
    class Unlocked(val banks: Int, val attempts: Int) : BackupUnlockVerdict

    data class Failed(val message: String) : BackupUnlockVerdict
}

/**
 * 恢复的结果。
 *
 * 成功时把"写进去多少、旧数据去了哪"一并带回去：这句话必须由组合根说，
 * 因为它要顺带提一句引用自检的结论，而自检要拿刚写进去的文件清单去核对整库。
 */
sealed interface RestoreVerdict {
    class Done(
        val filesWritten: Int,
        val banks: Int,
        val attempts: Int,
        val notes: Int,
        val previousLocation: String?,
        val payloadPaths: Set<String>,
    ) : RestoreVerdict

    data class Rejected(val message: String) : RestoreVerdict
}

/** 一条"上次恢复被打断"的通知，只发一次。 */
data class RecoveryNotice(val text: String, val isError: Boolean)

/**
 * 备份与迁移（`.qbk`）：导出、自检、读入、解锁、整体替换。
 *
 * 为什么值得单独成类：这一屏是**唯一会整体替换用户全部数据**的地方，而它要做的判断
 * 全是"什么时候不该做"—— 表单没填全不许导出、加密备份没解锁不许恢复、恢复失败
 * 一个字节都不许动。这些判断以前散在一屏的 `if` 里，和自检、导航、横幅混在一起，
 * 想确认"口令错了会不会已经把数据写了一半"得读完整段。
 *
 * 四条边界：
 *
 * - **不导航、不弹横幅。** 进页面 / 返回 / 说什么话留在 `AppController`。
 * - **不做引用自检。** 自检要看题库、合并后的图谱、笔记、扩展包、历史与磁盘文件清单 ——
 *   那是整个应用的数据，控制器只负责把结论记下来（[reportAudit]）并说出它是什么时候的。
 * - **不认识 `infrastructure`。** 打包 / 读包 / 替换三个动作由组合根包成 lambda 递进来，
 *   容器类型在递进来之前就已经摘成 [PackedBackup] / [BackupRead]。
 * - **口令不落盘。** [unlockPassphrase] 只在内存里，取消、恢复完成、换文件、离开页面即清空。
 */
class BackupController(
    private val vault: DataVault,
    private val picker: FilePicker,
    private val now: () -> Long,
    /** 打包：口令为空 = 明文，非空 = 加密。 */
    private val pack: suspend (String?) -> PackResult,
    /** 读一份备份，只读不写。 */
    private val read: (ByteArray, String?) -> BackupRead,
    /** 整体替换本机数据。 */
    private val replace: suspend (ByteArray, String?) -> RestoreVerdict,
    private val banks: () -> List<QuestionBank>,
    private val attemptCount: () -> Int,
    private val noteCount: () -> Int,
    private val decisionCount: () -> Int,
    initialRecovery: RestoreRecovery? = null,
) {

    // ---------------------------------------------------------------- 上次恢复被打断

    /**
     * 上次恢复被打断时，启动自愈做了什么（平台入口在构造组合根之前算好）。
     *
     * 这是一条**一次性**的通知：用户点过「知道了」就清空，之后不再提。
     * 不清空的话，每次进备份页都会看到一段"你曾经出过事"，久了就被当装饰。
     */
    var restoreRecovery by mutableStateOf(initialRecovery)
        private set

    private var recoveryAnnounced = false

    /** 首页横幅里的那一句。分情况写清楚"现在生效的是哪一份数据"。 */
    val recoveryHeadline: String?
        get() = when (restoreRecovery?.kind) {
            null -> null
            RestoreRecoveryKind.ROLLED_BACK ->
                "上次恢复在写新数据时被打断：本机数据一个字节没动"
            RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP ->
                "上次恢复在切换途中被打断：已经把原来的数据放回去了"
            RestoreRecoveryKind.ROLLED_FORWARD ->
                "上次恢复在切换途中被打断：备份里的数据已经接上，现在生效的就是它"
            RestoreRecoveryKind.ALREADY_DONE ->
                "上次恢复其实已经完成，只是没来得及收尾 —— 现在生效的就是备份里的数据"
            RestoreRecoveryKind.DATA_MISSING ->
                "上次恢复被打断，而且数据目录不见了 —— 需要你手动处理（详情在备份页）"
            RestoreRecoveryKind.JOURNAL_UNREADABLE ->
                "发现一份读不懂的恢复日志：本机数据没有被改动，详情在备份页"
            RestoreRecoveryKind.RECOVERY_FAILED ->
                "上次恢复被打断，但收拾现场时出错了 —— 本机数据没被改动，详情在备份页"
        }

    /** 需要用户动手的几种才用错误色，其余是"已经处理好了"的中性通知。 */
    val recoveryIsError: Boolean
        get() = when (restoreRecovery?.kind) {
            RestoreRecoveryKind.DATA_MISSING,
            RestoreRecoveryKind.JOURNAL_UNREADABLE,
            RestoreRecoveryKind.RECOVERY_FAILED,
            -> true
            else -> false
        }

    fun dismissRecovery() {
        restoreRecovery = null
        recoveryAnnounced = true
    }

    /**
     * 取走那条一次性通知：第一次调用返回它，之后永远返回 null。
     *
     * 与"读属性"分开，是因为**说不说**由组合根决定（横幅位可能被更要紧的加载警告占着），
     * 而**只说一次**是这一屏的规则。让调用点自己管 `announced` 标记，就会有人忘了置位。
     */
    fun takeRecoveryNotice(): RecoveryNotice? {
        if (recoveryAnnounced) return null
        restoreRecovery ?: return null
        recoveryAnnounced = true
        val text = recoveryHeadline ?: return null
        return RecoveryNotice(text, recoveryIsError)
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 刚导出的那份备份。留着是为了让用户**当场核对**：文件名、大小、摘要。
     * 摘要不落盘、不记住 —— 它只在这一屏有意义（"和另一台机器上那份是不是同一份"）。
     */
    var exported by mutableStateOf<BackupExportInfo?>(null)
        private set

    /**
     * 导出表单的状态放在控制器里，不放界面里。
     *
     * 理由不是"方便测试"，而是：放在 `remember` 里，用户切到别的页面再回来，
     * 输了一半的口令会静默消失，而"勾着加密但口令空了"这种状态最难解释。
     */
    var encrypt by mutableStateOf(false)
        private set

    var exportPassphrase by mutableStateOf("")
        private set

    var exportConfirm by mutableStateOf("")
        private set

    /** "我知道忘记口令就打不开"。必须由用户自己点，不能预勾。 */
    var exportAck by mutableStateOf(false)
        private set

    fun toggleEncrypt() {
        encrypt = !encrypt
        if (!encrypt) clearExportPassphrase()
    }

    fun updateExportPassphrase(value: String) {
        exportPassphrase = value
    }

    fun updateExportConfirm(value: String) {
        exportConfirm = value
    }

    fun updateExportAck(value: Boolean) {
        exportAck = value
    }

    /** 口令从界面上抹掉，但不关加密开关：用户可能只是想重输一次。 */
    private fun clearExportPassphrase() {
        exportPassphrase = ""
        exportConfirm = ""
        exportAck = false
    }

    /**
     * 现在能不能点"导出"。返回 null = 能点，否则是**不能点的原因**。
     *
     * 界面直接显示这句话，控制器在 [export] 里再查一次同样的规则 ——
     * 不是为了防用户，是为了让"按钮灰着但不说为什么"这种状态不可能出现。
     */
    val exportBlockReason: String?
        get() = when {
            !canExport -> "当前平台不能保存文件"
            summary.isEmpty -> "先导入一个题库，再来备份"
            !encrypt -> null
            exportPassphrase.length < BackupCryptoIds.MIN_PASSPHRASE_CHARS ->
                "口令至少 ${BackupCryptoIds.MIN_PASSPHRASE_CHARS} 个字符"
            exportPassphrase != exportConfirm -> "两次口令不一致"
            !exportAck -> "先确认「忘记口令就打不开」"
            else -> null
        }

    val supported: Boolean get() = vault.isSupported

    val unavailableReason: String?
        get() = when {
            !vault.isSupported -> vault.unsupportedReason
            else -> null
        }

    /** 导出还额外要求"能把文件写出去"：能读不能写的平台不该出现导出按钮。 */
    val canExport: Boolean get() = vault.isSupported && picker.canSave

    val dataLocation: String get() = vault.locationLabel

    /** 本机数据概况。不读文件、不打包，只是把已经载入的账报一遍。 */
    val summary: BackupSummary
        get() {
            val loaded = banks()
            return BackupSummary(
                banks = loaded.size,
                questions = loaded.sumOf { it.questions.size },
                attempts = attemptCount(),
                notes = noteCount(),
                reviews = decisionCount(),
            )
        }

    /**
     * 导出：先在内存里打包，再让用户选位置写出去。取消保存不算失败。
     *
     * 口令只在这一步用一次，**打包完成立刻从状态里清掉**：
     * 加密备份的意义是"只有我知道口令"，软件自己一直存着就等于没有加密。
     * 打包失败时**不清** —— 用户多半只是口令打错了字，替他把输入抹掉只会让他重敲一遍。
     */
    suspend fun export(): BackupExportVerdict {
        val blocked = exportBlockReason
        if (blocked != null) return BackupExportVerdict.Blocked(blocked)

        val secret = exportPassphrase.takeIf { encrypt && it.isNotEmpty() }
        if (secret != null && secret != secret.trim()) {
            return BackupExportVerdict.Failed("口令首尾不要有空格 —— 空格也算口令的一部分，很容易输漏")
        }

        return working {
            when (val packed = pack(secret)) {
                is PackResult.Failed -> BackupExportVerdict.Failed(packed.message)
                is PackResult.Ok -> {
                    val bundle = packed.packed
                    try {
                        if (!picker.saveFile(bundle.fileName, bundle.bytes)) {
                            clearExportPassphrase()
                            return@working BackupExportVerdict.Cancelled
                        }
                        val info = BackupExportInfo(
                            fileName = bundle.fileName,
                            bytes = bundle.bytes.size.toLong(),
                            digest = bundle.digest,
                            createdAtMillis = bundle.createdAtMillis,
                            // 加密备份的清单里没有文件明细，数量从打包时算好的统计里拿
                            fileCount = bundle.fileCount,
                            encrypted = bundle.encrypted,
                        )
                        exported = info
                        // 不管存没存成，口令都从界面状态里抹掉；要不要再导出让用户重新输。
                        clearExportPassphrase()
                        BackupExportVerdict.Saved(info, bundle.payloadPaths)
                    } catch (e: FilePickException) {
                        clearExportPassphrase()
                        BackupExportVerdict.Failed(e.message ?: "保存失败")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 读入与解锁

    /**
     * 已读入、还没确认的备份。
     *
     * [BackupIntake.bytes] 留着是因为"确认恢复"要用的就是这一份 —— 重新读一遍文件，
     * 用户中途换了文件就会恢复成另一份东西，那种意外不该发生。
     */
    var intake by mutableStateOf<BackupIntake?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /**
     * 恢复时用户输过的口令。
     *
     * 留在内存里，是因为"解锁看看"和"确认恢复"是两次读同一份字节的动作 ——
     * 不留下它，用户每点一次都要重新输一遍，还要再等一次 600000 轮派生。
     *
     * 生命周期很短：取消、恢复完成、换一份文件、离开页面都会清空。
     * **不落盘、不进日志、不写清单**（清单里只有盐和迭代次数）。
     */
    var unlockPassphrase by mutableStateOf("")
        private set

    /** 选一份备份读进来：只读清单与校验，不动本机数据。 */
    suspend fun pick(): BackupIntakeVerdict = working {
        val picked = try {
            picker.pickBackup()
        } catch (e: FilePickException) {
            return@working BackupIntakeVerdict.Failed(e.message ?: "读取文件失败")
        } ?: return@working BackupIntakeVerdict.Cancelled
        accept(picked.name, picked.bytes)
    }

    /** 文件名 + 字节 → 读入待确认。文件选择器与拖拽两条入口共用。 */
    fun accept(name: String, bytes: ByteArray): BackupIntakeVerdict {
        unlockPassphrase = ""
        val result = read(bytes, null)
        val loaded = BackupIntake(
            name = name,
            bytes = bytes,
            preview = result.preview,
            problems = result.problems,
            canRestore = result.canRestore,
            requiresPassphrase = result.requiresPassphrase,
        )
        intake = loaded
        return when {
            // 加密备份：这不是失败，下一步是输口令。
            loaded.requiresPassphrase -> BackupIntakeVerdict.NeedsPassphrase
            !loaded.canRestore ->
                BackupIntakeVerdict.Unusable(loaded.problems.firstOrNull()?.message ?: "校验没通过")
            else -> BackupIntakeVerdict.Ready
        }
    }

    /**
     * 用口令解锁一份加密备份。
     *
     * 失败时**只说"解不开"**，不猜是口令错还是文件被改 —— GCM 分不出这两件事，
     * 硬说"口令错误"会让拿到坏文件的人一直重试口令。
     */
    suspend fun unlock(passphrase: String): BackupUnlockVerdict {
        val loaded = intake ?: return BackupUnlockVerdict.Failed("先选一份备份")
        if (passphrase.isBlank()) return BackupUnlockVerdict.Failed("先输入口令")
        return working {
            val result = read(loaded.bytes, passphrase)
            val counts = result.preview?.manifest?.counts
            if (result.canRestore) {
                unlockPassphrase = passphrase
                intake = loaded.copy(
                    preview = result.preview,
                    problems = result.problems,
                    canRestore = true,
                    requiresPassphrase = false,
                )
                BackupUnlockVerdict.Unlocked(counts?.banks ?: 0, counts?.attempts ?: 0)
            } else {
                unlockPassphrase = ""
                intake = loaded.copy(
                    preview = result.preview ?: loaded.preview,
                    problems = result.problems,
                    canRestore = false,
                    requiresPassphrase = result.requiresPassphrase,
                )
                BackupUnlockVerdict.Failed(result.problems.firstOrNull()?.message ?: "这份备份解不开")
            }
        }
    }

    fun cancelRestore() {
        intake = null
        unlockPassphrase = ""
    }

    /**
     * 确认恢复：整体替换本机数据。
     *
     * 真正的"重新读一遍全世界"与引用自检留在组合根（[RestoreVerdict.Done.payloadPaths]
     * 就是给它用的）：那是全局的事，控制器只负责把本机状态清干净。
     */
    suspend fun restore(): RestoreVerdict {
        val loaded = intake ?: return RestoreVerdict.Rejected("还没有选一份备份")
        if (!loaded.canRestore) return RestoreVerdict.Rejected("这份备份还没通过校验")
        val secret = unlockPassphrase.takeIf { it.isNotEmpty() }
        return working {
            val verdict = replace(loaded.bytes, secret)
            if (verdict is RestoreVerdict.Done) {
                intake = null
                exported = null
                unlockPassphrase = ""
            }
            verdict
        }
    }

    // ---------------------------------------------------------------- 引用自检与进出页面

    /**
     * 最后一次引用自检的结果。
     *
     * 它不写进备份文件：一份备份里的"自检结论"是**导出那一刻**的判断，
     * 而用户是在恢复之后才看它 —— 中间可能过了半年、装过别的题库。
     * 把过期结论塞进文件，等于让人拿一句旧话当现在的事实。宁可每次重算。
     */
    var audit by mutableStateOf<ReferenceAudit?>(null)
        private set

    /** 自检是什么时候跑的。界面要写"刚才核对的"而不是让人以为它一直实时。 */
    var auditAtMillis by mutableStateOf<Long?>(null)
        private set

    /** 记下组合根刚算出来的那份自检结论。时间由这里打，免得两处各记一个"现在"。 */
    fun reportAudit(result: ReferenceAudit) {
        audit = result
        auditAtMillis = now()
    }

    fun dismissAudit() {
        audit = null
        auditAtMillis = null
    }

    /** 进页面：上一份待确认的备份与口令都不该跟着进来。 */
    fun enter() {
        intake = null
        unlockPassphrase = ""
    }

    /** 离开页面：连加密开关一起回到"关"，下次进来重新决定。 */
    fun leave() {
        intake = null
        exported = null
        unlockPassphrase = ""
        encrypt = false
        clearExportPassphrase()
    }

    /**
     * 在"忙"的状态下做一件事。
     *
     * 打包、解锁、恢复、重读自检都要让按钮变成"正在…"，而它们都会抛异常（磁盘满、文件被删）。
     * `try/finally` 写在四处很容易漏一处 —— 漏了的那处会让界面永远停在"正在恢复…"，
     * 而用户唯一能做的就是杀掉应用。
     */
    suspend fun <T> working(block: suspend () -> T): T {
        busy = true
        return try {
            block()
        } finally {
            busy = false
        }
    }
}
