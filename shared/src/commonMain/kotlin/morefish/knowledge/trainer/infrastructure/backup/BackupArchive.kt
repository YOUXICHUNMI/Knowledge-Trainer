package morefish.knowledge.trainer.infrastructure.backup

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import morefish.knowledge.trainer.domain.backup.BackupCounts
import morefish.knowledge.trainer.domain.backup.BackupCrypt
import morefish.knowledge.trainer.domain.backup.BackupEntry
import morefish.knowledge.trainer.domain.backup.BackupManifest
import morefish.knowledge.trainer.domain.backup.BackupPayloadIndex
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.domain.backup.BackupPreview
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipFormatException
import morefish.knowledge.trainer.infrastructure.archive.ZipReader
import morefish.knowledge.trainer.infrastructure.archive.ZipWriteEntry
import morefish.knowledge.trainer.infrastructure.archive.ZipWriter
import morefish.knowledge.trainer.infrastructure.crypto.AesGcm
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 生成好的一份备份。
 *
 * [contentDigest] 单独带出来：加密备份里它在密文内（明细不能留在信封上），
 * 界面要显示"这份备份的内容指纹"时不能再去清单里找。
 */
class BackupBundle(
    val fileName: String,
    val bytes: ByteArray,
    val manifest: BackupManifest,
    val contentDigest: String,
    /**
     * 这份备份里实际装着哪些文件的路径。
     *
     * 只为自检而带出来：要回答"题目引用的那张图到底在不在包里"，只能问**打包进去的那份集合**，
     * 不能问"此刻磁盘上有什么" —— 两者之间隔着一次打包，答案可能不同。
     * 加密备份里它只在内存里，不写进任何明文。
     */
    val payloadPaths: Set<String> = emptySet(),
) {
    val encrypted: Boolean get() = manifest.encrypted
}

/** 读一份备份的结果。失败时 [preview] 可能是 null —— 连清单都读不出来。 */
class BackupReadResult(
    val preview: BackupPreview?,
    val problems: List<BackupProblem>,
    /** 只有全部校验通过才有内容：清单里的每个文件 → 字节。 */
    val payload: Map<String, ByteArray>,
) {
    val canRestore: Boolean get() = problems.isEmpty() && payload.isNotEmpty()

    /**
     * 加密备份，还没给口令。
     *
     * **这不是错误**：界面据此把"这份备份不能用"换成"请输入口令"。
     * 把"要口令"混进失败分支，会让用户以为文件坏了。
     */
    val requiresPassphrase: Boolean
        get() = problems.any { it.code == BackupProblem.PASSPHRASE_REQUIRED }
}

/**
 * `.qbk` 备份的读写。
 *
 * 两种容器：
 *
 * ```
 * 未加密（backupVersion = 1）              加密（backupVersion = 2）
 *   backup-manifest.json                     backup-manifest.json   明文信封：只有统计与加密参数
 *   data/<原路径> × N                        payload.enc            一个 AES-256-GCM 密文
 *                                            └ 内层索引 + 全部文件字节
 * ```
 *
 * 信封为什么必须明文：不打开就不知道"要不要口令"。代价是几个统计数字（几个题库、几条历史）
 * 会被人看见；所以**文件名与每个文件的 sha256 一律进密文** —— 摘要留在外面等于给
 * "猜文件内容"提供验证器，猜到就能确认，加密就白做了。
 *
 * 读的时候**逐条重算 sha256**，不信任清单里写的值：清单是"承诺"，校验是"兑现"。
 */
class BackupArchive(private val codec: DeflateCodec = PureDeflateCodec) {

    private val writeJson = Json { encodeDefaults = true; prettyPrint = true }

    /** 密文里的索引不需要好看，紧凑一点省体积。 */
    private val compactJson = Json { encodeDefaults = true }

    private val readJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- 写 ----------

