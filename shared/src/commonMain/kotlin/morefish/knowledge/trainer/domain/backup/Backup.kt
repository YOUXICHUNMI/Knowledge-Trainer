package morefish.knowledge.trainer.domain.backup

import kotlinx.serialization.Serializable

/**
 * 一份备份的清单（`backup-manifest.json`）。
 *
 * 备份和题库包是**两件不同的事**，所以是两套格式，只共用 ZIP 与 sha256 这两样东西：
 *
 * | | `.qbank` | `.qbk` |
 * |---|---|---|
 * | 装的是 | 一个题库的内容 | 整台机器上的全部应用数据 |
 * | 给谁 | 别人 | 自己（换设备 / 重装系统 / 后悔了） |
 * | 判什么 | 格式是否合法、内容是否自洽 | 字节是否完整、布局版本是否读得懂 |
 *
 * 备份刻意**不做内容校验**：它不需要知道一道题的答案对不对，只需要知道
 * "这些字节是不是当初写进去的那些"。多一层解析就多一个"格式演进后旧备份读不出来"的理由。
 */
@Serializable
data class BackupManifest(
    val format: String = BACKUP_FORMAT,
    /** 备份容器本身的版本。不兼容时明确拒绝，不做"尽力读"。 */
    val backupVersion: Int = BACKUP_VERSION,
    /**
     * 数据布局版本。跟 [backupVersion] 分开：容器可以不变而数据布局变
     * （比如题库目录从 schema 2 升到 3），那时要拦的是"这份数据我不认识"，
     * 而不是"这个 ZIP 我打不开"。
     */
    val layoutVersion: Int = LAYOUT_VERSION,
    val createdAtMillis: Long = 0,
    /** 打这份备份的机器（系统 + 用户名）。恢复时用来提醒"这是另一台机器的数据"。 */
    val deviceLabel: String = "",
    val appVersion: String = "",
    /**
     * 加密标志。
     *
     * 加密备份里**清单仍明文**（否则"要不要口令"这个问题都问不出来），
     * 但清单里不写文件名、不写每个文件的 sha256 —— 只留几个统计数字。
     * 文件清单与摘要进密文（见 [BackupPayloadIndex]）。
     */
    val encrypted: Boolean = false,
    /** 加密参数（KDF / 迭代次数 / 盐 / nonce）。未加密时为空。 */
    val crypt: BackupCrypt? = null,
    /**
     * 密文条目的固定名字。加密时数据整段放在这一个条目里，
     * 不再是 `data/<原路径>` —— 那样连文件名都会泄漏。
     */
    val payloadEntry: String = PAYLOAD_ENTRY,
    val files: List<BackupEntry> = emptyList(),
    val counts: BackupCounts = BackupCounts(),
    /**
     * 整份备份的内容摘要（sha256，64 位十六进制）。
     *
     * 用途只有一个：两台机器各打一份备份，比一下这串字符就知道是不是同一份数据。
     * **它不是签名**：没有密钥，改完内容重算一次就自洽了。
     *
     * 加密备份里这个字段留在清单里会是空串 —— 摘要在密文里的索引中，
     * 因为"哪些文件、每个文件多大"本身也是要藏起来的信息。
     */
    val contentDigest: String = "",
) {
    companion object {
        const val BACKUP_FORMAT = "knowledge-trainer-backup"

        /** 当前应用能写、也能读的最高备份版本。2 = 带加密的容器。 */
        const val BACKUP_VERSION = 2

        /** 当前应用能读的最高数据布局版本。 */
        const val LAYOUT_VERSION = 1

        /** 清单在包里的固定路径。 */
        const val ENTRY_NAME = "backup-manifest.json"

        /** 未加密时的数据前缀：清单与数据分开，一眼能看出哪些是内容。 */
        const val DATA_PREFIX = "data/"

        /** 加密时的密文条目名（单个）。 */
        const val PAYLOAD_ENTRY = "payload.enc"
    }
}

/**
 * 加密参数。全部写进明文清单 —— 解密前必须知道用什么算法、多少轮、哪个盐。
 *
 * [iterations] 与 [saltHex] 也在 AAD 里参与认证：改小迭代次数来加速爆破，
 * 会让标签校验直接失败，而不是"顺利解开但更弱"。
 */
@Serializable
data class BackupCrypt(
    val kdf: String = BackupCryptoIds.KDF_PBKDF2_SHA256,
    val iterations: Int = 0,
    val saltHex: String = "",
    val cipher: String = BackupCryptoIds.CIPHER_AES_256_GCM,
    val nonceHex: String = "",
    val tagBytes: Int = 16,
)

/** 稳定算法标识。写进文件、读回来比对，**不认识就拒绝**，不做"猜一个相近的"。 */
object BackupCryptoIds {
    const val KDF_PBKDF2_SHA256 = "pbkdf2-hmac-sha256"
    const val CIPHER_AES_256_GCM = "aes-256-gcm"

