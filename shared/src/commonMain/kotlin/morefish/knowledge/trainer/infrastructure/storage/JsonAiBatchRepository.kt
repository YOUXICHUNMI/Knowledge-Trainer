package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.AiBatchRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchState

internal const val AI_BATCHES_FILE = "ai-batches-v1.json"

/**
 * 批量任务清单。
 *
 * 与账本（NDJSON 只追加）不同，这里是**整份重写**：任务是可变状态，
 * 追加写会产生"同一项有五行进度"的日志，重建时反而更容易错。
 * 写入频率由 [morefish.knowledge.trainer.application.usecase.RunBatchUseCase] 节流（每 5 项一次）。
 *
 * 只保留最近 [MAX_JOBS] 个任务：完成的任务是记录，不是档案。
 * 超出时优先丢"已经结束且最老"的，**正在跑/暂停的一律留着** ——
 * 把用户还没跑完的任务当作垃圾清掉，等于偷偷删了他的待办。
 */
class JsonAiBatchRepository(
    private val storage: TextStorage,
) : AiBatchRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun jobs(): List<AiBatchJob> = mutex.withLock {
        readLocked().sortedByDescending { it.createdAtMillis }
    }

    override suspend fun find(jobId: String): AiBatchJob? = mutex.withLock {
        readLocked().firstOrNull { it.jobId == jobId }
    }

    override suspend fun save(job: AiBatchJob) {
        mutex.withLock {
            val current = readLocked().filterNot { it.jobId == job.jobId }
            writeLocked(trim(current + job))
        }
    }

    override suspend fun delete(jobId: String) {
        mutex.withLock {
            val current = readLocked()
            if (current.none { it.jobId == jobId }) return@withLock
            writeLocked(current.filterNot { it.jobId == jobId })
        }
    }

    /** 保留所有未结束的任务，其余按创建时间留最新的，凑到上限为止。 */
    private fun trim(jobs: List<AiBatchJob>): List<AiBatchJob> {
        if (jobs.size <= MAX_JOBS) return jobs
        val (open, closed) = jobs.partition { it.isOpen }
        val room = (MAX_JOBS - open.size).coerceAtLeast(0)
        val keptClosed = closed.sortedByDescending { it.createdAtMillis }.take(room)
        return open + keptClosed
    }

    private fun writeLocked(jobs: List<AiBatchJob>) {
        storage.write(
            AI_BATCHES_FILE,
            json.encodeToString(
                AiBatchesFileDto.serializer(),
                AiBatchesFileDto(jobs = jobs.sortedByDescending { it.createdAtMillis }.map { it.toDto() }),
            ),
        )
    }

    private fun readLocked(): List<AiBatchJob> {
        val raw = storage.read(AI_BATCHES_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val file = runCatching { json.decodeFromString(AiBatchesFileDto.serializer(), raw) }.getOrNull()
        if (file == null) {
            // 整份坏掉不覆盖：里面是用户排好的待办，重建不出来（题目顺序、本次上限都是他的决定）
            warnings = listOf("批量任务清单无法解析，已按空处理（原文件未被覆盖）")
            return emptyList()
        }
        var skipped = 0
        val jobs = file.jobs.mapNotNull { dto ->
            val state = AiBatchState.fromWire(dto.state)
            if (dto.jobId.isBlank() || dto.bankId.isBlank() || state == null || dto.items.isEmpty()) {
                skipped++
                null
            } else {
                dto.toDomain(state)
            }
        }
        if (skipped > 0) {
            warnings = listOf("有 $skipped 个批量任务不完整，已跳过（原文件未修改）")
        }
        return jobs
    }

    private companion object {
        const val MAX_JOBS = 20
    }
}

// ------------------------------------------------------------------ 映射

private fun AiBatchJob.toDto(): AiBatchJobDto = AiBatchJobDto(
    jobId = jobId,
    bankId = bankId,
    bankTitle = bankTitle,
    model = model,
    locale = locale,
    state = state.wireName,
    cursor = cursor,
    maxCalls = maxCalls,
    note = note,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    items = items.map { it.toDto() },
)

private fun AiBatchItem.toDto(): AiBatchItemDto = AiBatchItemDto(
    bankId = bankId,
    questionId = questionId,
    locale = locale,
    questionRevision = questionRevision,
    contentFingerprint = contentFingerprint,
    state = state.wireName,
    attempts = attempts,
    note = note,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    costMicros = costMicros,
)

private fun AiBatchJobDto.toDomain(state: AiBatchState): AiBatchJob = AiBatchJob(
    jobId = jobId,
    bankId = bankId,
    bankTitle = bankTitle,
    model = model,
    locale = locale,
    items = items.mapNotNull { item ->
        val itemState = AiBatchItemState.fromWire(item.state) ?: return@mapNotNull null
        if (item.questionId.isBlank()) return@mapNotNull null
        AiBatchItem(
            // 旧文件里 item 的 bankId 可能是空的，回落到任务本身的
            bankId = item.bankId.ifBlank { bankId },
            questionId = item.questionId,
            locale = item.locale.ifBlank { locale },
            questionRevision = item.questionRevision,
            contentFingerprint = item.contentFingerprint,
            state = itemState,
            attempts = item.attempts,
            note = item.note,
            promptTokens = item.promptTokens,
            completionTokens = item.completionTokens,
            costMicros = item.costMicros,
        )
    },
    state = state,
    cursor = cursor,
    maxCalls = maxCalls,
    note = note,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = if (updatedAtMillis == 0L) createdAtMillis else updatedAtMillis,
)
