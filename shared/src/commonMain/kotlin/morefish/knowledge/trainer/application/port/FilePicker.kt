package morefish.knowledge.trainer.application.port

/** 用户从系统文件选择器里挑出来的文件。 */
data class PickedFile(val name: String, val bytes: ByteArray) {
    // ByteArray 是引用类型，data class 默认的 equals 在这里是误导性的；显式按引用比较更诚实
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = bytes.contentHashCode() * 31 + name.hashCode()
}

/**
 * 打开系统文件选择器。
 *
 * 做成注入端口而不是 expect/actual：各平台的"选文件"根本不是同一种机制
 * （桌面是阻塞对话框、Android 是 ActivityResult 回调、Web 是 input[type=file] 事件），
 * 硬凑一个 expect 签名只会在每端都别扭。
 *
 * 未实现的平台返回 [isAvailable] = false 与明确原因，UI 据此隐藏按钮或写明限制 ——
 * 不做一个点了没反应的按钮。
 */
interface FilePicker {
    val isAvailable: Boolean

    /** 不可用时的原因（直接展示给用户）；可用时为 null。 */
    val unavailableReason: String?

    /**
     * 选择一个包文件：`.qbank` 题库、`.qbext` 扩展包或 `.qbpatch` 更新补丁。
     *
     * 两者都由内容判定（读 manifest 的 format），不依赖文件扩展名 ——
     * 用户改过名的包应当得到"这不是可识别的包"，而不是被静默当成另一种格式去解析。
     */
    suspend fun pickPackage(): PickedFile?

    /** 是否支持把字节**写出去**（导出备份）。与 [isAvailable] 分开：能选文件不等于能存文件。 */
    val canSave: Boolean get() = false

    /**
     * 选择一份 `.qbk` 备份。单独一个方法而不是给 [pickPackage] 加参数：
     * 备份不是"包"，误选一个题库进来会走到完全不同的分支，用两个入口把这件事说清楚。
     */
    suspend fun pickBackup(): PickedFile? =
        throw FilePickException(unavailableReason ?: "当前平台尚未接入文件选择器")

    /**
     * 让用户选一个位置，把字节写出去。返回 false = 用户取消（不是错误）。
     *
     * 数据由调用方准备好再交进来：写文件这一步不懂备份格式，也就不可能"写一半"。
     */
    suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean =
        throw FilePickException(unavailableReason ?: "当前平台尚未接入文件保存")
}

object UnavailableFilePicker : FilePicker {
    override val isAvailable: Boolean = false
    override val unavailableReason: String = "当前平台尚未接入文件选择器"
    override suspend fun pickPackage(): PickedFile? = null
}

/** 单个题库文件的读取上限：与 KT-QBF 的"总解压量上限"对齐，防止选到超大文件直接把内存吃光。 */
const val MAX_BANK_FILE_BYTES: Long = 64L * 1024 * 1024

/**
 * 备份文件的读取上限。比题库包大一个量级：备份里有全部题库、全部历史、全部图片。
 * 与 [morefish.knowledge.trainer.infrastructure.archive.ZipReader.MAX_TOTAL_BYTES] 对齐，
 * 超过这个数的备份本来也读不进来 —— 在选文件这一步就挡住，比读进内存再报错好。
 */
const val MAX_BACKUP_FILE_BYTES: Long = 500L * 1024 * 1024

/** 文件选择器可抛出的错误：由 UI 层统一转成横幅提示。 */
class FilePickException(message: String) : Exception(message)
