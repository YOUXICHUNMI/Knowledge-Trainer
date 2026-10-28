package morefish.knowledge.trainer.infrastructure.archive

/**
 * 默认的 raw inflate 实现：纯 Kotlin（见 [Inflate]）。
 *
 * 曾经用 okio 的 `InflaterSource`，但它在 JS/Wasm 上没有对应实现，
 * 而浏览器唯一的原生解压 API（`DecompressionStream`）是异步的，接不进同步的 ZIP 解析。
 * 自己实现一份反而四端一致、无依赖、可单测。
 */
object PureDeflateCodec : DeflateCodec {

    override fun inflateRaw(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray? =
        Inflate.raw(data, offset, length, expectedSize)
}
