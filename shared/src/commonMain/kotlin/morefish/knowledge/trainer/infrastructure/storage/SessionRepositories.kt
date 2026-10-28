package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.FavoriteRepository
import morefish.knowledge.trainer.application.port.SessionRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.model.PracticeDraft
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.SessionState
import morefish.knowledge.trainer.domain.practice.QuestionKey

internal const val SESSION_FILE = "session-v1.json"
internal const val FAVORITES_FILE = "favorites-v1.json"

/**
 * 会话仓储的 JSON 实现。整文件重写，但只保存"当前这一场"，
 * 文件大小 = 本次抽到的题目数，与题库规模无关。
 */
class JsonSessionRepository(
    private val storage: TextStorage,
) : SessionRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun save(session: PracticeSession) {
        mutex.withLock {
            val dto = SessionFileDto(session = session.toDto())
            storage.write(SESSION_FILE, json.encodeToString(SessionFileDto.serializer(), dto))
        }
    }

    override suspend fun active(): PracticeSession? = mutex.withLock { readLocked() }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            val current = readLocked()
            // 只有仍然指向这一场会话时才清除，避免把后来新建的会话误删。
            if (current == null || current.sessionId == sessionId) {
                storage.write(SESSION_FILE, "")
            }
        }
    }

    private fun readLocked(): PracticeSession? {
        val raw = storage.read(SESSION_FILE) ?: return null
        if (raw.isBlank()) return null

        val dto = runCatching { json.decodeFromString(SessionFileDto.serializer(), raw) }.getOrNull()
        if (dto?.session == null) {
            warnings = listOf("上次的练习进度无法解析，已忽略（已提交的历史记录不受影响）")
            return null
        }
        val session = dto.session.toDomain()
        if (session == null) {
            warnings = listOf("上次的练习进度不完整（题目内容缺失），已忽略")
            return null
        }
        warnings = emptyList()
        return session.takeIf { it.isResumable }
    }
}

/** 收藏仓储：纯本地标记，不进历史，也不影响判题。 */
class JsonFavoriteRepository(
    private val storage: TextStorage,
) : FavoriteRepository {

    private val json = storageJson
    private val mutex = Mutex()

    override suspend fun all(): Set<QuestionKey> = mutex.withLock { readLocked() }

    override suspend fun toggle(key: QuestionKey): Set<QuestionKey> = mutex.withLock {
        val next = readLocked().toMutableSet()
        if (!next.add(key)) next.remove(key)
        val dto = FavoritesFileDto(items = next.map { it.wire }.sorted())
        storage.write(FAVORITES_FILE, json.encodeToString(FavoritesFileDto.serializer(), dto))
        next.toSet()
    }

    private fun readLocked(): Set<QuestionKey> {
        val raw = storage.read(FAVORITES_FILE) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        val dto = runCatching { json.decodeFromString(FavoritesFileDto.serializer(), raw) }.getOrNull()
            ?: return emptySet()
        return dto.items.mapNotNull { QuestionKey.parse(it) }.toSet()
    }
}

internal fun PracticeSession.toDto(): SessionDto = SessionDto(
    sessionId = sessionId,
    bankId = bankId,
    bankTitle = bankTitle,
    planLabel = planLabel,
    mode = mode.wireName,
    state = state.name,
    cursor = cursor,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    items = items.map { SessionItemDto(question = it.question.toDto(), displayOptionIds = it.displayOptionIds) },
    draft = DraftDto(
        selectedOptionIds = draft.selectedOptionIds.toList(),
        textAnswer = draft.textAnswer,
        orderedOptionIds = draft.orderedOptionIds,
    ),
    locale = locale,
)

internal fun SessionDto.toDomain(): PracticeSession? {
    if (items.isEmpty()) return null

    // 任何一题读不出来就整体放弃恢复：半截会话会导致题号与答案错位。
    val restoredItems = ArrayList<SessionItem>(items.size)
    for (item in items) {
        val question = item.question.toDomain() ?: return null
        if (item.displayOptionIds.any { question.option(it) == null }) return null
        restoredItems.add(
            SessionItem(
                question = question,
                displayOptionIds = item.displayOptionIds.ifEmpty { question.options.map { it.optionId } },
            ),
        )
    }

    return PracticeSession(
        sessionId = sessionId,
        bankId = bankId,
        mode = PracticeMode.entries.firstOrNull { it.wireName == mode } ?: PracticeMode.NATIVE,
        state = SessionState.entries.firstOrNull { it.name == state } ?: SessionState.IN_PROGRESS,
        items = restoredItems,
        cursor = cursor.coerceIn(0, restoredItems.size),
        attempts = emptyList(), // 已提交记录以历史仓储为准，恢复时再合并
        createdAtMillis = createdAtMillis,
        bankTitle = bankTitle,
        planLabel = planLabel,
        draft = PracticeDraft(
            selectedOptionIds = draft.selectedOptionIds.toSet(),
            textAnswer = draft.textAnswer,
            orderedOptionIds = draft.orderedOptionIds,
        ),
        updatedAtMillis = updatedAtMillis,
        locale = locale,
    )
}
