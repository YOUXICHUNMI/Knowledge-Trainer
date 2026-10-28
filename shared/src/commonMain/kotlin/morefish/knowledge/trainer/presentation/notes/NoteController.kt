package morefish.knowledge.trainer.presentation.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.NoteRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.Ids
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.ValidationIssue
import morefish.knowledge.trainer.domain.model.countForBank
import morefish.knowledge.trainer.domain.model.forQuestion

/** 删除的两种结果：删掉了 / 本来就没有。横幅说什么、下一屏去哪由调用点决定。 */
enum class NoteDelete { DELETED, NOTHING }

/**
 * 个人笔记：全部笔记、编辑器种子、校验结论、写盘。
 *
 * 为什么值得单独成类：
 *
 * 1. **笔记是应用里唯一"用户自己写的内容"。** 题库来自作者，历史来自作答，都能从别处
 *    重新算出来；笔记不行 —— 丢了就是丢了。所以它的规则是 fail-closed（校验有错就不落盘）
 *    加"不覆盖身份"（编辑沿用原 noteId 与 createdAt），这两条必须和写盘写在一起，
 *    否则没人能一眼确认"拒收之后真的没写、写了之后真的校验过"。
 * 2. **它的生命周期与题库解耦。** 笔记只存 bankId / questionId，不复制题目正文；
 *    题库改了、重新导入、甚至删了，笔记都还在，界面标"关联题目已不存在"。
 *    "引用而不是拷贝"这件事以前只存在于模型注释里，现在它有了一个能读的唯一实现者。
 * 3. **三种归属（题库 / 题目 / 草稿）的校验规则不同。** 草稿不挂题库，题目笔记必须挂到
 *    具体一道题 —— 这些规则按 scope 分支，散在三千行里最容易漏掉一支。
 *
 * 三条边界，与前几刀一致：
 *
 * - **不导航。** 进列表页、进编辑器、返回哪里都留在组合根（编辑器要记住"从练习页来的"）。
 * - **不弹横幅。** 删除成功经 [NoteDelete] 交回去；保存失败就停在编辑器里显示行内错误，
 *   不弹一句盖住表单的提示。
 * - **不碰题库与历史。** 它只按 id 指向它们，从不读题目内容，所以题库换了也不需要通知它。
 *
 * 数据是它自己的（[NoteRepository]），所以写盘发生在类里 —— 与 AI 讲解同一条边界，
 * 与前几刀"不写别人的数据"并不矛盾。
 */
class NoteController(
    private val repository: NoteRepository,
    private val clock: WallClock,
    private val scope: CoroutineScope,
) {

    /** 全部笔记。首页卡片的条数、笔记列表、引用自检与备份打包都读这一份。 */
    var all by mutableStateOf<List<Note>>(emptyList())
        private set

    /** 编辑器种子：noteId 为空表示新建。切换页面时整体替换，界面按它重置表单。 */
    var seed by mutableStateOf<Note?>(null)
        private set

    var issues by mutableStateOf<List<ValidationIssue>>(emptyList())
        private set

    /** 保存成功。界面据此把"保存"换成"已保存"，而种子一换（新建）就要复位。 */
    var saved by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    /** 从题库卡片进入笔记页时的筛选；null 表示看全部。 */
    var bankFilter by mutableStateOf<String?>(null)
        private set

    /** 备份概况里报"笔记 N 条"。 */
    val count: Int get() = all.size

    val editingExisting: Boolean get() = !seed?.noteId.isNullOrBlank()

    fun countFor(bankId: String): Int = all.countForBank(bankId)

    fun byId(noteId: String): Note? = all.firstOrNull { it.noteId == noteId }

    /** 同一题允许写多条，取最近修改的一条做入口 —— 规则在模型里，这里只转发。 */
    fun forQuestion(bankId: String, questionId: String): Note? = all.forQuestion(bankId, questionId)

    fun filterByBank(bankId: String?) {
        bankFilter = bankId
    }

    /** 离开列表页要清掉筛选，否则下次进来只剩一个题库。 */
    fun clearFilter() {
        bankFilter = null
    }

    /** 打开一条已有笔记：种子换成它，上一轮的校验结论与"已保存"一起作废。 */
    fun beginEdit(note: Note) {
        seed = note
        issues = emptyList()
        saved = false
    }

    /**
     * 新建笔记。[questionId] 非空时是"对当前题写"，此刻就把关联钉死在种子里，
     * 避免用户在编辑器里改出"题目笔记却没题"的悬空记录。
     */
    fun beginNew(
        scope: NoteScope = NoteScope.DRAFT,
        bankId: String? = null,
        questionId: String? = null,
    ) {
        seed = Note(
            noteId = "",
            scope = scope,
            bankId = bankId,
            questionId = questionId,
        )
        issues = emptyList()
        saved = false
    }

    fun clearSeed() {
        seed = null
    }

    suspend fun reload() {
        all = repository.all()
    }

    /**
     * 保存笔记。
     *
     * 校验 fail-closed：有 ERROR 就不落盘，但**种子不动** —— 界面上的文字仍然保留，
     * 不会因为一次校验失败让人重写。写盘时补上身份与时间戳：新建才发号，
     * 编辑沿用原 createdAt，"最近修改"才是真实的写作时间。
     */
    fun save(draft: Note) {
        val errors = draft.errors()
        issues = errors
        if (errors.isNotEmpty()) return

        scope.launch {
            busy = true
            val now = clock.nowMillis()
            val existing = draft.noteId.takeIf { it.isNotBlank() }?.let { repository.find(it) }
            val note = draft.copy(
                noteId = draft.noteId.ifBlank { Ids.prefixed("note", 10) },
                title = draft.title.trim(),
                bodyMarkdown = draft.bodyMarkdown.trim(),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            repository.upsert(note)
            seed = note
            busy = false
            saved = true
            issues = emptyList()
            reload()
        }
    }

    /**
     * 删除。删掉之后种子要清 —— 否则编辑器还拿着一条已经不存在的笔记，
     * 用户再点一次保存就会把它写回来。
     */
    suspend fun delete(noteId: String): NoteDelete {
        if (noteId.isBlank()) return NoteDelete.NOTHING
        busy = true
        repository.delete(noteId)
        busy = false
        seed = null
        saved = false
        reload()
        return NoteDelete.DELETED
    }

    /** 置顶只改 pinned，不动 updatedAt —— "最近修改"必须仍然是真实的写作时间。 */
    fun togglePin(noteId: String) {
        val note = all.firstOrNull { it.noteId == noteId } ?: return
        scope.launch {
            repository.upsert(note.copy(pinned = !note.pinned))
            reload()
        }
    }
}
