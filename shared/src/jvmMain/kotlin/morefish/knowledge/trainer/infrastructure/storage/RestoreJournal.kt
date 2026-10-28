package morefish.knowledge.trainer.infrastructure.storage

import java.io.File

/**
 * 恢复日志：一行一条记录，写在**数据目录外面**（同级），因为最危险的那个瞬间
 * 数据目录恰好不存在。
 *
 * 为什么不用 JSON：这份文件是"崩溃之后唯一还能解释磁盘状态的东西"。
 * 字段顺序、缩进、转义任何一处漂移都会让它在关键时刻读不出来，
 * 而它只由本类写、只由本类读。行格式 + 首行版本号没有第二种写法可言。
 *
 * 为什么必须放在数据目录之外：它要是进去了，就会出现在备份里 ——
 * 一份带着"我正在恢复"日志的备份装到另一台机器上，那台机器启动时会去收拾
 * 一场根本不存在的崩溃。测试里有一条专门守这件事。
 */
internal data class RestoreJournal(
    val stamp: Long,
    val phase: String,
    /** 恢复开始前数据目录是否存在。用来区分"本来就没有数据"和"数据不见了"。 */
    val hadRoot: Boolean,
    val stagingName: String,
    val previousName: String,
    val files: Int,
    val bytes: Long,
) {

    fun encode(): String = buildString {
        append(MAGIC).append(' ').append(VERSION).append('\n')
        append("stamp=").append(stamp).append('\n')
        append("phase=").append(phase).append('\n')
        append("hadRoot=").append(hadRoot).append('\n')
        append("staging=").append(oneLine(stagingName)).append('\n')
        append("previous=").append(oneLine(previousName)).append('\n')
        append("files=").append(files).append('\n')
        append("bytes=").append(bytes).append('\n')
    }

    companion object {
        const val MAGIC = "kt-qbk-restore-journal"
        const val VERSION = 1

        /** 正在往 staging 写文件：数据目录还没被碰过，任何情况下都能安全回滚。 */
        const val PHASE_STAGING = "STAGING"

        /** 从这一刻起数据目录可能已被移走：启动时必须先看现场再决定往前还是往后。 */
        const val PHASE_SWAPPING = "SWAPPING"

        fun decode(text: String): RestoreJournal? {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return null
            if (lines.first() != "$MAGIC $VERSION") return null
            val fields = lines.drop(1)
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
                }
                .toMap()
            val phase = fields["phase"] ?: return null
            if (phase != PHASE_STAGING && phase != PHASE_SWAPPING) return null
            return RestoreJournal(
                stamp = fields["stamp"]?.toLongOrNull() ?: return null,
                phase = phase,
                hadRoot = fields["hadRoot"]?.toBooleanStrictOrNull() ?: return null,
                stagingName = fields["staging"]?.takeIf { it.isNotEmpty() } ?: return null,
                previousName = fields["previous"]?.takeIf { it.isNotEmpty() } ?: return null,
                files = fields["files"]?.toIntOrNull() ?: return null,
                bytes = fields["bytes"]?.toLongOrNull() ?: return null,
            )
        }

        private fun oneLine(value: String): String =
            value.replace('\n', ' ').replace('\r', ' ')
    }
}

/**
 * 日志文件本身：位置、原子写、读、删、给读不懂的那份改名留档。
 *
 * 写用"临时文件 + rename"：半个日志比没有日志更坏 ——
 * 它会让人以为恢复停在了一个根本没到过的阶段。
 */
internal object RestoreJournalFile {

    fun locate(root: File): File =
        File(root.absoluteFile.parentFile ?: root.absoluteFile, "${root.name}.restore-journal.json")

    fun write(root: File, journal: RestoreJournal) {
        val target = locate(root)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(journal.encode())
        // rename 在 Windows 上不能覆盖已存在的文件，先删。
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("无法写入恢复日志 ${target.absolutePath}")
        }
    }

    /** 不存在或读不懂都返回 null —— 两者由调用方用 [locate] 的存在性区分。 */
    fun readOrNull(root: File): RestoreJournal? {
        val target = locate(root)
        if (!target.isFile) return null
        return runCatching { RestoreJournal.decode(target.readText()) }.getOrNull()
    }

    fun delete(root: File) {
        val target = locate(root)
        runCatching { if (target.exists()) target.delete() }
        runCatching { File(target.parentFile, "${target.name}.tmp").delete() }
    }

    /**
     * 读不懂的日志改名留档，而不是删掉。
     *
     * 那份文件的字节是"上次到底发生了什么"的唯一证据；删掉之后只剩一句
     * "有个日志读不出来"，用户和我们都没法再往下查。
     */
    fun quarantine(root: File, stamp: Long): String? {
        val target = locate(root)
        if (!target.exists()) return null
        val moved = File(target.parentFile, "${target.name}.unreadable-$stamp")
        return if (runCatching { target.renameTo(moved) }.getOrDefault(false)) moved.absolutePath else null
    }
}
