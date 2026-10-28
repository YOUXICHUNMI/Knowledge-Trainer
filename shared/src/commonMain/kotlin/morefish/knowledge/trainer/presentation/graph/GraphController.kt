package morefish.knowledge.trainer.presentation.graph

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.GraphRepository
import morefish.knowledge.trainer.application.port.QuestionLocation
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.mergedWith
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphEdgeView
import morefish.knowledge.trainer.domain.graph.GraphLookup
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphView
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionFact
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.graph.resolveGraph
import morefish.knowledge.trainer.domain.graph.snapshotOf
import morefish.knowledge.trainer.domain.graph.validate
import morefish.knowledge.trainer.domain.model.Ids
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.ValidationIssue

/** 编辑器里正在选哪一端。 */
enum class GraphPickSide { NONE, SOURCE, TARGET }

/** 编辑器里展示一端题目所需的最小信息。 */
data class GraphCard(val bankTitle: String, val promptMarkdown: String, val contentRevision: Int?)

/**
 * 一次写盘的结果。
 *
 * 不直接弹横幅，是因为"保存成功后要不要离开编辑器"属于导航：删除在列表页和编辑页
 * 都能点到，两处的下一屏不一样。让调用点拿着结果自己决定，比在这里猜 `screen` 可靠。
 */
sealed interface GraphWrite {
    /** 已落盘；[message] 是要告诉用户的那句话。 */
    data class Saved(val message: String) : GraphWrite

    /** 没落盘。[message] 为 null 表示问题已经显示在表单里，不需要再加一条横幅。 */
    data class Blocked(val message: String?) : GraphWrite
}

/**
 * 个人知识图谱：手建关系的读写、与生效扩展包的合并、解析视图，以及关系编辑器的草稿。
 *
 * 为什么值得单独成类：它是**唯一一处"显示的东西不等于存的东西"**的地方。
 * 磁盘上只有用户手建的那一份（[userGraph]），界面上看到的却是"手建 + 生效中扩展包"
 * （[merged]）—— 两者在视图层完全同权，唯一区别是扩展包带来的关系不能在这里改。
 * 这层合并以前夹在每次 `refresh()` 里，谁也没法一眼确认"删了包关系到底还在不在"。
 *
 * 跟着搬过来的还有**快照与新鲜度**：关系存的是两端题目的内容版本，题库换了一版就必须
 * 有人再看一眼（[revalidate]），软件只负责把"已看过"记下来，不假装自动修复。
 *
 * 三条边界：
 *
 * - **不导航。** 进图谱 / 编辑器的 `screen` 留在组合根；写盘回 [GraphWrite]，
 *   删除在列表页与编辑页的下一屏不同，由调用点决定。
 * - **不认识扩展包的"名字"。** "这条关系来自《某包》"那句话要查包清单，
 *   组合根在 [beginEdit] 之前就拦下了，这里只负责手建关系。
 * - **不自己决定题库标题怎么写。** 题库缺失时组合根还会去"待继续会话"里找标题，
 *   所以 [bankTitleOf] 由外部递入。
 */
