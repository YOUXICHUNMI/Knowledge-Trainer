package morefish.knowledge.trainer.infrastructure.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import morefish.knowledge.trainer.application.port.AiCallResult
import morefish.knowledge.trainer.application.port.AiExplainProvider
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.domain.ai.AiConfig

/**
 * OpenAI 兼容的 `/chat/completions` 适配器。
 *
 * 支持范围就是这一种协议：云端服务、Ollama、LM Studio、vLLM 都提供它。
 * 不支持的（各家私有协议）不做兼容层 —— 一个"看起来支持所有模型"的适配器，
 * 最后往往每个模型都只支持一半。
 */
class OpenAiCompatibleProvider(
    private val http: AiHttpClient,
) : AiExplainProvider {

    override suspend fun explain(
        config: AiConfig,
        apiKey: String?,
        input: AiPromptInput,
    ): AiCallResult {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxOutputTokens)
            put("stream", false)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt(input.language))
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt(input))
                }
            }
        }.toString()

        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
        }

        val response = http.post(
            AiHttpRequest(
                url = config.endpoint(),
                headers = headers,
                body = body,
                timeoutMillis = config.timeoutMillis,
            ),
        )

        if (response.transportError != null) {
            return AiCallResult.Failed("没能连上服务：${response.transportError}", retryable = true)
        }
        return readResponse(response.status, response.body, apiKey)
    }

    private fun readResponse(status: Int, body: String, apiKey: String?): AiCallResult {
        if (status !in 200..299) return mapHttpError(status, body, apiKey)

        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return AiCallResult.Failed("服务返回的内容不是 JSON（HTTP $status）", retryable = false, status)
        val choices = root["choices"] as? JsonArray
        val content = choices
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
        if (content.isNullOrBlank()) {
            return AiCallResult.Failed("服务返回了空内容（HTTP $status）", retryable = false, status)
        }
        val usage = root["usage"] as? JsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val model = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }.orEmpty()

        return AiCallResult.Ok(
            text = content,
            model = model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        )
    }

    /**
     * 错误映射。每条都要能回答"我该改哪儿"：
     * 401 是密钥，404 是地址少了 /v1，429 是额度或限流，400 通常是模型名写错。
     */
    private fun mapHttpError(status: Int, body: String, apiKey: String?): AiCallResult.Failed {
        val detail = errorMessageOf(body, apiKey)
        val suffix = if (detail.isBlank()) "" else "：$detail"
        return when (status) {
            400 -> AiCallResult.Failed("请求被拒绝（HTTP 400），检查模型名与参数$suffix", true, status)
            401, 403 -> AiCallResult.Failed("密钥无效或没有该模型的权限（HTTP $status）$suffix", false, status)
            404 -> AiCallResult.Failed("接口地址不存在（HTTP 404），baseUrl 一般要带 /v1$suffix", false, status)
            409 -> AiCallResult.Failed("服务端状态冲突（HTTP 409），稍后再试$suffix", true, status)
            413 -> AiCallResult.Failed("题目内容太长，被服务端拒绝（HTTP 413）", false, status)
            429 -> AiCallResult.Failed("被服务端限流或额度用尽（HTTP 429）$suffix", true, status)
            in 500..599 -> AiCallResult.Failed("服务端出错（HTTP $status）$suffix", true, status)
            else -> AiCallResult.Failed("请求失败（HTTP $status）$suffix", true, status)
        }
    }

    /**
     * 取服务端错误说明。只取 `error.message`，不整段回显（响应里可能带着我们的请求内容）。
     *
     * 还要**再洗一遍密钥**：有些服务会在报错里原样回显 `Invalid API key: sk-xxx`，
     * 直接显示在界面上、再被截图发出去，就等于把密钥公布了。
     */
    private fun errorMessageOf(body: String, apiKey: String?): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return ""
        val raw = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
            ?: return ""
        val withoutKey = if (!apiKey.isNullOrBlank()) raw.replace(apiKey, "***") else raw
        return withoutKey
            .replace(KEY_LIKE, "***")
            .replace('\n', ' ')
            .take(160)
    }

    private companion object {
        /** 长得像密钥的片段。认错一个普通单词的代价，远小于漏掉一处密钥。 */
        val KEY_LIKE = Regex("sk-[A-Za-z0-9_-]{6,}")
    }

    private fun systemPrompt(language: String): String = buildString {
        appendLine("你是知识训练器的讲解助手，任务是解释一道题为什么是这个答案。")
        appendLine("规则：")
        appendLine("1. 先用一句话给结论，再分步骤说明推理过程，最多 6 步。")
        appendLine("2. 只使用题目给出的信息和你确定的知识；不确定就写「不确定」，绝不编造来源、页码、引文或数据。")
        appendLine("3. 不要复述题干全文，直接讲关键点。")
        appendLine("4. 输出 Markdown；行内公式用 \$...\$，独立公式用 \$\$...\$\$。")
        appendLine("5. 如果题目本身有错（例如正确答案与题干矛盾），直接指出并说明理由。")
        append("6. 用").append(languageName(language)).append("回答。")
    }

    private fun userPrompt(input: AiPromptInput): String = buildString {
        appendLine("题型：${input.typeLabel}")
        appendLine("题干：")
        appendLine(input.promptMarkdown.trim())
        if (input.options.isNotEmpty()) {
            appendLine()
            appendLine("选项：")
            input.options.forEachIndexed { index, (_, text) ->
                appendLine("${index + 1}. ${text.trim()}")
            }
        }
        appendLine()
        if (input.correctOptions.isNotEmpty()) {
            appendLine("正确答案：${input.correctOptions.joinToString("；")}")
        }
        if (input.answerText.isNotBlank()) {
            appendLine("标准答案：${input.answerText}")
        }
        if (!input.userAnswer.isNullOrBlank()) {
            appendLine("我的答案：${input.userAnswer}")
        } else {
            appendLine("我的答案：（未作答）")
        }
    }

    private fun languageName(locale: String): String = when {
        locale.isEmpty() -> "简体中文"
        locale.startsWith("en", ignoreCase = true) -> "English"
        locale.startsWith("ja", ignoreCase = true) -> "日本語"
        locale.startsWith("ko", ignoreCase = true) -> "한국어"
        locale.startsWith("zh-Hant", ignoreCase = true) || locale.startsWith("zh-TW") -> "繁體中文"
        else -> "简体中文"
    }
}
