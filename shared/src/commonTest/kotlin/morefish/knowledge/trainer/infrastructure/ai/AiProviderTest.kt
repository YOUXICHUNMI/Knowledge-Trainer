package morefish.knowledge.trainer.infrastructure.ai

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AiCallResult
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiHttpResponse
import morefish.knowledge.trainer.application.port.AiPromptInput
import morefish.knowledge.trainer.domain.ai.AiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 适配器测试：组出来的请求长什么样、服务返回什么会被怎么解读。
 *
 * 用假的传输层而不是真网络：这些用例要验证的是"报文与错误映射"，
 * 拉一个真端点只会让测试变慢、变脆，还测不到 401/429 这些分支。
 */
class AiProviderTest {

    private class FakeHttp(private val response: AiHttpResponse) : AiHttpClient {
        var last: AiHttpRequest? = null
        var calls = 0

        override suspend fun post(request: AiHttpRequest): AiHttpResponse {
            calls++
            last = request
            return response
        }
    }

    private val config = AiConfig(baseUrl = "https://api.example.com/v1", model = "test-model")

    private fun input() = AiPromptInput(
        bankId = "bank-1",
        questionId = "q-1",
        locale = "zh-Hans",
        questionRevision = 3,
        contentFingerprint = "fp",
        typeLabel = "单选",
        promptMarkdown = "下列关于电场的说法正确的是？",
        options = listOf("o1" to "电场是矢量", "o2" to "电场是标量"),
        correctOptions = listOf("电场是矢量"),
        answerText = "",
        userAnswer = "电场是标量",
        language = "zh-Hans",
    )

    private fun okBody(content: String = "因为电场有方向。", prompt: Int = 120, completion: Int = 45) = """
        {
          "model": "test-model-0613",
          "choices": [{"message": {"role": "assistant", "content": "$content"}}],
          "usage": {"prompt_tokens": $prompt, "completion_tokens": $completion}
        }
    """.trimIndent()

    @Test
    fun requestCarriesModelMessagesAndBearerToken() = runTest {
        val http = FakeHttp(AiHttpResponse(200, okBody()))
        val provider = OpenAiCompatibleProvider(http)

        provider.explain(config, "sk-secret", input())

        val request = assertNotNull(http.last)
        assertEquals("https://api.example.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-secret", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = request.body
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":false"))
        // 系统提示词 + 用户题面，正好两条
        assertEquals(2, Regex("\"role\":").findAll(body).count())
        assertTrue(body.contains("电场是矢量"), "题干与选项必须进请求")
        assertTrue(body.contains("我的答案：电场是标量"))
    }

    @Test
    fun noAuthorizationHeaderForLocalModelsWithoutKey() = runTest {
        val http = FakeHttp(AiHttpResponse(200, okBody()))
        OpenAiCompatibleProvider(http).explain(
            AiConfig(baseUrl = "http://localhost:11434/v1", model = "qwen2.5:7b"),
            apiKey = null,
            input(),
        )
        assertNull(http.last?.headers?.get("Authorization"))
    }

    @Test
    fun successParsesContentUsageAndActualModel() = runTest {
        val provider = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(200, okBody())))
        val result = provider.explain(config, "k", input())
        assertTrue(result is AiCallResult.Ok)
        assertEquals("因为电场有方向。", result.text)
        assertEquals("test-model-0613", result.model, "要用服务端回的模型名记账，而不是我们请求的别名")
        assertEquals(120, result.promptTokens)
        assertEquals(45, result.completionTokens)
    }

    @Test
    fun invalidKeyIsNotRetryableAndNeverEchoesTheKey() = runTest {
        val provider = OpenAiCompatibleProvider(
            FakeHttp(AiHttpResponse(401, """{"error":{"message":"Invalid API key: sk-secret"}}""")),
        )
        val result = provider.explain(config, "sk-secret", input())
        assertTrue(result is AiCallResult.Failed)
        assertEquals(401, result.httpStatus)
        assertFalse(result.retryable, "密钥错了再试一百次也一样错")
        assertTrue(result.reason.contains("密钥无效"))
        assertFalse(result.reason.contains("sk-secret"), "错误信息不能把密钥念出来")
    }

    @Test
    fun rateLimitIsRetryableButNotAutomatic() = runTest {
        val provider = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(429, """{"error":{"message":"slow down"}}""")))
        val result = provider.explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        assertTrue(result.retryable)
        assertTrue(result.reason.contains("429"))
    }

    @Test
    fun serverErrorIsReportedWithStatus() = runTest {
        val result = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(503, "gateway down"))).explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        assertEquals(503, result.httpStatus)
        assertTrue(result.retryable)
    }

    @Test
    fun nonJsonSuccessBodyIsAFailureNotAnEmptyExplanation() = runTest {
        val result = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(200, "<html>hello</html>"))).explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        assertTrue(result.reason.contains("不是 JSON"))
    }

    @Test
    fun emptyChoicesAreRejected() = runTest {
        val body = """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":0}}"""
        val result = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(200, body))).explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        assertTrue(result.reason.contains("空内容"))
    }

    @Test
    fun transportErrorBecomesAReadableChineseMessage() = runTest {
        val result = OpenAiCompatibleProvider(
            FakeHttp(AiHttpResponse(0, transportError = "域名解析失败，检查接口地址或网络")),
        ).explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        assertTrue(result.reason.startsWith("没能连上服务"))
        assertTrue(result.retryable)
    }

    @Test
    fun serverErrorMessagesAreTruncated() = runTest {
        val long = "x".repeat(500)
        val result = OpenAiCompatibleProvider(FakeHttp(AiHttpResponse(400, """{"error":{"message":"$long"}}""")))
            .explain(config, "k", input())
        assertTrue(result is AiCallResult.Failed)
        // 160 字截断 + 前缀，不能把整段服务端原文糊到界面上
        assertTrue(result.reason.length < 260)
    }
}
