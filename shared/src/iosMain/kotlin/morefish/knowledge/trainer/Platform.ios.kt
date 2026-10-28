package morefish.knowledge.trainer

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    /** 同上：手机/平板上不把快捷键写进界面。 */
    override val hasPhysicalKeyboard: Boolean = false
}

actual fun getPlatform(): Platform = IOSPlatform()