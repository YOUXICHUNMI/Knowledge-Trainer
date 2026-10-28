package morefish.knowledge.trainer.domain.graph

import morefish.knowledge.trainer.domain.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeGraphTest {

    private val a = QuestionRef("bank-a", "q1")
    private val b = QuestionRef("bank-b", "q1")

    private fun edge(
        source: QuestionRef = a,
        target: QuestionRef = b,
        relation: GraphRelation = GraphRelation.RELATED,
        dependency: DependencyKind = DependencyKind.NAVIGATION,
        note: String = "",
        id: String = "edge-1",
    ) = GraphEdge(
        edgeId = id,
        source = source,
        target = target,
        relation = relation,
        dependency = dependency,
        noteMarkdown = note,
        createdAtMillis = 100,
        updatedAtMillis = 100,
    )

    private fun snapshot(
        ref: QuestionRef = b,
        revision: Int = 1,
        title: String = "B 库",
        excerpt: String = "被引用的那道题",
    ) = GraphSnapshot(
        ref = ref,
        bankTitle = title,
        promptExcerpt = excerpt,
        contentRevision = revision,
        capturedAtMillis = 100,
    )

    private class FakeLookup(
        private val banks: Map<String, String> = emptyMap(),
        private val facts: Map<QuestionRef, QuestionFact> = emptyMap(),
    ) : GraphLookup {
        override fun bankTitle(bankId: String): String? = banks[bankId]

        override fun question(ref: QuestionRef): QuestionFact? = facts[ref]
    }

    // ---- 校验 ----

    @Test
    fun selfReferenceIsRejected() {
        val issues = edge(source = a, target = a).validate()
        assertTrue(issues.any { it.code == "graph-self-reference" && it.severity == Severity.ERROR })
    }

    @Test
    fun blankReferenceIsRejected() {
        val issues = edge(target = QuestionRef("", "q1")).validate()
        assertTrue(issues.any { it.code == "graph-blank-ref" })
    }

    @Test
    fun hardDependencyOnlyOnDirectionalRelations() {
        // 对称关系说不出谁依赖谁，硬依赖必须被拒绝
        val symmetric = edge(relation = GraphRelation.RELATED, dependency = DependencyKind.HARD).validate()
        assertTrue(symmetric.any { it.code == "graph-hard-needs-direction" && it.severity == Severity.ERROR })

        val contrasting = edge(relation = GraphRelation.CONTRASTS, dependency = DependencyKind.HARD).validate()
        assertTrue(contrasting.any { it.code == "graph-hard-needs-direction" })

        // 有方向的关系可以承载判题依赖
        assertTrue(
            edge(relation = GraphRelation.EXPLAINS, dependency = DependencyKind.HARD)
                .validate()
                .none { it.severity == Severity.ERROR },
        )
        assertTrue(
            edge(relation = GraphRelation.PREREQUISITE, dependency = DependencyKind.HARD)
                .validate()
                .none { it.severity == Severity.ERROR },
        )
    }

    @Test
    fun longNoteIsOnlyAWarning() {
        val issues = edge(note = "长".repeat(MAX_NOTE_LENGTH + 1)).validate()
        assertEquals(Severity.WARNING, issues.single().severity)
    }

    // ---- 状态解析 ----

    @Test
    fun missingBankKeepsSnapshotButMarksItAsSnapshotOnly() {
        val graph = KnowledgeGraph(snapshots = listOf(snapshot()), edges = listOf(edge()))
        val view = resolveGraph(graph, FakeLookup())

        val target = view.edges.single().target
        assertEquals(RefStatus.MISSING_BANK, target.status)
        assertFalse(target.status.usable)
        assertTrue(target.fromSnapshotOnly, "题库不在时只能拿快照认题")
        assertEquals("被引用的那道题", target.displayPrompt)
        assertEquals("B 库", target.displayBankTitle)
    }

    @Test
    fun questionRemovedFromInstalledBankIsReportedSeparately() {
        val graph = KnowledgeGraph(snapshots = listOf(snapshot()), edges = listOf(edge()))
        val view = resolveGraph(graph, FakeLookup(banks = mapOf("bank-b" to "B 库")))

        val target = view.edges.single().target
        assertEquals(RefStatus.MISSING_QUESTION, target.status)
        assertTrue(target.fromSnapshotOnly)
    }

    @Test
    fun reimportingCompatibleBankRestoresTheReferenceAutomatically() {
        val graph = KnowledgeGraph(snapshots = listOf(snapshot(revision = 3)), edges = listOf(edge()))

        // 迁移后题库不在本机
        assertEquals(RefStatus.MISSING_BANK, resolveGraph(graph, FakeLookup()).edges.single().target.status)

        // 重新导入同一 bankId、同题号、同内容版本 → 自动恢复，不需要手工确认
        val restored = resolveGraph(
            graph,
            FakeLookup(
                banks = mapOf("bank-b" to "B 库"),
                facts = mapOf(b to QuestionFact("B 库", "被引用的那道题", contentRevision = 3)),
            ),
        ).edges.single().target
        assertEquals(RefStatus.PRESENT, restored.status)
        assertTrue(restored.status.usable)
        assertFalse(restored.fromSnapshotOnly, "题库回来了就该显示当前内容，而不是快照")

        // 标题相同但 UUID 不同 → 仍然是"未安装"，不按标题猜
        val sameTitleOtherId = resolveGraph(
            graph,
            FakeLookup(
                banks = mapOf("bank-c" to "B 库"),
                facts = mapOf(QuestionRef("bank-c", "q1") to QuestionFact("B 库", "另一道题", 3)),
            ),
        ).edges.single().target
        assertEquals(RefStatus.MISSING_BANK, sameTitleOtherId.status)
    }

    @Test
    fun changedContentNeedsRevalidationUntilSomeoneLooks() {
        val graph = KnowledgeGraph(snapshots = listOf(snapshot(revision = 1)), edges = listOf(edge()))
        val lookup = FakeLookup(
            banks = mapOf("bank-b" to "B 库"),
            facts = mapOf(b to QuestionFact("B 库", "作者改过的内容", contentRevision = 2)),
        )

        val before = resolveGraph(graph, lookup).edges.single().target
        assertEquals(RefStatus.NEEDS_REVALIDATION, before.status)
        assertFalse(before.status.usable)
        assertFalse(before.fromSnapshotOnly, "内容在，只是版本变了；不该退回快照")

        // 人确认之后按当前版本重记快照 → 恢复正常
        val confirmed = graph.withSnapshots(listOf(snapshot(revision = 2, excerpt = "作者改过的内容")))
        val after = resolveGraph(confirmed, lookup).edges.single().target
        assertEquals(RefStatus.PRESENT, after.status)
    }

    // ---- 依赖与视图 ----

    @Test
    fun onlyHardEdgesPointingAtUnavailableTargetsAreBlocked() {
        val hard = edge(relation = GraphRelation.EXPLAINS, dependency = DependencyKind.HARD, id = "hard")
        val nav = edge(relation = GraphRelation.EXPLAINS, dependency = DependencyKind.NAVIGATION, id = "nav")
        val graph = KnowledgeGraph(edges = listOf(hard, nav))
        val view = resolveGraph(graph, FakeLookup())

        assertTrue(view.edges.first { it.edge.edgeId == "hard" }.blocked)
        assertFalse(view.edges.first { it.edge.edgeId == "nav" }.blocked, "导航关系缺失不该被说成判题暂停")
        assertEquals(1, view.blockedCount)
        assertEquals(1, view.blocksFor(a))
        assertEquals(0, view.blocksFor(b), "依赖方向是 source 依赖 target，反向不算")
    }

    @Test
    fun viewPutsProblemsFirstAndDeduplicatesRefs() {
        val healthy = QuestionRef("bank-a", "q2")
        val healthyTarget = QuestionRef("bank-a", "q3")
        val edges = listOf(
            edge(id = "ok", source = healthy, target = healthyTarget),
            edge(id = "broken", source = a, target = b),
            edge(id = "also-broken", source = a, target = b, relation = GraphRelation.CONTRASTS),
        )
        val graph = KnowledgeGraph(edges = edges)
        val view = resolveGraph(
            graph,
            FakeLookup(
                banks = mapOf("bank-a" to "A 库"),
                facts = mapOf(
                    healthy to QuestionFact("A 库", "好题", 1),
                    healthyTarget to QuestionFact("A 库", "好题的下一步", 1),
                ),
            ),
        )

        assertEquals("ok", view.edges.last().edge.edgeId, "没问题的排最后")
        assertEquals(2, view.edges.count { it.hasProblem })
        // b 出现在两条关系里，但"涉及题目"只算一次
        assertEquals(1, view.refs.count { it.ref == b })
        assertEquals(
            setOf(healthy, healthyTarget, a, b),
            view.refs.map { it.ref }.toSet(),
        )
    }

    @Test
    fun prunedDropsSnapshotsNoLongerReferenced() {
        val graph = KnowledgeGraph(
            snapshots = listOf(snapshot(ref = a), snapshot(ref = b)),
            edges = listOf(edge()),
        ).withoutEdge("edge-1").pruned()

        assertTrue(graph.edges.isEmpty())
        assertTrue(graph.snapshots.isEmpty(), "关系都删了，快照没有存在理由")
    }

    @Test
    fun snapshotTruncatesLongPromptsAndKeepsRevision() {
        val fact = QuestionFact("B 库", "长".repeat(SNAPSHOT_LENGTH + 50), contentRevision = 7)
        val snap = snapshotOf(b, fact, nowMillis = 42)

        assertTrue(snap.promptExcerpt.length <= SNAPSHOT_LENGTH + 1, "快照只留开头，末尾带省略号")
        assertTrue(snap.promptExcerpt.endsWith("…"))
        assertEquals(7, snap.contentRevision)
        assertNotNull(snap.ref)
    }
}
