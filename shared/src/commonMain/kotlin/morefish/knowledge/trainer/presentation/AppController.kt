package morefish.knowledge.trainer.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.AiBatchRepository
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.application.port.AiRepository
import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.DataVault
import morefish.knowledge.trainer.application.port.ExtensionRepository
import morefish.knowledge.trainer.application.port.FavoriteRepository
import morefish.knowledge.trainer.application.port.FilePickException
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.GraphRepository
import morefish.knowledge.trainer.application.port.InMemoryMediaStore
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.MediaStore
import morefish.knowledge.trainer.application.port.NoteRepository
import morefish.knowledge.trainer.application.port.PreferencesStore
import morefish.knowledge.trainer.application.port.QuestionLocation
import morefish.knowledge.trainer.application.port.RevisionRepository
import morefish.knowledge.trainer.application.port.RestoreRecovery
import morefish.knowledge.trainer.application.port.ReviewRepository
import morefish.knowledge.trainer.application.port.SessionRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.application.port.UnsupportedDataVault
import morefish.knowledge.trainer.application.port.UnavailableFilePicker
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.AiGenerationOutcome
import morefish.knowledge.trainer.application.usecase.ApplyPatchUseCase
import morefish.knowledge.trainer.application.usecase.BackupExportOutcome
import morefish.knowledge.trainer.application.usecase.CreateBackupUseCase
import morefish.knowledge.trainer.application.usecase.InspectBackupUseCase
import morefish.knowledge.trainer.application.usecase.RestoreBackupUseCase
import morefish.knowledge.trainer.application.usecase.PatchIntake
import morefish.knowledge.trainer.application.usecase.PatchOutcome
import morefish.knowledge.trainer.application.usecase.ReviewHistoryUseCase
import morefish.knowledge.trainer.application.usecase.ExtensionOutcome
import morefish.knowledge.trainer.application.usecase.GenerateExplanationUseCase
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportExtensionUseCase
import morefish.knowledge.trainer.application.usecase.PlanPracticeUseCase
import morefish.knowledge.trainer.application.usecase.RunBatchUseCase
import morefish.knowledge.trainer.application.usecase.SessionLifecycle
import morefish.knowledge.trainer.application.usecase.StartPracticeSessionUseCase
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.domain.backup.auditReferences
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionState
import morefish.knowledge.trainer.domain.extension.extensionOwnerOf
import morefish.knowledge.trainer.domain.extension.stateIn
import morefish.knowledge.trainer.domain.grading.builtInGradingRegistry
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.WrongQuestionEntry
import morefish.knowledge.trainer.domain.patch.BankRevision
import morefish.knowledge.trainer.domain.patch.PatchApply
import morefish.knowledge.trainer.domain.patch.PatchChangeKind
import morefish.knowledge.trainer.domain.practice.QuestionKey
import morefish.knowledge.trainer.domain.practice.StudyStats
import morefish.knowledge.trainer.presentation.ai.AiController
import morefish.knowledge.trainer.presentation.ai.CurrentQuestion
import morefish.knowledge.trainer.presentation.batch.BatchController
import morefish.knowledge.trainer.presentation.backup.BackupController
import morefish.knowledge.trainer.presentation.backup.BackupExportVerdict
import morefish.knowledge.trainer.presentation.backup.BackupIntakeVerdict
import morefish.knowledge.trainer.presentation.backup.BackupRead
import morefish.knowledge.trainer.presentation.backup.BackupUnlockVerdict
import morefish.knowledge.trainer.presentation.backup.PackResult
import morefish.knowledge.trainer.presentation.backup.PackedBackup
import morefish.knowledge.trainer.presentation.backup.RestoreVerdict
import morefish.knowledge.trainer.presentation.editor.QuestionEditorController
import morefish.knowledge.trainer.presentation.graph.GraphController
import morefish.knowledge.trainer.presentation.graph.GraphWrite
import morefish.knowledge.trainer.presentation.history.HistoryController
import morefish.knowledge.trainer.presentation.importing.ImportController
import morefish.knowledge.trainer.presentation.importing.ImportVerdict
import morefish.knowledge.trainer.presentation.notes.NoteController
import morefish.knowledge.trainer.presentation.notes.NoteDelete
import morefish.knowledge.trainer.presentation.practice.PracticeController
import morefish.knowledge.trainer.presentation.practice.PracticeResume
import morefish.knowledge.trainer.presentation.review.ReviewController
import morefish.knowledge.trainer.presentation.theme.ThemeController
import morefish.knowledge.trainer.presentation.setup.PracticeSetupController
import morefish.knowledge.trainer.presentation.setup.SetupStart
import morefish.knowledge.trainer.presentation.toolbox.ToolboxController
import morefish.knowledge.trainer.infrastructure.ai.OpenAiCompatibleProvider
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.infrastructure.importing.PackageFormat
import morefish.knowledge.trainer.infrastructure.importing.PackageProbe
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.backup.BackupArchive
import morefish.knowledge.trainer.infrastructure.storage.JsonAiRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonAiBatchRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonExtensionRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonFavoriteRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonGraphRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonNoteRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonPreferencesRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonRevisionRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonReviewRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonSessionRepository

/**
 * 组合根：所有依赖显式注入，没有全局可变单例。
 * 数据放在什么介质完全由入口传入的 [TextStorage] 决定，
 * 因此同一套仓储实现可以跑在桌面文件、Android 私有目录或内存里。
 */
class AppDependencies(
    val storage: TextStorage,
    val clock: WallClock = WallClock.System,
    val persistentStorage: Boolean = storage.isPersistent,
    val filePicker: FilePicker = UnavailableFilePicker,
    /** 媒体字节仓库；未显式传入时用内存实现（图片本次会话内可见，重启丢失）。 */
    val mediaStore: MediaStore = InMemoryMediaStore(),
    /**
     * AI 的 HTTP 传输。null 表示这个平台没有接入联网能力 ——
     * 桌面端注入真实实现，其余平台保持 null，AI 页会如实显示"当前平台不支持"，
     * 而不是弹一个点了没反应的按钮。
     */
    val aiHttpClient: AiHttpClient? = null,
    /**
     * 数据目录级读写，只用于备份/恢复。
     *
     * 默认是"不支持"：只有数据真的落在磁盘上的平台才注入实现（桌面端）。
     * Web / iOS 的数据在内存里，备份这个动作在那些平台上**没有意义**，
     * 界面上会如实写原因，而不是给一个点了没反应的按钮。
     */
    val vault: DataVault = UnsupportedDataVault,
    /**
     * 启动时收拾"上一次被打断的恢复"的结果。
     *
     * 由平台入口在**读任何数据之前**执行（[DataVault.recoverInterrupted]）并传进来。
     * 放在这里而不是让控制器自己去调：控制器构造时窗口已经在画了，
     * 那时读到的可能是"空数据"，用户会先看见一次"东西全没了"。
     *
     * null = 上次恢复是干净的（绝大多数时候）。
     */
    val restoreRecovery: RestoreRecovery? = null,
) {
    val bankRepository: BankRepository = JsonBankRepository(storage)
    val attemptRepository: AttemptRepository = JsonAttemptRepository(storage)
    val sessionRepository: SessionRepository = JsonSessionRepository(storage)
    val favoriteRepository: FavoriteRepository = JsonFavoriteRepository(storage)
    val noteRepository: NoteRepository = JsonNoteRepository(storage)
    val graphRepository: GraphRepository = JsonGraphRepository(storage)
    val extensionRepository: ExtensionRepository = JsonExtensionRepository(storage)
    val aiRepository: AiRepository = JsonAiRepository(storage)
    val aiBatchRepository: AiBatchRepository = JsonAiBatchRepository(storage)
    val revisionRepository: RevisionRepository = JsonRevisionRepository(storage)
    val reviewRepository: ReviewRepository = JsonReviewRepository(storage)

    /**
     * 界面偏好（目前只有主题）。
     *
     * 它和业务数据一样存在数据目录里，因此进备份、也跟着恢复：
     * 换机器之后"这台机器长什么样"也该一起搬过去。
     */
    val preferences: PreferencesStore = JsonPreferencesRepository(storage)

    companion object {
        /** 内存组合根：供 Preview 与不支持文件系统的平台使用。 */
        fun inMemory(): AppDependencies = AppDependencies(InMemoryTextStorage())
    }
}