class GraphController(
    private val graphs: GraphRepository,
    private val bankRepository: BankRepository,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val banks: () -> List<QuestionBank>,
    private val bankTitleOf: (String) -> String,
    private val activeExtensions: () -> List<ExtensionPack>,
) {

    /**
     * 图谱本体（用户手建的**那一份**，也是写回磁盘的那一份）。
     *
     * 扩展包的关系不写进这里：它们每次从包合并，删包即消失，
     * 不会出现"包删了、关系还挂在图谱文件里"的半截状态。
     */
    private var userGraph by mutableStateOf(KnowledgeGraph.Empty)

    /**
     * 界面真正看到的图谱：手建关系 + 生效中扩展包的关系。
     *
     * 两者在视图层完全同权（同样参与缺失判定、同样能触发硬依赖暂停），
     * 唯一区别是扩展包带来的关系不能在这里改 —— 改包才是改它。
     */
    var merged by mutableStateOf(KnowledgeGraph.Empty)
        private set

    /** 解析过的一帧：状态、可用性、排序都已算好，界面不做判断。 */
    var view by mutableStateOf(GraphView.Empty)
        private set

    /** 从练习页进来时只看这道题的关系；首页进来是 null（看全部）。 */
    var questionFilter by mutableStateOf<QuestionRef?>(null)
        private set

    var relationFilter by mutableStateOf<GraphRelation?>(null)
        private set

    var problemsOnly by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
        private set

    var hits by mutableStateOf<List<QuestionLocation>>(emptyList())
        private set

    var pickSide by mutableStateOf(GraphPickSide.NONE)
        private set

    var source by mutableStateOf<QuestionRef?>(null)
        private set

    var target by mutableStateOf<QuestionRef?>(null)
        private set

    var draftRelation by mutableStateOf(GraphRelation.RELATED)
        private set

    var draftDependency by mutableStateOf(DependencyKind.NAVIGATION)
        private set

    var draftNote by mutableStateOf("")
        private set

    var issues by mutableStateOf<List<ValidationIssue>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var editingEdgeId by mutableStateOf<String?>(null)
        private set

    /** 刚选中的端点（还没进图谱，解析结果里没有）。只在编辑期间用。 */
    private var pickedCards by mutableStateOf<Map<String, QuestionLocation>>(emptyMap())

    private var searchJob: Job? = null

    val editing: Boolean get() = !editingEdgeId.isNullOrBlank()

    /** 当前关系能不能选判题硬依赖。对称关系不能。 */
    val hardAllowed: Boolean get() = draftRelation.allowsHardDependency

    fun cardOf(ref: QuestionRef?): GraphCard? {
        if (ref == null) return null
        pickedCards[ref.key]?.let { return GraphCard(it.bankTitle, it.promptMarkdown, it.contentRevision) }
        val resolved = view.refs.firstOrNull { it.ref == ref }
        if (resolved != null) {
            return GraphCard(
                bankTitle = resolved.displayBankTitle,
                promptMarkdown = resolved.displayPrompt,
                contentRevision = resolved.liveRevision ?: resolved.snapshot?.contentRevision,
            )
        }
        return GraphCard(bankTitle = bankTitleOf(ref.bankId), promptMarkdown = "", contentRevision = null)
    }

    /** 关系列表：题目筛选 → 关系筛选 → 只看问题，三个条件可叠加。 */
    val filteredEdges: List<GraphEdgeView>
        get() {
            var list = view.edges
            questionFilter?.let { focus ->
                list = list.filter { it.edge.source == focus || it.edge.target == focus }
            }
            relationFilter?.let { relation ->
                list = list.filter { it.edge.relation == relation }
            }
            if (problemsOnly) list = list.filter { it.hasProblem }
            return list
        }

    fun edgeCountFor(ref: QuestionRef): Int = merged.edgesFor(ref).size

    /** 这条关系是不是手建的（决定能不能改、能不能按版本复核）。 */
    fun ownsEdge(edgeId: String): Boolean = userGraph.edge(edgeId) != null

    // ---- 读 ----

    /** 重读磁盘上的那一份，再把生效中的扩展包并进来。 */
    suspend fun reload() {
        userGraph = graphs.load()
        rematch()
    }

    /** 包装卸之后重算合并视图：文件没变，变的是"谁在生效"。 */
    suspend fun rematch() {
        merged = userGraph.mergedWith(activeExtensions())
        refreshView()
    }

    /**
     * 重新解析一帧。
     *
     * 只看关系里出现过的引用，不扫题库 —— 一份十万题的库被"打开图谱"拖住，
     * 用户会以为软件卡死了。
     */
    private suspend fun refreshView() {
        val refs = merged.refs()
        val located = if (refs.isEmpty()) emptyMap() else bankRepository.locate(refs)
        val lookup = object : GraphLookup {
            override fun bankTitle(bankId: String): String? =
                banks().firstOrNull { it.bankId == bankId }?.title

            override fun question(ref: QuestionRef): QuestionFact? =
                located[ref]?.let { QuestionFact(it.bankTitle, it.promptMarkdown, it.contentRevision) }
        }
        view = resolveGraph(merged, lookup)
    }

    // ---- 筛选 ----

    /** 进图谱页：焦点可带可不带，另外两个条件是新的开始。 */
    fun open(focus: QuestionRef?) {
        questionFilter = focus
        relationFilter = null
        problemsOnly = false
    }

    fun resetFilters() {
        questionFilter = null
        relationFilter = null
        problemsOnly = false
    }

    fun filterRelation(relation: GraphRelation?) {
        relationFilter = relation
    }

    fun toggleProblemsOnly() {
        problemsOnly = !problemsOnly
    }

    fun clearFocus() {
        questionFilter = null
    }

    // ---- 编辑器草稿 ----

    /**
     * 打开编辑器。
     *
     * [fallbackSource] 为空时用当前筛选的题目做起点 —— 从某道题进来，
     * 默认就该从这道题连出去。扩展包带来的关系由调用点先拦掉，这里只认手建的。
     */
    fun beginEdit(edgeId: String? = null, fallbackSource: QuestionRef? = null) {
        val edge = edgeId?.let { userGraph.edge(it) }
        editingEdgeId = edge?.edgeId
        source = edge?.source ?: fallbackSource
        target = edge?.target
        draftRelation = edge?.relation ?: GraphRelation.RELATED
        draftDependency = edge?.dependency ?: DependencyKind.NAVIGATION
        draftNote = edge?.noteMarkdown ?: ""
        issues = emptyList()
        pickSide = GraphPickSide.NONE
        query = ""
        hits = emptyList()
    }

    fun cancelEdit() {
        searchJob?.cancel()
        pickSide = GraphPickSide.NONE
        query = ""
        hits = emptyList()
        source = null
        target = null
        editingEdgeId = null
    }

    fun beginPick(side: GraphPickSide) {
        pickSide = side
        query = ""
        hits = emptyList()
    }

    fun cancelPick() {
        pickSide = GraphPickSide.NONE
        query = ""
        hits = emptyList()
    }

    /** 边打边搜，但等手停下来再查 —— 每敲一个字扫一遍题库不是搜索，是折腾磁盘。 */
    fun updateQuery(value: String) {
        query = value
        searchJob?.cancel()
        val keyword = value.trim()
        if (keyword.isEmpty()) {
            hits = emptyList()
            return
        }
        searchJob = scope.launch {
            delay(250)
            hits = bankRepository.searchQuestions(keyword, SEARCH_LIMIT)
        }
    }

    fun pickHit(hit: QuestionLocation) {
        when (pickSide) {
            GraphPickSide.SOURCE -> source = hit.ref
            GraphPickSide.TARGET -> target = hit.ref
            GraphPickSide.NONE -> return
        }
        pickedCards = pickedCards + (hit.ref.key to hit)
        pickSide = GraphPickSide.NONE
        query = ""
        hits = emptyList()
        issues = emptyList()
    }

    fun selectRelation(relation: GraphRelation) {
        draftRelation = relation
        // 对称关系承载不了"谁依赖谁"，切过去时把硬依赖收回导航
        if (draftDependency == DependencyKind.HARD && !relation.allowsHardDependency) {
            draftDependency = DependencyKind.NAVIGATION
        }
    }

    fun selectDependency(kind: DependencyKind) {
        if (kind == DependencyKind.HARD && !draftRelation.allowsHardDependency) return
        draftDependency = kind
    }

    fun updateNote(value: String) {
        draftNote = value
    }

    // ---- 写 ----

    /**
     * 保存关系。
     *
     * 校验 fail-closed：有 ERROR 就不落盘，但编辑中的内容全部保留 ——
     * 一次校验失败让人重选两端才叫难受。
     */
    suspend fun saveEdge(): GraphWrite {
        val from = source
        val to = target
        if (from == null || to == null) {
            issues = listOf(
                ValidationIssue(Severity.ERROR, "graph-needs-both", "两端都要选：先选起点，再选目标"),
            )
            return GraphWrite.Blocked(null)
        }

        val now = clock.nowMillis()
        val existing = editingEdgeId?.let { userGraph.edge(it) }
        val edge = GraphEdge(
            edgeId = existing?.edgeId ?: Ids.prefixed("edge", 10),
            source = from,
            target = to,
            relation = draftRelation,
            dependency = draftDependency,
            noteMarkdown = draftNote.trim(),
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )

        val problems = edge.validate()
        issues = problems
        if (problems.any { it.severity == Severity.ERROR }) return GraphWrite.Blocked(null)

        busy = true
        val located = bankRepository.locate(listOf(from, to))
        val fresh = listOf(from, to).mapNotNull { ref ->
            located[ref]?.let { fact ->
                snapshotOf(ref, QuestionFact(fact.bankTitle, fact.promptMarkdown, fact.contentRevision), now)
            }
        }
        val next = userGraph.withEdge(edge).withSnapshots(fresh).pruned()
        graphs.save(next)
        userGraph = next
        merged = next.mergedWith(activeExtensions())
        busy = false
        editingEdgeId = null
        source = null
        target = null
        draftNote = ""
        refreshView()
        return GraphWrite.Saved("关系已保存")
    }

    suspend fun deleteEdge(edgeId: String): GraphWrite {
        if (edgeId.isBlank()) return GraphWrite.Blocked(null)
        busy = true
        val next = userGraph.withoutEdge(edgeId).pruned()
        graphs.save(next)
        userGraph = next
        merged = next.mergedWith(activeExtensions())
        busy = false
        refreshView()
        return GraphWrite.Saved("关系已删除（题库内容不受影响）")
    }

    /**
     * 复核：用户看过当前内容，确认"这条关系仍然成立"。
     *
     * 这不是自动修复。内容版本变了就必须有人再看一眼，
     * 软件只负责把"已看过"这件事记下来。
     */
    suspend fun revalidate(edgeId: String): GraphWrite {
        val edge = userGraph.edge(edgeId) ?: return GraphWrite.Blocked(null)
        busy = true
        val now = clock.nowMillis()
        val refs = listOf(edge.source, edge.target)
        val located = bankRepository.locate(refs)
        val fresh = refs.mapNotNull { ref ->
            located[ref]?.let { fact ->
                snapshotOf(ref, QuestionFact(fact.bankTitle, fact.promptMarkdown, fact.contentRevision), now)
            }
        }
        if (fresh.isEmpty()) {
            busy = false
            return GraphWrite.Blocked("两端现在都定位不到，无法复核（原快照已保留）")
        }
        val next = userGraph.withSnapshots(fresh).withEdge(edge.copy(updatedAtMillis = now))
        graphs.save(next)
        userGraph = next
        merged = next.mergedWith(activeExtensions())
        busy = false
        refreshView()
        return GraphWrite.Saved("已按当前内容复核")
    }

    private companion object {
        const val SEARCH_LIMIT = 8
    }
}
