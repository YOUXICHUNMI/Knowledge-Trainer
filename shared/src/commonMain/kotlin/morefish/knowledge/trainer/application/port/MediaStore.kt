package morefish.knowledge.trainer.application.port

/**
 * 题库媒体字节的存取端口。
 *
 * 键是 (bankId, assetId)：图片属于**某一次导入的题库**，同名 assetId 在不同题库间互不相干。
 * 字节在导入 .qbank 时一次性写入（导入成功 = 媒体已完整落盘），练习/回顾界面按需读取。
 *
 * 桌面 / Android 用文件实现；Web / iOS 用内存实现（随存储警告一起提示，不假装持久）。
 */
interface MediaStore {
    suspend fun put(bankId: String, assetId: String, bytes: ByteArray)
    suspend fun get(bankId: String, assetId: String): ByteArray?

    /** 题库被外部清空时同步清理媒体，避免磁盘上留下无人引用的字节。 */
    suspend fun removeAll(bankId: String)
}

class InMemoryMediaStore : MediaStore {
    // 与 InMemoryTextStorage 相同的并发模型：JS/Wasm 单线程无需同步；
    // JVM/Android 上默认内存实现只用于测试与降级路径，不做细粒度锁。
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun put(bankId: String, assetId: String, bytes: ByteArray) {
        files["$bankId\u0000$assetId"] = bytes
    }

    override suspend fun get(bankId: String, assetId: String): ByteArray? =
        files["$bankId\u0000$assetId"]

    override suspend fun removeAll(bankId: String) {
        files.keys.removeAll { it.startsWith("$bankId\u0000") }
    }
}
