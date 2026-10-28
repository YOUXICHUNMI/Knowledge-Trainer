package morefish.knowledge.trainer.presentation.importing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.ImportParseResult
import morefish.knowledge.trainer.application.port.ImportPreview
import morefish.knowledge.trainer.application.port.PickedFile
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.domain.model.QuestionBank

/**
 * 一次导入的结论。
 *
 * 做成返回值而不是"自己弹提示"，是因为**这话只有组合根说得出来**：题库落库可能
 * 让某个"等待题库"的扩展包当场生效，而"哪一个生效了"要拿落库前后的扩展清单去比 ——
 * 那两份清单都在组合根手里。让导入控制器自己拼这句话，就得把扩展包状态也搬进来，
 * 两个功能从此互相引用。
 *
 * 与 [ImportOutcome] 的区别不只是换个名字：[ImportOutcome] 分的是"校验/查重走到了哪一步"，
 * 这里分的是"界面接下来该说哪句话、去哪个页面"。[Rejected] 两侧都没有附加信息，
 * 因为"没有可导入的题目"是行内错误，不该再配一条横幅。
 */
sealed interface ImportVerdict {
    /** 题库已经落库。 */
    data class Imported(val bank: QuestionBank) : ImportVerdict

    /** 同一份材料以前导过：不建副本，也不覆盖。 */
    data class Duplicate(val existing: QuestionBank) : ImportVerdict

    /**
     * 同一个 bankId、不同内容 —— 作者发了新版本。
     *
     * 不覆盖是刻意的：直接换掉题面，历史里那些记录还停在旧答案上，用户完全不知道
     * 发生过什么。正确路径是 `.qbpatch`，那里会把每条改动列出来。
     */
    data class IdentityConflict(val existing: QuestionBank, val incomingCount: Int) : ImportVerdict

    /** 有题目需要用户确认是单选还是多选；猜错会永久写进题库身份。 */
    data class NeedsMapping(val ambiguousCount: Int) : ImportVerdict

    /** 解析成功但没有一道可用题目；[ImportController.error] 里已有行内说明。 */
    data object Rejected : ImportVerdict

    /** 解析或校验失败，一个字都没写。 */
    data class Failed(val message: String) : ImportVerdict

    /**
     * 还没走到解析就停下了：不是包、读文件失败、太大。
     *
     * 与 [Failed] 分开：这不是"材料有问题"，而是"这次没试成"，横幅措辞不一样。
     */
    data class Refused(val reason: String) : ImportVerdict
}

/**
 * 导入：粘贴文本 / `.qbank` 包，以及"选文件"这个入口本身。
 *
 * 为什么值得单独成类：它是全应用唯一**两种材料共用一套结论**的地方 ——
 * 文本走宽松导入（允许部分题目有问题、其余照常导入、要题型映射），
 * 包走严格校验（一条不合规就整包拒绝、不需要映射）。两条路径的**过程**完全不同，
 * 但"导入之后"这件事必须一致：清不清空输入、预览留不留、重复导入怎么说。
 * 此前这套一致性只存在于一处 `when` 里，混在三千行中间，改一条分支没人知道另一条还在不在。
 *
 * 三条边界：
 *
 * - **不导航、不弹横幅。** 进页面 / 返回 / 说什么话留在 `AppController`。
 * - **不认得"别的包"。** `.qbext` / `.qbpatch` / `.qbk` 分别落到扩展、补丁、备份三个
 *   功能上，认包类型要靠 `infrastructure` 的探针 —— 那不是本类该碰的东西，
 *   所以分发留在组合根（它本来就允许接触 infrastructure）。
 * - **不管忙碌标记。** 什么时候算"忙"取决于谁在等这次导入，由调用点决定。
 */
