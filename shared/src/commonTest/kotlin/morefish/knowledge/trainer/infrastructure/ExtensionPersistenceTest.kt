package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.extension.BankBinding
import morefish.knowledge.trainer.domain.extension.ExtensionAnnotation
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionRelation
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.infrastructure.storage.EXTENSIONS_FILE
import morefish.knowledge.trainer.infrastructure.storage.JsonExtensionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扩展包存储。
 *
 * 关键不变量：移除一个包之后，文件里不能残留它的关系；
 * 单个包读坏只丢那一个，整份坏掉则按未安装处理且**不覆盖原文件**。
 */
class ExtensionPersistenceTest {

    private val bankA = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
    private val bankB = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

    private fun pack(
        packageId: String = "physiology-crossref",
        name: String = "生理学补丁",
        version: String = "1.0.0",
        importedAt: Long = 1_000,
    ) = ExtensionPack(
        packageId = packageId,
        name = name,
        version = version,
        author = "测试",
        license = "CC-BY-SA-4.0",
        binding = BankBinding(bankA, "生理学"),
        relations = listOf(
            ExtensionRelation(
                relationId = "r1",
                source = QuestionRef(bankA, "q1"),
                target = QuestionRef(bankB, "t1"),
                relation = GraphRelation.PREREQUISITE,
                dependency = DependencyKind.HARD,
                noteMarkdown = "先看右边",
            ),
        ),
        annotations = listOf(
            ExtensionAnnotation("a1", "q1", "一处易错点", "心率与每搏量并不独立。"),
            ExtensionAnnotation("a2", null, "整库说明", "这份补丁只补关系。"),
        ),
        importedAtMillis = importedAt,
        sourceLabel = "qbext:test.qbext",
    )

    @Test
    fun roundTripsRelationsAnnotationsAndBinding() = runTest {
        val storage = InMemoryTextStorage()
        JsonExtensionRepository(storage).apply { save(pack()) }

        val loaded = JsonExtensionRepository(storage).all().single()
        assertEquals("physiology-crossref", loaded.packageId)
        assertEquals(bankA, loaded.binding.bankId)
        assertEquals("生理学", loaded.binding.bankTitleHint)
        assertEquals("CC-BY-SA-4.0", loaded.license)
        assertEquals(1, loaded.relations.size)
        assertEquals(DependencyKind.HARD, loaded.relations.single().dependency)
        assertEquals(GraphRelation.PREREQUISITE, loaded.relations.single().relation)
        // 起点永远是绑定的题库：文件里只存 questionId
        assertEquals(bankA, loaded.relations.single().source.bankId)
        assertEquals(2, loaded.annotations.size)
        assertNull(loaded.annotations.first { it.annotationId == "a2" }.questionId, "整库注释没有题号")
    }

    @Test
    fun saveWithSamePackageIdReplacesInsteadOfAccumulating() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonExtensionRepository(storage)
        repo.save(pack(version = "1.0.0"))
        repo.save(pack(version = "1.1.0"))

        val loaded = repo.all()
        assertEquals(1, loaded.size, "同 packageId 不能并存两份")
        assertEquals("1.1.0", loaded.single().version)
    }

    @Test
    fun removeDeletesOnlyThatPack() = runTest {
        val storage = InMemoryTextStorage()
        val repo = JsonExtensionRepository(storage)
        repo.save(pack(packageId = "a", importedAt = 1))
        repo.save(pack(packageId = "b", importedAt = 2))

        repo.remove("a")

        assertEquals(listOf("b"), repo.all().map { it.packageId })
        // 关系随之消失：文件里不能再出现 a 的任何痕迹
        assertTrue(storage.read(EXTENSIONS_FILE)?.contains("\"a\"") != true)
    }

    @Test
    fun oneBrokenPackIsSkippedWithoutLosingTheOthers() = runTest {
        val storage = InMemoryTextStorage()
        // 一半好一半坏：坏的那条缺 bankId（绑定丢了就不知道该给谁加关系）
        val good = """{"packageId":"good","name":"好包","version":"1.0.0","bankId":"$bankA",""" +
            """"relations":[{"relationId":"r1","sourceQuestionId":"q1","targetBankId":"$bankB",""" +
            """"targetQuestionId":"t1","relation":"related"}]}"""
        val broken = """{"packageId":"broken","name":"坏包","version":"1.0.0","bankId":""}"""
        storage.write(EXTENSIONS_FILE, """{"schemaVersion":1,"packs":[$good,$broken]}""")

        val repo = JsonExtensionRepository(storage)
        assertEquals(listOf("good"), repo.all().map { it.packageId })
        assertTrue(repo.loadWarnings.single().contains("跳过"))
    }

    @Test
    fun corruptFileIsNotOverwritten() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(EXTENSIONS_FILE, "{not json")
        val repo = JsonExtensionRepository(storage)

        assertTrue(repo.all().isEmpty())
        assertTrue(repo.loadWarnings.single().contains("无法解析"))
        assertEquals("{not json", storage.read(EXTENSIONS_FILE), "坏文件必须原样保留，等用户自己处理")
    }

    @Test
    fun missingFileIsNotAWarning() = runTest {
        val repo = JsonExtensionRepository(InMemoryTextStorage())
        assertTrue(repo.all().isEmpty())
        assertTrue(repo.loadWarnings.isEmpty(), "从未装过扩展包不是故障")
    }
}
