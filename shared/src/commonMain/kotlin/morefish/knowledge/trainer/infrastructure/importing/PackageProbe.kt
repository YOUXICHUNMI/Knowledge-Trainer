package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import morefish.knowledge.trainer.domain.backup.BackupManifest
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.ZipReader

/** 认出来的包类型。三种格式不可互相改名使用，所以这里也只看内容、不看扩展名。 */
enum class PackageFormat(val label: String) {
    QBANK(".qbank 题库"),
    QBEXT(".qbext 扩展包"),
    QBPATCH(".qbpatch 更新补丁"),
    QBK(".qbk 本机备份"),
    UNKNOWN("未知"),
}

/**
 * 只读 manifest.json 里的 format 字段，判断"这是哪种包"。
 *
 * 拖拽进来的文件没有可靠的扩展名（也可能被改名），所以分发必须看内容；
 * 但**只看一眼清单**，不在这里做完整校验 —— 真正的校验由各自的读取器 fail-closed 完成。
 */
object PackageProbe {

    private const val MANIFEST = "manifest.json"
    const val QBANK_FORMAT_ID = "knowledge-trainer-question-bank"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun formatOf(bytes: ByteArray, codec: DeflateCodec): PackageFormat {
        if (!ZipReader.isZip(bytes)) return PackageFormat.UNKNOWN
        val entries = runCatching { ZipReader.entries(bytes) }.getOrNull() ?: return PackageFormat.UNKNOWN
        // 备份的清单叫 backup-manifest.json：它和数据文件放一起，改名成 .qbank 也不该被当成题库。
        val manifest = entries.firstOrNull { !it.isDirectory && it.name == MANIFEST }
        if (manifest == null) {
            return if (entries.any { it.name == BackupManifest.ENTRY_NAME }) PackageFormat.QBK else PackageFormat.UNKNOWN
        }
        val text = runCatching { ZipReader.read(bytes, manifest, codec).decodeToString() }.getOrNull()
            ?: return PackageFormat.UNKNOWN
        val format = runCatching {
            (json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject["format"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
        }.getOrNull()
        return when (format) {
            QBANK_FORMAT_ID -> PackageFormat.QBANK
            QbextImporter.FORMAT_ID -> PackageFormat.QBEXT
            QbpatchImporter.FORMAT_ID -> PackageFormat.QBPATCH
            BackupManifest.BACKUP_FORMAT -> PackageFormat.QBK
            else -> PackageFormat.UNKNOWN
        }
    }

    /** 给错误提示用：把清单里的 format / title / name 尽量摸出来，但不保证存在。 */
    fun describeUnknown(bytes: ByteArray, codec: DeflateCodec): String {
        if (!ZipReader.isZip(bytes)) return "不是 ZIP 包"
        val entries = runCatching { ZipReader.entries(bytes) }.getOrNull()
            ?: return "ZIP 中央目录损坏"
        val manifest = entries.firstOrNull { !it.isDirectory && it.name == MANIFEST }
            ?: return "ZIP 里没有 manifest.json"
        val text = runCatching { ZipReader.read(bytes, manifest, codec).decodeToString() }.getOrNull()
            ?: return "manifest.json 无法解压"
        val obj: JsonObject = runCatching { json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject }
            .getOrNull() ?: return "manifest.json 不是 JSON 对象"
        val format = (obj["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (format == null) "manifest.json 没有 format 字段" else "未知的包类型 format=$format"
    }
}
