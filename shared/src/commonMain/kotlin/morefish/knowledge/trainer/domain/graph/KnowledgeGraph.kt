package morefish.knowledge.trainer.domain.graph

import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.ValidationIssue

/**
 * 指向一道题的最小引用：`bankId` 是题库 UUID，`questionId` 在库内唯一。
 *
 * 图谱**只存引用**，不复制题目。题目正文属于作者，改一次就该跟着变；
 * 快照只用来在题库暂时缺席时说清"这条关系原本连的是什么"。
 */
data class QuestionRef(val bankId: String, val questionId: String) {

    val key: String get() = "$bankId/$questionId"

    fun isComplete(): Boolean = bankId.isNotBlank() && questionId.isNotBlank()

    override fun toString(): String = key
}

/**
 * 两种端点之间的语义。
 *
 * 方向统一读作「source ——关系—— target」：
 * - [RELATED] / [CONTRASTS] 是对称关系，存一条就够；
 * - [PREREQUISITE] 表示 source 的前置是 target（先学右边，再看左边）；
 * - [EXPLAINS] 表示 target 是 source 的判题依据（跨库资料）。
 *
 * [allowsHardDependency] 只在有方向的"依赖型"关系上为真 ——
 * 对称关系说清"谁依赖谁"都做不到，自然不能承载判题依赖。
 */
