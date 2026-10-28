package morefish.knowledge.trainer.infrastructure.storage

import morefish.knowledge.trainer.application.port.MediaStore
import morefish.knowledge.trainer.domain.model.mediaFileId
import java.io.File

/** 基于应用私有目录的媒体存储（Android）。与桌面端实现同构，只是根目录来源不同。 */
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