    @OptIn(ExperimentalTime::class)
    fun create(
        files: Map<String, ByteArray>,
        deviceLabel: String,
        appVersion: String,
        nowMillis: Long,
        passphrase: String? = null,
    ): BackupBundle {
        if (files.isEmpty()) {
            throw BackupArchiveException(BackupProblem.EMPTY, "本机没有任何数据文件，这份备份会是空的")
        }
        // 按路径排序后再写：Map 的迭代顺序不能进文件，否则同数据打两次字节不同。
        // 注意不能用 `toSortedMap()` —— 那是 JVM 专有的扩展，commonMain 里没有。
        val sorted = files.entries.sortedBy { it.key }
        val entries = sorted.map { (path, bytes) ->
            BackupEntry(path = path, bytes = bytes.size.toLong(), sha256 = Sha256.hex(bytes))
        }
        val counts = countsOf(files)
        val digest = digestOf(entries)
        val fileName = fileNameOf(nowMillis)

        if (passphrase.isNullOrEmpty()) {
            val manifest = BackupManifest(
                backupVersion = 1,
                createdAtMillis = nowMillis,
                deviceLabel = deviceLabel,
                appVersion = appVersion,
                encrypted = false,
                counts = counts,
                files = entries,
                contentDigest = digest,
            )
            val zipEntries = buildList {
                add(ZipWriteEntry(BackupManifest.ENTRY_NAME, encodeManifest(manifest)))
                sorted.forEach { (path, bytes) -> add(ZipWriteEntry(BackupManifest.DATA_PREFIX + path, bytes)) }
            }
            return BackupBundle(fileName, ZipWriter.write(zipEntries), manifest, digest, files.keys)
        }

        if (passphrase.length < BackupCrypto.MIN_PASSPHRASE_CHARS) {
            throw BackupArchiveException(
                BackupProblem.PASSPHRASE_TOO_SHORT,
                "口令至少要 ${BackupCrypto.MIN_PASSPHRASE_CHARS} 个字符 —— 再短就挡不住暴力猜测",
            )
        }

        val salt = BackupCrypto.newSalt()
        val nonce = BackupCrypto.newNonce()
        val crypt = BackupCrypt(
            iterations = BackupCrypto.ITERATIONS,
            saltHex = BackupCrypto.hexOf(salt),
            nonceHex = BackupCrypto.hexOf(nonce),
        )
        // 信封先建出来，因为 AAD 要拿它的字段；files 故意留空（明细进密文）
        val envelope = BackupManifest(
            backupVersion = 2,
            createdAtMillis = nowMillis,
            deviceLabel = deviceLabel,
            appVersion = appVersion,
            encrypted = true,
            crypt = crypt,
            payloadEntry = BackupManifest.PAYLOAD_ENTRY,
            counts = counts,
            files = emptyList(),
            contentDigest = "",
        )
        val index = BackupPayloadIndex(files = entries, counts = counts, contentDigest = digest)
        val plain = encodePayload(index, sorted.map { it.value })
        if (plain.size.toLong() > AesGcm.MAX_BYTES) {
            throw BackupArchiveException(
                BackupProblem.TOO_LARGE,
                "加密备份一次最多处理 ${AesGcm.MAX_BYTES / (1024 * 1024)} MB 数据" +
                    "（本机这份是 ${plain.size / (1024 * 1024)} MB）—— 请先删掉部分图片，或改用不加密的备份",
            )
        }
        val sealed = BackupCrypto.seal(passphrase, plain, crypt, BackupCrypto.aadOf(envelope, crypt))
        val zipEntries = listOf(
            ZipWriteEntry(BackupManifest.ENTRY_NAME, encodeManifest(envelope)),
            ZipWriteEntry(envelope.payloadEntry, sealed),
        )
        return BackupBundle(fileName, ZipWriter.write(zipEntries), envelope, digest, files.keys)
    }

    private fun encodeManifest(manifest: BackupManifest): ByteArray =
        writeJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()

    /**
     * 密文里的字节布局：
     * ```
     * "QBK2" | 索引长度(4字节, 大端) | 索引 JSON | 全部文件字节（按索引顺序首尾相接）
     * ```
     * 不塞进 JSON 的 base64：那样体积涨三分之一，而且大备份要在内存里多转一趟。
     */
    private fun encodePayload(index: BackupPayloadIndex, contents: List<ByteArray>): ByteArray {
        val indexBytes = compactJson.encodeToString(BackupPayloadIndex.serializer(), index).encodeToByteArray()
        val total = MAGIC.size + 4 + indexBytes.size + contents.sumOf { it.size }
        val out = ByteArray(total)
        MAGIC.copyInto(out, 0)
        putInt(out, MAGIC.size, indexBytes.size)
        indexBytes.copyInto(out, MAGIC.size + 4)
        var offset = MAGIC.size + 4 + indexBytes.size
        for (content in contents) {
            content.copyInto(out, offset)
            offset += content.size
        }
        return out
    }

