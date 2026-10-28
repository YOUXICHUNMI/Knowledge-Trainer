package morefish.knowledge.trainer.presentation.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * JS（浏览器，Canvas 后端）：skiko-js 的 Skia 解码在 JS 里是同步的，
 * 无需切调度器 —— Dispatchers.Default 在 JS 上就是主线程。
 */
actual suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
