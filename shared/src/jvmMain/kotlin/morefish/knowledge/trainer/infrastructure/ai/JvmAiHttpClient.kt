package morefish.knowledge.trainer.infrastructure.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiHttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 桌面端（JVM）的 HTTP 传输。
 *
 * 用 JDK 自带的 `HttpURLConnection` 而不是额外引一个客户端库：
 * 这里只有"POST 一段 JSON、读回一段文本"这一件事，多一个依赖只会多一份要跟着升级的东西。
 *
 * 三条硬规则：
 * 1. 不跟随重定向（重定向可能把 Authorization 头带去别的主机）；
 * 2. 不抛异常，全部折成 [AiHttpResponse.transportError] 的中文说明；
 * 3. 响应体截断在 20 万字符，防止一个失控的端点把内存吃光。
 */
class JvmAiHttpClient : AiHttpClient {

    override suspend fun post(request: AiHttpRequest): AiHttpResponse = withContext(Dispatchers.IO) {
        val connection = try {
            (URI.create(request.url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = request.timeoutMillis.toInt()
                readTimeout = request.timeoutMillis.toInt()
                doOutput = true
                instanceFollowRedirects = false
                useCaches = false
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
        } catch (e: Exception) {
            return@withContext AiHttpResponse(0, transportError = describe(e))
        }

        try {
            connection.outputStream.use { it.write(request.body.encodeToByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            AiHttpResponse(status = status, body = body.take(MAX_BODY_CHARS))
        } catch (e: Exception) {
            AiHttpResponse(0, transportError = describe(e))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is SocketTimeoutException -> "请求超时（服务没在设定时间内返回）"
        is UnknownHostException -> "域名解析失败，检查接口地址或网络"
        is SSLException -> "TLS 握手失败，检查代理或证书"
        is IOException -> "网络错误：${error.message?.take(120) ?: error::class.simpleName}"
        else -> "请求失败：${error.message?.take(120) ?: error::class.simpleName}"
    }

    private companion object {
        const val MAX_BODY_CHARS = 200_000
    }
}
