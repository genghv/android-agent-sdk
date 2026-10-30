package ai.agentkernel.llm.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** JVM 默认传输（单测/桌面用）。Android 请换 OkHttp 实现。 */
class JdkHttpTransport(
    private val timeoutSeconds: Long = 120L,
) : Transport, GetTransport, StreamingTransport {
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
                // ⚠️ **先抹后截**：上游可能把 Authorization 原样回显在错误体里 ——
                //   这段文本会进异常消息、再回灌给模型 ⇒ 明文 key 不能跟着走。
                error("LLM HTTP ${resp.statusCode()}: ${HeaderRedaction.redact(resp.body(), headers).take(500)}")
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
                error("LLM HTTP ${resp.statusCode()}: ${HeaderRedaction.redact(resp.body(), headers).take(500)}")
            }
            resp.body()
        }

    /**
     * **真流式**：响应体**按块**吐出来（不等 body 结束）。
     *
     * ## 与 `post` 的差别
     *
     * - `post` 用 `BodyHandlers.ofString()` ⇒ 必须等**整个 body 结束**才返回
     *   ⇒ 服务端早就把 `[DONE]` 发完了、客户端还在干等（假流式）。
     * - 这里用 `BodyHandlers.ofInputStream()` ⇒ **响应头一到**就能拿到 body 的流，
     *   随到随吐。
     *
     * ## 三件必须做对的事
     *
     * 1. **取消必须立刻生效** —— 客户端见到 `[DONE]` 会**主动取消**本流
     *    （`StreamingCompletion` 抛 `Error` 子类来中断收集）。
     *    ⇒ 取消时必须**立刻关掉底层 socket**，否则被取消的协程卡死在阻塞读里，
     *    客户端永远返回不了。
     *    （JDK 的 `HttpResponseInputStream.close()` 会 `subscription.cancel()`
     *    并往队列里塞一个结束哨兵 ⇒ 阻塞中的 `read()` **立刻**醒来抛异常 ✓。
     *    ⚠️ 关的是**原始流**而不是 `InputStreamReader`：
     *    后者的 `StreamDecoder` 锁正被阻塞中的 `read()` 持有 ⇒ 关它会死锁。）
     * 2. **UTF-8 跨块不得乱码** —— 块边界可能劈开一个多字节字符
     *    ⇒ 必须用 `InputStreamReader` 读（它自己处理半截字符）；
     *    **不要**对每个 chunk 单独 `String(bytes, UTF_8)`（那样会出 `�`）。
     * 3. **错误处理与 `post` 同款** —— 非 2xx 抛异常，消息里**先抹凭据再截断**。
     *
     * ## 超时
     *
     * 每请求 `.timeout(timeoutSeconds)`（与 `post` 一致）。
     * ⚠️ JDK 的 request timeout **只管到响应头到达**（`ofInputStream` 的 future
     * 在 body 还没收完时就完成了）⇒ 另加一个同预算的看门狗把总时长也压回去，
     * 否则流式路径的总时长上限会比 `post` 松。
     */
    override fun postStream(url: String, headers: Map<String, String>, body: String): Flow<String> =
        callbackFlow {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (k, v) -> builder.header(k, v) }

            val producer = this          // ProducerScope：关 channel / 把异常递给收集端
            val closer = StreamCloser()  // 取消时：关掉「正在读的那个流」
            val timedOut = AtomicBoolean(false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)

            // ofInputStream：响应头一到 future 就完成 ⇒ 不用等 body 结束。
            val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

            val reader = launch(Dispatchers.IO) {
                try {
                    val resp = future.await()
                    val input = resp.body()
                    closer.register(input)
                    if (resp.statusCode() !in 200..299) {
                        val text = input.readBytes().toString(Charsets.UTF_8)
                        // ⚠️ **先抹后截**（与 post 同款）：上游可能把 Authorization 原样回显。
                        throw IllegalStateException(
                            "LLM HTTP ${resp.statusCode()}: ${HeaderRedaction.redact(text, headers).take(500)}"
                        )
                    }
                    // ⚠️ **必须用 InputStreamReader 读**：块边界可能劈开一个多字节字符，
                    //   Reader 自己处理半截字符；逐块 String(bytes, UTF_8) 会乱码。
                    val rd = InputStreamReader(input, Charsets.UTF_8)
                    val buf = CharArray(CHUNK_CHARS)
                    while (true) {
                        val n = rd.read(buf)
                        if (n < 0) break
                        // send 返回 Unit（通道关掉时抛异常）⇒ 由下面的 catch 收口。
                        producer.send(String(buf, 0, n))
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // 把异常作为 channel 的关闭原因递出去 ⇒ 收集端看到的是**原始异常**
                    // （直接抛的话会被父作用域的 CancellationException 盖掉）。
                    val cause = if (timedOut.get() && e is IOException) timeoutError() else e
                    producer.close(cause)
                } finally {
                    producer.close()
                }
            }

            // 看门狗：把「总时长」也压回 timeoutSeconds（request timeout 只管到响应头）。
            val watchdog = launch(Dispatchers.Default) {
                val remain = deadline - System.nanoTime()
                if (remain > 0) delay(remain / 1_000_000)
                timedOut.set(true)
                future.cancel(true)
                closer.close()
            }

            try {
                awaitClose {
                    // 取消 ⇒ 立刻关掉底层 socket ⇒ 阻塞中的 read 立刻醒（否则客户端永远返回不了）。
                    future.cancel(true)
                    closer.close()
                }
            } finally {
                // ⚠️ 这个 finally 是**取消路径的兜底**：万一取消发生在进入 awaitClose 之前
                //   （awaitClose 里的 check(isActive) 会直接抛），清理也必须照样发生，
                //   否则被取消的协程会卡在阻塞读里，整个 collect 永远返回不了。
                reader.cancel()
                watchdog.cancel()
                future.cancel(true)
                closer.close()
            }
        }

    private fun timeoutError(): HttpTimeoutException =
        HttpTimeoutException("LLM HTTP request timed out after ${timeoutSeconds}s")
}

/** 一次流式读取的块大小（字符数）。 */
private const val CHUNK_CHARS = 4096

/**
 * 「取消时要把哪个流关掉」的登记处。
 *
 * 为什么需要它：JDK 的 `ofInputStream` 给的响应流**只有拿到之后**才能关，
 * 而「取消」可能发生在「拿到流」之前或之后 ⇒ 需要一个能同时覆盖两种时序的登记处。
 *
 * 关闭动作**幂等**、且**不抛异常**（取消路径上任何异常都会盖掉真正的错误）。
 */
private class StreamCloser {
    private val lock = Any()
    private var stream: InputStream? = null
    private var closed = false

    /** 登记正在读的那个流；若已经关过了就立刻把它关掉（避免时序竞态漏关）。 */
    fun register(s: InputStream) {
        val closeNow = synchronized(lock) {
            if (closed) true else { stream = s; false }
        }
        if (closeNow) closeQuietly(s)
    }

    /** 关掉登记的流（可重复调用）。 */
    fun close() {
        val s = synchronized(lock) {
            closed = true
            val cur = stream
            stream = null
            cur
        }
        if (s != null) closeQuietly(s)
    }

    private fun closeQuietly(s: InputStream) {
        try {
            s.close()
        } catch (_: Exception) {
            // 取消路径上关流失败无所谓：流已经不可用了。
        }
    }
}
