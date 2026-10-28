package morefish.knowledge.trainer.infrastructure.archive

/**
 * 平台无关的 DEFLATE 解压能力。
 *
 * 为什么不用 java.util.zip.ZipFile：它是 JVM 专属，Web/iOS 上不存在。
 * 而 ZIP 的**解压**只需要「raw inflate」这一件事，各平台都有成熟实现：
 * JVM/Android 用 java.util.zip.Inflater，JS 用 DecompressionStream("deflate-raw")，
 * iOS 用 Compression framework 的 COMPRESSION_ZLIB。
 *
 * ZIP 的**结构解析**（中央目录、本地头、CRC）则由纯 Kotlin 的 [ZipReader] 完成，
 * 这样三个平台只有这一小段平台代码。
 */
interface DeflateCodec {
    /**
     * 解压一段 raw deflate 数据。
     * @param expectedSize 解压后应有的大小（来自 ZIP 本地头）；用于预分配与超限保护。
     * @return 解压结果；失败返回 null（调用方决定是"损坏"还是"不支持"）。
     */
    fun inflateRaw(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray?
}

/** 未提供实现时的占位：让不支持 ZIP 的平台给出明确错误，而不是崩溃。 */
object UnsupportedDeflateCodec : DeflateCodec {
    override fun inflateRaw(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray? = null
}
