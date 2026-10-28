package morefish.knowledge.trainer.presentation.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** Wasm（浏览器，Skiko Wasm 后端）：与 JS 相同的同步 Skia 解码。 */
actual suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
