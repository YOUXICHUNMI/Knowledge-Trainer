package morefish.knowledge.trainer.presentation.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.AppPreferences
import morefish.knowledge.trainer.application.port.PreferencesStore
import morefish.knowledge.trainer.application.port.ThemeMode

/**
 * 主题选择：三种模式、一个写盘点。
 *
 * 它不属于任何一个功能，只回答"这台机器上次关掉时是什么样"。写盘是**即改即存**：
 * 主题是唯一一种"改完立刻就能看见"的偏好，让用户再去找一次"保存"没有道理。
 *
 * 写盘失败不弹横幅 —— 主题存不下来最多是下次启动回到默认，
 * 为这件事打断作答是更糟的交换。真正读不出来时由 [load] 把原因交给调用方去说。
 */
class ThemeController(
    private val store: PreferencesStore,
    private val scope: CoroutineScope,
) {

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** 上次 [load] 时发现的问题（偏好文件损坏等）；null = 一切正常。 */
    var warning by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        val prefs = store.load()
        mode = prefs.themeMode
        warning = store.loadWarnings.firstOrNull()
    }

    fun set(next: ThemeMode) {
        if (next == mode) return
        mode = next
        persist()
    }

    /** 点一下按「跟随系统 → 浅色 → 深色」循环。 */
    fun cycle() = set(mode.next())

    private fun persist() {
        val snapshot = AppPreferences(themeMode = mode)
        scope.launch { store.save(snapshot) }
    }
}
