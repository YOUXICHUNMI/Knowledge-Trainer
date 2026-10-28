package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.AiRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.ai.AiBudget
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiPricing
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.ai.AiUsage
import morefish.knowledge.trainer.domain.ai.AiUsageEntry

internal const val AI_CONFIG_FILE = "ai-config-v1.json"
internal const val AI_CREDENTIAL_FILE = "ai-credential-v1.json"
internal const val AI_EXPLANATIONS_FILE = "ai-explanations-v1.json"
internal const val AI_USAGE_FILE = "ai-usage-v1.ndjson"

/**
 * AI 数据的文件仓储。
 *
 * 四个文件各自负责一件事：
 * - `ai-config-v1.json`：可分享的接入配置（地址、模型、预算、单价），**没有密钥**
 * - `ai-credential-v1.json`：密钥单独一份，关闭"记住密钥"时会被清空
 * - `ai-explanations-v1.json`：讲解记录（缓存 + 审核状态），量级是"练习过的题"
 * - `ai-usage-v1.ndjson`：账本，只追加不重写
 *
 * 讲解放这里而不是放进题库：它是**用户这边**产生的派生内容，
 * 题库文件永远只存作者给的东西。缓存丢了最多重新生成一次，不会伤到题目。
 */
class JsonAiRepository(
    private val storage: TextStorage,
) : AiRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun config(): AiConfig = mutex.withLock { readConfigLocked() }

    override suspend fun saveConfig(config: AiConfig) {
        mutex.withLock {
            storage.write(
                AI_CONFIG_FILE,
                json.encodeToString(AiConfigFileDto.serializer(), config.toDto()),
            )
        }
    }

    override suspend fun credential(): String? = mutex.withLock {
        val raw = storage.read(AI_CREDENTIAL_FILE) ?: return@withLock null
        if (raw.isBlank()) return@withLock null
        val dto = runCatching { json.decodeFromString(AiCredentialFileDto.serializer(), raw) }.getOrNull()
        dto?.apiKey?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveCredential(key: String) {
        mutex.withLock {
            storage.write(
                AI_CREDENTIAL_FILE,
                json.encodeToString(
                    AiCredentialFileDto.serializer(),
                    AiCredentialFileDto(apiKey = key.trim()),
                ),
            )
        }
    }

    override suspend fun records(): List<AiExplanation> = mutex.withLock { readRecordsLocked() }

    override suspend fun find(key: String): AiExplanation? = mutex.withLock {
        readRecordsLocked().firstOrNull { it.key == key }
    }

    override suspend fun save(record: AiExplanation) {
        mutex.withLock {
            val current = readRecordsLocked()
            val next = (current.filterNot { it.key == record.key } + record)
                .sortedByDescending { it.updatedAtMillis }
            storage.write(
                AI_EXPLANATIONS_FILE,
                json.encodeToString(
                    AiExplanationsFileDto.serializer(),
                    AiExplanationsFileDto(records = next.map { it.toDto() }),
                ),
            )
        }
    }

    override suspend fun usage(epochDay: Long): AiUsage = mutex.withLock {
        var total = AiUsage(epochDay = epochDay)
        readUsageLinesLocked().forEach { entry ->
            if (entry.epochDay == epochDay) total = total.plus(entry)
        }
        total
    }

    override suspend fun appendUsage(entry: AiUsageEntry) {
        mutex.withLock {
            storage.appendLine(
                AI_USAGE_FILE,
                json.encodeToString(
                    AiUsageEntryDto.serializer(),
                    AiUsageEntryDto(
                        day = entry.epochDay,
                        at = entry.atMillis,
                        key = entry.key,
                        model = entry.model,
                        ok = entry.ok,
                        promptTokens = entry.promptTokens,
                        completionTokens = entry.completionTokens,
                        costMicros = entry.costMicros,
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------ 读取

    private fun readConfigLocked(): AiConfig {
        val raw = storage.read(AI_CONFIG_FILE) ?: return AiConfig()
        if (raw.isBlank()) return AiConfig()
        val dto = runCatching { json.decodeFromString(AiConfigFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            warnings = listOf("AI 配置无法解析，已按未配置处理（原文件未被覆盖）")
            return AiConfig()
        }
        return dto.toDomain()
    }

    private fun readRecordsLocked(): List<AiExplanation> {
        val raw = storage.read(AI_EXPLANATIONS_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val dto = runCatching { json.decodeFromString(AiExplanationsFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            // 整份坏掉不覆盖：里面是花过钱生成的内容，重建不出来
            warnings = listOf("AI 讲解记录无法解析，已按空处理（原文件未被覆盖）")
            return emptyList()
        }
        var skipped = 0
        val records = dto.records.mapNotNull { record ->
            val state = AiRecordState.fromWire(record.state)
            if (record.key.isBlank() || record.questionId.isBlank() || state == null) {
                skipped++
                null
            } else {
                record.toDomain(state)
            }
        }.sortedByDescending { it.updatedAtMillis }
        if (skipped > 0) {
            warnings = listOf("有 $skipped 条 AI 讲解记录不完整，已跳过（原文件未修改）")
        }
        return records
    }

    private fun readUsageLinesLocked(): List<AiUsageEntry> {
        val raw = storage.read(AI_USAGE_FILE) ?: return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString(AiUsageEntryDto.serializer(), line) }.getOrNull()
                    ?.let { dto ->
                        AiUsageEntry(
                            epochDay = dto.day,
                            atMillis = dto.at,
                            key = dto.key,
                            model = dto.model,
                            ok = dto.ok,
                            promptTokens = dto.promptTokens,
                            completionTokens = dto.completionTokens,
                            costMicros = dto.costMicros,
                        )
                    }
            }
            .toList()
    }
}

private fun AiConfig.toDto(): AiConfigFileDto = AiConfigFileDto(
    baseUrl = baseUrl,
    model = model,
    maxOutputTokens = maxOutputTokens,
    temperature = temperature,
    timeoutMillis = timeoutMillis,
    maxCallsPerDay = budget.maxCallsPerDay,
    maxTokensPerDay = budget.maxTokensPerDay,
    maxCostMicrosPerDay = budget.maxCostMicrosPerDay,
    promptMicrosPerMillion = pricing.promptMicrosPerMillion,
    completionMicrosPerMillion = pricing.completionMicrosPerMillion,
    uploadConsent = uploadConsent,
    rememberKey = rememberKey,
)

private fun AiConfigFileDto.toDomain(): AiConfig = AiConfig(
    baseUrl = baseUrl,
    model = model,
    maxOutputTokens = maxOutputTokens,
    temperature = temperature,
    timeoutMillis = timeoutMillis,
    budget = AiBudget(
        maxCallsPerDay = maxCallsPerDay,
        maxTokensPerDay = maxTokensPerDay,
        maxCostMicrosPerDay = maxCostMicrosPerDay,
    ),
    pricing = AiPricing(
        promptMicrosPerMillion = promptMicrosPerMillion,
        completionMicrosPerMillion = completionMicrosPerMillion,
    ),
    uploadConsent = uploadConsent,
    rememberKey = rememberKey,
)

private fun AiExplanation.toDto(): AiExplanationDto = AiExplanationDto(
    key = key,
    bankId = bankId,
    questionId = questionId,
    locale = locale,
    questionRevision = questionRevision,
    model = model,
    state = state.wireName,
    text = text,
    error = error,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    costMicros = costMicros,
)

private fun AiExplanationDto.toDomain(state: AiRecordState): AiExplanation = AiExplanation(
    key = key,
    bankId = bankId,
    questionId = questionId,
    locale = locale,
    questionRevision = questionRevision,
    model = model,
    state = state,
    text = text,
    error = error,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = if (updatedAtMillis == 0L) createdAtMillis else updatedAtMillis,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    costMicros = costMicros,
)
