package morefish.knowledge.trainer.domain.backup

import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.mediaRelativePath

/**
 * 一处引用是谁提出的。
 *
 * 分这四档不是为了好看：用户看到"3 处接不上"时，第一句话一定是"哪三处"，
 * 而"图谱里的关系"和"我写的那条笔记"需要去完全不同的页面处理。
 */
enum class RefOwner(val label: String) {
    GRAPH("图谱关系"),
    NOTE("笔记"),
    EXTENSION("扩展包"),
    HISTORY("历史"),
}

/**
 * 引用为什么接不上。
 *
 * 只有两种，而且**都不是错误**：题库没装、题目被删，都会让一条引用暂时指空。
 * 界面上不能说"数据损坏"，那会把一次正常的卸载说成一次事故。
 */
enum class RefGapKind(val label: String, val hint: String) {
    BANK_MISSING(
        "题库不在这份数据里",
        "引用本身是好的，只是目标不在。装上 bankId 相同的题库后自动接上，不需要重新建关系",
    ),
    QUESTION_MISSING(
        "题目已不在题库里",
        "题库更新过、这道题被删掉了。引用不会自动消失 —— 留着它，比悄悄抹掉用户建过的东西好",
    ),
}

/**
 * 一类接不上的引用，**按目标聚合**，不按记录罗列。
 *
 * 十条关系指着同一个没装的题库，用户要处理的是"一个题库"，不是"十条关系"。
 * 按记录列出来只会让人以为问题有十条那么大。
 */
data class RefGap(
    val bankId: String,
    /** 为空 = 库级引用（扩展包绑定、整库笔记、历史）。 */
    val questionId: String?,
    val kind: RefGapKind,
    /** 题库在本机时带上标题；题库整个不在时为 null（那时连标题都无从得知）。 */
    val bankTitle: String?,
    /** 哪些地方提出了这个引用（按固定顺序，不用 Set 是因为界面要稳定顺序）。 */
    val owners: List<RefOwner>,
    /** 一共多少处引用指着这个目标。 */
    val occurrences: Int,
) {
    /** 界面上的一行标题。 */
    val target: String
        get() = when {
            bankTitle != null && questionId != null -> "《$bankTitle》里的 $questionId"
            bankTitle != null -> "《$bankTitle》"
            // 库里整个不在时只剩 bankId 可用。它是 UUID 就截短，是作者写的可读 id 就整个显示 ——
            // 把 `bank-thermal-handbook` 截成 `bank-the…` 只会让用户认不出那是什么。
            questionId != null -> "题库 ${shortId(bankId)} 里的 $questionId"
            else -> "题库 ${shortId(bankId)}"
        }

    private fun shortId(id: String): String =
        if (id.length <= 24) id else id.take(8) + "…"
}

/**
 * 一张题图在数据目录里找不到。
 *
 * 这是**文件与内容对不上**，与"引用指空"是两种问题：
 * 引用指空只是少一条线索，图缺失会让一道题看起来坏了（题干里空一块）。
 */
data class MediaGap(
    val bankId: String,
    val assetId: String,
    val bankTitle: String?,
    /** 有几道题引用了这张图。 */
    val questionCount: Int,
)

/**
 * 一次备份/恢复的完整性自检。
 *
 * 它回答的是产品总纲里那句"完整备份扫描跨库引用依赖闭包，报告遗漏"，
 * 以及"数据库与文件资源一致性" —— 只是这里没有数据库，对应的是
 * "题目里写的 assetId 在文件里到底有没有那一张"。
 *
 * **只报告，不拦截。** 指空的引用是既有状态，不是打包失败；
 * 为此挡住导出，等于让用户拿不到唯一的退路。
 */