    /** 拆密文。结构不对一律抛 [BackupArchiveException]，由 [read] 转成稳定错误码。 */
    private fun decodePayload(plain: ByteArray): DecodedPayload {
        if (plain.size < MAGIC.size + 4) {
            throw BackupArchiveException(BackupProblem.MANIFEST_INVALID, "解密成功但内容太短，不像一份备份")
        }
        for (i in MAGIC.indices) {
            if (plain[i] != MAGIC[i]) {
                throw BackupArchiveException(BackupProblem.MANIFEST_INVALID, "解密出来的内容没有备份头标记")
            }
        }
        val indexLength = readInt(plain, MAGIC.size)
        val indexStart = MAGIC.size + 4
        if (indexLength <= 0 || indexStart + indexLength > plain.size) {
            throw BackupArchiveException(BackupProblem.MANIFEST_INVALID, "索引长度 ${indexLength} 越界")
        }
        val indexBytes = plain.copyOfRange(indexStart, indexStart + indexLength)
        val index = try {
            readJson.decodeFromString(BackupPayloadIndex.serializer(), indexBytes.decodeToString())
        } catch (e: Exception) {
            throw BackupArchiveException(BackupProblem.MANIFEST_INVALID, "索引读不出来：${e.message ?: "无法解析"}")
        }

        val duplicate = index.files.groupBy { it.path }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw BackupArchiveException(
                BackupProblem.MANIFEST_INVALID,
                "索引里同一个路径出现了多次（${duplicate.key}）",
            )
        }
        // 加密不等于可信：路径怎么来的都要查。
        val unsafe = index.files.firstOrNull { !isSafePath(it.path) }
        if (unsafe != null) {
            throw BackupArchiveException(
                BackupProblem.PATH_UNSAFE,
                "备份里有不安全或不合法的路径（${unsafe.path}），整份拒绝",
            )
        }

