package morefish.knowledge.trainer.domain.extension

import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.graph.MAX_NOTE_LENGTH
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.ValidationIssue

/**
 * 扩展包绑定的题库。
 *
 * **只有 [bankId] 是身份**：[bankTitleHint] 是给人看的，同名不同 UUID 的库永远不是同一个库。
 * 这一点与图谱的匹配规则一致 —— 用户手改一个标题不该让别人的关系连到自己身上。
 */
data class BankBinding(val bankId: String, val bankTitleHint: String = "")

/**
 * 扩展包带来的一条跨库关系。
 *
 * `source` 必须在被绑定的题库内（扩展包只补充自己那份题库的"出口"），
 * `target` 可以指向任何库 —— 那正是跨库关系的意义。目标不在本机时关系仍然保留，
 * 这与手工建立的关系走同一套 `RefStatus` 判定，不需要另造一套"半失效"状态。
 */
data class ExtensionRelation(
    val relationId: String,
    val source: QuestionRef,
    val target: QuestionRef,
    val relation: GraphRelation,
    val dependency: DependencyKind = DependencyKind.NAVIGATION,
    val noteMarkdown: String = "",
)

/** 扩展包带来的一段讲解。绑定到某道题，或（[questionId] 为空时）针对整库。 */
data class ExtensionAnnotation(
    val annotationId: String,
    val questionId: String?,
    val title: String,
    val bodyMarkdown: String,
)

/**
 * 一个已安装的扩展包。它不是题库的一部分：题库照旧只读，
 * 删掉扩展包只删这些补充内容，题目与历史一条都不动。
 */
data class ExtensionPack(
    val packageId: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String = "",
    val binding: BankBinding,
    val relations: List<ExtensionRelation> = emptyList(),
    val annotations: List<ExtensionAnnotation> = emptyList(),
    val importedAtMillis: Long = 0,
    val sourceLabel: String = "",
) {
    val relationCount: Int get() = relations.size

    val annotationCount: Int get() = annotations.size

    val isEmpty: Boolean get() = relations.isEmpty() && annotations.isEmpty()

    /** 这个包里的关系都以本题库为起点（校验保证），这里顺手给出它覆盖的题目数。 */
    val coveredQuestionCount: Int get() = relations.map { it.source.questionId }.distinct().size
}

/**
 * 扩展包在**这台设备上**的状态。刻意不写进存储：
 * 装了题库就是生效，卸了题库就等它回来 —— 状态是推导出来的，不是一份会过期的副本。
 */
enum class ExtensionState(val label: String) {
    ACTIVE("生效中"),
    PENDING("等待题库"),
}

fun ExtensionPack.stateIn(installedBankIds: Set<String>): ExtensionState =
    if (binding.bankId in installedBankIds) ExtensionState.ACTIVE else ExtensionState.PENDING

/** 关系条数上限：扩展包是"补充说明"，不是第二个题库。 */
const val MAX_EXTENSION_RELATIONS = 5000
const val MAX_EXTENSION_ANNOTATIONS = 2000
const val MAX_ANNOTATION_CHARS = 20_000

/** 扩展包关系的 edgeId 前缀：图谱文件里靠它区分"手建的"与"包带来的"。 */
private const val EXTENSION_EDGE_PREFIX = "ext:"

fun extensionEdgeId(packageId: String, relationId: String): String =
    "$EXTENSION_EDGE_PREFIX$packageId:$relationId"

/**
 * 从 edgeId 反查它属于哪个扩展包。
 *
 * 手建关系的 edgeId 是 `edge-xxxx`，永远解析不出包名 —— 这正是想要的：
 * 图谱写回磁盘时只写手建的那些，扩展包的关系每次从包重新合并。
 */
fun extensionOwnerOf(edgeId: String): String? {
    if (!edgeId.startsWith(EXTENSION_EDGE_PREFIX)) return null
    val rest = edgeId.removePrefix(EXTENSION_EDGE_PREFIX)
    val cut = rest.indexOf(':')
    return if (cut <= 0) null else rest.substring(0, cut)
}

/** 一条扩展关系转成图谱里的一帧。快照为空是刻意的：包不替别人保存正文。 */
fun ExtensionRelation.toGraphEdge(pack: ExtensionPack): GraphEdge = GraphEdge(
    edgeId = extensionEdgeId(pack.packageId, relationId),
    source = source,
    target = target,
    relation = relation,
    dependency = dependency,
    noteMarkdown = noteMarkdown,
    createdAtMillis = pack.importedAtMillis,
    updatedAtMillis = pack.importedAtMillis,
)

/**
 * 把生效中的扩展包并进图谱。
 *
 * 只读合并：`graph` 仍是用户的图谱本体，写回磁盘时不会带上这些关系。
 * 移除扩展包 → 重新合并一次，关系自然消失，不留"删了包还挂着几条关系"的残渣。
 */
