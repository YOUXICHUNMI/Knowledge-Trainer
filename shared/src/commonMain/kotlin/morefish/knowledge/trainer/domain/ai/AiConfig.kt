package morefish.knowledge.trainer.domain.ai

/**
 * 云端 / 本地模型的接入配置。
 *
 * **这里没有 API Key。** 密钥单独存在另一个文件里（`ai-credential-v1.json`），
 * 这样"导出/分享配置"永远不会顺手把密钥也带出去，日志里也不会因为打印配置而泄漏。
 *
 * 只支持 OpenAI 兼容的 `/chat/completions`：市面上的云端服务、Ollama、LM Studio
 * 都提供这个形状，一个适配器覆盖绝大多数选择，也就不用为每家写一套。
 */
data class AiConfig(
    val baseUrl: String = "",
    val model: String = "",
    /** 单次回答的 token 上限。既是成本闸门，也是"别让模型写一篇论文"的提示。 */
    val maxOutputTokens: Int = 700,
    val temperature: Double = 0.2,
    val timeoutMillis: Long = 30_000,
    val budget: AiBudget = AiBudget(),
    val pricing: AiPricing = AiPricing(),
    /** 是否同意把题目内容发送给这个服务。默认关：没点过就不发，一次也不发。 */
    val uploadConsent: Boolean = false,
    /**
     * 是否把密钥留在本机。关掉则只在本次会话有效 ——
     * 与他人共用电脑时，这个开关比一句"我们会妥善保管"有用。
     */
    val rememberKey: Boolean = true,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** 拼出 `.../chat/completions`。用户填的 baseUrl 常带尾斜杠，这里统一去掉。 */
    fun endpoint(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** 本机地址（本地模型）。这些允许 http，其余的必须 https。 */
    val isLocalEndpoint: Boolean
        get() {
            val host = hostOf(baseUrl)
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }

    /**
     * 配置校验。返回空表示可用。
     *
     * 明文 http 一律拒绝（本机地址除外）：把题目和密钥用明文发到公网上，
     * 是那种"用户以为软件会处理"的事，实际只能由软件来拦。
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            problems += "还没有填写接口地址"
        } else if (!url.startsWith("https://") && !url.startsWith("http://")) {
            problems += "接口地址要以 https:// 开头（本地模型可用 http://localhost）"
        } else if (url.startsWith("http://") && !isLocalEndpoint) {
            problems += "只有本机地址允许 http，公网地址必须用 https"
        }
        if (model.isBlank()) problems += "还没有填写模型名"
        if (maxOutputTokens <= 0) problems += "单次输出上限必须大于 0"
        if (maxOutputTokens > 8_000) problems += "单次输出上限不要超过 8000，否则一次就可能吃掉整天的预算"
        if (timeoutMillis < 5_000) problems += "超时不要小于 5 秒"
        if (temperature < 0.0 || temperature > 2.0) problems += "temperature 要在 0 到 2 之间"
        if (budget.maxCallsPerDay < 0 || budget.maxTokensPerDay < 0 || budget.maxCostMicrosPerDay < 0) {
            problems += "预算不能是负数"
        }
        return problems
    }

    companion object {
        /** 只做"够用"的解析：不引入 URL 库，也不假装能校验所有畸形输入。 */
        internal fun hostOf(url: String): String {
            val afterScheme = url.substringAfter("://", "")
            val authority = afterScheme.substringBefore('/')
            val hostPort = authority.substringAfter('@')
            // IPv6 字面量形如 [::1]:11434，冒号在方括号里，不能直接 split
            if (hostPort.startsWith("[")) return hostPort.substringBefore(']') + "]"
            return hostPort.substringBefore(':').lowercase()
        }
    }
}