data class ReferenceAudit(
    val banks: Int = 0,
    val questions: Int = 0,
    val graphEdges: Int = 0,
    val notes: Int = 0,
    val extensionPacks: Int = 0,
    val historyBanks: Int = 0,
    val gaps: List<RefGap> = emptyList(),
    /** 是否真的看过文件清单。false = 这个平台给不出文件列表（数据在内存里），媒体一项无从检查。 */
    val mediaChecked: Boolean = false,
    /** 引用到的图（按 bankId + assetId 去重）。 */
    val mediaReferenced: Int = 0,
    val mediaGaps: List<MediaGap> = emptyList(),
) {

    /**
     * 核对过多少处引用。
     *
     * 单位是"一次依赖声明"，不是"一条记录"：历史有三千条但只涉及一个题库，
     * 那算一处 —— 用户脑子里的问题也只有一个："那个库还在吗"。
     */
    val refCount: Int get() = graphEdges + notes + extensionPacks + historyBanks

    val gapCount: Int get() = gaps.size

    val bankGapCount: Int get() = gaps.count { it.kind == RefGapKind.BANK_MISSING }

    val questionGapCount: Int get() = gaps.count { it.kind == RefGapKind.QUESTION_MISSING }

    val isClean: Boolean get() = gaps.isEmpty() && mediaGaps.isEmpty()

    /** 一句话结论。横幅与页面共用同一句，避免同一个事实出现两种说法。 */
    val summary: String
        get() {
            val refs = if (refCount == 0) "没有需要核对的引用" else "核对了 $refCount 处引用"
            val refPart = when {
                refCount == 0 -> refs
                gaps.isEmpty() -> "$refs，全部接上"
                else -> "$refs，其中 ${gaps.size} 个目标接不上"
            }
            if (!mediaChecked) return "$refPart（文件清单不可用，图片没有核对）"
            val mediaPart = when {
                mediaReferenced == 0 -> "没有引用的图片"
                mediaGaps.isEmpty() -> "引用的 $mediaReferenced 张图都在"
                else -> "引用的 $mediaReferenced 张图里缺 ${mediaGaps.size} 张"
            }
            return "$refPart；$mediaPart"
        }
}

/**
 * 合并缺口时的分组键。
 *
 * `questionId` 只在题目级缺失时参与分组 —— 整个库不在时，"哪道题"没有意义，
 * 那个库里的每一道题都一样地接不上。
 */
private data class GapKey(val bankId: String, val kind: RefGapKind, val questionId: String?)

/**
 * 扫描这份数据里的引用闭包。
 *
 * 输入全是**已经载入内存的对象**，不读磁盘：一次导出已经把整份数据取过一遍，
 * 再读一遍只是把"自检"变成一个让人不想点的动作。
 *
 * [availablePaths] 是这份数据里真实存在的相对路径集合（备份里就是打包进去的那些）。
 * 传 null 表示"不知道"，此时跳过媒体检查 —— **不能当成"一个文件都没有"**，
 * 那会把每一张图都报成缺失。
 *
 * 调用方要传**合并后的图谱**（含扩展包带来的关系）：扩展包的关系恰恰是最容易
 * 指向"从没装过的题库"的那一批，漏掉它们等于漏掉最该报的那部分。
 * 因此这里只把扩展包的**绑定**与**注释**算作 [RefOwner.EXTENSION]，
 * 关系本身已经在 [graph] 里，不再重复计。
 *
 * 历史只查**库级**引用：补丁删题是正常操作，历史指向一道已被删掉的题
 * 是"过去确实做过这道题"的证据，不是悬空。把这种情况也报出来，
 * 只会让自检永远不干净，于是没人再看它。
 */
