package morefish.knowledge.trainer.domain.extension

import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扩展包的领域规则。
 *
 * 这些用例只管"合并与校验算得对不对"，不碰 ZIP、不碰磁盘 ——
 * 打包/读包在 QbextImportTest，安装流程在 ExtensionFlowTest。
 */
class ExtensionsTest {

    private val bankA = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
    private val bankB = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

    private fun pack(
        packageId: String = "physiology",
        bankId: String = bankA,
        relations: List<ExtensionRelation> = emptyList(),
        annotations: List<ExtensionAnnotation> = emptyList(),
        importedAt: Long = 1_000,
    ) = ExtensionPack(
        packageId = packageId,
        name = "示例",
        version = "1.0.0",
        author = "test",
        binding = BankBinding(bankId, "生理学"),
        relations = relations,
        annotations = annotations,
        importedAtMillis = importedAt,
    )

    private fun relation(
        id: String = "r1",
        sourceQ: String = "q1",
        targetBank: String = bankB,
        targetQ: String = "t1",
        relation: GraphRelation = GraphRelation.RELATED,
        dependency: DependencyKind = DependencyKind.NAVIGATION,
    ) = ExtensionRelation(
        relationId = id,
        source = QuestionRef(bankA, sourceQ),
        target = QuestionRef(targetBank, targetQ),
        relation = relation,
        dependency = dependency,
    )

    @Test
    fun stateFollowsWhetherTheBoundBankIsInstalled() {
        val p = pack()
        assertEquals(ExtensionState.PENDING, p.stateIn(emptySet()))
        assertEquals(ExtensionState.ACTIVE, p.stateIn(setOf(bankA)))
        // 别的题库装了不算：只认 UUID
        assertEquals(ExtensionState.PENDING, p.stateIn(setOf(bankB)))
    }

    @Test
    fun mergeAddsExtensionEdgesWithoutTouchingUserEdges() {
        val userEdge = GraphEdge(
            edgeId = "edge-1",
            source = QuestionRef(bankA, "q1"),
            target = QuestionRef(bankA, "q2"),
            relation = GraphRelation.RELATED,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        val user = KnowledgeGraph(edges = listOf(userEdge))

        val merged = user.mergedWith(listOf(pack(relations = listOf(relation()))))

        assertEquals(1, user.edges.size, "合并不能改原图谱")
        assertEquals(2, merged.edges.size)
        assertEquals(1, merged.edges.count { it.edgeId.startsWith("ext:") })
    }

    @Test
    fun mergedEdgesAreStableWhenMergedTwice() {
        val p = pack(relations = listOf(relation()))
        val once = KnowledgeGraph.Empty.mergedWith(listOf(p))
        val twice = once.mergedWith(listOf(p))
        assertEquals(once.edges.size, twice.edges.size, "同一个包合并两次不该出现两条")
    }

    @Test
    fun mergingNoPacksIsIdentity() {
        val graph = KnowledgeGraph.Empty
        assertTrue(graph.mergedWith(emptyList()) === graph)
    }

    @Test
    fun edgeIdRoundTripsToItsPackage() {
        val id = extensionEdgeId("physiology", "r1")
        assertEquals("physiology", extensionOwnerOf(id))
        assertNull(extensionOwnerOf("edge-ab12cd34"), "手建关系不属于任何扩展包")
        assertNull(extensionOwnerOf("ext:"))
    }

    @Test
    fun extensionEdgeCarriesPackageTimeAndNote() {
        val p = pack(
            relations = listOf(relation(id = "r1").copy(noteMarkdown = "先看右边")),
            importedAt = 42,
        )
        val edge = p.relations.single().toGraphEdge(p)
        assertEquals(42, edge.createdAtMillis)
        assertEquals("先看右边", edge.noteMarkdown)
        assertEquals(DependencyKind.NAVIGATION, edge.dependency)
    }

    @Test
    fun relationSourceMustBelongToTheBoundBank() {
        val foreign = relation().copy(source = QuestionRef(bankB, "q1"))
        val issues = foreign.validate(bankA)
        assertTrue(
            issues.any { it.code == "ext-relation-foreign-source" && it.severity == Severity.ERROR },
            "扩展包不能给别人的题库加关系",
        )
    }

    @Test
    fun hardDependencyRejectedOnSymmetricRelation() {
        val symmetric = relation(relation = GraphRelation.RELATED, dependency = DependencyKind.HARD)
        val issues = symmetric.validate(bankA)
        assertTrue(issues.any { it.code == "ext-relation-hard-needs-direction" })
        // 有方向的依赖关系才允许
        val directional = relation(relation = GraphRelation.PREREQUISITE, dependency = DependencyKind.HARD)
        assertTrue(directional.validate(bankA).none { it.severity == Severity.ERROR })
    }

    @Test
    fun selfReferenceIsRejected() {
        val self = relation(targetBank = bankA, targetQ = "q1")
        assertTrue(self.validate(bankA).any { it.code == "ext-relation-self" })
    }

    @Test
    fun emptyPackIsRejected() {
        val issues = pack().validate()
        assertTrue(issues.any { it.code == "ext-empty" && it.severity == Severity.ERROR })
    }

    @Test
    fun annotationRequiresBody() {
        val empty = ExtensionAnnotation(annotationId = "a1", questionId = null, title = "标题", bodyMarkdown = "  ")
        assertTrue(empty.validate().any { it.code == "ext-annotation-empty" })
        val ok = ExtensionAnnotation(annotationId = "a1", questionId = "q1", title = "标题", bodyMarkdown = "正文")
        assertTrue(ok.validate().none { it.severity == Severity.ERROR })
    }

    @Test
    fun packageValidationAggregatesInnerProblems() {
        val issues = pack(
            relations = listOf(relation(relation = GraphRelation.CONTRASTS, dependency = DependencyKind.HARD)),
        ).validate()
        assertTrue(issues.any { it.code == "ext-relation-hard-needs-direction" })
    }

    @Test
    fun versionsCompareNumericallyNotLexically() {
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
        // 看不懂的版本号一律当作更旧：宁可拒绝覆盖
        assertTrue(compareVersions("beta", "1.0.0") < 0)
        assertNull(semanticVersionOf("1.0"))
        assertNull(semanticVersionOf("1.0.0-rc1"))
    }
}
