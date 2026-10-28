package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.DataVault
import morefish.knowledge.trainer.application.port.RestoreOutcome
import morefish.knowledge.trainer.application.port.RestoreRecovery
import morefish.knowledge.trainer.application.port.RestoreRecoveryKind
import morefish.knowledge.trainer.application.port.VaultException
import morefish.knowledge.trainer.application.port.VaultSnapshot
import java.io.File

/**
 * 数据目录级的备份/恢复（桌面端）。
 *
 * 布局就是 [FileTextStorage] 与 [FileMediaStore] 用的那个根目录：
 *
 * ```
 * ~/.knowledge-trainer/
 *   banks-v1.json
 *   banks/<bankId>/questions-v1.ndjson
 *   attempts-v1.ndjson
 *   notes-v1.json
 *   media/<bankId>/<assetId>
 *   ...
 * ```
 *
 * 三个关键约定：
 *
 * 1. **快照按字节抄，不解析、不重组**。恢复之后身份完全不变（bankId / questionId /
 *    作答时间戳 / 复核决定），因为它们本来就没被碰过。
 * 2. **恢复是"整目录换掉"**：先写到同级的 staging 目录并逐字节复核，
 *    再把旧目录改名为 `*.pre-restore-<时间>`、staging 改名为正式目录。
 *    任何一步失败都回滚，原数据不动 —— "恢复一半"比不恢复更糟。
 * 3. **整个过程记在恢复日志里**（[RestoreJournal]）。日志在数据目录外面，
 *    所以"数据目录暂时不存在"这个瞬间也能被解释清楚；下次启动由
 *    [recoverInterrupted] 收拾现场并告诉用户现在生效的是哪一份数据。
 *
 * 旧数据目录**不删**：它是用户唯一的退路，界面会告诉用户它在哪。
 * 下一次恢复时才清理更早的那一份，避免无限堆积。
 */
