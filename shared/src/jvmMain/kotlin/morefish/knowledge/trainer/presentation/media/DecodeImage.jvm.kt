package morefish.knowledge.trainer.presentation.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/** 桌面端（JVM）：Skia 解码，支持 JPEG / PNG / WebP / GIF 等包内常见格式。 */
actual suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
