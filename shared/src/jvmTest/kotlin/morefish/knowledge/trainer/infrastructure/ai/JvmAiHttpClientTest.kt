package morefish.knowledge.trainer.infrastructure.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.application.port.AiHttpRequest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真的发一次 HTTP 请求。
 *
 * 前面用假传输层验证的是"报文怎么组、状态码怎么解读"；这里补最后一环：
 * JDK 客户端真的能把请求发出去、能读到响应、能把 4xx 当成响应而不是异常。
 * 服务端用 JDK 自带的 `HttpServer`，不依赖外网也不依赖第三方库。
 */
class JvmAiHttpClientTest {

    private suspend fun withServer(
        status: Int,
        responseBody: String,
        block: suspend (port: Int, seen: MutableMap<String, String>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableMapOf<String, String>()
        server.createContext("/chat/completions") { exchange ->
            seen["method"] = exchange.requestMethod
            seen["auth"] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            seen["body"] = exchange.requestBody.readBytes().decodeToString()
            val bytes = responseBody.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server.address.port, seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendsHeadersAndBodyOverRealHttp() = runBlocking {
        withServer(200, """{"choices":[{"message":{"content":"ok"}}]}""") { port, seen ->
            val response = JvmAiHttpClient().post(
                AiHttpRequest(
                    url = "http://127.0.0.1:$port/chat/completions",
                    headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer sk-test"),
                    body = """{"model":"m"}""",
                    timeoutMillis = 5_000,
                ),
            )
            assertEquals(200, response.status)
            assertNull(response.transportError)
            assertTrue(response.body.contains("\"content\":\"ok\""))

            assertEquals("POST", seen["method"])
            assertEquals("Bearer sk-test", seen["auth"])
            assertEquals("""{"model":"m"}""", seen["body"])
        }
    }

    @Test
    fun errorStatusIsReturnedNotThrown() = runBlocking {
        withServer(429, """{"error":{"message":"slow down"}}""") { port, _ ->
            val response = JvmAiHttpClient().post(
                AiHttpRequest(
                    url = "http://127.0.0.1:$port/chat/completions",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{}",
                    timeoutMillis = 5_000,
                ),
            )
            // 4xx 必须走 errorStream 读回来，否则界面只能看到一句"请求失败"而没有原因
            assertEquals(429, response.status)
            assertNull(response.transportError)
            assertTrue(response.body.contains("slow down"))
        }
    }

    @Test
    fun unreachableHostBecomesTransportErrorInsteadOfCrash() = runBlocking {
        // 端口 1 上不会有服务；这里验证的是"连不上"这条路不会抛异常
        val response = JvmAiHttpClient().post(
            AiHttpRequest(
                url = "http://127.0.0.1:1/chat/completions",
                headers = emptyMap(),
                body = "{}",
                timeoutMillis = 1_000,
            ),
        )
        assertEquals(0, response.status)
        assertTrue(response.transportError?.isNotBlank() == true)
    }
}
