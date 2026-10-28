package morefish.knowledge.trainer.presentation.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import morefish.knowledge.trainer.application.port.AiRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.AiGenerationOutcome
import morefish.knowledge.trainer.application.usecase.AiPromptAssembler
import morefish.knowledge.trainer.application.usecase.GenerateExplanationUseCase
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.ai.AiUsage
import morefish.knowledge.trainer.domain.ai.formatMicros
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.practice.LocalDates

/**
 * 组合根交过来的"当前这道题"。
 *
 * 只带 AI 请求真正用得上的几项，**不带会话结构**：会话有几道题、游标在哪、
 * 切题时要清空哪些字段，都不是 AI 该知道的事。语言的选择留在这里算，
 * 是因为"什么时候回退到题库源语言"是提示词规则，不是会话规则。
 */
class CurrentQuestion(
    val bankId: String,
    val question: Question,
    /** 会话当前用的语言；null = 没指定，由 [AiPromptAssembler.localeOf] 继续回退。 */
    val sessionLocale: String?,
    /** 题库；会话里的题库被删掉时为 null。 */
    val bank: QuestionBank?,
    /** 这道题的作答记录；null = 还没作答。 */
    val attempt: AttemptRecord?,
)

/**
 * AI 讲解：接入配置、用量账本、讲解记录与人工审核。
 *
 * 单独存在的主要理由是**这一块一半是错误处理**：密钥没填、没同意上传、日预算用完、
 * 结构校验没过、网络断了、返回里没有正文 —— 每种失败都得说清是哪一种，说错一句
 * 用户就会去翻配置、翻题库，而问题其实在别处。这些分支以前和导入、备份、图谱挤在
 * 同一个类里，主线（"生成一道题的解释"）根本看不出来。
 *
 * 与前几刀不同的两条边界：
 *
 * - **它会写盘，而且必须由它写。** 配置、密钥、讲解记录、用量账本是本功能自己的数据，
 *   换个地方存就等于这份状态有两处真相。前几刀"子控制器不写盘"针对的是**别人的**数据
 *   （会话、题库、历史），那些仍归组合根。
 * - **它不导航、不弹提示。** `openAi` / `closeAi` 留在组合根；要说的话经 [onNotice]
 *   交出去，横幅怎么显示、要不要顺带跳页由那边决定。否则"点了一下生成"会同时改
 *   三处状态（页面、横幅、忙碌标记），回头没人说得清哪次点击触发了哪次跳转。
 *
 * 当前题目通过 [currentQuestion] 读取器拿，而不是构造时快照：切题、作答、换语言都发生在
 * 组合根里，快照会导致"题目换了、讲解还挂着上一题"。这里不只是显示错位 ——
 * 缓存键按题目算，错配等于让用户为上一道题再付一次钱。
 */
