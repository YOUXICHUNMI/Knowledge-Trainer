package morefish.knowledge.trainer

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    /** 手机上默认没有物理键盘：界面不该写"按 A 选择"。 */
    override val hasPhysicalKeyboard: Boolean = false
}

actual fun getPlatform(): Platform = AndroidPlatform()