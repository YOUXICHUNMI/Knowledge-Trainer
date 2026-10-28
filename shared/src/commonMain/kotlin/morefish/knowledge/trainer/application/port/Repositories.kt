package morefish.knowledge.trainer.application.port

import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.patch.BankRevision
import morefish.knowledge.trainer.domain.practice.AttemptReview
import morefish.knowledge.trainer.domain.practice.QuestionKey

/**
 * 定位到的一道题。只带展示与版本判断需要的最小信息 ——
 * 图谱要确认"还在不在、内容版本变没变"，没必要把整库读进内存。
 */
data class QuestionLocation(
    val bankId: String,
    val bankTitle: String,
    val questionId: String,
    val promptMarkdown: String,
    val type: QuestionType,
    val contentRevision: Int,
) {
    val ref: QuestionRef get() = QuestionRef(bankId, questionId)
}

/** 题库仓储。作者原始题库只读语义在本层体现为"整体替换/新增"，不支持就地改单题。 */
interface BankRepository {
    suspend fun list(): List<QuestionBank>
    suspend fun find(bankId: String): QuestionBank?
    suspend fun findByFingerprint(fingerprint: String): QuestionBank?
    suspend fun save(bank: QuestionBank)

    /**
     * 按引用精确定位题目。题库不在、题目不在时对应键缺席。
     *
     * 实现应按库只读一次文件、逐行扫描，**不构造整库题目列表**：
     * 一次图谱刷新可能只查两三道题，却发生在十万题的库上。
     */
    suspend fun locate(refs: List<QuestionRef>): Map<QuestionRef, QuestionLocation>

    /** 跨库按关键词搜题（图谱选端点用）。[limit] 是总数上限，先到先得。 */
    suspend fun searchQuestions(keyword: String, limit: Int): List<QuestionLocation>

    /** 最近一次加载时发现的问题（存储布局迁移、损坏行等），用于向用户如实告警。 */
    val loadWarnings: List<String> get() = emptyList()
}

/**
 * 个人知识图谱仓储。
 *
 * 与题库不同，图谱是用户自己积累的东西，**可以改可以删**；
 * 但删除关系不等于删除题库内容，反之亦然。
 */
interface GraphRepository {
    suspend fun load(): KnowledgeGraph
    suspend fun save(graph: KnowledgeGraph)

    val loadWarnings: List<String>
}

/**
 * 扩展包仓储（`.qbext`）。
 *
 * 存的是"装了什么"，不是"现在生效没有"：生效与否由题库装没装决定，
 * 把它写进文件只会在用户卸载题库后留下一句谎话。
 */
interface ExtensionRepository {
    suspend fun all(): List<ExtensionPack>

    /** 按 packageId 新增或替换。同包的其他版本不会并存。 */
    suspend fun save(pack: ExtensionPack)

    /** 移除一个包。它带来的关系与注释一起消失，题库内容与历史一条不动。 */
    suspend fun remove(packageId: String)

    val loadWarnings: List<String> get() = emptyList()
}

/**
 * 会话仓储。只保留"当前这一场未结束的练习"：
 * 已完成的会话属于历史，不需要重复保存；被放弃的会话按 id 精确清除。
 */
interface SessionRepository {
    suspend fun save(session: PracticeSession)

    /** 读取可恢复的会话；没有或数据不可用时返回 null。 */
    suspend fun active(): PracticeSession?

    /** 只清除指定会话，避免误删后来新建的会话。 */
    suspend fun clear(sessionId: String)

    val loadWarnings: List<String>
}

/** 收藏：用户手动标记"需要复习"，不伪造为错误作答。 */
interface FavoriteRepository {
    suspend fun all(): Set<QuestionKey>
    suspend fun toggle(key: QuestionKey): Set<QuestionKey>
}

/**
 * 个人笔记仓储。
 *
 * 与题库不同，笔记是用户自己写的东西，**可以改可以删**：
 * 写错的备注没有任何保留价值，保留只会让人不敢写。
 */
interface NoteRepository {
    suspend fun all(): List<Note>
    suspend fun find(noteId: String): Note?

    /** 按 noteId 新增或替换；调用方负责时间戳与校验。 */
    suspend fun upsert(note: Note)
    suspend fun delete(noteId: String)

    val loadWarnings: List<String>
}

/** 历史仓储。只提供追加与读取，刻意不提供 update / delete。 */
interface AttemptRepository {
    suspend fun append(record: AttemptRecord)
    suspend fun all(): List<AttemptRecord>
    suspend fun forSession(sessionId: String): List<AttemptRecord>

    /** 最近一次加载时发现的问题（损坏行等），用于向用户如实告警。 */
    val loadWarnings: List<String>
}

/**
 * 历史复核仓储。
 *
 * 与 [AttemptRepository] 一样只追加：复核是"后来我怎么判的"这一事件，
 * 推翻上一次决定也要写成新的一条 —— 让"我改过主意"这件事本身可查。
 * 同一条历史以最后一条为准。
 */
interface ReviewRepository {
    suspend fun all(): List<AttemptReview>

    suspend fun append(review: AttemptReview)

    val loadWarnings: List<String>
}

/**
 * 题库修订日志。
 *
 * 只追加，不修改历史条目 —— 修订日志是"发生过什么"的记录，
 * 允许改写就等于允许篡改。
 */
interface RevisionRepository {
    /** 某个题库的全部修订，按修订号升序。 */
    suspend fun of(bankId: String): List<BankRevision>

    /** 最新一条；没有更新过时为 null。 */
    suspend fun latest(bankId: String): BankRevision?

    /**
     * 一次读出所有题库的最新修订。
     *
     * 首页要给每个题库标"第几版"，按库逐个读等于把同一份文件解析 N 遍 ——
     * 题库数量一多就变成可测量的卡顿。
     */
    suspend fun latestAll(): Map<String, BankRevision>

    suspend fun append(bankId: String, revision: BankRevision)

    val loadWarnings: List<String>
}

/**
 * 极小的文本存储端口。桌面端与 Android 端用文件实现，
 * Web / iOS 先退化为内存实现（重启丢失，界面会明确提示）。
 */
interface TextStorage {
    val isPersistent: Boolean
    fun read(name: String): String?
    fun write(name: String, content: String)
    fun appendLine(name: String, line: String)
}

class InMemoryTextStorage : TextStorage {
    private val files = mutableMapOf<String, StringBuilder>()

    override val isPersistent: Boolean = false

    override fun read(name: String): String? = files[name]?.toString()

    override fun write(name: String, content: String) {
        files[name] = StringBuilder(content)
    }

    override fun appendLine(name: String, line: String) {
        val sb = files.getOrPut(name) { StringBuilder() }
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        sb.append(line).append('\n')
    }
}
