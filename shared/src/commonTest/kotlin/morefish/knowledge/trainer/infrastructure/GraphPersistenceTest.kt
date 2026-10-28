package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphSnapshot
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.infrastructure.storage.GRAPH_FILE
import morefish.knowledge.trainer.infrastructure.storage.JsonGraphRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphPersistenceTest {

    private val a = QuestionRef("bank-a", "q1")
    private val b = QuestionRef("bank-b", "q7")

    private fun edge(
        dependency: DependencyKind = DependencyKind.NAVIGATION,
        relation: GraphRelation = GraphRelation.EXPLAINS,
        note: String = "右边那张公式表就是判题依据",
    ) = GraphEdge(
        edgeId = "edge-1",
        source = a,
        target = b,
        relation = relation,
        dependency = dependency,
        noteMarkdown = note,
        createdAtMillis = 1_700_000_000_000,
        updatedAtMillis = 1_700_000_100_000,
    )

    private fun snapshot(ref: QuestionRef = b) = GraphSnapshot(
        ref = ref,
        bankTitle = "另一库",
        promptExcerpt = "被引用的题干摘要",
        contentRevision = 4,
        capturedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun roundTripKeepsRelationDependencyAndSnapshots() = runTest {
        val storage = InMemoryTextStorage()
        JsonGraphRepository(storage).save(
            KnowledgeGraph(
                snapshots = listOf(snapshot(), snapshot(a)),
                edges = listOf(edge(dependency = DependencyKind.HARD)),
            ),
        )

        val loaded = JsonGraphRepository(storage).load()
        val only = assertNotNull(loaded.edges.singleOrNull())
        assertEquals(a, only.source)
        assertEquals(b, only.target)
        assertEquals(GraphRelation.EXPLAINS, only.relation)
        assertEquals(DependencyKind.HARD, only.dependency, "硬依赖必须原样落盘，不能退化成导航")
        assertEquals("右边那张公式表就是判题依据", only.noteMarkdown)
        assertEquals(1_700_000_000_000, only.createdAtMillis)

        val snap = assertNotNull(loaded.snapshotOf(b))
        assertEquals("另一库", snap.bankTitle)
        assertEquals(4, snap.contentRevision)
        assertNotNull(loaded.snapshotOf(a))
    }

    @Test
    fun oneBrokenEdgeIsSkippedAndCounted() = runTest {
        val storage = InMemoryTextStorage()
        // 第二条：对称关系挂了硬依赖 —— 结构上说不通，读回来必须丢掉
        storage.write(
            GRAPH_FILE,
            """
            {"schemaVersion":1,"edges":[
              {"edgeId":"good","sourceBankId":"a","sourceQuestionId":"q1",
               "targetBankId":"b","targetQuestionId":"q7","relation":"related"},
              {"edgeId":"bad","sourceBankId":"a","sourceQuestionId":"q1",
               "targetBankId":"b","targetQuestionId":"q7","relation":"contrasts","dependency":"hard"},
              {"edgeId":"unknown-relation","sourceBankId":"a","sourceQuestionId":"q1",
               "targetBankId":"b","targetQuestionId":"q7","relation":"invented"}
            ]}
            """.trimIndent(),
        )

        val repository = JsonGraphRepository(storage)
        val loaded = repository.load()
        assertEquals(listOf("good"), loaded.edges.map { it.edgeId })
        assertTrue(loaded.edges.isNotEmpty(), "坏行不该把整张图弄空")
        assertTrue(
            repository.loadWarnings.any { "2 条" in it },
            "跳过的条数要如实说出来：${repository.loadWarnings}",
        )
    }

    @Test
    fun corruptedFileOpensEmptyWithoutOverwriting() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(GRAPH_FILE, "{ 这不是 JSON")

        val loaded = JsonGraphRepository(storage).load()
        assertTrue(loaded.edges.isEmpty())
        assertEquals("{ 这不是 JSON", storage.read(GRAPH_FILE), "读不出来也不能覆盖 —— 那可能是用户唯一的图")
    }

    @Test
    fun missingFileIsAnEmptyGraphNotAWarning() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonGraphRepository(storage)

        assertTrue(repository.load().edges.isEmpty())
        assertTrue(repository.loadWarnings.isEmpty(), "第一次使用没有图谱，不是故障")
    }

    @Test
    fun savingPrunesSnapshotsWithoutLiveEdges() = runTest {
        val storage = InMemoryTextStorage()
        JsonGraphRepository(storage).save(
            KnowledgeGraph(snapshots = listOf(snapshot()), edges = emptyList()),
        )

        val loaded = JsonGraphRepository(storage).load()
        assertTrue(loaded.snapshots.isEmpty(), "没有关系引用的快照不该留在盘上")
        assertFalse(storage.read(GRAPH_FILE).isNullOrBlank())
    }
}
