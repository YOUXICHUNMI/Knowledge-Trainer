package morefish.knowledge.trainer.application.port

import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
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

/** [BankRepository.addQuestion] 的三种结局。没有"失败"这一档：写不进去就抛异常。 */
sealed interface AddQuestionResult {
    /** 已追加，目录同步更新。 */
    data object Added : AddQuestionResult

    /** 目录里没有这个题库（已被移除，或 id 写错）。 */
    data object BankMissing : AddQuestionResult

    /** 这个库里已经有同 id 的题。什么都没写 —— "已经在里面"和"刚加进去"是两件事。 */
    data object DuplicateId : AddQuestionResult
}

/** 题库仓储。作者原始题库只读语义在本层体现为"整体替换/新增"，不支持就地改单题。 */
interface BankRepository {
    suspend fun list(): List<QuestionBank>
    suspend fun find(bankId: String): QuestionBank?
    suspend fun findByFingerprint(fingerprint: String): QuestionBank?
    suspend fun save(bank: QuestionBank)

    /**
     * 往已有题库里**追加一道题**。
     *
     * 结果与 `save(bank.copy(questions = bank.questions + q, contentRevision = … + 1))` 相同，
     * 区别只在代价：`save` 的语义是"用这份列表替换该库的全部题目"，实现只能整库重写；
     * 而"追加一道"是可以只写一行的。十万题的库上这两件事差了五个数量级
     * （49.9MB vs 512 字节，实测见 `docs/STATUS-P10.md` 第 20、21 节）。
     *
     * 契约（实现必须满足，且可与"读出来 copy 一份再 save"逐字段对照）：
     *
     * - 写完之后，目录里的题数、题型分布、语言覆盖度、内容指纹必须**等于实际内容**；
     * - 该库内容变了，`contentRevision` 加一；别的库一概不碰；
     * - 题目 id 重复时**什么都不写**并返回 [AddQuestionResult.DuplicateId]。
     *
     * 默认实现是"读出来改名再整库写回"—— 语义最直白的那个。存储实现可以覆盖它，
     * 但覆盖之后上面几条一句都不能少。
     */
    suspend fun addQuestion(bankId: String, question: Question): AddQuestionResult {
        val bank = find(bankId) ?: return AddQuestionResult.BankMissing
        if (bank.questions.any { it.questionId == question.questionId }) return AddQuestionResult.DuplicateId
        save(bank.copy(questions = bank.questions + question, contentRevision = bank.contentRevision + 1))
        return AddQuestionResult.Added
    }

    /**
     * 从书架上移除一个题库：目录条目与它的题目文件一起删掉。
     *
     * 返回 false 表示库里本来就没有它 —— "本来就不在"和"刚删掉"是两件事，
     * 界面不该把前者也说成"已移除"。
     *
     * **历史、笔记、收藏不归它管**：那些是既成事实，删题库不等于删记忆。
     * 指向这个库的关系与未完成会话由调用方各自收拾（两边都不属于题库仓储）。
     */
    suspend fun delete(bankId: String): Boolean

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

    /**
     * 删除一个文件。不存在时返回 false。
     *
     * 端口只认名字，不认目录：目录怎么摆是实现的私事。删除是**幂等**的 ——
     * "再删一次"不该失败，删除本来就是"希望它不在"，第二次它确实不在。
     */
    fun delete(name: String): Boolean
}

/**
 * 「只读一段」的能力（可选）。
 *
 * [TextStorage] 只会整份读、整份写，于是"取一道题"在十万题的库上要读 50MB 再逐行扫。
 * 真正让这件事变便宜的只有一件事：知道该读哪几个字节。这个端口就是那个能力本身 ——
 * 它不规定索引怎么存、存在哪，只规定「给定字节区间，把那段文字还回来」。
 *
 * 偏移是 **UTF-8 字节**，不是字符：文件是 UTF-8，一个汉字三字节。切在行中间会得到
 * 半个字（解码成替换符），所以调用方只该拿它来读整行 —— 行边界由写索引那一侧记录。
 *
 * 只有真文件后端实现得了；内存后端本来就整份在手里，没有"读一部分"这回事，
 * 也不该假装有。拿不到这个能力时，上层退回整份读 + 扫描，结果一样、只是慢。
 */
interface RandomAccessText {
    /** 文件有多少字节；不存在返回 null。 */
    fun byteLength(name: String): Long?

    /** 从第 [offset] 个字节起读 [length] 个；越界或文件不在返回 null。 */
    fun readRange(name: String, offset: Long, length: Int): String?
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

    override fun delete(name: String): Boolean = files.remove(name) != null
}
