package morefish.knowledge.trainer.application.port

/**
 * 一次数据快照：相对路径（`/` 分隔）→ 原始字节。
 *
 * 用"整份数据目录"而不是"逐仓储导出"的理由：备份要能原样搬回去，
 * 而仓储的领域模型会演进，序列化器也会演进 —— 只要文件字节一模一样，
 * 恢复之后身份（bankId / questionId / 作答时间戳 / 复核决定）就不会变。
 * 反过来，如果把备份做成"领域对象的另一种序列化"，恢复就成了"重建"，
 * 重建就一定会丢东西（比如某天新增的字段、未知的历史版本）。
 */
data class VaultSnapshot(val files: Map<String, ByteArray>)

/** 恢复完成后的实情：写了多少文件、旧数据留在哪里。 */
data class RestoreOutcome(
    val filesWritten: Int,
    val bytesWritten: Long,
    /** 被替换下来的旧数据目录的人类可读位置；首次恢复（原本没有数据）时为 null。 */
    val previousLocation: String?,
)

/** 数据目录级操作的失败。code 稳定，便于界面按情况说话。 */
class VaultException(val code: String, message: String) : Exception(message)

/**
 * 上一次恢复被打断时，启动自愈做了什么。
 *
 * 分成这么多种，是因为**磁盘上留下的现场本来就不一样**，而用户需要知道的
 * 恰好是"现在到底是哪一份数据在生效"。把这些情况合并成一句"已修复"，
 * 就等于在最需要说清的场合含糊过去。
 */
enum class RestoreRecoveryKind {
    /** 半成品已清理，本机数据一个字节没动（恢复在写新数据时被打断）。 */
    ROLLED_BACK,

    /** 旧数据已换下来、新数据没接上：已把原来的数据放回去。 */
    ROLLED_BACK_AFTER_SWAP,

    /** 新数据已经接上（数据目录里现在就是备份里的内容）。 */
    ROLLED_FORWARD,

    /** 上次其实已经换好了，只是没来得及收尾 —— 删掉日志即可。 */
    ALREADY_DONE,

    /** 数据目录不见了，旧数据与半成品都不可用 —— 不能假装正常。 */
    DATA_MISSING,

    /** 日志读不懂，什么都没动。 */
    JOURNAL_UNREADABLE,

    /**
     * 自愈过程中自己出了错（磁盘拒绝写入、权限不足），**什么都没敢动**。
     *
     * 这种情况最需要说清：不能既不说、也不动，让人以为一切正常。
     */
    RECOVERY_FAILED,
}

/**
 * 一次"启动时发现上次恢复没做完"的报告。
 *
 * 这是**给用户看的**，不是内部状态：界面会明说"现在生效的是哪一份数据"、
 * "旧数据留在哪"、"要不要手动退回去"。
 */
data class RestoreRecovery(
    val kind: RestoreRecoveryKind,
    /** 数据目录当前的位置（已经在用的人话路径）。 */
    val dataLocation: String,
    /** 被换下来的旧数据目录；没有就是 null。 */
    val previousLocation: String? = null,
    /** 有没有删掉半成品目录。 */
    val stagingRemoved: Boolean = false,
    /** 自愈之后数据目录里有多少个文件；说不清时为 null。 */
    val filesInPlace: Int? = null,
    /** 需要人工处理时，给一句具体的话（含路径）。 */
    val detail: String? = null,
)

/**
 * 数据目录端口：把"这台机器上的全部应用数据"当成一个整体来读写。
 *
 * 只有"数据真的落在磁盘上"的平台才实现它。Web / iOS 的数据在内存里，
 * 关掉应用就没了 —— 那种平台上这个功能没有意义，如实写在 [unsupportedReason] 里，
 * 而不是给一个点了没反应的按钮。
 *
 * 刻意**不**提供"按文件读写"：备份与恢复要的是整体替换，
 * 半个备份或半个恢复都比没有更糟。
 */
interface DataVault {

    val isSupported: Boolean

    /** 不支持时的原因（直接展示给用户）；支持时为 null。 */
    val unsupportedReason: String?

    /** 这份数据住在哪（"我要备份的是哪一份数据"）。给界面用，不参与任何判断。 */
    val locationLabel: String

    /**
     * 这台机器的名字（系统 + 用户名），写进备份清单。
     *
     * 只有平台自己知道该写什么，所以放在端口上而不是让用例去猜。
     * 恢复时界面会说"这是 机器A 的数据"，用户才知道自己是不是正要拿别人的备份盖掉自己。
     */
    val deviceLabel: String

    /** 读一份完整快照。跳过临时文件（`*.tmp`）与上一次恢复留下的旧数据目录。 */
    suspend fun snapshot(): VaultSnapshot

    /**
     * 只列路径，不读内容。
     *
     * 引用自检要回答"题目里写的那张图，文件里到底有没有"，只需要路径。
     * 为了这个问题把整份数据读进内存（几十上百 MB），会让"自检"变成一个没人愿意点的动作。
     *
     * 返回 null = 这个平台给不出文件清单（数据在内存里）。
     * **不能返回空集合代替** —— 那会让每一张图都被报成缺失，比不检查更糟。
     */
    suspend fun listPaths(): Set<String>? = null

    /**
     * 用给定文件**整体替换**当前数据。
     *
     * 实现必须保证：要么全部生效，要么原数据一个字节不动。
     * 返回旧数据被放到了哪里 —— 用户有权知道"我刚才是不是把东西覆盖没了"。
     */
    suspend fun replaceAll(files: Map<String, ByteArray>): RestoreOutcome

    /**
     * 启动时调一次：上次恢复如果被打断（断电、被杀、盘满），把现场收拾干净并如实报告。
     *
     * 返回 null = 没有需要处理的事（绝大多数时候）。**这是唯一允许在启动阶段写磁盘的操作**，
     * 必须在任何仓储读数据之前调用 —— 否则应用会先读到"空数据"，用户以为东西全丢了。
     *
     * 默认实现返回 null：没有磁盘的平台没有什么可收拾的。
     */
    suspend fun recoverInterrupted(): RestoreRecovery? = null
}

/**
 * 没有磁盘可写的平台（Web / iOS / 内存降级）。
 *
 * 两个方法都直接抛：宁可让调用方拿到一句明确的话，也不要静默返回空快照 ——
 * 空快照会让"备份成功、文件里什么都没有"这种事看起来像正常结果。
 */
object UnsupportedDataVault : DataVault {

    override val isSupported: Boolean = false
    override val unsupportedReason: String =
        "这个平台的数据存在内存里，关掉应用就没了 —— 没有可备份的东西"
    override val locationLabel: String = "（内存，无文件）"
    override val deviceLabel: String = ""

    override suspend fun snapshot(): VaultSnapshot =
        throw VaultException("VAULT_UNSUPPORTED", unsupportedReason)

    override suspend fun replaceAll(files: Map<String, ByteArray>): RestoreOutcome =
        throw VaultException("VAULT_UNSUPPORTED", unsupportedReason)
}
