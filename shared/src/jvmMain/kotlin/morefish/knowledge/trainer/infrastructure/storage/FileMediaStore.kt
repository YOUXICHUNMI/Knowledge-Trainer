package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.MediaStore
import morefish.knowledge.trainer.domain.model.mediaFileId
import java.io.File

/**
 * 基于文件系统的媒体存储（桌面端）。
 *
 * 布局：`<root>/media/<bankId>/<assetId>`。写入先落 .tmp 再重命名，
 * 与 [FileTextStorage] 相同的原子性约定 —— 导入中断不会留下半个文件。
 */
class FileMediaStore(private val root: File) : MediaStore {

    private val mediaRoot: File by lazy {
        val dir = File(root, "media")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    override suspend fun put(bankId: String, assetId: String, bytes: ByteArray) {
        val file = file(bankId, assetId)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "无法写入 ${file.absolutePath}" }
        }
    }

    override suspend fun get(bankId: String, assetId: String): ByteArray? {
        val file = file(bankId, assetId)
        return if (file.isFile) file.readBytes() else null
    }

    override suspend fun removeAll(bankId: String) {
        val dir = File(mediaRoot, mediaFileId(bankId))
        dir.deleteRecursively()
    }

    private fun file(bankId: String, assetId: String): File =
        File(File(mediaRoot, mediaFileId(bankId)), mediaFileId(assetId))
}
