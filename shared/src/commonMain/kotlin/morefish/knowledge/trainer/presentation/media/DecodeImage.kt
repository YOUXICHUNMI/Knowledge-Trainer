package morefish.knowledge.trainer.presentation.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 把图片字节解码成可显示的 [ImageBitmap]。
 *
 * 各平台原生解码器不同（桌面/iOS/Web 走 Skia，Android 走 BitmapFactory），
 * 解码失败一律返回 null，由调用方决定占位表现 —— 不允许抛异常打断作答流程。
 */
expect suspend fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