enum class GraphRelation(
    val wireName: String,
    val label: String,
    val glyph: String,
    val hint: String,
    val directional: Boolean,
    val allowsHardDependency: Boolean,
) {
    RELATED(
        wireName = "related",
        label = "相关",
        glyph = "↔",
        hint = "两题讲同一类对象，互相补充",
        directional = false,
        allowsHardDependency = false,
    ),
    PREREQUISITE(
        wireName = "prerequisite",
        label = "前置",
        glyph = "←",
        hint = "左边这道题的前置是右边，先学右边",
        directional = true,
        allowsHardDependency = true,
    ),
    EXPLAINS(
        wireName = "explains",
        label = "依据",
        glyph = "⇒",
        hint = "右边是左边的判题依据（例如另一库里的公式表）",
        directional = true,
        allowsHardDependency = true,
    ),
    CONTRASTS(
        wireName = "contrasts",
        label = "对比",
        glyph = "⇄",
        hint = "两题容易混淆，放在一起对照记忆",
        directional = false,
        allowsHardDependency = false,
    ),
    ;

    companion object {
        fun fromWire(value: String?): GraphRelation? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * 一条关系对做题的实际影响。
 *
 * [NAVIGATION] 只是导航：缺了顶多少一条线索；
 * [HARD] 是判题硬依赖：目标不在时，**不能拿它当判题依据**，只能标注暂停。
 */
enum class DependencyKind(val wireName: String, val label: String, val hint: String) {
    NAVIGATION("navigation", "导航", "只是帮你看得懂，缺了不影响做题"),
    HARD("hard", "判题硬依赖", "目标缺失时这条依据不能用，会明确标注暂停"),
    ;

    companion object {
        fun fromWire(value: String?): DependencyKind =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: NAVIGATION
    }
}

/** 建立关系时对端点的展示快照。只在对方缺席时使用，且必须标明"这是快照"。 */
data class GraphSnapshot(
    val ref: QuestionRef,
    val bankTitle: String,
    val promptExcerpt: String,
    val contentRevision: Int,
    val capturedAtMillis: Long,
)

data class GraphEdge(
    val edgeId: String,
    val source: QuestionRef,
    val target: QuestionRef,
    val relation: GraphRelation,
    val dependency: DependencyKind = DependencyKind.NAVIGATION,
    val noteMarkdown: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** 关系自检。ERROR 拒绝写入，WARNING 允许保存但要让人看见。 */
fun GraphEdge.validate(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()

    if (edgeId.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "graph-edge-id", "关系缺少 edgeId")
    }
    if (!source.isComplete() || !target.isComplete()) {
        issues += ValidationIssue(Severity.ERROR, "graph-blank-ref", "关系两端都必须是完整引用（题库 + 题目）")
    }
    if (source == target) {
        issues += ValidationIssue(Severity.ERROR, "graph-self-reference", "一道题不能和自己建立关系")
    }
    if (dependency == DependencyKind.HARD && !relation.allowsHardDependency) {
        issues += ValidationIssue(
            Severity.ERROR,
            "graph-hard-needs-direction",
            "「${relation.label}」是对称关系，说不清谁依赖谁，不能作为判题硬依赖",
        )
    }
    if (noteMarkdown.length > MAX_NOTE_LENGTH) {
        issues += ValidationIssue(
            Severity.WARNING,
            "graph-note-long",
            "备注超过 $MAX_NOTE_LENGTH 字，列表里只会显示开头",
        )
    }
    return issues
}

/**
 * 个人知识图谱。
 *
 * 它**独立于原始题库**：题库可以换、可以暂时不装，关系与快照仍在这里。
 * 但图谱也不冒充题库 —— 快照永远只是快照，缺题库时界面必须这么说。
 */
data class KnowledgeGraph(
    val snapshots: List<GraphSnapshot> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
) {
    fun snapshotOf(ref: QuestionRef): GraphSnapshot? = snapshots.firstOrNull { it.ref == ref }

    fun edge(edgeId: String): GraphEdge? = edges.firstOrNull { it.edgeId == edgeId }

    fun edgesFor(ref: QuestionRef): List<GraphEdge> =
        edges.filter { it.source == ref || it.target == ref }

    /** 所有被引用的题（去重，顺序稳定）。 */
    fun refs(): List<QuestionRef> = edges.flatMap { listOf(it.source, it.target) }.distinct()

    fun withEdge(edge: GraphEdge): KnowledgeGraph =
        copy(edges = (edges.filterNot { it.edgeId == edge.edgeId } + edge).sortedBy { it.createdAtMillis })

    fun withoutEdge(edgeId: String): KnowledgeGraph = copy(edges = edges.filterNot { it.edgeId == edgeId })

    fun withSnapshots(fresh: List<GraphSnapshot>): KnowledgeGraph {
        if (fresh.isEmpty()) return this
        return copy(snapshots = snapshots.filterNot { old -> fresh.any { it.ref == old.ref } } + fresh)
    }

    /** 没有关系引用的快照没有存在理由，删关系时顺手清掉。 */
    fun pruned(): KnowledgeGraph {
        val live = refs().toSet()
        return copy(snapshots = snapshots.filter { it.ref in live })
    }

    companion object {
        val Empty = KnowledgeGraph()
    }
}

/** 一道题此刻的真实情况。由仓储按需定位得到，不从快照里推断。 */
data class QuestionFact(
    val bankTitle: String,
    val promptMarkdown: String,
    val contentRevision: Int,
)

/**
 * 解析引用时唯一允许的事实来源。
 *
 * 刻意做成接口：解析必须是纯函数，测试可以给一个"题库已卸载"的假实现，
 * 而不需要真的删文件。
 */
interface GraphLookup {
    fun bankTitle(bankId: String): String?

    /** 题库在、题目也在时返回事实；任一缺席返回 null。 */
    fun question(ref: QuestionRef): QuestionFact?
}

/**
 * 引用当前的状态。
 *
 * [NEEDS_REVALIDATION] 不是错误的同义词：作者更新了题目（内容版本变了），
 * 原来那条"这是依据"的判断需要人再看一眼，不能自动当作仍然成立。
 */
enum class RefStatus(val label: String, val usable: Boolean) {
    PRESENT("正常", true),
    MISSING_BANK("题库未安装", false),
    MISSING_QUESTION("题目已不存在", false),
    NEEDS_REVALIDATION("内容已变化", false),
}

/**
 * 一个引用的解析结果。
 *
 * [livePrompt] 为空而 [snapshot] 有内容时，界面只能显示快照，
 * 并且必须通过 [fromSnapshotOnly] 说明这一点。
 */
data class RefResolution(
    val ref: QuestionRef,
    val status: RefStatus,
    val snapshot: GraphSnapshot?,
    val livePrompt: String? = null,
    val liveRevision: Int? = null,
    val liveBankTitle: String? = null,
) {
    val displayPrompt: String get() = livePrompt ?: snapshot?.promptExcerpt ?: "（没有可显示的内容）"

    val displayBankTitle: String
        get() = liveBankTitle ?: snapshot?.bankTitle
            ?: if (status == RefStatus.MISSING_BANK) "（题库未安装，未留快照）" else "（题库未知）"

    val fromSnapshotOnly: Boolean get() = livePrompt == null && snapshot != null

    val hasProblem: Boolean get() = status != RefStatus.PRESENT
}

data class GraphEdgeView(
    val edge: GraphEdge,
    val source: RefResolution,
    val target: RefResolution,
) {
    /**
     * 硬依赖且目标不可用 → 这条依据不能用。
     *
     * 注意"不能用"的实现方式：调用方会拿到 false 并据此标注暂停，
     * 而不是退回快照继续判题 —— 快照不是权威判题依据。
     */
    val blocked: Boolean
        get() = edge.dependency == DependencyKind.HARD && !target.status.usable

    val hasProblem: Boolean get() = source.hasProblem || target.hasProblem
}

/** 图谱的一帧视图：所有状态在这里算好，界面只负责画。 */
data class GraphView(val edges: List<GraphEdgeView> = emptyList()) {

    val relationCount: Int get() = edges.size

    val refs: List<RefResolution>
        get() = edges.flatMap { listOf(it.source, it.target) }.distinctBy { it.ref }

    val problemRefCount: Int get() = refs.count { it.hasProblem }

    val blockedCount: Int get() = edges.count { it.blocked }

    fun relationCount(relation: GraphRelation): Int = edges.count { it.edge.relation == relation }

    fun blocksFor(ref: QuestionRef): Int = edges.count { it.edge.source == ref && it.blocked }

    companion object {
        val Empty = GraphView()
    }
}

/** 快照长度上限。够看清是哪道题，又不至于把整道大题抄进索引文件。 */
const val SNAPSHOT_LENGTH = 240

const val MAX_NOTE_LENGTH = 2000

/**
 * 解析一个引用。
 *
 * 判定顺序是刻意的：先问题库在不在，再问题目在不在，最后才比内容版本。
 * 标题从不参与匹配 —— 同名但不同 UUID 的库必须被当成另一个库。
 */
fun resolveRef(ref: QuestionRef, snapshot: GraphSnapshot?, lookup: GraphLookup): RefResolution {
    val bankTitle = lookup.bankTitle(ref.bankId)
    val fact = lookup.question(ref)

    if (fact == null) {
        val status = if (bankTitle == null) RefStatus.MISSING_BANK else RefStatus.MISSING_QUESTION
        return RefResolution(
            ref = ref,
            status = status,
            snapshot = snapshot,
            liveBankTitle = bankTitle,
        )
    }

    val status = when {
        snapshot == null -> RefStatus.PRESENT
        snapshot.contentRevision == fact.contentRevision -> RefStatus.PRESENT
        else -> RefStatus.NEEDS_REVALIDATION
    }

    return RefResolution(
        ref = ref,
        status = status,
        snapshot = snapshot,
        livePrompt = fact.promptMarkdown,
        liveRevision = fact.contentRevision,
        liveBankTitle = fact.bankTitle,
    )
}

/**
 * 把整张图谱解析成一帧视图。
 *
 * 排序规则：有问题的排前面（这是要处理的），同组内按最近修改时间倒序。
 * 图谱列表的用途是"看看哪里断线了"，不是按创建时间怀旧。
 */
fun resolveGraph(graph: KnowledgeGraph, lookup: GraphLookup): GraphView {
    if (graph.edges.isEmpty()) return GraphView.Empty

    val cache = mutableMapOf<QuestionRef, RefResolution>()
    fun resolutionOf(ref: QuestionRef): RefResolution =
        cache.getOrPut(ref) { resolveRef(ref, graph.snapshotOf(ref), lookup) }

    val views = graph.edges.map { edge ->
        GraphEdgeView(edge = edge, source = resolutionOf(edge.source), target = resolutionOf(edge.target))
    }

    val ordered = views.sortedWith(
        compareBy<GraphEdgeView> { if (it.hasProblem) 0 else 1 }
            .thenByDescending { it.edge.updatedAtMillis }
            .thenBy { it.edge.edgeId },
    )
    return GraphView(ordered)
}

/** 建立关系时记录快照：只截取开头，够认出这道题就行。 */
fun snapshotOf(ref: QuestionRef, fact: QuestionFact, nowMillis: Long): GraphSnapshot {
    val excerpt = fact.promptMarkdown.trim().let { text ->
        if (text.length <= SNAPSHOT_LENGTH) {
            text
        } else {
            var cut = text.take(SNAPSHOT_LENGTH)
            // 截断点落在行内公式中间时，退到公式开头：留下半个 $...$ 只会显示成一串源码
            if (cut.count { it == '$' } % 2 != 0) cut = cut.substringBeforeLast('$')
            cut.trimEnd() + "…"
        }
    }
    return GraphSnapshot(
        ref = ref,
        bankTitle = fact.bankTitle,
        promptExcerpt = excerpt,
        contentRevision = fact.contentRevision,
        capturedAtMillis = nowMillis,
    )
}