enum class Screen {
    HOME, IMPORT, PRACTICE, PRACTICE_SETUP, SUMMARY, HISTORY, WRONG,

    /** 离线手工编辑：在既有题库内新建题目（个人扩展）。 */
    QUESTION_EDITOR,

    /** 题库标准 / AI 制包 Skill / 示例包与校验工具：三块纯本地说明，无联网无账号。 */
    STANDARD, SKILL, TOOLS,

    /** 个人笔记列表与编辑器。 */
    NOTES, NOTE_EDITOR,

    /** 工具箱：离线科学计算器与单位换算。与 [TOOLS]（题库工具的说明页）不是一件事。 */
    TOOLBOX,

    /** 个人知识图谱：跨题库手工建立的关系，以及关系两端的缺失状态。 */
    GRAPH, GRAPH_EDITOR,

    /** 已安装的扩展包（`.qbext`）：绑定某个题库的跨库关系与讲解。 */
    EXTENSIONS,

    /** AI 讲解的接入配置、今日用量与记录。整块可选：没配置时其余功能照常。 */
    AI,

    /** 批量生成队列：持久化、可暂停/继续/取消，仍受每日预算约束。 */
    AI_BATCH,

    /** 题库更新补丁的预览：改哪几道、哪几道答案变了、历史里有多少条相关记录。 */
    PATCH,

    /** 历史复核：答案改了之后，由用户逐条决定旧记录按新标准还是按当时的标准算。 */
    REVIEW,

    /** 备份与迁移（`.qbk`）：导出本机全部数据、或用一份备份整体恢复。 */
    BACKUP,
}

data class Banner(val text: String, val isError: Boolean)

/**
 * 单屏状态机。刻意不做"千行 ViewModel"：这里只负责串联用例与 UI 状态，
 * 判题、会话推进、抽题、错题聚合全部在领域/应用层完成。
 */
