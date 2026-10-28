package morefish.knowledge.trainer

interface Platform {
    val name: String

    /**
     * 这台设备上有没有能用的物理键盘。
     *
     * 只用来决定"要不要把快捷键写进界面"：手机屏幕上写"按 A 选择"，只会让人到处找按键。
     * 能不能响应是另一回事 —— 平板接键盘、模拟器映射键盘，按键本身照常管用。
     */
    val hasPhysicalKeyboard: Boolean get() = true
}

expect fun getPlatform(): Platform
