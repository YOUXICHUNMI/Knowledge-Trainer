package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.RevisionRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.patch.BankRevision

internal const val REVISIONS_FILE = "bank-revisions-v1.json"

/**
 * 修订日志仓储：整文件重写。
 *
 * 量级是"一个题库被更新过几次"，最多几十条，不值得为它设计追加式存储。
 * 但有两件事必须做对：
 *
 * 1. **同一修订号只留一条**：重放同一个补丁时不该出现两条"第 2 版"。
 * 2. **整份损坏时不覆盖原文件**：这份日志记录着"哪几道题的答案什么时候变的"，
 *    重建不出来 —— 宁可这次读成空、让用户看到一条告警，也不能把它抹掉。
 */
class JsonRevisionRepository(
    private val storage: TextStorage,
) : RevisionRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    /**
     * 读不出来的那份原文。
     *
     * 只有留着它，才谈得上"不覆盖原文件" —— append 迟早要重写这个文件，
     * 重写前先把原文另存一份，用户至少还能拿它去人工恢复。
     */
    private var corruptRaw: String? = null

    override val loadWarnings: List<String> get() = warnings

    override suspend fun of(bankId: String): List<BankRevision> = mutex.withLock {
        readLocked().firstOrNull { it.bankId == bankId }?.entries.orEmpty()
            .sortedBy { it.revision }
            .map { it.toDomain() }
    }

    override suspend fun latest(bankId: String): BankRevision? = of(bankId).lastOrNull()

    override suspend fun latestAll(): Map<String, BankRevision> = mutex.withLock {
        readLocked()
            .mapNotNull { log -> log.entries.maxByOrNull { it.revision }?.let { log.bankId to it.toDomain() } }
            .toMap()
    }

    override suspend fun append(bankId: String, revision: BankRevision) {
        mutex.withLock {
            val all = readLocked().toMutableList()
            corruptRaw?.let { raw ->
                storage.write("$REVISIONS_FILE.corrupt", raw)
                corruptRaw = null
            }
            val index = all.indexOfFirst { it.bankId == bankId }
            val entries = if (index >= 0) all[index].entries.toMutableList() else mutableListOf()
            // 同一修订号覆盖而不是并列：重放同一个补丁不该产生两条"第 N 版"
            entries.removeAll { it.revision == revision.revision }
            entries += revision.toDto()
            val log = BankRevisionLogDto(bankId, entries.sortedBy { it.revision })
            if (index >= 0) all[index] = log else all += log
            writeLocked(all)
        }
    }

    private fun writeLocked(logs: List<BankRevisionLogDto>) {
        storage.write(
            REVISIONS_FILE,
            json.encodeToString(RevisionsFileDto.serializer(), RevisionsFileDto(banks = logs)),
        )
    }

    private fun readLocked(): List<BankRevisionLogDto> {
        val raw = storage.read(REVISIONS_FILE)
        if (raw.isNullOrBlank()) {
            warnings = emptyList()
            corruptRaw = null
            return emptyList()
        }
        val dto = runCatching { json.decodeFromString(RevisionsFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            // 下次 append 会重写这份文件，所以先把原文另存一份：
            // 里面记着"哪几道题的答案什么时候变的"，重建不出来
            warnings = listOf(
                "$REVISIONS_FILE 无法解析，题库更新记录暂时不可见" +
                    "（下次写入前会把原文另存为 $REVISIONS_FILE.corrupt）",
            )
            corruptRaw = raw
            return emptyList()
        }
        warnings = emptyList()
        corruptRaw = null
        return dto.banks.filter { it.bankId.isNotBlank() }
    }
}

internal fun RevisionEntryDto.toDomain(): BankRevision = BankRevision(
    revision = revision,
    patchId = patchId,
    name = name,
    atMillis = atMillis,
    added = added,
    updated = updated,
    removed = removed,
    answerChangedIds = answerChangedIds,
    affectedAttempts = affectedAttempts,
    note = note,
)

internal fun BankRevision.toDto(): RevisionEntryDto = RevisionEntryDto(
    revision = revision,
    patchId = patchId,
    name = name,
    atMillis = atMillis,
    added = added,
    updated = updated,
    removed = removed,
    answerChangedIds = answerChangedIds,
    affectedAttempts = affectedAttempts,
    note = note,
)
