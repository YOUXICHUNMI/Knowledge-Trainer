package morefish.knowledge.trainer.presentation.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.application.port.AppPreferences
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.PreferencesStore
import morefish.knowledge.trainer.application.port.ThemeMode
import morefish.knowledge.trainer.infrastructure.storage.JsonPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主题选择的存取与循环。
 *
 * 这里要钉住的两件事：**点一下确实换到下一个**（顺序错乱是最常见的接线错误），
 * 以及**换完确实写盘**（写漏了的表现是"用得好好的，一重启又回到浅色"，
 * 而那种问题几乎不会有人立刻反馈）。
 */
class ThemeControllerTest {

    private class RecordingStore(var prefs: AppPreferences = AppPreferences()) : PreferencesStore {
        var saves = 0
        override suspend fun load(): AppPreferences = prefs
        override suspend fun save(preferences: AppPreferences) {
            saves += 1
            prefs = preferences
        }
    }

    private fun controller(store: PreferencesStore): ThemeController =
        ThemeController(store, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun beforeAnythingIsLoadedTheDefaultIsFollowingTheSystem() {
        val store = RecordingStore(AppPreferences(ThemeMode.DARK))
        val theme = controller(store)

        assertEquals(ThemeMode.SYSTEM, theme.mode, "构造时不该先猜一个，等 load 说了算")
        assertEquals(0, store.saves, "还没人做选择，不该写盘")
    }

    @Test
    fun loadingRestoresWhatWasSavedLastTime() = runBlocking {
        val store = RecordingStore(AppPreferences(ThemeMode.DARK))
        val theme = controller(store)

        theme.load()

        assertEquals(ThemeMode.DARK, theme.mode)
        assertNull(theme.warning, "读得出来就不该有警告")
    }

    @Test
    fun tappingCyclesThroughAllThreeAndComesBack() {
        val store = RecordingStore()
        val theme = controller(store)
        val seen = buildList {
            add(theme.mode)
            repeat(3) {
                theme.cycle()
                add(theme.mode)
            }
        }

        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM),
            seen,
            "循环顺序是「跟随系统 → 浅色 → 深色」，因为那是最不容易点错的那个顺序",
        )
        assertEquals(3, store.saves, "每次真的换了才写盘")
    }

    @Test
    fun settingTheValueItAlreadyHasDoesNotTouchTheDisk() = runBlocking {
        val store = RecordingStore()
        val theme = controller(store)
        theme.load()

        theme.set(ThemeMode.SYSTEM)

        assertEquals(0, store.saves, "没变化就不写，免得每次进页面都动一次磁盘")
    }

    @Test
    fun theChoiceSurvivesARestart() = runBlocking {
        val storage = InMemoryTextStorage()
        val first = controller(JsonPreferencesRepository(storage))
        first.load()
        first.set(ThemeMode.DARK)

        // 换一个进程（新仓储、新控制器）读同一份存储
        val second = controller(JsonPreferencesRepository(storage))
        second.load()

        assertEquals(ThemeMode.DARK, second.mode, "重开之后应当还是深色")
    }

    @Test
    fun aCorruptPreferenceFileFallsBackToTheSystemInsteadOfFailing() = runBlocking {
        val storage = InMemoryTextStorage()
        storage.write("prefs-v1.json", "{ 这不是 JSON")

        val repo = JsonPreferencesRepository(storage)
        val theme = controller(repo)
        theme.load()

        assertEquals(ThemeMode.SYSTEM, theme.mode, "读坏了就回到跟随系统，不能让应用起不来")
        assertTrue(
            theme.warning?.contains("界面偏好") == true,
            "读坏了要说出来，实际是 ${theme.warning}",
        )
    }

    @Test
    fun anUnknownThemeNameIsTreatedAsFollowingTheSystem() = runBlocking {
        val storage = InMemoryTextStorage()
        storage.write("prefs-v1.json", """{"schemaVersion":1,"themeMode":"SEPIA"}""")

        val repo = JsonPreferencesRepository(storage)
        val theme = controller(repo)
        theme.load()

        assertEquals(ThemeMode.SYSTEM, theme.mode, "认不出来的名字按跟随系统，而不是猜一个相近的")
        assertNull(theme.warning, "这是一个能解析的文件，只是值不认识 —— 不值得打断用户")
    }

    /** 标签直接显示在首页胶囊上，写错了用户看到的就一直是错的。 */
    @Test
    fun everyModeHasAShortLabel() {
        assertEquals(
            listOf("跟随系统", "浅色", "深色"),
            ThemeMode.entries.map { it.label },
        )
    }
}
