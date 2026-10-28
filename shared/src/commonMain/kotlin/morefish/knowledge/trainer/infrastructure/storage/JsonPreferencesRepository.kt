package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import morefish.knowledge.trainer.application.port.AppPreferences
import morefish.knowledge.trainer.application.port.PreferencesStore
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.application.port.ThemeMode

internal const val PREFERENCES_FILE = "prefs-v1.json"

@Serializable
internal data class PreferencesFileDto(
    val schemaVersion: Int = 1,
    /** 主题选择的枚举名（`SYSTEM` / `LIGHT` / `DARK`），认不出来时按跟随系统处理。 */
    val themeMode: String = ThemeMode.SYSTEM.name,
)

/**
 * 界面偏好：单独一个小文件，与业务数据完全分开。
 *
 * 放进 `notes-v1.json` 或 `ai-config-v1.json` 都会让"换个主题"去碰一份有语义的文件 ——
 * 主题坏了不该牵连笔记，笔记格式演进了也不该顺手把主题重置。
 *
 * 现存于数据目录，因此它天然进了备份（`.qbk` 是整目录打包）；
 * 恢复一份别人的备份会连主题一起换过去，这是可接受的 —— 那是"这台机器的样子"。
 */
class JsonPreferencesRepository(
    private val storage: TextStorage,
) : PreferencesStore {

    private val json = storageJson
    private val mutex = Mutex()

    override var loadWarnings: List<String> = emptyList()
        private set

    override suspend fun load(): AppPreferences = mutex.withLock {
        val raw = storage.read(PREFERENCES_FILE)
        if (raw.isNullOrBlank()) {
            loadWarnings = emptyList()
            return AppPreferences()
        }
        val dto = runCatching { json.decodeFromString(PreferencesFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            loadWarnings = listOf("界面偏好无法解析，已回到默认（跟随系统）")
            return AppPreferences()
        }
        loadWarnings = emptyList()
        AppPreferences(themeMode = ThemeMode.parse(dto.themeMode))
    }

    override suspend fun save(preferences: AppPreferences) {
        mutex.withLock {
            val dto = PreferencesFileDto(themeMode = preferences.themeMode.name)
            storage.write(PREFERENCES_FILE, json.encodeToString(PreferencesFileDto.serializer(), dto))
        }
    }
}