        var offset = indexStart + indexLength
        val contents = LinkedHashMap<String, ByteArray>(index.files.size)
        for (entry in index.files) {
            if (entry.bytes < 0 || offset + entry.bytes > plain.size) {
                throw BackupArchiveException(
                    BackupProblem.FILE_MISSING,
                    "索引说 ${entry.path} 有 ${entry.bytes} 字节，但密文里已经没有这么多内容了",
                )
            }
            val end = offset + entry.bytes.toInt()
            contents[entry.path] = plain.copyOfRange(offset, end)
            offset = end
        }
        if (offset != plain.size) {
            throw BackupArchiveException(
                BackupProblem.FILE_UNLISTED,
                "密文里还有 ${plain.size - offset} 字节没有被索引列出 —— 索引与内容对不上",
            )
        }
        return DecodedPayload(index, contents)
    }

    private class DecodedPayload(val index: BackupPayloadIndex, val contents: Map<String, ByteArray>)

    // ---------- 读 ----------

    fun read(bytes: ByteArray, passphrase: String? = null): BackupReadResult {
        if (!ZipReader.isZip(bytes)) {
            return failed(BackupProblem.NOT_ZIP, "这不是一个备份文件（是否选错了文件？）")
        }
        val entries = try {
            ZipReader.entries(bytes)
        } catch (e: ZipFormatException) {
            return failed(BackupProblem.NOT_ZIP, "备份文件损坏或不是备份：${e.message}")
        }

        val manifestEntry = entries.firstOrNull { it.name == BackupManifest.ENTRY_NAME }
            ?: return failed(BackupProblem.MANIFEST_MISSING, "备份里没有 ${BackupManifest.ENTRY_NAME}，这不是 Knowledge Trainer 的备份")
        val manifestBytes = try {
            ZipReader.read(bytes, manifestEntry, codec)
        } catch (e: ZipFormatException) {
            return failed(BackupProblem.MANIFEST_INVALID, "备份清单读不出来：${e.message}")
        }
        val manifest = try {
            readJson.decodeFromString(BackupManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            return failed(BackupProblem.MANIFEST_INVALID, "备份清单格式不对：${e.message ?: "无法解析"}")
        }

        val problems = mutableListOf<BackupProblem>()
        val warnings = mutableListOf<String>()

        if (manifest.format != BackupManifest.BACKUP_FORMAT) {
            problems += BackupProblem(
                BackupProblem.FORMAT_UNKNOWN,
                "这个包的格式是「${manifest.format}」，不是备份 —— 题库请用导入页",
            )
        }
        if (manifest.backupVersion > BackupManifest.BACKUP_VERSION) {
            problems += BackupProblem(
                BackupProblem.VERSION_UNSUPPORTED,
                "这份备份由更新版本的应用生成（备份格式 ${manifest.backupVersion}，本机只认到 " +
                    "${BackupManifest.BACKUP_VERSION}）—— 请先升级应用，不做「尽力恢复」",
            )
        }
        if (manifest.layoutVersion > BackupManifest.LAYOUT_VERSION) {
            problems += BackupProblem(
                BackupProblem.LAYOUT_UNSUPPORTED,
                "备份里的数据布局是第 ${manifest.layoutVersion} 版，本机只认到第 " +
                    "${BackupManifest.LAYOUT_VERSION} 版 —— 恢复进去也读不出来，所以不恢复",
            )
        }

        // 加密与否只决定"数据从哪来"，后面的校验完全共用一套。
        var effective = manifest
        val raw: Map<String, ByteArray?>

        if (manifest.encrypted) {
            val crypt = manifest.crypt
            val paramError = BackupCrypto.checkParams(crypt)
            if (crypt == null || paramError != null) {
                problems += BackupProblem(BackupProblem.CRYPT_PARAMS, paramError ?: "缺少加密参数")
                return finish(effective, problems, warnings, emptyMap())
            }
            if (manifest.files.isNotEmpty()) {
                problems += BackupProblem(
                    BackupProblem.MANIFEST_INVALID,
                    "加密备份的明文清单里不该出现文件名 —— 这份清单被人改过",
                )
                return finish(effective, problems, warnings, emptyMap())
            }
            if (passphrase.isNullOrEmpty()) {
                // 信封上的统计还能看：这是"要不要这份"的判断依据，不是错误。
                problems += BackupProblem(
                    BackupProblem.PASSPHRASE_REQUIRED,
                    "这份备份用口令加密了，输入口令才能看到里面的文件明细",
                )
                return finish(effective, problems, warnings, emptyMap())
            }
            val sealedEntry = entries.firstOrNull { it.name == manifest.payloadEntry }
                ?: return failed(
                    BackupProblem.FILE_MISSING,
                    "加密备份里找不到密文条目 ${manifest.payloadEntry} —— 这份备份不完整",
                )
            val sealed = try {
                ZipReader.read(bytes, sealedEntry, codec)
            } catch (e: ZipFormatException) {
                return failed(BackupProblem.FILE_MISSING, "密文条目读不出来：${e.message}")
            }
            val plain = BackupCrypto.open(passphrase, sealed, crypt, BackupCrypto.aadOf(manifest, crypt))
                ?: return failed(
                    BackupProblem.DECRYPT_FAILED,
                    "解不开：口令不对，或者这份文件被改动过 —— 这两件事在密码学上分不开",
                )
            val decoded = try {
                decodePayload(plain)
            } catch (e: BackupArchiveException) {
                return failed(e.code, e.message ?: "密文结构不对")
            }
            effective = manifest.copy(
                files = decoded.index.files,
                counts = decoded.index.counts,
                contentDigest = decoded.index.contentDigest,
            )
            raw = decoded.contents

            val extraInZip = entries.filter {
                it.name != BackupManifest.ENTRY_NAME && it.name != manifest.payloadEntry
            }
            if (extraInZip.isNotEmpty()) {
                problems += BackupProblem(
                    BackupProblem.FILE_UNLISTED,
                    "加密备份里还有多余条目（如 ${extraInZip.first().name}）—— 这个包被塞过东西",
                )
            }
        } else {
            // 路径安全：清单里的路径就是恢复时要写的路径，必须先过这一关再看别的。
            val unsafe = manifest.files.filter { !isSafePath(it.path) }
            if (unsafe.isNotEmpty()) {
                problems += BackupProblem(
                    BackupProblem.PATH_UNSAFE,
                    "备份清单里有不安全或不合法的路径（如 ${unsafe.first().path}），整份拒绝",
                )
            }

            // 实际条目 → 逐个核对。清单说有的必须真有，包里多的必须报出来（不做"忽略多余条目"）。
            val dataEntries = entries.filter { it.name.startsWith(BackupManifest.DATA_PREFIX) }
            val byName = dataEntries.associateBy { it.name.removePrefix(BackupManifest.DATA_PREFIX) }
            val read = LinkedHashMap<String, ByteArray?>(manifest.files.size)
            for (entry in manifest.files) {
                val zipEntry = byName[entry.path]
                read[entry.path] = if (zipEntry == null) {
                    null
                } else {
                    try {
                        ZipReader.read(bytes, zipEntry, codec)
                    } catch (e: ZipFormatException) {
                        null
                    }
                }
            }
            raw = read

            val listed = manifest.files.mapTo(HashSet()) { it.path }
            val extra = byName.keys.filterNot { it in listed }
            if (extra.isNotEmpty()) {
                problems += BackupProblem(
                    BackupProblem.FILE_UNLISTED,
                    "备份里有清单没有列出的文件（如 ${extra.first()}）—— 清单与内容对不上",
                )
            }
        }

        val payload = LinkedHashMap<String, ByteArray>(effective.files.size)
        for (entry in effective.files) {
            val content = raw[entry.path]
            if (content == null) {
                problems += BackupProblem(
                    BackupProblem.FILE_MISSING,
                    "备份里缺少数据文件 ${entry.path} —— 这份备份不完整",
                )
                continue
            }
            if (content.size.toLong() != entry.bytes) {
                problems += BackupProblem(
                    BackupProblem.HASH_MISMATCH,
                    "数据文件 ${entry.path} 的大小与清单不符（清单 ${entry.bytes} 字节，实际 ${content.size}）",
                )
                continue
            }
            if (Sha256.hex(content) != entry.sha256) {
                problems += BackupProblem(
                    BackupProblem.HASH_MISMATCH,
                    "数据文件 ${entry.path} 的摘要与清单不符 —— 文件被改过或没拷完整",
                )
                continue
            }
            payload[entry.path] = content
        }

        if (effective.files.isEmpty()) {
            problems += BackupProblem(BackupProblem.EMPTY, "这份备份里没有任何数据文件")
        }

        // 清单自己声明的内容摘要：重算一遍。不符要如实说出来 —— 说明这份清单被人改过。
        if (effective.contentDigest.isNotBlank()) {
            val recomputed = digestOf(effective.files)
            if (recomputed != effective.contentDigest) {
                problems += BackupProblem(
                    BackupProblem.DIGEST_MISMATCH,
                    "整份备份的摘要与清单里写的不一致 —— 清单被改动过",
                )
            }
        }

        if (effective.deviceLabel.isBlank()) warnings += "这份备份没有记录来源设备"
        if (effective.createdAtMillis <= 0) warnings += "这份备份没有记录创建时间"
        if (payload.values.sumOf { it.size.toLong() } > ZipReader.MAX_TOTAL_BYTES) {
            problems += BackupProblem(BackupProblem.TOO_LARGE, "备份内容超过上限，恢复会把内存吃光")
        }

        return finish(effective, problems, warnings, payload)
    }

    // ---------- 工具 ----------

    private fun finish(
        manifest: BackupManifest,
        problems: MutableList<BackupProblem>,
        warnings: MutableList<String>,
        payload: Map<String, ByteArray>,
    ): BackupReadResult {
        val preview = BackupPreview(
            manifest = manifest,
            problems = problems.distinctBy { it.code to it.message },
            warnings = warnings,
        )
        return BackupReadResult(
            preview = preview,
            problems = preview.problems,
            payload = if (preview.canRestore) payload else emptyMap(),
        )
    }

    private fun failed(code: String, message: String): BackupReadResult {
        val problem = BackupProblem(code, message)
        return BackupReadResult(preview = null, problems = listOf(problem), payload = emptyMap())
    }

    /**
     * 路径只允许"相对、`/` 分隔、无 `..`、无盘符"。与 [ZipWriter] 的写入规则同源，
     * 保证"我们自己写的备份自己一定读得回来"。密文里也一样要查 —— 加密不等于可信。
     */
    private fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.length > 400) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        if (path.any { it.code < 0x20 }) return false
        return path.split('/').none { it == ".." || it == "." || it.isEmpty() }
    }

    /** 整份备份的指纹：与题库包的整包摘要同一套做法，但版本标签不同（两者不能混用）。 */
    private fun digestOf(entries: List<BackupEntry>): String {
        val body = buildString {
            append(DIGEST_VERSION).append('\n')
            entries.sortedBy { it.path }.forEach { append(it.path).append(' ').append(it.sha256).append('\n') }
        }
        return Sha256.hex(body.encodeToByteArray())
    }

    /** 文件名带日期时间，方便用户在一堆备份里认人。日期按本机时区，不按 UTC。 */
    @OptIn(ExperimentalTime::class)
    private fun fileNameOf(nowMillis: Long): String {
        val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        fun pad(v: Int) = if (v < 10) "0$v" else "$v"
        return "kt-backup-${local.year}-${pad(local.month.ordinal + 1)}-${pad(local.day)}-" +
            "${pad(local.hour)}${pad(local.minute)}.qbk"
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    companion object {
        /** 与题库包的 `kt-qbf-digest-1` 不是一回事：这是"整份备份"的指纹。 */
        const val DIGEST_VERSION = "kt-qbk-digest-1"

        /** 密文的头标记。它出现在**解密之后**，所以本身不是安全边界，只是"格式对不对"的自检。 */
        private val MAGIC = "QBK2".encodeToByteArray()

        /**
         * 从数据文件里数出"看得懂的几项"。
         *
         * 全部用宽松解析：备份可能来自旧版本，某个文件格式不认识也只当作"数不出来"，
         * 绝不能因为统计失败就判定备份坏了 —— 统计只是给人看的。
         */
        fun countsOf(files: Map<String, ByteArray>): BackupCounts {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            fun textOf(name: String): String? =
                files.entries.firstOrNull { it.key == name || it.key.endsWith("/$name") }
                    ?.value?.decodeToString()

            var banks = 0
            var questions = 0
            textOf(BANKS_FILE)?.let { raw ->
                runCatching {
                    val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching
                    val array = root["banks"] as? JsonArray ?: return@runCatching
                    banks = array.size
                    array.forEach { item ->
                        val obj = item as? JsonObject ?: return@forEach
                        // schema 2：目录里直接有题数；schema 1：题目内嵌在 banks 里
                        questions += (obj["questionCount"] as? JsonPrimitive)?.content?.toIntOrNull()
                            ?: (obj["questions"] as? JsonArray)?.size
                            ?: 0
                    }
                }
            }

            val attempts = lineCount(textOf(ATTEMPTS_FILE))
            val reviews = lineCount(textOf(REVIEWS_FILE))
            val notes = runCatching {
                val root = json.parseToJsonElement(textOf(NOTES_FILE) ?: "{}") as? JsonObject
                (root?.get("notes") as? JsonArray)?.size ?: 0
            }.getOrDefault(0)

            var mediaFiles = 0
            var mediaBytes = 0L
            files.forEach { (path, bytes) ->
                if (path.startsWith(MEDIA_PREFIX)) {
                    mediaFiles++
                    mediaBytes += bytes.size
                }
            }

            val totalFiles = files.size
            val totalBytes = files.values.sumOf { it.size.toLong() }
            // "其他"= 上面没单独统计过的文件（各库题目文件、会话、图谱、扩展包、AI 记录…）。
            // 不逐个解析：它们随时会增减字段，统计跟着坏比不统计更糟。
            val known = files.keys.count { it in KNOWN_TOP_FILES } + mediaFiles
            return BackupCounts(
                banks = banks,
                questions = questions,
                attempts = attempts,
                notes = notes,
                reviews = reviews,
                mediaFiles = mediaFiles,
                mediaBytes = mediaBytes,
                otherFiles = (totalFiles - known).coerceAtLeast(0),
                totalFiles = totalFiles,
                totalBytes = totalBytes,
            )
        }

        private fun lineCount(text: String?): Int {
            if (text.isNullOrBlank()) return 0
            return text.lineSequence().count { it.isNotBlank() }
        }

        private const val BANKS_FILE = "banks-v1.json"
        private const val ATTEMPTS_FILE = "attempts-v1.ndjson"
        private const val REVIEWS_FILE = "attempt-reviews-v1.ndjson"
        private const val NOTES_FILE = "notes-v1.json"
        private const val MEDIA_PREFIX = "media/"

        private val KNOWN_TOP_FILES = setOf(BANKS_FILE, ATTEMPTS_FILE, REVIEWS_FILE, NOTES_FILE)
    }
}

/** 备份读写过程中"这份备份不能用"的失败。 */
class BackupArchiveException(val code: String, message: String) : Exception(message)