fun KnowledgeGraph.mergedWith(packs: List<ExtensionPack>): KnowledgeGraph {
    val extra = packs.flatMap { pack -> pack.relations.map { it.toGraphEdge(pack) } }
    if (extra.isEmpty()) return this
    val existing = edges.map { it.edgeId }.toSet()
    return copy(edges = (edges + extra.filterNot { it.edgeId in existing }).sortedBy { it.createdAtMillis })
}

// ---- 校验 ----

/**
 * 关系的自检。除图谱那套通用规则外，多一条**来源必须属于被绑定的题库**：
 * 一个"生理学"的扩展包没有资格给别人的题库加关系，哪怕它知道对方的 UUID。
 */
fun ExtensionRelation.validate(boundBankId: String): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()

    if (relationId.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-relation-id", "扩展关系缺少 relationId")
    }
    if (source.bankId != boundBankId) {
        issues += ValidationIssue(
            Severity.ERROR,
            "ext-relation-foreign-source",
            "关系起点必须是本扩展包绑定的题库（$boundBankId），实际是 ${source.bankId.ifBlank { "（空）" }}",
        )
    }
    if (!source.isComplete() || !target.isComplete()) {
        issues += ValidationIssue(Severity.ERROR, "ext-relation-blank-ref", "关系两端都必须是完整引用（题库 + 题目）")
    }
    if (source == target) {
        issues += ValidationIssue(Severity.ERROR, "ext-relation-self", "一道题不能和自己建立关系")
    }
    if (dependency == DependencyKind.HARD && !relation.allowsHardDependency) {
        issues += ValidationIssue(
            Severity.ERROR,
            "ext-relation-hard-needs-direction",
            "「${relation.label}」是对称关系，说不清谁依赖谁，不能作为判题硬依赖",
        )
    }
    if (noteMarkdown.length > MAX_NOTE_LENGTH) {
        issues += ValidationIssue(Severity.WARNING, "ext-relation-note-long", "备注超过 $MAX_NOTE_LENGTH 字，列表只显示开头")
    }
    return issues
}

fun ExtensionAnnotation.validate(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    if (annotationId.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-annotation-id", "注释缺少 annotationId")
    }
    if (title.isBlank()) {
        issues += ValidationIssue(Severity.WARNING, "ext-annotation-title", "注释没有标题，列表里会只显示正文开头")
    }
    if (bodyMarkdown.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-annotation-empty", "注释正文为空")
    }
    if (bodyMarkdown.length > MAX_ANNOTATION_CHARS) {
        issues += ValidationIssue(
            Severity.ERROR,
            "ext-annotation-too-long",
            "注释超过 $MAX_ANNOTATION_CHARS 字，请拆成多条",
        )
    }
    return issues
}

fun ExtensionPack.validate(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    if (packageId.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-package-id", "扩展包缺少 packageId")
    }
    if (name.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-package-name", "扩展包缺少名称")
    }
    if (semanticVersionOf(version) == null) {
        issues += ValidationIssue(Severity.ERROR, "ext-package-version", "版本号 $version 不是 主.次.修 形式")
    }
    if (binding.bankId.isBlank()) {
        issues += ValidationIssue(Severity.ERROR, "ext-binding-missing", "扩展包没有绑定题库 UUID")
    }
    if (isEmpty) {
        issues += ValidationIssue(Severity.ERROR, "ext-empty", "扩展包既没有关系也没有注释，装上也不会有任何变化")
    }
    if (relations.size > MAX_EXTENSION_RELATIONS) {
        issues += ValidationIssue(Severity.ERROR, "ext-too-many-relations", "关系超过 $MAX_EXTENSION_RELATIONS 条")
    }
    if (annotations.size > MAX_EXTENSION_ANNOTATIONS) {
        issues += ValidationIssue(Severity.ERROR, "ext-too-many-annotations", "注释超过 $MAX_EXTENSION_ANNOTATIONS 条")
    }
    relations.forEach { issues += it.validate(binding.bankId) }
    annotations.forEach { issues += it.validate() }
    return issues
}

/** 解析 `1.2.3`；不合法返回 null（也用于导入时的版本字段校验）。 */
fun semanticVersionOf(text: String): Triple<Int, Int, Int>? {
    val parts = text.trim().split('.')
    if (parts.size != 3) return null
    val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    return Triple(numbers[0], numbers[1], numbers[2])
}

/**
 * 比较版本。[a] 比 [b] 新返回正数，相同为 0。
 *
 * 只接受 `主.次.修`，解析不了的一律当作"更旧" —— 宁可拒绝升级，
 * 也不要在看不懂版本号的时候覆盖用户已经装好的包。
 */
fun compareVersions(a: String, b: String): Int {
    val left = semanticVersionOf(a) ?: return -1
    val right = semanticVersionOf(b) ?: return 1
    left.toList().zip(right.toList()).forEach { (l, r) ->
        if (l != r) return l - r
    }
    return 0
}

/** 已安装包的一张摘要，界面用。 */
data class ExtensionSummary(
    val pack: ExtensionPack,
    val state: ExtensionState,
) {
    val boundaryTitle: String
        get() = pack.binding.bankTitleHint.ifBlank { pack.binding.bankId.take(8) }
}