class DirectoryVault(
    private val root: File,
    override val deviceLabel: String = defaultDeviceLabel(),
) : DataVault {

    override val isSupported: Boolean = true
    override val unsupportedReason: String? = null
    override val locationLabel: String = root.absolutePath

    override suspend fun snapshot(): VaultSnapshot {
        if (!root.exists()) return VaultSnapshot(emptyMap())
        val files = sortedMapOf<String, ByteArray>()
        var total = 0L
        root.walkTopDown()
            .onEnter { dir -> !isIgnoredName(dir.name) }
            .forEach { file ->
                if (!file.isFile || isIgnoredName(file.name)) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val bytes = file.readBytes()
                total += bytes.size
                if (total > MAX_SNAPSHOT_BYTES) {
                    throw VaultException(
                        "VAULT_TOO_LARGE",
                        "数据超过 ${MAX_SNAPSHOT_BYTES / 1024 / 1024} MB，这次备份没有做 —— " +
                            "请先清理不需要的题库或图片",
                    )
                }
                files[relative] = bytes
            }
        if (files.size > MAX_SNAPSHOT_FILES) {
            throw VaultException(
                "VAULT_TOO_MANY_FILES",
                "数据文件超过 $MAX_SNAPSHOT_FILES 个，这次备份没有做",
            )
        }
        return VaultSnapshot(files)
    }

    /**
     * 只走一遍目录树取路径。
     *
     * 跳过规则必须和 [snapshot] 完全一致：自检看的文件集合要是打包时那一个，
     * 否则 `.tmp` 之类的中间态会让"图在不在"这件事两个地方给出不同答案。
     */
    override suspend fun listPaths(): Set<String> {
        if (!root.exists()) return emptySet()
        val paths = HashSet<String>()
        root.walkTopDown()
            .onEnter { dir -> !isIgnoredName(dir.name) }
            .forEach { file ->
                if (!file.isFile || isIgnoredName(file.name)) return@forEach
                paths += file.relativeTo(root).invariantSeparatorsPath
            }
        return paths
    }

    override suspend fun replaceAll(files: Map<String, ByteArray>): RestoreOutcome {
        if (files.isEmpty()) throw VaultException("VAULT_EMPTY", "这份备份里没有任何数据文件，不能用来恢复")
        val parent = root.absoluteFile.parentFile
            ?: throw VaultException("VAULT_NO_PARENT", "数据目录没有上级目录，无法原地替换")
        if (!parent.exists() && !parent.mkdirs()) {
            throw VaultException("VAULT_NO_PARENT", "无法创建上级目录 ${parent.absolutePath}")
        }

        val stamp = System.currentTimeMillis()
        val staging = File(parent, "${root.name}.restore-staging-$stamp")
        val previous = File(parent, "${root.name}.pre-restore-$stamp")
        // 恢复开始前数据目录在不在：写进日志，"本来就没有数据"与"数据不见了"才能分开
        val hadRoot = root.exists()
        var completed = false

        try {
            // ---- STAGING：接下来的写入不会碰数据目录，任何中断都能安全回滚
            RestoreJournalFile.write(
                root,
                RestoreJournal(
                    stamp = stamp,
                    phase = RestoreJournal.PHASE_STAGING,
                    hadRoot = hadRoot,
                    stagingName = staging.name,
                    previousName = previous.name,
                    files = files.size,
                    bytes = 0,
                ),
            )

            staging.mkdirs()
            var bytes = 0L
            files.forEach { (path, content) ->
                val target = File(staging, path)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw VaultException("VAULT_PATH_UNSAFE", "备份里有指向数据目录之外的路径：$path")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                bytes += content.size
            }

            // 写完立刻复核长度：磁盘满或被其他进程截断时，rename 会成功但内容是残的。
            files.forEach { (path, content) ->
                val written = File(staging, path)
                if (!written.isFile || written.length() != content.size.toLong()) {
                    throw VaultException("VAULT_WRITE_INCOMPLETE", "写入不完整：$path（可能磁盘空间不足）")
                }
            }

            // ---- SWAPPING：从这一行开始，数据目录可能短暂不存在
            RestoreJournalFile.write(
                root,
                RestoreJournal(
                    stamp = stamp,
                    phase = RestoreJournal.PHASE_SWAPPING,
                    hadRoot = hadRoot,
                    stagingName = staging.name,
                    previousName = previous.name,
                    files = files.size,
                    bytes = bytes,
                ),
            )

            if (hadRoot) {
                if (!root.renameTo(previous)) {
                    throw VaultException(
                        "VAULT_BUSY",
                        "无法把当前数据移到 ${previous.name}（可能有文件正被占用），本机数据没有被改动",
                    )
                }
            }
            if (staging.renameTo(root)) {
                completed = true
            } else {
                // 换入失败：把旧数据搬回来，宁可"什么都没发生"。
                if (previous.exists()) previous.renameTo(root)
                throw VaultException("VAULT_SWAP_FAILED", "切换到新数据失败，已回滚到原来的数据")
            }

            RestoreJournalFile.delete(root)
            cleanupOlderThan(previous)
            return RestoreOutcome(
                filesWritten = files.size,
                bytesWritten = bytes,
                previousLocation = if (previous.exists()) previous.absolutePath else null,
            )
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            throw VaultException("VAULT_IO", "恢复失败：${e.message ?: e::class.simpleName}")
        } finally {
            // 失败时自己收尾，免得下次启动把一次正常的失败当成"上次被打断"。
            // 但**只在数据目录还在的时候**这么做：数据目录不在，说明回滚也没成功，
            // 那份日志必须留着，让下次启动去收拾。
            if (!completed && root.exists()) RestoreJournalFile.delete(root)
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * 启动时收拾上一次被打断的恢复。
     *
     * 只有四种现场，每一种的正确答案都不一样：
     *
     * | 日志阶段 | 数据目录 | staging | 判断 |
     * |---|---|---|---|
     * | STAGING | 在 | 可能有半成品 | 回滚：删掉半成品，数据目录没被碰过 |
     * | SWAPPING | 在 + 旧目录在 | — | 已经换好了，只是没来得及收尾 |
     * | SWAPPING | 在 + 没有旧目录 | — | 还没开始移旧目录，回滚 |
     * | SWAPPING | 不在 | 完整 | 前滚：把新数据接上 |
     * | SWAPPING | 不在 | 缺失/残缺 | 回滚：把旧数据放回去 |
     *
     * **自愈只收拾半成品，不删任何"可能是退路"的数据**：
     * 旧数据目录、日志指向的目录，一律不动（除了把它改名为正式目录）。
     */
    override suspend fun recoverInterrupted(): RestoreRecovery? {
        // 这个方法**不抛**：它在启动路径上，抛出去就等于"应用起不来"。
        // 真出错时如实返回 RECOVERY_FAILED，界面会说"什么都没敢动"。
        return try {
            healInterrupted()
        } catch (e: Exception) {
            RestoreRecovery(
                kind = RestoreRecoveryKind.RECOVERY_FAILED,
                dataLocation = root.absolutePath,
                detail = "收拾现场时出错：${e.message ?: e::class.simpleName} —— 本机数据没有被改动",
            )
        }
    }

    private fun healInterrupted(): RestoreRecovery? {
        val journalFile = RestoreJournalFile.locate(root)
        if (!journalFile.exists()) return null

        val journal = RestoreJournalFile.readOrNull(root)
            ?: run {
                val kept = RestoreJournalFile.quarantine(root, System.currentTimeMillis())
                return RestoreRecovery(
                    kind = RestoreRecoveryKind.JOURNAL_UNREADABLE,
                    dataLocation = root.absolutePath,
                    detail = if (kept != null) {
                        "读不懂的那份日志已改名保存在 $kept（没有删 —— 它是当时唯一的证据）"
                    } else {
                        "读不懂的那份日志没能移走（可能被占用）：下次启动还会再报一次，数据仍然不动"
                    },
                )
            }

        val parent = root.absoluteFile.parentFile ?: root.absoluteFile
        val staging = File(parent, journal.stagingName)
        val previous = File(parent, journal.previousName)
        val rootExists = root.exists()
        val previousExists = previous.exists()

        return when (journal.phase) {
            RestoreJournal.PHASE_STAGING -> when {
                // 数据目录从来没存在过（首次恢复），半成品清掉就是干净状态
                !rootExists && !journal.hadRoot -> {
                    val removed = staging.exists()
                    staging.deleteRecursively()
                    RestoreJournalFile.delete(root)
                    RestoreRecovery(
                        kind = RestoreRecoveryKind.ROLLED_BACK,
                        dataLocation = root.absolutePath,
                        stagingRemoved = removed,
                        detail = if (removed) "本机原本就没有数据，半成品已经清掉" else "本机原本就没有数据",
                    )
                }
                // 数据目录没被碰过：删掉半成品，别的什么都不做
                rootExists -> {
                    val removed = staging.exists()
                    staging.deleteRecursively()
                    RestoreJournalFile.delete(root)
                    RestoreRecovery(
                        kind = RestoreRecoveryKind.ROLLED_BACK,
                        dataLocation = root.absolutePath,
                        stagingRemoved = removed,
                        filesInPlace = countFiles(root),
                    )
                }
                // 该阶段不该出现数据目录消失；真消失了就是别人的事，如实说
                else -> RestoreRecovery(
                    kind = RestoreRecoveryKind.DATA_MISSING,
                    dataLocation = root.absolutePath,
                    stagingRemoved = false,
                    previousLocation = previous.takeIf { previousExists }?.absolutePath,
                    detail = "数据目录不见了。这个阶段本来不该动它，更可能是别的程序删掉的 —— " +
                        "请手动检查 ${root.absolutePath} 和同级的 ${previous.name}",
                )
            }

            else -> when {
                // 换入已经完成：root 是新数据，旧数据在旁边
                rootExists && previousExists -> {
                    RestoreJournalFile.delete(root)
                    RestoreRecovery(
                        kind = RestoreRecoveryKind.ALREADY_DONE,
                        dataLocation = root.absolutePath,
                        previousLocation = previous.absolutePath,
                        filesInPlace = countFiles(root),
                    )
                }
                // 还没开始移旧目录：root 仍是原来的数据
                rootExists -> {
                    val removed = staging.exists()
                    staging.deleteRecursively()
                    RestoreJournalFile.delete(root)
                    RestoreRecovery(
                        kind = RestoreRecoveryKind.ROLLED_BACK,
                        dataLocation = root.absolutePath,
                        stagingRemoved = removed,
                        filesInPlace = countFiles(root),
                    )
                }
                // 数据目录不在：优先前滚（新数据已经写完并逐字节复核过）
                staging.exists() && stagingMatches(staging, journal) -> {
                    if (staging.renameTo(root)) {
                        RestoreJournalFile.delete(root)
                        RestoreRecovery(
                            kind = RestoreRecoveryKind.ROLLED_FORWARD,
                            dataLocation = root.absolutePath,
                            previousLocation = previous.takeIf { previousExists }?.absolutePath,
                            filesInPlace = countFiles(root),
                        )
                    } else {
                        rollbackPrevious(previous, previousExists, staging)
                    }
                }
                // 半成品残缺：不能前滚，把旧数据放回去
                else -> rollbackPrevious(previous, previousExists, staging)
            }
        }
    }

    private fun rollbackPrevious(previous: File, previousExists: Boolean, staging: File): RestoreRecovery {
        if (previousExists && previous.renameTo(root)) {
            val removed = staging.exists()
            staging.deleteRecursively()
            RestoreJournalFile.delete(root)
            return RestoreRecovery(
                kind = RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP,
                dataLocation = root.absolutePath,
                previousLocation = null,
                stagingRemoved = removed,
                filesInPlace = countFiles(root),
            )
        }
        // 旧数据在但改不回去（被占用 / 权限）：**不要删任何东西**，留着让人自己救
        return RestoreRecovery(
            kind = RestoreRecoveryKind.DATA_MISSING,
            dataLocation = root.absolutePath,
            previousLocation = previous.takeIf { previousExists }?.absolutePath,
            detail = if (previousExists) {
                "数据目录现在是空的；旧数据完整地在 ${previous.absolutePath}，" +
                    "但改名回原位失败（可能被别的程序占用）—— 先关掉其他窗口再试一次"
            } else {
                "数据目录是空的，旧数据目录 ${previous.absolutePath} 也不存在 —— 没有可自动恢复的东西了"
            },
        )
    }

    /** staging 是否"写完过"：文件数与总字节都要和日志对得上。 */
    private fun stagingMatches(staging: File, journal: RestoreJournal): Boolean {
        var files = 0
        var bytes = 0L
        staging.walkTopDown().forEach { if (it.isFile) { files++; bytes += it.length() } }
        return files == journal.files && bytes == journal.bytes
    }

    private fun countFiles(dir: File): Int? {
        if (!dir.exists()) return null
        var files = 0
        dir.walkTopDown().forEach { if (it.isFile) files++ }
        return files
    }

    /** 只留下本次那一份旧数据；更早的清理掉，否则每恢复一次就多存一整套数据。 */
    private fun cleanupOlderThan(keep: File) {
        val parent = keep.parentFile ?: return
        val prefix = "${root.name}.pre-restore-"
        parent.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) && it.name != keep.name }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * 快照要跳过的东西：
     * - `*.tmp`：[FileTextStorage] 的写入中间态，抄进备份等于把半截 JSON 带过去；
     * - `*.restore-staging-*` / `*.pre-restore-*`：恢复过程的临时目录与旧数据，
     *   它们和正式目录同级（不在 root 里面），这里只是多兜一道底；
     * - `*.restore-journal.json`：恢复日志。**进了备份就等于把"我正在恢复"带到别人机器上**，
     *   那台机器启动时会去收拾一场不存在的崩溃。
     */
    private fun isIgnoredName(name: String): Boolean =
        name.endsWith(".tmp") ||
            name.contains(".restore-staging-") ||
            name.contains(".pre-restore-") ||
            name.contains(".restore-journal")

    companion object {
        /** 单次备份的数据上限。超过就明确拒绝，而不是打一个 4 GB 的包让用户自己发现。 */
        const val MAX_SNAPSHOT_BYTES: Long = 2L * 1024 * 1024 * 1024

        const val MAX_SNAPSHOT_FILES = 50000

        private fun defaultDeviceLabel(): String {
            val os = System.getProperty("os.name") ?: "桌面端"
            val user = System.getProperty("user.name") ?: ""
            return if (user.isBlank()) os else "$os · $user"
        }
    }
}
