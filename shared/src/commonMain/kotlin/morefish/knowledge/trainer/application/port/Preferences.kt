package morefish.knowledge.trainer.application.port

/**
 * 界面主题的用户选择。
 *
 * 只有三种，且默认跟随系统 —— "跟随系统"是唯一一个不需要用户先做决定的选项：
 * 大多数人在系统里已经选过了，应用再问一次等于把同一个问题问两遍。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** 点一下按钮之后轮到哪一个（跟随系统 → 浅色 → 深色 → 跟随系统）。 */
    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    /** 界面上的短标签。 */
    val label: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            LIGHT -> "浅色"
            DARK -> "深色"
        }

    companion object {
        /**
         * 从磁盘上的字符串还原。
         *
         * 认不出来一律回到 [SYSTEM]，而不是抛异常或猜一个：一个手改坏的字段
         * 不该让应用起不来，也不该让用户莫名其妙被锁在深色里。
         */
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

/** 本机的界面偏好。与题库、历史无关，纯"这台机器上的人希望它长什么样"。 */
data class AppPreferences(val themeMode: ThemeMode = ThemeMode.SYSTEM)

/**
 * 偏好存取。
 *
 * 与其他仓储的区别：这里没有身份、没有版本、没有合并语义 —— 就是"上次关掉时是什么样"。
 * 因此读失败一律返回默认值并留一条警告，**不**把它当成"用户改过"。
 */
interface PreferencesStore {
    suspend fun load(): AppPreferences
    suspend fun save(preferences: AppPreferences)

    /** 读取时发现的问题（文件损坏等），用于如实告警。 */
    val loadWarnings: List<String> get() = emptyList()
}
