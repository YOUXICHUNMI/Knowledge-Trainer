package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.ai.AiBudget
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.ai.AiUsageEntry
import morefish.knowledge.trainer.infrastructure.storage.JsonAiRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 数据的落盘行为。
 *
 * 重点不是"能不能存下来"，而是几件容易做错的事：
 * 密钥不能混进配置文件、账本不能被重写、整份损坏不能被覆盖。
 */
class AiPersistenceTest {

    private val configFile = "ai-config-v1.json"
    private val credentialFile = "ai-credential-v1.json"
    private val recordsFile = "ai-explanations-v1.json"
    private val usageFile = "ai-usage-v1.ndjson"

    private fun record(key: String, state: AiRecordState = AiRecordState.SCHEMA_VALIDATED, text: String = "讲解") =
        AiExplanation(
            key = key,
            bankId = "bank-1",
            questionId = "q-$key",
            locale = "zh-Hans",
            questionRevision = 2,
            model = "test-model",
            state = state,
            text = text,
            createdAtMillis = 100,
            updatedAtMillis = 100,
            promptTokens = 100,
            completionTokens = 50,
            costMicros = 400,
        )

    @Test
    fun configRoundTripsWithoutLosingBudgetOrConsent() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiRepository(storage)
        val config = AiConfig(
            baseUrl = "https://api.example.com/v1",
            model = "test-model",
            maxOutputTokens = 900,
            budget = AiBudget(maxCallsPerDay = 7, maxTokensPerDay = 1234, maxCostMicrosPerDay = 250_000),
            uploadConsent = true,
            rememberKey = false,
        )
        repo.saveConfig(config)

        val loaded = JsonAiRepository(storage).config()
        assertEquals("https://api.example.com/v1", loaded.baseUrl)
        assertEquals("test-model", loaded.model)
        assertEquals(900, loaded.maxOutputTokens)
        assertEquals(7, loaded.budget.maxCallsPerDay)
        assertEquals(1234, loaded.budget.maxTokensPerDay)
        assertEquals(250_000, loaded.budget.maxCostMicrosPerDay)
        assertTrue(loaded.uploadConsent)
        assertFalse(loaded.rememberKey)
    }

    @Test
    fun credentialNeverLeaksIntoTheConfigFile() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiRepository(storage)
        repo.saveConfig(AiConfig(baseUrl = "https://api.example.com/v1", model = "m"))
        repo.saveCredential("sk-very-secret-key")

        val configText = assertNotNull(storage.read(configFile))
        assertFalse(configText.contains("sk-very-secret-key"), "配置文件是要能贴给别人看的，不能带密钥")
        assertTrue(assertNotNull(storage.read(credentialFile)).contains("sk-very-secret-key"))
        assertEquals("sk-very-secret-key", JsonAiRepository(storage).credential())
    }

    @Test
    fun clearingCredentialMakesItAbsent() = runTest {
        val repo = JsonAiRepository(InMemoryTextStorage())
        repo.saveCredential("sk-abc")
        assertNotNull(repo.credential())
        repo.saveCredential("")
        assertNull(repo.credential())
    }

    @Test
    fun savingSameKeyReplacesInsteadOfAppending() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiRepository(storage)
        repo.save(record("k1", text = "第一版"))
        repo.save(record("k1").copy(updatedAtMillis = 200, text = "第二版", state = AiRecordState.APPROVED))

        val records = JsonAiRepository(storage).records()
        assertEquals(1, records.size)
        assertEquals("第二版", records.single().text)
        assertEquals(AiRecordState.APPROVED, records.single().state)
    }

    @Test
    fun recordsComeBackNewestFirst() = runTest {
        val repo = JsonAiRepository(InMemoryTextStorage())
        repo.save(record("old").copy(updatedAtMillis = 100))
        repo.save(record("new").copy(updatedAtMillis = 900))
        assertEquals(listOf("new", "old"), repo.records().map { it.key })
    }

    @Test
    fun oneBrokenRecordDoesNotDestroyTheOthers() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(
            recordsFile,
            """{"schemaVersion":1,"records":[
              {"key":"good","bankId":"b","questionId":"q","state":"schema_validated","text":"ok"},
              {"key":"bad","bankId":"b","questionId":"q","state":"not-a-state"}
            ]}""".trimIndent(),
        )
        val repo = JsonAiRepository(storage)
        val records = repo.records()
        assertEquals(1, records.size)
        assertEquals("good", records.single().key)
        assertTrue(repo.loadWarnings.single().contains("1 条"))
    }

    @Test
    fun totallyBrokenFileIsNotOverwritten() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(recordsFile, "{ 这不是 JSON")
        val repo = JsonAiRepository(storage)
        assertEquals(emptyList(), repo.records())
        assertTrue(repo.loadWarnings.isNotEmpty())
        assertEquals("{ 这不是 JSON", storage.read(recordsFile), "坏掉的记录是花过钱的内容，不能一读就抹掉")
    }

    @Test
    fun usageLedgerSumsOnlyTheRequestedDay() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiRepository(storage)
        repo.appendUsage(AiUsageEntry(epochDay = 10, atMillis = 1, key = "a", model = "m", ok = true, promptTokens = 10, completionTokens = 5, costMicros = 100))
        repo.appendUsage(AiUsageEntry(epochDay = 11, atMillis = 2, key = "b", model = "m", ok = true, promptTokens = 20, completionTokens = 7, costMicros = 200))
        repo.appendUsage(AiUsageEntry(epochDay = 10, atMillis = 3, key = "c", model = "m", ok = false))

        val day10 = JsonAiRepository(storage).usage(10)
        assertEquals(2, day10.calls)
        assertEquals(1, day10.failedCalls)
        assertEquals(15, day10.tokens)
        assertEquals(100, day10.costMicros)

        val day11 = JsonAiRepository(storage).usage(11)
        assertEquals(1, day11.calls)
        assertEquals(27, day11.tokens)
    }

    @Test
    fun appendingUsageNeverRewritesEarlierLines() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonAiRepository(storage)
        repo.appendUsage(AiUsageEntry(epochDay = 10, atMillis = 1, key = "a", model = "m", ok = true))
        val firstLine = assertNotNull(storage.read(usageFile))
        repo.appendUsage(AiUsageEntry(epochDay = 10, atMillis = 2, key = "b", model = "m", ok = true))
        val both = assertNotNull(storage.read(usageFile))
        assertTrue(both.startsWith(firstLine), "账本只能追加：重写一次就可能把上一笔抹掉")
        assertEquals(2, both.trim().lines().size)
    }

    @Test
    fun freshInstallHasNoWarnings() = runTest {
        val repo = JsonAiRepository(InMemoryTextStorage())
        repo.config()
        repo.records()
        repo.usage(1)
        assertEquals(emptyList(), repo.loadWarnings)
        assertNull(repo.credential())
    }
}