class AppController(
    private val deps: AppDependencies,
    private val scope: CoroutineScope,
) {
    private val startPractice = StartPracticeSessionUseCase(deps.clock)
    private val planPractice = PlanPracticeUseCase(startPractice)
    private val submitAnswer = SubmitAnswerUseCase(
        attempts = deps.attemptRepository,
        grading = builtInGradingRegistry(),
        clock = deps.clock,
    )
    private val lifecycle = SessionLifecycle(deps.sessionRepository, deps.attemptRepository, deps.clock)
    private val importExtension = ImportExtensionUseCase(deps.extensionRepository, deps.clock)
    private val generateExplanation = GenerateExplanationUseCase(
        repo = deps.aiRepository,
        provider = deps.aiHttpClient?.let { OpenAiCompatibleProvider(it) },
        clock = deps.clock,
    )
    private val reviewHistory = ReviewHistoryUseCase(
        banks = deps.bankRepository,
        attempts = deps.attemptRepository,
        reviews = deps.reviewRepository,
        clock = deps.clock,
    )
    private val applyPatch = ApplyPatchUseCase(
        banks = deps.bankRepository,
        revisions = deps.revisionRepository,
        attempts = deps.attemptRepository,
        clock = deps.clock,
    )
    private val runBatch = RunBatchUseCase(
        repo = deps.aiRepository,
        batches = deps.aiBatchRepository,
        generate = generateExplanation,
        clock = deps.clock,
    )

    /**
     * 备份的三个用例共用同一个 [BackupArchive] 实例：
     * 它只是个无状态的读写器，共用只是为了让"写出去的格式"与"读回来的格式"是同一份代码。
     */
    private val backupArchive = BackupArchive()
    private val createBackup = CreateBackupUseCase(
        vault = deps.vault,
        archive = backupArchive,
        clock = deps.clock,
    )
    private val inspectBackup = InspectBackupUseCase(backupArchive)
    private val restoreBackup = RestoreBackupUseCase(deps.vault, backupArchive)

    var screen by mutableStateOf(Screen.HOME)
        private set

    /** 说明页的返回目标：从导入页进来就回导入页，从首页进来就回首页。 */
    private var standardReturn by mutableStateOf(Screen.HOME)

    var banks by mutableStateOf<List<QuestionBank>>(emptyList())
        private set

    var history by mutableStateOf<List<AttemptRecord>>(emptyList())
        private set

    /**
     * 主题选择（跟随系统 / 浅色 / 深色）。
     *
     * 它不改任何业务数据，也不参与导航 —— 是这棵树上唯一一个"只影响观感"的状态。
     * 放在组合根上的理由和别处一样：它要写盘，而写盘是组合根的职责。
     */
    val theme = ThemeController(store = deps.preferences, scope = scope)

    /**
     * 历史页的筛选与派生视图（日历、错题本）。
     *
     * 名字带 `View` 是为了不跟上面那份原始列表撞车：`history` 是**数据**（共享内核，
     * 统计、会话恢复、复核都要用），`historyView` 是**看它的方式**。
     * 它拿的是 `history` 的读取器而不是快照，所以不会出现两份历史。
     */
    val historyView = HistoryController(clock = deps.clock) { history }

    /**
     * 历史复核：待复核清单、逐条表态、首页徽章。
     *
     * 它同时保管"原始历史"与"复核决定"两份数据，[readHistory] 负责把它们合并成
     * 生效历史 —— 合并只在这一处发生。以前 `loadAll` 与 `reloadHistory` 各写一行，
     * 漏掉任何一处，统计与错题本就会停在旧判定上而界面看不出区别。
     */
    val review = ReviewController(
        useCase = reviewHistory,
        attempts = deps.attemptRepository,
        reviews = deps.reviewRepository,
        scope = scope,
        latestRevisions = { latestRevisions },
        onHistoryChanged = { reloadHistory() },
        onNotice = { text, isError -> banner = Banner(text, isError) },
    )

    /** 打开复核页：清单要读题库内容，所以这时候才真的重算一遍。 */
    fun openReview() {
        screen = Screen.REVIEW
        review.scan()
    }

    fun closeReview() {
        // 清单留着不清：用户可能是去题库里看一眼再回来，重扫一遍会让他重新等一次
        goHome()
    }

    /**
     * AI 讲解：配置、用量、记录与审核。
     *
     * 它是目前唯一**自己写盘**的子控制器 —— 配置、密钥、讲解记录、用量是本功能自己的
     * 数据。当前题目则相反，用读取器从会话里现取：切题、作答都发生在组合根里，
     * 快照会让"题目换了、讲解还挂着上一题"，而缓存键按题目算，错配就是白花一次钱。
     */
    val ai = AiController(
        repository = deps.aiRepository,
        generate = generateExplanation,
        clock = deps.clock,
        scope = scope,
        available = deps.aiHttpClient != null,
        currentQuestion = ::aiTargetOf,
        onNotice = { text, isError -> banner = text?.let { Banner(it, isError) } },
    )

    /** 打开 AI 页。刷新放在这里而不是构造时：配置可能被另一个窗口改过。 */
    fun openAi() {
        screen = Screen.AI
        scope.launch { refreshAi() }
    }

    fun closeAi() {
        screen = Screen.HOME
    }

    /**
     * 会话里的"当前这题"，整理成 AI 侧认识的样子。
     *
     * 这里只做搬运与匹配，不做判断：语言回退、能不能生成、生成后怎么记，都在
     * [AiController] 与用例里。作答记录要按 questionId 再对一次 ——
     * 提交反馈与当前题目之间隔着一次"下一题"，直接拿来开会把上一题的答案发出去。
     */
    private fun aiTargetOf(): CurrentQuestion? {
        val active = practice.session ?: return null
        val question = active.current?.question ?: return null
        return CurrentQuestion(
            bankId = active.bankId,
            question = question,
            sessionLocale = active.locale,
            bank = banks.firstOrNull { it.bankId == active.bankId },
            attempt = practice.feedback?.attempt?.takeIf { it.questionId == question.questionId },
        )
    }

    var studyStats by mutableStateOf(StudyStats.Empty)
        private set

    var favorites by mutableStateOf<Set<QuestionKey>>(emptySet())
        private set

    var banner by mutableStateOf<Banner?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    // ---- 笔记 ----

    /**
     * 个人笔记：全部笔记、编辑器种子、校验结论与写盘。
     *
     * 它是第二条"自己写盘"的分支（第一条是 AI 讲解）：笔记只属于用户自己，
     * 换谁去写都是两份真相。而"编辑时沿用原 noteId 与 createdAt""校验不过就不落盘"
     * 这两条规则必须和写盘待在一起 —— 所以整块搬进 [NoteController]。
     *
     * 组合根只留导航：进列表、进编辑器、返回目标，以及删除成功之后去哪一屏。
     */
    val notes = NoteController(
        repository = deps.noteRepository,
        clock = deps.clock,
        scope = scope,
    )

    /** 编辑器返回目标：从练习页进来就回练习页，而不是把人丢进笔记列表。 */
    private var noteReturn by mutableStateOf(Screen.NOTES)

    val storageWarning: String? get() = if (deps.persistentStorage) null else "当前平台为内存存储，重启后题库、进度与历史会丢失"

    // ---- 批量队列 ----

    /**
     * 批量生成队列：建、跑、暂停、取消、删除。
     *
     * 它是唯一长时间在后台推进的功能，所以"叫停"这件事必须由它自己说明白：
     * 用户点暂停只是写下意图，任务状态由执行器在请求边界上收尾。
     */
    val batch = BatchController(
        useCase = runBatch,
        repository = deps.aiBatchRepository,
        bankRepository = deps.bankRepository,
        clock = deps.clock,
        scope = scope,
        config = { ai.config },
        banks = { banks },
        onJobFinished = { refreshAi() },
        onNotice = { text, isError -> banner = Banner(text, isError) },
    )

    fun openBatch() {
        screen = Screen.AI_BATCH
        scope.launch { batch.refresh() }
    }

    fun closeBatch() {
        screen = Screen.AI
    }

    // ---- 题库更新（.qbpatch）----

    /** 正在预览的补丁：读文件之后、点应用之前。null 表示没有。 */
    var patchIntake by mutableStateOf<PatchIntake?>(null)
        private set

    /** 各题库最近一次更新记录。首页据此标"第 2 版"，修订日志页据此列历史。 */
    var latestRevisions by mutableStateOf<Map<String, BankRevision>>(emptyMap())
        private set

    /**
     * 重读历史并重算派生状态。
     *
     * 刻意比 [refresh] 窄：不重读题库、不改导航、不动正在看的复核清单 ——
     * 复核是"逐条处理"的动作，每点一下就把整页重来一遍会让人没法一条条看完。
     */
    private suspend fun reloadHistory() {
        history = review.readHistory()
        studyStats = StudyStats.derive(history)
        // 按语言缓存的那份统计作废：否则设置页会拿复核前的"未练习"数去抽题
        setup.invalidateStats()
        // 复核生效后错题本与日历都要重算：它们的输入是"已合并复核结果的历史"
        historyView.update(history)
    }

    /**
     * 读一个更新补丁并算出"会改什么"。
     *
     * 只读不写：这一趟的全部产出是一个预览。用户在看见"改三道题、其中两道答案变了、
     * 你练过七次"之前，不应该有任何东西被改掉。
     */
    fun openPatch(bytes: ByteArray, name: String) {
        screen = Screen.PATCH
        scope.launch {
            busy = true
            val intake = applyPatch.intake(bytes)
            busy = false
            patchIntake = intake
            when (intake) {
                is PatchIntake.Rejected -> banner = Banner(
                    "「$name」不是可用的更新补丁，什么都没有改：" +
                        (intake.issues.firstOrNull()?.message ?: "格式不符"),
                    isError = true,
                )

                is PatchIntake.BankMissing -> banner = Banner(
                    "这台机器上没有这个补丁要更新的题库（它要求第 ${intake.patch.baseRevision} 版的内容）。" +
                        "先装上那个题库的完整包，再来应用补丁。",
                    isError = true,
                )

                is PatchIntake.Ready -> if (intake.warnings.isNotEmpty()) {
                    banner = Banner(intake.warnings.first(), isError = false)
                }
            }
        }
    }

    fun closePatch() {
        patchIntake = null
        goHome()
    }

    /**
     * 应用预览过的补丁。
     *
     * 只有 `PatchApply.Ok` 才允许调用 —— 其余三种状态在界面上没有对应按钮，
     * 这里再拦一次是因为"能点"和"该点"是两件事。
     */
    fun applyPickedPatch() {
        val ready = patchIntake as? PatchIntake.Ready ?: return
        if (ready.plan !is PatchApply.Ok) return
        scope.launch {
            busy = true
            val outcome = applyPatch.apply(ready.patch)
            busy = false
            when (outcome) {
                is PatchOutcome.Applied -> {
                    patchIntake = null
                    refresh()
                    goHome()
                    banner = Banner(
                        buildString {
                            append("题库《${outcome.bank.title}》已更新到第 ${outcome.report.afterRevision} 版：")
                            append("新增 ${outcome.report.added}、修改 ${outcome.report.updated}、删除 ${outcome.report.removed}")
                            val answers = outcome.report.answerChanges
                            if (answers.isNotEmpty()) {
                                append("。其中 ").append(answers.size).append(" 道题的答案变了")
                                if (outcome.revision.affectedAttempts > 0) {
                                    append("，你有 ").append(outcome.revision.affectedAttempts)
                                    append(" 条历史记录是按旧答案判的 —— 那些记录一条都没改。")
                                    append("要不要按新答案复核，由你决定（首页「复核」）")
                                }
                            }
                        },
                        isError = false,
                    )
                }

                is PatchOutcome.NothingToDo -> {
                    patchIntake = null
                    goHome()
                    banner = Banner(outcome.reason, isError = false)
                }

                is PatchOutcome.Refused -> banner = Banner(
                    "没有更新：${outcome.conflicts.firstOrNull()?.message ?: "补丁与本地题库对不上"}",
                    isError = true,
                )

                is PatchOutcome.Failed -> banner = Banner(outcome.message, isError = true)
            }
        }
    }

    // ---- 首次加载 ----

    fun refresh() {
        scope.launch { loadAll() }
    }

    /**
     * 一次刷新同时喂饱两处：讲解（配置 / 密钥 / 用量 / 记录）与批量队列。
     *
     * 它们读的是同一个仓库、同一个时刻，分两次刷新只会让界面先看到一半新数据。
     * 等批量队列也搬出去，这里改成调它的 `refresh()` 即可，调用点不用再动。
     */
    private suspend fun refreshAi() {
        ai.refresh()
        batch.refresh()
    }

    /**
     * 把"这台机器上的一切"重新读一遍。
     *
     * 单独拆出来是为了能被 **await**：恢复之后必须先读完，才谈得上"最后解析引用"。
     * 用即发即忘的 [refresh] 去接自检，自检读到的可能还是恢复之前那份内存 ——
     * 结论会是一句看着很确定的谎话。
     *
     * 读的顺序有依赖：题库 → 扩展包 → 图谱（要合并扩展包里那些关系）→ 历史。
     * 产品总纲要求恢复后"先题库、后扩展与图谱、再历史、最后解析引用"：
     * 文件是整体换上去的，不存在"题库到了、图谱没到"的中间态；
     * 但**读**的顺序仍要保证图谱看到的是已经装好的扩展包。
     */
    private suspend fun loadAll() {
        // 主题先读：它是唯一影响"这一帧怎么画"的状态，晚一步就会先闪一下浅色
        theme.load()
        banks = deps.bankRepository.list()
        latestRevisions = deps.revisionRepository.latestAll()
        // 历史读进来就套上复核结果：统计、错题本、日历因此不需要知道复核存在
        history = review.readHistory()
        // 题库变了（装了补丁 / 新建了题），上一份复核清单就作废，进页面时重扫
        review.invalidate()
        studyStats = StudyStats.derive(history)
        // 历史变了，按语言缓存的那份统计就作废：否则设置页会拿旧的"未练习"数去抽题
        setup.invalidateStats()
        favorites = deps.favoriteRepository.all()
        notes.reload()
        // 扩展包先于图谱读：图谱视图要从"生效中的包"合并关系
        extensions = deps.extensionRepository.all()
        // 图谱只是一份引用清单，读它很便宜；练习页要知道"本题的硬依赖还在不在"
        graph.reload()
        // 会话文件只存题目与游标；已答数量从上面这份历史合并，避免两处各存一份而失配。
        practice.reconcileResumable(history)
        // 日历与错题本按全量历史重算（不受历史页筛选影响）
        historyView.update(history)
        refreshAi()
        val warning = deps.bankRepository.loadWarnings.firstOrNull()
            ?: deps.sessionRepository.loadWarnings.firstOrNull()
            ?: deps.attemptRepository.loadWarnings.firstOrNull()
            ?: deps.reviewRepository.loadWarnings.firstOrNull()
            ?: deps.noteRepository.loadWarnings.firstOrNull()
            ?: deps.extensionRepository.loadWarnings.firstOrNull()
            ?: deps.graphRepository.loadWarnings.firstOrNull()
            ?: deps.aiRepository.loadWarnings.firstOrNull()
            ?: deps.aiBatchRepository.loadWarnings.firstOrNull()
            ?: deps.revisionRepository.loadWarnings.firstOrNull()
            // 排最后：主题读不出来只是"回到跟随系统"，不该盖掉任何一条关于数据的警告
            ?: deps.preferences.loadWarnings.firstOrNull()
        if (warning != null) banner = Banner(warning, isError = false)
        // 装了扩展包但题库还没到：这不是错误，但用户有权知道"它为什么没反应"
        if (banner == null && pendingExtensions.isNotEmpty()) {
            val first = pendingExtensions.first()
            banner = Banner(
                "有 ${pendingExtensions.size} 个扩展包在等题库《${extensionBoundaryLabel(first)}》装好" +
                    "（装上后自动生效，不需要重装扩展包）",
                isError = false,
            )
        }
        // 上次恢复被打断：只说一次，且不挤掉上面那些更要紧的加载警告。
        // 备份页上有固定的卡片，错过这条横幅也还能找回来。
        // 「只说一次」是备份控制器记的：横幅位归谁由这里定，说没说过由那里定。
        val recovery = backup.takeRecoveryNotice()
        if (banner == null && recovery != null) {
            banner = Banner(recovery.text, isError = recovery.isError)
        }
    }

    fun dismissBanner() {
        banner = null
    }

    // ---- 导航 ----

    /**
     * 回首页。进行中的会话不是"被丢弃"，而是暂停：
     * 保存游标与草稿，首页出现"继续练习"入口。真正丢弃必须显式点"放弃"。
     */
    fun goHome() {
        practice.leave()
        screen = Screen.HOME
    }

    fun pauseAndExit() {
        if (!practice.pauseAndExit()) return
        banner = Banner("已暂停。回到首页可以接着做，进度与草稿都已保存。", isError = false)
        screen = Screen.HOME
    }

    fun openImport(sample: String) {
        importing.open(sample)
        screen = Screen.IMPORT
    }

    /** 打开题库标准说明；returnTo 记录从哪来，返回时回到原处。 */
    fun openStandard(target: Screen, from: Screen = screen) {
        standardReturn = from
        screen = target
    }

    fun closeStandard() {
        screen = standardReturn
    }

    fun openHistory() {
        screen = Screen.HISTORY
        refresh()
    }

    fun openWrong() {
        screen = Screen.WRONG
        refresh()
    }

    // ---- 个人笔记 ----
    //
    // 这里只剩导航：进列表、进编辑器、返回目标，以及删除成功之后去哪一屏。
    // 校验、时间戳、写盘全在 [NoteController] 里 —— 那几件事分开写就没人能确认它们一起发生。

    fun openNotes(bankId: String? = null) {
        notes.filterByBank(bankId)
        screen = Screen.NOTES
        scope.launch { notes.reload() }
    }

    fun closeNotes() {
        notes.clearFilter()
        screen = Screen.HOME
    }

    fun openNoteEditor(noteId: String) {
        val note = notes.byId(noteId) ?: return
        if (screen != Screen.NOTE_EDITOR) noteReturn = screen
        notes.beginEdit(note)
        screen = Screen.NOTE_EDITOR
    }

    /** 新建笔记。关联在种子里就钉死，进入编辑器后不会再变。 */
    fun openNewNote(scope: NoteScope = NoteScope.DRAFT, bankId: String? = null, questionId: String? = null) {
        if (screen != Screen.NOTE_EDITOR) noteReturn = screen
        notes.beginNew(scope, bankId, questionId)
        screen = Screen.NOTE_EDITOR
    }

    /** 练习页入口：同一题已有笔记就去编辑那一条，没有才新建。 */
    fun openNoteForCurrentQuestion() {
        val active = practice.session ?: return
        val question = active.current?.question ?: return
        val existing = notes.forQuestion(active.bankId, question.questionId)
        if (existing != null) openNoteEditor(existing.noteId) else {
            openNewNote(NoteScope.QUESTION, active.bankId, question.questionId)
        }
    }

    fun closeNoteEditor() {
        notes.clearSeed()
        screen = noteReturn
        scope.launch { notes.reload() }
    }

    /** 删除之后回列表页并说一句；"删掉了没有"由控制器给结论，这里只决定去哪。 */
    fun deleteNote(noteId: String) {
        scope.launch {
            if (notes.delete(noteId) == NoteDelete.DELETED) {
                screen = Screen.NOTES
                banner = Banner("笔记已删除", isError = false)
            }
        }
    }

    // ---- 扩展包（.qbext） ----

    /**
     * 已安装的扩展包。
     *
     * 存进文件的只有"装了什么"；**生效与否是现算的** —— 取决于它绑定的题库
     * 此刻在不在本机。所以先装扩展包、后装题库也会自动生效，反过来则自动暂停。
     */
    var extensions by mutableStateOf<List<ExtensionPack>>(emptyList())
        private set

    /** 展开哪个包的注释正文。同一时刻只展开一个：这一页的用途是"读"，不是"管理一大堆"。 */
    var expandedExtensionId by mutableStateOf<String?>(null)
        private set

    var extensionBusy by mutableStateOf(false)
        private set

    private val installedBankIds: Set<String> get() = banks.mapTo(mutableSetOf()) { it.bankId }

    val activeExtensions: List<ExtensionPack>
        get() = extensions.filter { it.stateIn(installedBankIds) == ExtensionState.ACTIVE }

    /** 等题库的包：不是错误，是"先到一步"。首页横幅会提一句。 */
    val pendingExtensions: List<ExtensionPack>
        get() = extensions.filter { it.stateIn(installedBankIds) == ExtensionState.PENDING }

    fun extensionStateOf(pack: ExtensionPack): ExtensionState = pack.stateIn(installedBankIds)

    val extensionRelationCount: Int get() = extensions.sumOf { it.relationCount }

    val extensionAnnotationCount: Int get() = extensions.sumOf { it.annotationCount }

    /** 这条关系来自哪个扩展包？手建的关系返回 null。 */
    fun extensionNameOf(edgeId: String): String? {
        val owner = extensionOwnerOf(edgeId) ?: return null
        return extensions.firstOrNull { it.packageId == owner }?.name ?: owner
    }

    /** 绑定对象的展示名：包里的标题只是提示，UUID 才是身份。 */
    fun extensionBoundaryLabel(pack: ExtensionPack): String =
        pack.binding.bankTitleHint.ifBlank { "题库 ${pack.binding.bankId.take(8)}" }

    fun openExtensions() {
        screen = Screen.EXTENSIONS
        scope.launch {
            banks = deps.bankRepository.list()
            refreshExtensions()
        }
    }

    fun closeExtensions() {
        expandedExtensionId = null
        screen = Screen.HOME
    }

    fun toggleExtensionExpanded(packageId: String) {
        expandedExtensionId = if (expandedExtensionId == packageId) null else packageId
    }

    /** 移除扩展包：只删这个包带来的关系与注释，题库、历史、手建关系一条不动。 */
    fun removeExtension(packageId: String) {
        val pack = extensions.firstOrNull { it.packageId == packageId } ?: return
        scope.launch {
            extensionBusy = true
            importExtension.remove(packageId)
            refreshExtensions()
            graph.rematch()
            extensionBusy = false
            if (expandedExtensionId == packageId) expandedExtensionId = null
            banner = Banner("已移除扩展包《${pack.name}》（题库与历史未受影响）", isError = false)
        }
    }

    /**
     * 重读已装扩展包。
     *
     * 顺带对齐题库目录：包的生效与否取决于绑定的题库在不在，
     * 只读扩展包而不读题库，会让"刚装的题库"看起来没让包生效。
     */
    private suspend fun refreshExtensions() {
        banks = deps.bankRepository.list()
        extensions = deps.extensionRepository.all()
    }

    // ---- 备份与迁移（.qbk） ----

    /**
     * 导出 / 读入 / 解锁 / 恢复的状态与规则都在 [BackupController] 里。
     *
     * 三个用例留在这里、包成 lambda 递进去，不是因为"顺手"：它们的返回类型
     * （`BackupBundle`、`BackupReadResult`）来自 `infrastructure`，而子控制器只认
     * application 与 domain。摘字段这一层本来也只有组合根能做 —— 它是唯一允许
     * 挑具体实现的地方。
     */
    val backup = BackupController(
        vault = deps.vault,
        picker = deps.filePicker,
        now = { deps.clock.nowMillis() },
        pack = { secret ->
            when (val outcome = createBackup.create(secret)) {
                is BackupExportOutcome.Ok -> PackResult.Ok(
                    PackedBackup(
                        fileName = outcome.bundle.fileName,
                        bytes = outcome.bundle.bytes,
                        digest = outcome.bundle.contentDigest,
                        createdAtMillis = outcome.bundle.manifest.createdAtMillis,
                        fileCount = outcome.bundle.manifest.counts.totalFiles,
                        encrypted = outcome.bundle.encrypted,
                        payloadPaths = outcome.bundle.payloadPaths,
                    ),
                )

                is BackupExportOutcome.Failed -> PackResult.Failed(outcome.message)
            }
        },
        read = { bytes, passphrase ->
            val result = inspectBackup.inspect(bytes, passphrase)
            BackupRead(
                preview = result.preview,
                problems = result.problems,
                canRestore = result.canRestore,
                requiresPassphrase = result.requiresPassphrase,
            )
        },
        replace = { bytes, passphrase ->
            when (val outcome = restoreBackup.restore(bytes, passphrase)) {
                is RestoreBackupUseCase.Outcome.Rejected -> RestoreVerdict.Rejected(
                    outcome.problems.firstOrNull()?.message ?: "校验没通过",
                )

                is RestoreBackupUseCase.Outcome.Ok -> RestoreVerdict.Done(
                    filesWritten = outcome.outcome.filesWritten,
                    banks = outcome.preview.manifest.counts.banks,
                    attempts = outcome.preview.manifest.counts.attempts,
                    notes = outcome.preview.manifest.counts.notes,
                    previousLocation = outcome.outcome.previousLocation,
                    payloadPaths = outcome.payloadPaths,
                )
            }
        },
        banks = { banks },
        attemptCount = { review.rawAttempts.size },
        noteCount = { notes.count },
        decisionCount = { review.decisionCount },
        initialRecovery = deps.restoreRecovery,
    )

    fun openBackup() {
        screen = Screen.BACKUP
        backup.enter()
        // 进来就重读一遍并跑一次自检：这一页的每个数字（几个题库、多大、图在不在）
        // 都必须来自"现在"，而不是上一次打开时的那份内存。
        scope.launch {
            backup.working {
                loadAll()
                runBackupAudit()
            }
        }
    }

    fun closeBackup() {
        backup.leave()
        screen = Screen.HOME
    }

    /** 页面上的"重新核对"。先重读一遍数据，否则核对的是一份可能已经过期的内存。 */
    fun recheckBackupAudit() {
        scope.launch {
            backup.working {
                loadAll()
                runBackupAudit()
            }
        }
    }

    /**
     * 跑一次引用自检。
     *
     * [paths] 是"这份数据的文件集合"：导出时传打包进去的那些，恢复时传写进去的那些。
     * 传 null 表示用当前磁盘上的清单（页面上的"重新核对"走这条）。
     * 给不出清单的平台会退回 null，媒体检查因此跳过 —— 而不是把每张图都报成缺失。
     *
     * 算完递给备份控制器：这里同时拿得到题库、合并后的图谱、笔记、扩展包与历史，
     * 而"这份结论属于哪一屏、什么时候该作废"是备份页自己的事。
     */
    private suspend fun runBackupAudit(paths: Set<String>? = null) {
        val available = paths ?: deps.vault.listPaths()
        backup.reportAudit(
            auditReferences(
                banks = banks,
                graph = graph.merged,
                notes = notes.all,
                extensions = extensions,
                history = review.rawAttempts,
                availablePaths = available,
            ),
        )
    }

    fun exportBackup() {
        scope.launch { announceBackupExport(backup.export()) }
    }

    /** 导出之后要顺带重跑一次自检（对着包里那份清单），所以它本身得在协程里。 */
    private suspend fun announceBackupExport(verdict: BackupExportVerdict) {
        when (verdict) {
            is BackupExportVerdict.Blocked -> banner = Banner(verdict.message, isError = true)
            is BackupExportVerdict.Failed -> banner = Banner(verdict.message, isError = true)
            BackupExportVerdict.Cancelled -> banner = Banner("已取消保存", isError = false)
            is BackupExportVerdict.Saved -> {
                // 自检的是**真正打包进去的那份文件清单**，不是"此刻磁盘上有什么"：
                // 两者之间隔着一次打包，结论必须对着包里那份说。
                runBackupAudit(verdict.payloadPaths)
                val auditLine = backup.audit?.let { "引用自检：${it.summary}。" }.orEmpty()
                val info = verdict.info
                banner = if (info.encrypted) {
                    Banner(
                        "已导出加密备份：${info.fileName}（${info.fileCount} 个文件）。" +
                            "口令只有你知道：忘记就打不开，软件没有找回方式。" + auditLine,
                        isError = false,
                    )
                } else {
                    Banner(
                        "已导出备份：${info.fileName}（${info.fileCount} 个文件）。" +
                            "备份没有加密，别放到公共网盘或聊天记录里。" + auditLine,
                        isError = false,
                    )
                }
            }
        }
    }

    /** 选一份备份读进来：只读清单与校验，不动本机数据。 */
    fun pickBackupFile() {
        scope.launch { announceBackupIntake(backup.pick()) }
    }

    /** 拖拽进来的 `.qbk`：与"选文件"走同一条路，只是没有对话框。 */
    fun intakeBackupBytes(name: String, bytes: ByteArray) {
        announceBackupIntake(backup.accept(name, bytes))
    }

    private fun announceBackupIntake(verdict: BackupIntakeVerdict) {
        when (verdict) {
            // 用户什么都没选：不导航，也不留一句"你取消了"。
            BackupIntakeVerdict.Cancelled -> return
            is BackupIntakeVerdict.Failed -> banner = Banner(verdict.message, isError = true)
            // 加密备份：这不是失败，下一步是输口令。用普通提示而不是红条。
            BackupIntakeVerdict.NeedsPassphrase ->
                banner = Banner("已读入一份加密备份：输入口令后可以看到里面有什么", isError = false)
            is BackupIntakeVerdict.Unusable ->
                banner = Banner("这份备份不能用：${verdict.message}", isError = true)
            // 明文且校验通过：清单已经摊开了，不需要再多说一句
            BackupIntakeVerdict.Ready -> Unit
        }
        screen = Screen.BACKUP
    }

    fun unlockBackup(passphrase: String) {
        scope.launch {
            banner = when (val verdict = backup.unlock(passphrase)) {
                is BackupUnlockVerdict.Unlocked -> Banner(
                    "已解锁：${verdict.banks} 个题库、${verdict.attempts} 条历史 —— 看清清单再决定要不要替换本机",
                    isError = false,
                )

                is BackupUnlockVerdict.Failed -> Banner(verdict.message, isError = true)
            }
        }
    }

    /**
     * 确认恢复：整体替换本机数据，然后**重新读一遍全世界**。
     *
     * 恢复之后不重启、不弹"请重新打开应用"：仓储每次读都走存储层，
     * 一次 [loadAll] 就够了 —— 会话、笔记、图谱、扩展、AI 记录都在里面。
     */
    fun confirmRestore() {
        scope.launch {
            when (val verdict = backup.restore()) {
                is RestoreVerdict.Rejected -> banner = Banner(
                    "没有恢复：${verdict.message}",
                    isError = true,
                )

                is RestoreVerdict.Done -> {
                    backup.working {
                        loadAll()
                        // 总纲说的"最后解析引用"就是这一句：数据落地之后，
                        // 拿刚写进去的那份文件清单核对一遍，告诉用户"有没有东西没接上"。
                        runBackupAudit(verdict.payloadPaths)
                    }
                    screen = Screen.HOME
                    val where = verdict.previousLocation
                    banner = Banner(
                        "已恢复 ${verdict.filesWritten} 个文件：" +
                            "题库 ${verdict.banks}、" +
                            "历史 ${verdict.attempts} 条、" +
                            "笔记 ${verdict.notes} 条。 " +
                            "引用自检：${backup.audit?.summary ?: "没有核对"}。" +
                            (if (where != null) " 原来的数据已移到 $where，需要退回去就把它改回原位" else ""),
                        isError = false,
                    )
                }
            }
        }
    }

    // ---- 个人知识图谱 ----

    /**
     * 图谱本体、合并视图、筛选与编辑器草稿都在 [GraphController] 里。
     * 这里只留导航：进出图谱页 / 编辑器的 `screen`，以及"写完之后要不要离开编辑器"。
     */
    val graph = GraphController(
        graphs = deps.graphRepository,
        bankRepository = deps.bankRepository,
        clock = deps.clock,
        scope = scope,
        banks = { banks },
        bankTitleOf = ::bankTitle,
        activeExtensions = { activeExtensions },
    )

    /** 图谱返回目标：从练习页进来就回练习页。 */
    private var graphReturn by mutableStateOf(Screen.HOME)

    /** 编辑器返回目标：从图谱列表进编辑器回列表，从别处进就回原处。 */
    private var graphEditorReturn by mutableStateOf(Screen.GRAPH)

    fun openGraph(from: Screen = screen, focus: QuestionRef? = null) {
        if (screen != Screen.GRAPH) graphReturn = from
        graph.open(focus)
        screen = Screen.GRAPH
        scope.launch {
            // 题库可能刚导入过：先对齐目录，否则"题库未安装"会误报
            banks = deps.bankRepository.list()
            graph.reload()
        }
    }

    fun closeGraph() {
        graph.resetFilters()
        screen = graphReturn
    }

    /** 练习页入口：看这道题已有的关系，返回时回到同一道题。 */
    fun openGraphForCurrentQuestion() {
        val ref = currentSessionRef() ?: return
        openGraph(from = Screen.PRACTICE, focus = ref)
    }

    /**
     * 打开关系编辑器。[source] 为空时用当前筛选的题目做起点 ——
     * 从某道题进来，默认就该从这道题连出去。
     *
     * "这条关系来自《某包》"要查包清单，属于组合根的事：子控制器不认识扩展包的名字。
     */
    fun openGraphEditor(edgeId: String? = null, source: QuestionRef? = null) {
        val owner = edgeId?.let { extensionNameOf(it) }
        if (owner != null) {
            banner = Banner("这条关系来自扩展包《$owner》，要改它请改包后重新安装；移除扩展包即可去掉它", isError = true)
            return
        }
        if (screen != Screen.GRAPH_EDITOR) {
            graphEditorReturn = if (screen == Screen.GRAPH || screen == Screen.GRAPH_EDITOR) Screen.GRAPH else screen
        }
        graph.beginEdit(edgeId, source ?: graph.questionFilter)
        screen = Screen.GRAPH_EDITOR
    }

    fun closeGraphEditor() {
        graph.cancelEdit()
        screen = graphEditorReturn
    }

    fun saveGraphEdge() {
        scope.launch { graph.saveEdge().announce() }
    }

    fun deleteGraphEdge(edgeId: String) {
        val owner = extensionNameOf(edgeId)
        if (owner != null) {
            banner = Banner("这条关系来自扩展包《$owner》，请在扩展包页移除整个包", isError = true)
            return
        }
        scope.launch { graph.deleteEdge(edgeId).announce() }
    }

    fun revalidateGraphEdge(edgeId: String) {
        if (!graph.ownsEdge(edgeId)) {
            // 扩展包带来的关系不按版本复核：包自带版本号，重装新版即可
            if (extensionNameOf(edgeId) != null) {
                banner = Banner("扩展包带来的关系不按版本复核：包自带版本号，重装新版即可", isError = true)
            }
            return
        }
        scope.launch { graph.revalidate(edgeId).announce() }
    }

    /**
     * 写盘之后的收尾：说一句，并且只在"当前停在编辑器"时才离开。
     *
     * 删除在列表页与编辑页都能点到：列表页删完还留在列表，编辑页删完必须退出去，
     * 否则留在一条已经不存在的记录上。同一条规则用在保存上刚好也成立。
     */
    private fun GraphWrite.announce() {
        when (this) {
            is GraphWrite.Saved -> {
                if (screen == Screen.GRAPH_EDITOR) screen = graphEditorReturn
                banner = Banner(message, isError = false)
            }

            is GraphWrite.Blocked -> if (message != null) banner = Banner(message, isError = true)
        }
    }


    /** 当前作答中的题目引用；不在练习页就是 null。 */
    private fun currentSessionRef(): QuestionRef? {
        val active = practice.session ?: return null
        val question = active.current?.question ?: return null
        return QuestionRef(active.bankId, question.questionId)
    }

    /**
     * 练习时检查本题的判题硬依赖。
     *
     * 只有"本题为起点 + 硬依赖"才需要看；没有这类关系就直接归零，不碰磁盘。
     * 算出来的数字交给练习控制器显示 —— 只有组合根同时看得到图谱关系与题库修订号，
     * 而"换题就归零"是练习页的时间规则。
     */
    private fun checkHardDependencies() {
        val ref = currentSessionRef()
        val targets = ref?.let { source ->
            graph.merged.edges
                .filter { it.source == source && it.dependency == DependencyKind.HARD }
                .map { it.target }
                .distinct()
        }.orEmpty()

        if (targets.isEmpty()) {
            practice.reportBlockedDependencies(0)
            return
        }

        scope.launch {
            val located = deps.bankRepository.locate(targets)
            practice.reportBlockedDependencies(
                targets.count { target ->
                    val fact = located[target] ?: return@count true
                    val snapshot = graph.merged.snapshotOf(target)
                    snapshot != null && snapshot.contentRevision != fact.contentRevision
                },
            )
        }
    }

    // ---- 工具箱 ----

    /** 工具箱返回目标：从练习页进来就回练习页，不会把人踢回首页。 */
    private var toolboxReturn by mutableStateOf(Screen.HOME)

    /**
     * 计算器与单位换算的状态与行为在 [ToolboxController] 里。
     * 这里只留导航：进出工具箱属于路由，不属于工具箱自己。
     */
    val toolbox = ToolboxController()

    fun openToolbox(from: Screen = screen) {
        if (screen != Screen.TOOLBOX) toolboxReturn = if (from == Screen.PRACTICE) Screen.PRACTICE else from
        if (from == Screen.PRACTICE) practice.markToolUsed()
        screen = Screen.TOOLBOX
    }

    fun closeToolbox() {
        screen = toolboxReturn
    }

    // ---- 练习设置 ----

    /**
     * 抽题参数与那套"按语言算"的统计。
     *
     * 放在这里是因为导航要它：`openSetup` 打开页面、`startPlannedPractice` 拿到会话后
     * 还要决定横幅与跳转。**会话本身由它造、由这里接**，它不碰 `screen` 也不写盘。
     */
    val setup = PracticeSetupController(
        planPractice = planPractice,
        banks = { banks },
        history = { history },
        favorites = { favorites },
    )

    fun openSetup(bankId: String) {
        setup.open(bankId)
        screen = Screen.PRACTICE_SETUP
    }

    // ---- 离线手工编辑 ----

    /**
     * 手工新建题目的草稿校验与落盘。
     *
     * 表单字段留在 `QuestionEditorScreen` 的本地状态里，它只收"点保存那一刻"的成品题目 ——
     * 每敲一个字就构造一个不可变题目，会让"正在输入、还没校验"的中间态变成全局状态。
     */
    val questionEditor = QuestionEditorController(
        repository = deps.bankRepository,
        banks = { banks },
        scope = scope,
        onBankChanged = { refresh() },
    )

    fun openQuestionEditor(bankId: String) {
        questionEditor.open(bankId)
        screen = Screen.QUESTION_EDITOR
    }

    fun closeQuestionEditor() {
        screen = Screen.HOME
    }

    /**
     * 开始按设置页的选择练习。
     *
     * 抽题、收敛题量、算不了时的解释都在 [PracticeSetupController.start] 里；
     * 这里只做组合根该做的两件事：把"抽不出题"变成横幅，把会话接过来。
     */
    fun startPlannedPractice() {
        when (val outcome = setup.start()) {
            is SetupStart.Ready -> applySession(outcome.session)
            is SetupStart.Empty -> banner = Banner(outcome.reason, isError = true)
        }
    }

    // ---- 导入 ----

    /**
     * 粘贴文本与 `.qbank` 包的状态、解析、落库都在它里面。
     *
     * 组合根只留入口（[openImport] / [importDroppedFile] / [pickPackageFile]）、
     * 包类型分发（认包要碰 infrastructure），以及"导入之后"的编排（[announceImport]）。
     */
    val importing = ImportController(
        useCase = ImportBankUseCase(deps.bankRepository, media = deps.mediaStore, clock = deps.clock),
        picker = deps.filePicker,
    )

    fun confirmImport(mapping: ChoiceMapping = ChoiceMapping.AUTO) {
        scope.launch {
            // 落库前先记下哪些扩展包是生效的：题库到位会让"等待题库"的包当场生效，
            // 这件事只有拿前后两份清单比才说得出来
            val activeBefore = activeExtensionIds()
            busy = true
            val verdict = importing.confirmText(mapping)
            busy = false
            announceImport(verdict, activeBefore)
        }
    }

    /**
     * 拖拽一个文件进窗口（桌面端 AWT DropTarget 提供）。
     * 按包内清单判定是题库还是扩展包，两条路径的失败语义一致：要么整包通过，要么整包不落盘。
     */
    fun importDroppedFile(name: String, bytes: ByteArray) {
        screen = Screen.IMPORT
        // 64 MB 与桌面文件选择器的上限一致：先挡住内存峰值，再谈校验。
        if (bytes.size > 64 * 1024 * 1024) {
            banner = Banner("「$name」超过 64 MB，当前版本不支持导入这么大的包", isError = true)
            return
        }
        scope.launch { importPackageBytes(name, bytes) }
    }

    /**
     * 从系统文件选择器选包：`.qbank` 或 `.qbext`，由内容判定，不看扩展名。
     *
     * 与粘贴文本走**不同**的路径：包内声明已通过 Schema 校验，
     * 因此不做题型映射，也不允许"部分成功"—— 校验器 fail-closed，错一条就整包拒绝。
     */
    fun pickPackageFile() {
        if (!importing.pickerAvailable) {
            banner = Banner(importing.pickerUnavailableReason ?: "当前平台不能选择文件", isError = true)
            return
        }
        scope.launch {
            busy = true
            val picked = try {
                importing.pick()
            } catch (e: FilePickException) {
                busy = false
                banner = Banner(e.message ?: "选择文件失败", isError = true)
                return@launch
            } catch (e: Throwable) {
                busy = false
                banner = Banner("读取文件失败：${e.message ?: e::class.simpleName}", isError = true)
                return@launch
            }
            busy = false
            if (picked == null) return@launch // 用户取消

            importPackageBytes(picked.name, picked.bytes)
        }
    }

    /**
     * 分发：先认包类型，再走各自的严格读取器。
     *
     * 认包要碰 infrastructure，所以这一段留在组合根；[ImportController] 只管题库本身。
     * 认不出来时把"为什么认不出来"说清楚（不是 ZIP / 没有 manifest / format 未知），
     * 比一句"格式不支持"有用得多。
     */
    private suspend fun importPackageBytes(name: String, bytes: ByteArray) {
        importing.notePickedFile(name)
        when (PackageProbe.formatOf(bytes, PureDeflateCodec)) {
            PackageFormat.QBANK -> importQbankBytes(name, bytes)
            PackageFormat.QBEXT -> importExtensionBytes(name, bytes)
            PackageFormat.QBPATCH -> openPatch(bytes, name)
            // 备份拖进来就走备份页：它是"恢复"，不是"导入"，落点完全不同。
            PackageFormat.QBK -> intakeBackupBytes(name, bytes)
            PackageFormat.UNKNOWN -> banner = Banner(
                "「$name」不是可识别的包：${PackageProbe.describeUnknown(bytes, PureDeflateCodec)}。" +
                    "如果这是文本 JSON，请粘贴到下面的输入框。",
                isError = true,
            )
        }
    }

    /** 安装扩展包。题库没装不算失败 —— 包先存下，等题库回来再生效。 */
    private suspend fun importExtensionBytes(name: String, bytes: ByteArray) {
        busy = true
        val outcome = importExtension.install(bytes, sourceLabel = "qbext:$name")
        busy = false
        when (outcome) {
            is ExtensionOutcome.Installed -> {
                val pack = outcome.pack
                refreshExtensions()
                graph.rematch()
                screen = Screen.EXTENSIONS
                val pending = pack.stateIn(installedBankIds) == ExtensionState.PENDING
                banner = Banner(
                    buildString {
                        append("已安装扩展包《${pack.name}》")
                        outcome.replaced?.let { append("（${it.version} → ${pack.version}）") }
                        append("：${pack.relationCount} 条关系、${pack.annotationCount} 条注释")
                        if (pending) {
                            append("。它绑定的是《${extensionBoundaryLabel(pack)}》，题库还没装，装上后自动生效")
                        }
                        outcome.warnings.forEach { append("。注意：").append(it) }
                    },
                    isError = false,
                )
            }

            is ExtensionOutcome.Duplicate -> {
                screen = Screen.EXTENSIONS
                banner = Banner(
                    "《${outcome.existing.name}》${outcome.existing.version} 已经装过了，本次没有重复安装。",
                    isError = true,
                )
            }

            is ExtensionOutcome.Failed -> {
                importing.showError(outcome.message)
                screen = Screen.IMPORT
                banner = Banner("扩展包未通过校验，没有写入任何数据：${outcome.message}", isError = true)
            }
        }
    }

    /** 文件名 + 包字节 → 校验导入。文件选择器与拖拽两条入口共用。 */
    private suspend fun importQbankBytes(name: String, bytes: ByteArray) {
        // 落库前先记下哪些扩展包是生效的，见 [announceImport]
        val activeBefore = activeExtensionIds()
        busy = true
        val verdict = importing.importQbank(name, bytes)
        busy = false
        announceImport(verdict, activeBefore)
    }

    private fun activeExtensionIds(): Set<String> = activeExtensions.mapTo(mutableSetOf()) { it.packageId }

    /**
     * 导入之后的去向：说什么话、去哪个页面、要不要整体重读。
     *
     * 两个来源（粘贴文本 / `.qbank` 包）走同一段：[ImportController] 保证"过程"的差异
     * 不外泄，这里保证"之后"完全一致 —— 以前这是两条各自 `when` 的分支，
     * 改一条另一条就容易落下。
     */
    private fun announceImport(verdict: ImportVerdict, activeBefore: Set<String>) {
        when (verdict) {
            is ImportVerdict.Imported -> {
                refresh()
                goHome()
                banner = Banner(
                    buildString {
                        append("已导入《${verdict.bank.title}》，${verdict.bank.questionCount} 题")
                        // 题库到位可能让"等待题库"的扩展包开始生效，这件事必须说出来
                        val activated = activeExtensions.filter { it.packageId !in activeBefore }
                        if (activated.isNotEmpty()) {
                            append("。扩展包 ")
                            append(activated.joinToString("、") { "《${it.name}》" })
                            append(" 已生效（${activated.sumOf { it.relationCount }} 条跨库关系）")
                        }
                    },
                    isError = false,
                )
            }

            is ImportVerdict.Duplicate -> banner = Banner(
                "该题库已经导入过（《${verdict.existing.title}》）。为避免重复，本次未创建副本。",
                isError = true,
            )

            // 不覆盖、也不假装成功：说清楚"这是什么"和"该怎么更新"，否则用户只会反复重装
            is ImportVerdict.IdentityConflict -> banner = Banner(
                "《${verdict.existing.title}》已经装过第 ${verdict.existing.contentRevision} 版" +
                    "（本地 ${verdict.existing.questionCount} 题，这个包 ${verdict.incomingCount} 题）。" +
                    "同一个题库的更新请用 .qbpatch 补丁导入 —— 那会逐条列出改动，并保留你的练习历史。",
                isError = true,
            )

            is ImportVerdict.NeedsMapping -> banner = Banner(
                "有 ${verdict.ambiguousCount} 道题的题型需要你确认",
                isError = true,
            )

            // 行内已经写了"没有可导入的题目"，横幅再说一遍只是重复
            ImportVerdict.Rejected -> Unit

            is ImportVerdict.Failed -> banner = Banner(
                "导入失败：包未通过校验，没有写入任何数据",
                isError = true,
            )

            is ImportVerdict.Refused -> banner = Banner(verdict.reason, isError = true)
        }
    }

    // ---- 练习 ----

    /**
     * 作答过程：会话、草稿、判题反馈、总结。
     *
     * 组合根只留导航（作答页 / 总结页 / 首页），以及三件只有它才知道的事：
     * 抽题（setup）、错题聚合（historyView）、判题硬依赖是否过期（graph）。
     */
    val practice = PracticeController(
        submitAnswer = SubmitAnswerUseCase(
            attempts = deps.attemptRepository,
            grading = builtInGradingRegistry(),
            clock = deps.clock,
        ),
        lifecycle = SessionLifecycle(deps.sessionRepository, deps.attemptRepository, deps.clock),
        clock = deps.clock,
        scope = scope,
        banks = { banks },
        onAttemptRecorded = { refreshHistoryOnly() },
        onSessionClosed = { refresh() },
        onNotice = { text, isError -> banner = Banner(text, isError) },
    )

    // ---- 移除题库 ----

    /**
     * 从书架上移除一个题库。
     *
     * 这件事只有组合根做得了：它要动四样东西 —— 题库（仓储）、指向它的关系（图谱）、
     * 挂在它上面的未完成练习（作答）、它的媒体字节（媒体库）。四样各有各的主人，
     * 谁替谁顺手删都不合适，所以编排留在这里，每一步仍由各自那一侧执行。
     *
     * **不动的**：历史、复核、笔记、收藏。它们是已经发生的事，删题库不等于删记忆 ——
     * 错题本按"关联已失效"标注，笔记里那几句是自己写的，一个字都不该少。
     *
     * 顺序也是想过的：先删题库（目录是提交点），再收拾指向它的东西。反过来的话，
     * 中途失败会留下"关系删了、题库还在"的状态 —— 那比不删更难解释。
     */
    fun deleteBank(bankId: String) {
        val bank = banks.firstOrNull { it.bankId == bankId } ?: return
        scope.launch {
            if (!deps.bankRepository.delete(bankId)) {
                // 磁盘上本来就没有它（另一次删除、或恢复覆盖了数据）：把内存里这份也放掉，
                // 但不当成"刚删掉" —— 对一次没发生的删除说"已移除"是在编。
                loadAll()
                return@launch
            }
            val edges = graph.pruneBank(bankId)
            val dropped = practice.dropResumableOf(bankId)
            deps.mediaStore.removeAll(bankId)
            loadAll()
            banner = Banner(removalNotice(bank, edges, dropped), isError = false)
        }
    }

    private fun removalNotice(bank: QuestionBank, edges: Int, droppedSession: Boolean): String = buildString {
        append("已移除题库《").append(bank.title).append("》（").append(bank.questionCount).append(" 题）")
        if (edges > 0) append("，$edges 条指向它的关系随之删除")
        if (droppedSession) append("，没做完的那场练习已放弃")
        append("。历史与笔记一条没动。")
    }

    /** 题库卡片上的快捷入口：全部范围、固定题量。抽题规则见 [PracticeSetupController.quickSession]。 */
    fun startPractice(bank: QuestionBank, questionCount: Int? = null) {
        val created = setup.quickSession(bank, questionCount)
        if (created == null) {
            banner = Banner("这个题库没有可练习的题目（全部未通过结构校验）", isError = true)
            return
        }
        applySession(created)
    }

    /** 只练当前错题：题目集合来自错题聚合，不重新抽取。 */
    fun startWrongReview(entries: List<WrongQuestionEntry>) {
        val bankId = entries.firstOrNull()?.bankId ?: return
        val bank = banks.firstOrNull { it.bankId == bankId } ?: return
        val questions = entries.filter { it.bankId == bankId }.mapNotNull { questionOf(it) }
        if (questions.isEmpty()) {
            banner = Banner("错题对应的题目已找不到（题库可能已更新），无法开练", isError = true)
            return
        }
        // 错题用它们当初作答的语言复习：换成另一种语言，判题依据就变了
        val locale = entries.firstOrNull()?.latestAttempt?.locale?.takeIf { it.isNotBlank() }
            ?: setup.defaultLocale(bank)
        applySession(
            startPractice.startFor(
                bank = bank,
                questions = questions,
                planLabel = "当前错题 · ${questions.size} 题",
                locale = locale,
            ),
        )
    }

    /** 接住一个刚建好的会话：进作答页，再看本题的判题硬依赖还在不在。 */
    private fun applySession(created: PracticeSession) {
        if (!practice.begin(created)) {
            banner = Banner("这个题库没有可练习的题目（全部未通过结构校验）", isError = true)
            return
        }
        screen = Screen.PRACTICE
        checkHardDependencies()
    }

    /** 下一题；后面没有题了就去收尾。 */
    fun next() {
        if (practice.next()) {
            checkHardDependencies()
            return
        }
        finish()
    }

    fun finish() {
        if (practice.finish()) screen = Screen.SUMMARY
    }

    fun toggleFavorite(question: Question) {
        val bankId = practice.session?.bankId ?: setup.bankId ?: return
        val key = QuestionKey(bankId, question.questionId)
        scope.launch { favorites = deps.favoriteRepository.toggle(key) }
    }

    fun isFavorite(questionId: String): Boolean {
        val bankId = practice.session?.bankId ?: return false
        return QuestionKey(bankId, questionId) in favorites
    }

    // ---- 恢复与丢弃 ----

    fun resumeSession() {
        val stored = practice.resumable ?: return
        scope.launch {
            when (val outcome = practice.resume()) {
                null -> Unit
                // 会话文件里最后一题已经交过、只是没点"查看总结"：直接去总结页
                PracticeResume.Finished -> finish()
                is PracticeResume.Ready -> {
                    screen = Screen.PRACTICE
                    checkHardDependencies()
                    if (outcome.session.cursor != stored.cursor) {
                        banner = Banner(
                            "上次提交的题目已跳过，从第 ${outcome.session.cursor + 1} 题继续" +
                                "（已答 ${outcome.session.answeredCount}）",
                            isError = false,
                        )
                    }
                }
            }
        }
    }

    fun discardResumable() {
        val stored = practice.resumable ?: return
        practice.discard()
        banner = Banner(
            "已放弃该练习。已提交的 ${stored.answeredCount} 条记录仍保留在历史中（历史只追加，不删除）。",
            isError = false,
        )
    }

    private fun refreshHistoryOnly() {
        scope.launch { reloadHistory() }
    }

    /**
     * 错题对应的题面，按它当初作答的语言取。
     *
     * 用原语言显示一道英文作答的题，会让人对不上"我到底答错了哪句"；
     * 译文缺失时退回原文，宁可换个语言也不给空白。
     */
    fun questionOf(entry: WrongQuestionEntry): Question? =
        questionIn(entry.bankId, entry.questionId, entry.latestAttempt.locale)

    /** 历史卡片用：同一道题按那次作答的语言显示。 */
    fun questionFor(attempt: AttemptRecord): Question? =
        questionIn(attempt.bankId, attempt.questionId, attempt.locale)

    private fun questionIn(bankId: String, questionId: String, locale: String): Question? {
        val bank = banks.firstOrNull { it.bankId == bankId } ?: return null
        val raw = bank.questions.firstOrNull { it.questionId == questionId } ?: return null
        val want = locale.ifBlank { bank.sourceLocale }
        return raw.localized(want, bank.sourceLocale)
            ?: raw.localized(bank.sourceLocale, bank.sourceLocale)
            ?: raw
    }


    /** 练习/回顾界面按 bankId + assetId 取媒体字节；取不到时 UI 显示占位而不是报错。 */
    suspend fun loadMedia(bankId: String, assetId: String): ByteArray? =
        deps.mediaStore.get(bankId, assetId)

    fun bankTitle(bankId: String): String =
        banks.firstOrNull { it.bankId == bankId }?.title
            ?: practice.resumable?.takeIf { it.bankId == bankId }?.bankTitle?.takeIf { it.isNotBlank() }
            ?: "（题库缺失）"
}

/**
 * 界面语言。目前界面只有中文一种，所以它是常量；等界面本身做多语言时，
 * 这里会换成用户设置项 —— 但"默认练习语言跟随界面语言"的规则不会变。
 */
internal const val UI_LOCALE: String = "zh-Hans"
