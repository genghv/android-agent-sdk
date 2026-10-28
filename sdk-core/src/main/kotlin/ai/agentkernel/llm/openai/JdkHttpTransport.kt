package ai.agentkernel.llm.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** JVM 默认传输（单测/桌面用）。Android 请换 OkHttp 实现。 */
class JdkHttpTransport(
    private val timeoutSeconds: Long = 120L,
) : Transport, GetTransport {
    /**
     * **强制 HTTP/1.1**。
     *
     * 为什么不用默认的 HTTP_2：JDK 的 HttpClient 默认会先发 h2c upgrade 头，
     * 而不少自建/反代服务对 `Upgrade: h2c` 的处理是**直接关连接、不回任何响应**
     * —— 客户端收到的是「HTTP/1.1 header parser received no bytes」，
     * 看起来像服务端挂了，其实是升级握手没谈拢。
     * 手机端走 OkHttp（明文 http 本来就是 HTTP/1.1），不受影响；
     * 但桌面/JVM 宿主会踩，所以这里写死 HTTP_1_1。
     */
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                error("LLM HTTP ${resp.statusCode()}: ${resp.body().take(500)}")
            }
            resp.body()
        }

    /** GET（拉档位清单用）。协议版本同上，同样强制 HTTP/1.1。 */
    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                error("LLM HTTP ${resp.statusCode()}: ${resp.body().take(500)}")
            }
            resp.body()
        }
}