class ImportController(
    private val useCase: ImportBankUseCase,
    private val picker: FilePicker,
) {

    /** 粘贴框里的文本。成功导入后清空 —— 见 [resetInput]。 */
    var text by mutableStateOf("")
        private set

    /** 文本导入时的题库名；留空则由导入器给一个"未命名题库"。 */
    var title by mutableStateOf("")
        private set

    /** 解析预览：`null` = 还没有可看的预览（没解析过、或解析失败）。 */
    var preview by mutableStateOf<ImportPreview?>(null)
        private set

    /** 行内错误（显示在输入框下方），与横幅是两回事：这里说的是"这份材料哪里不行"。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 最近一次落地的文件名，导入页与扩展页都靠它显示"这次处理的是哪个文件"。 */
    var pickedFileName by mutableStateOf<String?>(null)
        private set

    val pickerAvailable: Boolean get() = picker.isAvailable

    val pickerUnavailableReason: String? get() = picker.unavailableReason

    /**
     * 打开导入页。
     *
     * 只有输入框是空的时候才放示例进去：用户已经把内容贴进来了，再点一次"导入题库"
     * 就把他的东西冲掉了。预览与错误倒是每次都清 —— 它们是上一次操作的残留，
     * 留着会让人以为这次一进来就已经解析过了。
     */
    fun open(sample: String) {
        if (text.isBlank()) text = sample
        preview = null
        error = null
    }

    /** 改一个字，上一次的预览就作废：它还对应着旧文本，继续显示等于给了错误的安全感。 */
    fun updateText(value: String) {
        text = value
        preview = null
        error = null
    }

    /** 改标题不影响解析结果，预览留着。 */
    fun updateTitle(value: String) {
        title = value
    }

    /** 只解析不落库。失败也只是"这份材料不行"，不写任何东西。 */
    fun previewText(mapping: ChoiceMapping = ChoiceMapping.AUTO) {
        when (val result = useCase.preview(text, mapping)) {
            is ImportParseResult.Failed -> {
                preview = null
                error = result.message
            }

            is ImportParseResult.Ok -> {
                preview = result.preview
                error = null
            }
        }
    }

    /** 跳过预览直接导入（也可以带着用户选定的题型映射）。 */
    suspend fun confirmText(mapping: ChoiceMapping = ChoiceMapping.AUTO): ImportVerdict {
        val outcome = useCase.import(
            raw = text,
            mapping = mapping,
            title = title,
            sourceLabel = "pasted-json",
        )
        return accept(outcome, fromText = true)
    }

    /**
     * 打开系统文件选择器。用户取消返回 `null`。
     *
     * 读文件失败抛 [morefish.knowledge.trainer.application.port.FilePickException]，
     * 由调用方决定怎么说 —— "选文件失败"和"这个包不合格"是两句不同的话。
     */
    suspend fun pick(): PickedFile? = picker.pickPackage()

    /** 记下这次处理的是哪个文件；包类型由组合根判定，这里只负责显示。 */
    fun notePickedFile(name: String) {
        pickedFileName = name
    }

    /**
     * 把一条失败原因写进导入页的行内错误。
     *
     * 扩展包校验失败时也会落在这里：用户是在导入页拖进来的，原因就该显示在他眼前，
     * 而不是跳回首页再弹一句"未通过校验"。文案仍由组合根写 —— 这里只提供位置。
     */
    fun showError(message: String) {
        error = message
    }

    /**
     * 导入 `.qbank` 包。
     *
     * 与文本不同：包内已由作者声明题型、答案与判题策略，且已通过 Schema 校验，
     * 因此**不做题型映射**，也**不允许部分成功** —— 校验器 fail-closed，错一条整包拒绝。
     */
    suspend fun importQbank(name: String, bytes: ByteArray): ImportVerdict {
        if (!useCase.looksLikeQbank(bytes)) {
            // 兜底：能走到这里说明认包那一步认为它是包，但内容不是 ZIP。
            // 说清楚"这是文本的话该贴到哪"，比一句"格式不支持"有用。
            return ImportVerdict.Refused(
                "「$name」不是 .qbank 包（ZIP 格式）；如果这是文本 JSON，请粘贴到输入框。",
            )
        }
        val outcome = useCase.importQbank(
            bytes = bytes,
            fallbackTitle = name.substringBeforeLast('.').ifBlank { name },
            sourceLabel = "qbank:$name",
        )
        return accept(outcome, fromText = false)
    }

    /**
     * 两条来源共用的收尾。
     *
     * [fromText] 决定成功之后要不要清空输入框：清空的是"我刚贴进去的那段",
     * 而包导入时输入框里那段文本与这次导的包无关，替用户删掉等于替他丢东西。
     */
    private fun accept(outcome: ImportOutcome, fromText: Boolean): ImportVerdict = when (outcome) {
        is ImportOutcome.Imported -> {
            preview = outcome.preview
            if (fromText) {
                text = ""
                title = ""
            }
            error = null
            ImportVerdict.Imported(outcome.bank)
        }

        is ImportOutcome.Duplicate -> {
            // 预览留着：用户刚拖进来一个"已经装过"的包，得能看见它到底是什么
            preview = outcome.preview
            ImportVerdict.Duplicate(outcome.existing)
        }

        is ImportOutcome.IdentityConflict -> {
            preview = outcome.preview
            ImportVerdict.IdentityConflict(outcome.existing, outcome.preview.accepted.size)
        }

        is ImportOutcome.NeedsMapping -> {
            preview = outcome.preview
            ImportVerdict.NeedsMapping(outcome.preview.ambiguousCount)
        }

        is ImportOutcome.Rejected -> {
            preview = outcome.preview
            error = "没有可导入的题目，请查看下方问题列表"
            ImportVerdict.Rejected
        }

        is ImportOutcome.Failed -> {
            preview = null
            error = outcome.message
            ImportVerdict.Failed(outcome.message)
        }
    }
}
