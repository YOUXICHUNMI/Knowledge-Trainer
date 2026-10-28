package morefish.knowledge.trainer.application.port

import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiUsage
import morefish.knowledge.trainer.domain.ai.AiUsageEntry

/**
 * HTTP 传输端口。
 *
 * 只做"把这段字节发出去、把状态码和响应文本拿回来"，**不抛异常**：
 * 超时、DNS 失败、证书错误都折成 [AiHttpResponse.transportError]。
 * 理由很实际 —— 异常会一路冒到界面上，成为一句英文堆栈；
 * 折成字符串之后，界面能说出"连不上，可能代理没开"这种人话。
 */
interface AiHttpClient {
    suspend fun post(request: AiHttpRequest): AiHttpResponse
}

data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMillis: Long,
)

data class AiHttpResponse(
    val status: Int,
    val body: String = "",
    /** 非空表示根本没拿到 HTTP 响应（连不上 / 超时 / TLS 失败）。 */
    val transportError: String? = null,
)

/**
 * AI 讲解的数据仓储。
 *
 * 四样东西分开存：配置（可分享）、密钥（不可分享）、讲解记录（缓存 + 审核）、
 * 用量账本（只追加）。混在一个文件里的后果是"清缓存把账单也清了"。
 */
interface AiRepository {
    suspend fun config(): AiConfig
    suspend fun saveConfig(config: AiConfig)

    /** 没存过或清空后返回 null。 */
    suspend fun credential(): String?
    suspend fun saveCredential(key: String)

    /** 按 [AiExplanation.updatedAtMillis] 倒序。 */
    suspend fun records(): List<AiExplanation>
    suspend fun find(key: String): AiExplanation?
    suspend fun save(record: AiExplanation)

    /** 某一天（本地日历天）的用量汇总。 */
    suspend fun usage(epochDay: Long): AiUsage
    suspend fun appendUsage(entry: AiUsageEntry)

    val loadWarnings: List<String>
}

/**
 * 一次讲解请求的输入。
 *
 * 只带"讲这道题需要的东西"：题干、选项、正确答案、用户答案。
 * **刻意不带**笔记、历史、其他题库 —— 上下文越多，用户越不知道自己上传了什么。
 */
data class AiPromptInput(
    val bankId: String,
    val questionId: String,
    /** 这道题当前展示的语言（缓存键的一部分）。 */
    val locale: String,
    val questionRevision: Int,
    /** 题目内容指纹：题目改一个字，旧讲解就不再命中。 */
    val contentFingerprint: String,
    val typeLabel: String,
    val promptMarkdown: String,
    /** optionId 与文案。顺序就是用户看到的顺序。 */
    val options: List<Pair<String, String>> = emptyList(),
    val correctOptions: List<String> = emptyList(),
    val answerText: String = "",
    val userAnswer: String? = null,
    /** 希望模型用哪种语言讲（来自题目/会话的语言标签）。 */
    val language: String = "",
)

sealed interface AiCallResult {
    data class Ok(
        val text: String,
        val model: String,
        val promptTokens: Int,
        val completionTokens: Int,
    ) : AiCallResult

    /**
     * 失败原因直接面向用户，因此必须是中文、且不含密钥。
     * [retryable] 只表示"再试一次有意义"，**不表示软件会自动重试** —— 自动重试会重复扣费。
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
        val httpStatus: Int? = null,
    ) : AiCallResult
}

/** 讲解生成端口。实现方负责组请求、解析响应、映射错误。 */
interface AiExplainProvider {
    suspend fun explain(config: AiConfig, apiKey: String?, input: AiPromptInput): AiCallResult
}

/**
 * 批量任务的仓储。
 *
 * 与讲解记录分开存：任务只是"待办清单"，题目与讲解都不该因为它而多一份。
 * 任务丢了最多重新建一次队列 —— 已经生成过的讲解仍在缓存里，重跑时会被扫成"跳过"，
 * 不会再花一次钱。这也是"进度不必逐题落盘"的前提。
 */
interface AiBatchRepository {
    /** 按创建时间倒序。 */
    suspend fun jobs(): List<AiBatchJob>
    suspend fun find(jobId: String): AiBatchJob?
    suspend fun save(job: AiBatchJob)
    suspend fun delete(jobId: String)

    val loadWarnings: List<String>
}
