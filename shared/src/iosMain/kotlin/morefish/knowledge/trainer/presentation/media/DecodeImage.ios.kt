package morefish.knowledge.trainer.presentation.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/** iOS：与桌面共用 Skia 解码。 */
actual suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Main) {
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
