package morefish.knowledge.trainer.presentation.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android：BitmapFactory 解码；inPreferredConfig 用 ARGB_8888 保证缩放与透明度质量。 */
actual suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}
