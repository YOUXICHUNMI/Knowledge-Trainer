package morefish.knowledge.trainer.presentation.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.ValidationIssue
import morefish.knowledge.trainer.domain.model.validate

/**
 * 离线手工新建题目。
 *
 * 值得单独存在的原因是"校验"与"写入"在这里是同一件事的两端：规矩是 **fail-closed** ——
 * 有任何 ERROR 级问题就不落盘，且落盘要连带把 `contentRevision` 加一。
 * 这两句话分开写在两个文件里，就没人能一眼确认"校验过了就真的会写、写了就真的校验过"。
 *
 * 边界：
 *
 * - **表单字段不进来。** 题干、选项、答案这些由界面用本地状态持有，只在点保存那一刻
 *   组装成 [Question] 递过来。每敲一个字就往控制器里推一个不可变题目，既慢，也会让
 *   "用户正在输入、还没校验"的中间态成为全局状态，别处读到时无法判断它算不算数。
 * - **它写的是题库，不是自己的数据。** 所以写完之后不自己重读 —— 重读要连带历史、统计、
 *   图谱一起换，那是组合根的事。这里只通过 [onBankChanged] 说一句"题库变了"。
 * - **它不导航。** `openQuestionEditor` / `closeQuestionEditor` 仍在组合根；
 *   这里只管"这次保存的结果"，不管用户接下来去哪一页。
 */
class QuestionEditorController(
    private val repository: BankRepository,
    /** 读模型：界面要知道正在往哪个题库里加题。写盘走 [repository]，读取走这里。 */
    private val banks: () -> List<QuestionBank>,
    private val scope: CoroutineScope,
    /** 题库已经落盘，请组合根重读。 */
    private val onBankChanged: () -> Unit,
) {

    /** 正在编辑的题库；null 表示编辑器没打开过。 */
    var bankId by mutableStateOf<String?>(null)
        private set

    /** 上一次保存的校验结果。空 = 没问题（或还没试过）。 */
    var issues by mutableStateOf<List<ValidationIssue>>(emptyList())
        private set

    /** 上一次保存成功了。界面据此把"保存"换成"再写一题"。 */
    var saved by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    val bank: QuestionBank? get() = bankId?.let { id -> banks().firstOrNull { it.bankId == id } }

    fun open(bankId: String) {
        this.bankId = bankId
        issues = emptyList()
        saved = false
    }

    /** 「再写一题」：清掉保存成功标记与旧校验信息，表单字段由界面自己重置。 */
    fun beginNext() {
        saved = false
        issues = emptyList()
    }

    /**
     * 手工新建题目（个人扩展）。校验 fail-closed：有任何 ERROR 就不落盘。
     * 题库 contentRevision 递增，题目按现状追加，历史不动。
     */
    fun add(bankId: String, question: Question) {
        val errors = question.validate().filter { it.severity == Severity.ERROR }
        issues = errors
        if (errors.isNotEmpty()) return

        scope.launch {
            busy = true
            val bank = repository.find(bankId)
            if (bank == null) {
                busy = false
                issues = listOf(
                    ValidationIssue(Severity.ERROR, "bank-missing", "题库不存在或已被删除"),
                )
                return@launch
            }
            if (bank.questions.any { it.questionId == question.questionId }) {
                busy = false
                issues = listOf(
                    ValidationIssue(Severity.ERROR, "duplicate-id", "题目 ID ${question.questionId} 已存在，请换一个"),
                )
                return@launch
            }
            repository.save(
                bank.copy(
                    questions = bank.questions + question,
                    contentRevision = bank.contentRevision + 1,
                ),
            )
            busy = false
            saved = true
            issues = emptyList()
            onBankChanged()
        }
    }
}
