package morefish.knowledge.trainer.domain.model

/**
 * 笔记的三种归属。
 *
 * 刻意只分三档，而不是"任意层级 + 标签"：
 * 学习时真正需要写下来的只有"这份资料讲了什么"（题库）、
 * "这道题我为什么错"（题目）和"临时想法"（草稿）。
 * 草稿不挂题库，因此导入新资料、删除题库都不会牵连它。
 */
enum class NoteScope(val wireName: String, val label: String, val hint: String) {
    BANK("bank", "题库笔记", "记这份资料的来源、范围与使用心得"),
    QUESTION("question", "题目笔记", "记思路、易错点与相关知识点"),
    DRAFT("draft", "临时草稿", "随手写，不挂题库，随时清空");

    companion object {
        fun fromWire(value: String): NoteScope? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 一条个人笔记。
 *
 * 与题库内容严格分离：笔记只引用 [bankId] / [questionId]，
 * 不复制题目正文 —— 题库更新或重新导入后，笔记仍然指向同一身份。
 * 引用对象不存在时笔记不删除，界面标注"关联题目已不存在"，避免用户白写的东西消失。
 */
data class Note(
    val noteId: String,
    val scope: NoteScope,
    val bankId: String? = null,
    val questionId: String? = null,
    val title: String = "",
    val bodyMarkdown: String = "",
    val pinned: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    /** 上限是保护存储层（整文件重写）用的，不是产品限制。 */
    fun validate(): List<ValidationIssue> = buildList {
        if (bodyMarkdown.isBlank()) {
            add(ValidationIssue(Severity.ERROR, "note-empty", "笔记内容不能为空"))
        }
        if (title.trim().length > MAX_TITLE) {
            add(ValidationIssue(Severity.ERROR, "note-title-too-long", "标题最多 $MAX_TITLE 个字"))
        }
        if (bodyMarkdown.length > MAX_BODY) {
            add(ValidationIssue(Severity.ERROR, "note-body-too-long", "正文最多 $MAX_BODY 个字符"))
        }
        when (scope) {
            NoteScope.BANK -> if (bankId.isNullOrBlank()) {
                add(ValidationIssue(Severity.ERROR, "note-bank-missing", "题库笔记必须关联一个题库"))
            }

            NoteScope.QUESTION -> {
                if (bankId.isNullOrBlank()) {
                    add(ValidationIssue(Severity.ERROR, "note-bank-missing", "题目笔记必须关联一个题库"))
                }
                if (questionId.isNullOrBlank()) {
                    add(ValidationIssue(Severity.ERROR, "note-question-missing", "题目笔记必须关联一道题目"))
                }
            }

            NoteScope.DRAFT -> Unit
        }
    }

    fun errors(): List<ValidationIssue> = validate().filter { it.severity == Severity.ERROR }

    /** 搜索：标题与正文都参与，大小写不敏感（拉丁字母）；中文按子串匹配。 */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return title.contains(needle, ignoreCase = true) || bodyMarkdown.contains(needle, ignoreCase = true)
    }

    /** 列表标题：没写标题时退回正文首行，去掉常见的 Markdown 前缀。 */
    val displayTitle: String
        get() {
            val explicit = title.trim()
            if (explicit.isNotEmpty()) return explicit
            val firstLine = bodyMarkdown.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            return firstLine.trimStart('#', '>', '-', '*', ' ').take(EXCERPT_LENGTH).ifBlank { "（无标题）" }
        }

    companion object {
        const val MAX_TITLE = 80
        const val MAX_BODY = 20_000
        const val EXCERPT_LENGTH = 40
    }
}

/** 置顶优先，其次最近修改。列表页与首页都用同一套顺序，避免两处不一致。 */
fun List<Note>.sortedForDisplay(): List<Note> =
    sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAtMillis })

/** 某道题已有的笔记（同一题允许写多条，这里取最近修改的一条做入口）。 */
fun List<Note>.forQuestion(bankId: String, questionId: String): Note? =
    filter { it.scope == NoteScope.QUESTION && it.bankId == bankId && it.questionId == questionId }
        .maxByOrNull { it.updatedAtMillis }

fun List<Note>.countForBank(bankId: String): Int = count { it.bankId == bankId }