class AiController(
    private val repository: AiRepository,
    private val generate: GenerateExplanationUseCase,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** 平台是否接了 HTTP 传输。false 时整块 AI 只是"不可用"，不拖累其他功能。 */
    val available: Boolean,
    private val currentQuestion: () -> CurrentQuestion?,
    /**
     * 把要说的话交给组合根。它是回调而不是状态，因为这里不该攒"待显示的提示"。
     *
     * [text] 为 null 表示这次没什么要说的，顺带把上一条收起来 —— 例如命中缓存：
     * 既不是新生成、也不是失败，让上一条"已生成"继续挂着会让人以为又花了一次钱。
     */
    private val onNotice: (text: String?, isError: Boolean) -> Unit,
) {

    /** 已保存的接入配置。界面上的表单是它的草稿，点保存才回到这里。 */
    var config by mutableStateOf(AiConfig())
        private set

    /** 本机是否存着密钥。不保存密钥本身，界面只需要知道"有没有"。 */
    var hasKey by mutableStateOf(false)
        private set

    var usageToday by mutableStateOf(AiUsage())
        private set

    /** 讲解记录（缓存 + 审核留档），按更新时间倒序。 */
    var records by mutableStateOf<List<AiExplanation>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    /** 练习页的讲解面板是否展开。生成中也会自动展开，否则用户看不到进度。 */
    var panelOpen by mutableStateOf(false)
        private set

    /** 当前这道题在该模型下的缓存键；没有题目或没填模型时为 null。 */
    val currentKey: String?
        get() {
            val target = currentQuestion() ?: return null
            if (config.model.isBlank()) return null
            return AiExplanation.keyFor(
                bankId = target.bankId,
                questionId = target.question.questionId,
                locale = localeOf(target),
                questionRevision = target.question.contentRevision,
                contentFingerprint = target.question.contentFingerprint(),
                model = config.model,
            )
        }

    val currentExplanation: AiExplanation?
        get() {
            val key = currentKey ?: return null
            return records.firstOrNull { it.key == key }
        }

    suspend fun refresh() {
        config = repository.config()
        hasKey = !repository.credential().isNullOrBlank()
        usageToday = repository.usage(LocalDates.epochDayOf(clock.nowMillis()))
        records = repository.records()
    }

    /**
     * 保存配置。
     *
     * 密钥只在这次真的输入了才写入 —— 否则每次保存都会被空输入覆盖成空密钥。
     * 关掉"记住密钥"会把已存的密钥删掉，而不是"不再读取它"（文件还在就等于还在）。
     */
    fun saveConfig(config: AiConfig, apiKeyDraft: String?) {
        val cleared = config.baseUrl.isBlank() && config.model.isBlank()
        val problems = if (cleared) emptyList() else config.validate()
        if (problems.isNotEmpty()) {
            onNotice(problems.first(), true)
            return
        }
        scope.launch {
            repository.saveConfig(config)
            val draft = apiKeyDraft?.trim().orEmpty()
            when {
                draft.isNotEmpty() -> repository.saveCredential(draft)
                !config.rememberKey -> repository.saveCredential("")
            }
            refresh()
            onNotice(
                when {
                    cleared -> "AI 已关闭，其余功能不受影响"
                    hasKey -> "AI 配置已保存"
                    else -> "配置已保存，但还没有密钥 —— 公网服务用不了，本地模型可以不填"
                },
                false,
            )
        }
    }

    fun clearCredential() {
        scope.launch {
            repository.saveCredential("")
            refresh()
            onNotice("已清除本机保存的密钥", false)
        }
    }

    fun togglePanel() {
        panelOpen = !panelOpen
    }

    /**
     * 生成当前题的讲解。
     *
     * 三道闸门在用例里（配置 / 同意上传 / 预算），这里只多拦一道：
     * **没作答就先看讲解 = 提前看答案**，对复习没有好处。
     */
    fun generate(force: Boolean = false) {
        if (busy) return
        val target = currentQuestion()
        if (target == null) {
            onNotice("先打开一道题，再让 AI 讲解", true)
            return
        }
        if (target.attempt == null) {
            onNotice("先作答再看讲解 —— 没作答就看，等于提前看答案", true)
            return
        }
        panelOpen = true
        busy = true
        scope.launch {
            val input = AiPromptAssembler.inputOf(
                bankId = target.bankId,
                question = target.question,
                locale = localeOf(target),
                attempt = target.attempt,
            )
            val outcome = generate.generate(input, force)
            busy = false
            refresh()
            when (outcome) {
                is AiGenerationOutcome.Ready ->
                    if (outcome.fromCache) {
                        onNotice(null, false)
                    } else if (outcome.record.state == AiRecordState.NEEDS_REVIEW) {
                        onNotice("讲解已生成，但结构校验没通过（${outcome.record.error}），请自己判断是否可信", true)
                    } else {
                        onNotice("讲解已生成（本次 ${formatMicros(outcome.record.costMicros)}）", false)
                    }
                is AiGenerationOutcome.Unavailable -> onNotice(outcome.reason, true)
                is AiGenerationOutcome.Blocked -> onNotice(outcome.reason, true)
                is AiGenerationOutcome.Failed -> onNotice(
                    if (outcome.retryable) "${outcome.reason}（不会自动重试，避免重复扣费）" else outcome.reason,
                    true,
                )
            }
        }
    }

    /** 人工处置：采纳 / 弃用 / 打回待复核。只有这三条路能改审核状态。 */
    fun reviewCurrent(next: AiRecordState) {
        val record = currentExplanation ?: return
        scope.launch {
            repository.save(record.reviewed(next, clock.nowMillis()))
            refresh()
            onNotice(
                when (next) {
                    AiRecordState.APPROVED -> "已标记为有用"
                    AiRecordState.REJECTED -> "已弃用，下次会重新生成"
                    else -> "已打回待复核"
                },
                false,
            )
        }
    }

    /** 这道题当前该用哪种语言。题目自带 → 会话 → 题库源语言，逐级回退。 */
    private fun localeOf(target: CurrentQuestion): String = AiPromptAssembler.localeOf(
        question = target.question,
        sessionLocale = target.sessionLocale,
        bank = target.bank,
    )
}
