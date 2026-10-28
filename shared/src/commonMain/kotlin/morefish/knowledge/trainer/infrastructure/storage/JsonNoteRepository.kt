package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import morefish.knowledge.trainer.application.port.NoteRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope

internal const val NOTES_FILE = "notes-v1.json"

internal val noteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 笔记仓储的 JSON 实现。
 *
 * 与题库一样整文件重写，但读写时机不同：笔记是"用户正在编辑的东西"，
 * 所以每次保存都立即落盘，不做延迟合并 —— 崩溃丢掉刚写的笔记是最难解释的损失。
 *
 * 单条记录损坏不影响其余记录：跳过并计数，通过 [loadWarnings] 告知用户。
 */
class JsonNoteRepository(
    private val storage: TextStorage,
) : NoteRepository {

    private val json = noteJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun all(): List<Note> = mutex.withLock { readLocked() }

    override suspend fun find(noteId: String): Note? = all().firstOrNull { it.noteId == noteId }

    override suspend fun upsert(note: Note) {
        mutex.withLock {
            val existing = readLocked()
            val next = existing.filterNot { it.noteId == note.noteId } + note
            writeLocked(next)
        }
    }

    override suspend fun delete(noteId: String) {
        mutex.withLock {
            val existing = readLocked()
            if (existing.none { it.noteId == noteId }) return@withLock
            writeLocked(existing.filterNot { it.noteId == noteId })
        }
    }

    private fun writeLocked(notes: List<Note>) {
        storage.write(NOTES_FILE, json.encodeToString(NotesFileDto.serializer(), NotesFileDto(notes = notes.map { it.toDto() })))
    }

    private fun readLocked(): List<Note> {
        val raw = storage.read(NOTES_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val dto = runCatching { json.decodeFromString(NotesFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            warnings = listOf("笔记文件无法解析，已按空笔记打开（原始文件未被覆盖，下次保存会重建）")
            return emptyList()
        }
        var skipped = 0
        val notes = dto.notes.mapNotNull { item ->
            // 计数放在 also 里：写成 if/else 会让 lambda 推断成 Any（Int 或 Note）
            item.toDomain().also { if (it == null) skipped++ }
        }
        warnings = if (skipped == 0) emptyList() else listOf("笔记中有 $skipped 条记录格式不受支持，已跳过")
        return notes
    }
}

private fun Note.toDto(): NoteDto = NoteDto(
    noteId = noteId,
    scope = scope.wireName,
    bankId = bankId,
    questionId = questionId,
    title = title,
    bodyMarkdown = bodyMarkdown,
    pinned = pinned,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private fun NoteDto.toDomain(): Note? {
    val scope = NoteScope.fromWire(scope) ?: return null
    if (noteId.isBlank()) return null
    return Note(
        noteId = noteId,
        scope = scope,
        bankId = bankId?.takeIf { it.isNotBlank() },
        questionId = questionId?.takeIf { it.isNotBlank() },
        title = title,
        bodyMarkdown = bodyMarkdown,
        pinned = pinned,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}