    /**
     * 口令最短字符数。
     *
     * 界面、用例、加密层共用这一个常量 —— 三处各写一个 10，早晚变成"界面放行、加密层拒绝"，
     * 而用户看到的是"点了导出没反应"。
     *
     * 它不是安全性的保证（`1234567890` 照样过），只是拦住"随手四个字母"。
     */
    const val MIN_PASSPHRASE_CHARS = 10
}

/**
 * 密文里装的东西：真正的文件清单、统计与摘要。
 *
 * 单独一个结构而不是复用 [BackupManifest]：清单是"外面那个人能看的信封"，
 * 索引是"封里面的东西"，两者将来会分开演进（比如信封加一个"需要新版本"的提示字段）。
 */
@Serializable
data class BackupPayloadIndex(
    val files: List<BackupEntry> = emptyList(),
    val counts: BackupCounts = BackupCounts(),
    val contentDigest: String = "",
)

@Serializable
data class BackupEntry(
    /** 相对数据根目录的路径，`/` 分隔。恢复时按同一路径写回去。 */
    val path: String,
    val bytes: Long = 0,
    val sha256: String = "",
)

/**
 * 备份里有多少东西。**只是给人看的数字**，不参与任何判断。
 *
 * 为什么不全：统计每一个模块的条数意味着备份逻辑要认识每一个模块的格式，
 * 而格式一变（新增字段、换 schema）统计就先坏。这里只数"数得准且用户真的在意"的几项，
 * 其余归到 [otherFiles] —— 诚实地说"还有一些别的文件"，比编一个可能错的数字好。
 */
@Serializable
data class BackupCounts(
    val banks: Int = 0,
    val questions: Int = 0,
    val attempts: Int = 0,
    val notes: Int = 0,
    val reviews: Int = 0,
    val mediaFiles: Int = 0,
    val mediaBytes: Long = 0,
    val otherFiles: Int = 0,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
)

/**
 * 读一份备份得到的结果。
 *
 * [problems] 非空 = 不能恢复（一个字节都不写）。[warnings] 只提示，不挡路。
 * 两者分开是刻意的：把"这份备份是别的数据布局"当成警告，就是在赌用户能自己看出来。
 */
data class BackupPreview(
    val manifest: BackupManifest,
    val problems: List<BackupProblem> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val canRestore: Boolean get() = problems.isEmpty()

    /** 包里有几份数据文件（恢复会写这么多个）。 */
    val fileCount: Int get() = manifest.files.size

    val totalBytes: Long get() = manifest.files.sumOf { it.bytes }
}

/** 稳定错误码，便于测试逐条断言、也便于界面对不同原因说不同的话。 */
data class BackupProblem(val code: String, val message: String) {
    companion object {
        const val NOT_ZIP = "BACKUP_NOT_ZIP"
        const val MANIFEST_MISSING = "BACKUP_MANIFEST_MISSING"
        const val MANIFEST_INVALID = "BACKUP_MANIFEST_INVALID"
        const val FORMAT_UNKNOWN = "BACKUP_FORMAT_UNKNOWN"
        const val VERSION_UNSUPPORTED = "BACKUP_VERSION_UNSUPPORTED"
        const val LAYOUT_UNSUPPORTED = "BACKUP_LAYOUT_UNSUPPORTED"
        const val FILE_MISSING = "BACKUP_FILE_MISSING"
        const val FILE_UNLISTED = "BACKUP_FILE_UNLISTED"
        const val HASH_MISMATCH = "BACKUP_HASH_MISMATCH"
        const val PATH_UNSAFE = "BACKUP_PATH_UNSAFE"
        const val EMPTY = "BACKUP_EMPTY"
        const val DIGEST_MISMATCH = "BACKUP_DIGEST_MISMATCH"
        const val TOO_LARGE = "BACKUP_TOO_LARGE"

        /** 加密备份，还没给口令。**不是错误**：界面据此换成一个口令输入框。 */
        const val PASSPHRASE_REQUIRED = "BACKUP_PASSPHRASE_REQUIRED"

        /**
         * 解不开。**刻意不区分"口令错"与"文件被改"** —— GCM 的标签校验失败时，
         * 密码学上这两件事本来就分不出来；硬写"口令错误"会把一个被篡改的文件
         * 说成"你记错了口令"，让人一直试下去。
         */
        const val DECRYPT_FAILED = "BACKUP_DECRYPT_FAILED"

        /** 加密参数不可接受（迭代次数过低、算法不认识、盐长度不对）。 */
        const val CRYPT_PARAMS = "BACKUP_CRYPT_PARAMS"

        /** 口令太短。导出前的最后一道闸门（界面上先拦一次，这里是兜底）。 */
        const val PASSPHRASE_TOO_SHORT = "BACKUP_PASSPHRASE_TOO_SHORT"
    }
}
