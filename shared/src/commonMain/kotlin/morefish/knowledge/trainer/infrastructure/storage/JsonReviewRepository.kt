package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.ReviewRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.practice.AttemptReview
import morefish.knowledge.trainer.domain.practice.ReviewDecision

internal const val REVIEWS_FILE = "attempt-reviews-v1.ndjson"

/**
 * 历史复核仓储：NDJSON 追加写。
 *
 * 与历史本身同一个纪律 —— **只追加，不改写**。理由也一样：
 * "我后来改判了这条"是一个发生过的事件，允许就地改写就等于允许篡改。
 * 推翻上一次决定时再追加一条，读取时以最后一条为准。
 *
 * 同一条历史可能被复核多次（题目又改了），所以文件会随复核次数线性增长；
 * 量级是"用户主动点过的决定"，与作答历史不在一个数量级，不值得做压缩。
 */
class JsonReviewRepository(
    private val storage: TextStorage,
) : ReviewRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun all(): List<AttemptReview> = mutex.withLock { readLocked() }

    override suspend fun append(review: AttemptReview) {
        mutex.withLock {
            storage.appendLine(
                REVIEWS_FILE,
                json.encodeToString(AttemptReviewDto.serializer(), review.toDto()),
            )
        }
    }

    private fun readLocked(): List<AttemptReview> {
        val raw = storage.read(REVIEWS_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        var corrupt = 0
        val records = buildList {
            raw.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val dto = runCatching {
                    json.decodeFromString(AttemptReviewDto.serializer(), line)
                }.getOrNull()
                val review = dto?.toDomain()
                if (review == null) corrupt++ else add(review)
            }
        }
        warnings = if (corrupt == 0) {
            emptyList()
        } else {
            listOf("历史复核记录中有 $corrupt 行无法解析，已跳过（原始文件未修改）")
        }
        return records
    }
}

internal fun AttemptReview.toDto(): AttemptReviewDto = AttemptReviewDto(
    attemptId = attemptId,
    bankId = bankId,
    questionId = questionId,
    decision = when (decision) {
        ReviewDecision.ACCEPT -> "accept"
        ReviewDecision.RESTORE -> "restore"
    },
    previousVerdict = previousVerdict.name,
    effectiveVerdict = effectiveVerdict.name,
    basisFingerprint = basisFingerprint,
    decidedAtMillis = decidedAtMillis,
)

internal fun AttemptReviewDto.toDomain(): AttemptReview? {
    if (attemptId.isBlank()) return null
    val previous = Verdict.entries.firstOrNull { it.name == previousVerdict } ?: return null
    val effective = Verdict.entries.firstOrNull { it.name == effectiveVerdict } ?: return null
    val kind = when (decision.trim().lowercase()) {
        "accept" -> ReviewDecision.ACCEPT
        "restore" -> ReviewDecision.RESTORE
        else -> return null
    }
    return AttemptReview(
        attemptId = attemptId,
        bankId = bankId,
        questionId = questionId,
        decision = kind,
        previousVerdict = previous,
        effectiveVerdict = effective,
        basisFingerprint = basisFingerprint,
        decidedAtMillis = decidedAtMillis,
    )
}