fun auditReferences(
    banks: List<QuestionBank>,
    graph: KnowledgeGraph,
    notes: List<Note>,
    extensions: List<ExtensionPack>,
    history: List<AttemptRecord>,
    availablePaths: Set<String>? = null,
): ReferenceAudit {
    val titles = banks.associate { it.bankId to it.title }

    // 题目存在性按库惰性建索引：十万题的库不该为了一条关系被整库摊平，
    // 但同一次自检里反复查同一库时，逐个 any{} 会变成 O(引用数 × 题数)。
    val questionIds = mutableMapOf<String, Set<String>>()
    fun questionExists(bankId: String, questionId: String): Boolean {
        val index = questionIds.getOrPut(bankId) {
            banks.firstOrNull { it.bankId == bankId }
                ?.questions
                ?.mapTo(HashSet()) { it.questionId }
                .orEmpty()
        }
        return questionId in index
    }

    // 一个"目标"：库级（questionId 为空）或题级。
    val owners = mutableMapOf<Pair<String, String?>, MutableList<RefOwner>>()

    fun target(bankId: String?, questionId: String?, owner: RefOwner) {
        if (bankId.isNullOrBlank()) return
        val key = bankId to questionId?.takeIf { it.isNotBlank() }
        owners.getOrPut(key) { mutableListOf() } += owner
    }

    graph.edges.forEach { edge ->
        target(edge.source.bankId, edge.source.questionId, RefOwner.GRAPH)
        target(edge.target.bankId, edge.target.questionId, RefOwner.GRAPH)
    }
    notes.forEach { note ->
        // 草稿不挂任何题库，也不该被算进来 —— 它按定义就是"还没有归属"
        if (note.scope == NoteScope.DRAFT) return@forEach
        val questionId = if (note.scope == NoteScope.QUESTION) note.questionId else null
        target(note.bankId, questionId, RefOwner.NOTE)
    }
    extensions.forEach { pack ->
        target(pack.binding.bankId, null, RefOwner.EXTENSION)
        pack.annotations.forEach { annotation ->
            target(pack.binding.bankId, annotation.questionId, RefOwner.EXTENSION)
        }
    }
    history.map { it.bankId }.distinct().forEach { bankId ->
        target(bankId, null, RefOwner.HISTORY)
    }

    // 先按目标判"接不接得上"，再把"整个库都不在"的那些并成一条。
    val rawGaps = owners.mapNotNull { (key, who) ->
        val (bankId, questionId) = key
        val bankExists = bankId in titles
        when {
            !bankExists -> RefGap(
                bankId = bankId,
                questionId = null,
                kind = RefGapKind.BANK_MISSING,
                bankTitle = null,
                owners = who.toList(),
                occurrences = who.size,
            )

            questionId != null && !questionExists(bankId, questionId) -> RefGap(
                bankId = bankId,
                questionId = questionId,
                kind = RefGapKind.QUESTION_MISSING,
                bankTitle = titles[bankId],
                owners = who.toList(),
                occurrences = who.size,
            )

            else -> null
        }
    }

    // 整个库不在时，它的每一道被引用的题都指向同一个"没装的那个库" ——
    // 合并成一条，否则十个引用会显示成十条问题。题目级缺失则必须逐题保留：
    // 它们是不同的目标，合并会把"删了两道题"说成"删了一道"。
    val merged = rawGaps
        .groupBy { gap ->
            if (gap.kind == RefGapKind.BANK_MISSING) GapKey(gap.bankId, gap.kind, null)
            else GapKey(gap.bankId, gap.kind, gap.questionId)
        }
        .map { (key, group) ->
            val ordered = RefOwner.entries.filter { owner -> group.any { owner in it.owners } }
            RefGap(
                bankId = key.bankId,
                questionId = key.questionId,
                kind = key.kind,
                bankTitle = group.first().bankTitle,
                owners = ordered,
                occurrences = group.sumOf { it.occurrences },
            )
        }
        .sortedWith(
            compareBy<RefGap> { if (it.kind == RefGapKind.BANK_MISSING) 0 else 1 }
                .thenByDescending { it.occurrences }
                .thenBy { it.bankId }
                .thenBy { it.questionId.orEmpty() },
        )

    // ---- 媒体文件 ----
    val referenced = mutableMapOf<Pair<String, String>, Int>()
    banks.forEach { bank ->
        bank.questions.forEach { question ->
            question.assetIds.distinct().forEach { assetId ->
                val key = bank.bankId to assetId
                referenced[key] = (referenced[key] ?: 0) + 1
            }
        }
    }
    val mediaGaps = if (availablePaths == null) {
        emptyList()
    } else {
        referenced.mapNotNull { (key, count) ->
            val (bankId, assetId) = key
            if (availablePaths.contains(mediaRelativePath(bankId, assetId))) return@mapNotNull null
            MediaGap(bankId = bankId, assetId = assetId, bankTitle = titles[bankId], questionCount = count)
        }.sortedWith(compareBy({ it.bankId }, { it.assetId }))
    }

    return ReferenceAudit(
        banks = banks.size,
        questions = banks.sumOf { it.questions.size },
        graphEdges = graph.edges.size,
        notes = notes.count { it.scope != NoteScope.DRAFT && !it.bankId.isNullOrBlank() },
        extensionPacks = extensions.size,
        historyBanks = history.map { it.bankId }.distinct().size,
        gaps = merged,
        mediaChecked = availablePaths != null,
        mediaReferenced = referenced.size,
        mediaGaps = mediaGaps,
    )
}
