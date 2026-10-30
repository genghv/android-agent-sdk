package ai.agentkernel.android

import ai.agentkernel.llm.openai.GetTransport
import ai.agentkernel.llm.openai.HeaderRedaction
import ai.agentkernel.llm.openai.StreamingTransport
import ai.agentkernel.llm.openai.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Android 上的 HTTP 传输（OkHttp 实现）。用法：ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()) */
class OkHttpTransport(
    timeoutSeconds: Long = 120L,
) : Transport, GetTransport, StreamingTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).post(body.toRequestBody(jsonMedia))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // ⚠️ **先抹后截**：上游可能把 Authorization 原样回显在错误体里 ——
                    //   这段文本会进异常消息、再回灌给模型 ⇒ 明文 key 不能跟着走。
                    error("LLM HTTP ${resp.code}: ${HeaderRedaction.redact(text, headers).take(500)}")
                }
                text
            }
        }

    /** GET（拉档位清单用）。 */
    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("LLM HTTP ${resp.code}: ${HeaderRedaction.redact(text, headers).take(500)}")
                }
                text
            }
        }

    /**
     * **真流式**：响应体**按块**吐出来（不等 body 结束）。
     *
     * ## 与 `post` 的差别
     *
     * - `post` 用 `body.string()` ⇒ 必须等**整个 body 结束**才返回
     *   ⇒ 服务端早就把 `[DONE]` 发完了、客户端还在干等（假流式）。
     * - 这里直接读 `body.source()` ⇒ 随到随吐。
     *
     * ## 三件必须做对的事
     *
     * 1. **取消必须立刻生效** —— 客户端见到 `[DONE]` 会**主动取消**本流
     *    ⇒ 取消时必须 `call.cancel()`（关掉底层 socket），
     *    否则被取消的协程卡死在阻塞读里，客户端永远返回不了。
     * 2. **UTF-8 跨块不得乱码** —— 块边界可能劈开一个多字节字符。
     *    ⚠️ 这里**没有**用 `source.readUtf8(n)`：okio 的
     *    `RealBufferedSource.readUtf8(long)` 实测就是 `buffer.readUtf8(byteCount)`
     *    —— 它**按字节数**切，落在半个字符上就是 `�`；而且它要求缓冲区里
     *    已经凑够 n 个字节（否则直接抛 `size < byteCount`）⇒ 拿它做流式切块
     *    既会乱码又会抛。
     *    ⇒ 改用 `InputStreamReader`（和 JDK 侧同一个机制）：
     *       Reader 自己处理半截字符，**不手动拼字节**。
     * 3. **错误处理与 `post` 同款** —— 非 2xx 抛异常，消息里**先抹凭据再截断**。
     *
     * ## 超时
     *
     * 沿用 client 上那三个参数（`callTimeout` / `connectTimeout` / `readTimeout`），
     * **没有另建 client** ⇒ 流式路径的总时长上限与 `post` 一致。
     */
    override fun postStream(url: String, headers: Map<String, String>, body: String): Flow<String> =
        callbackFlow {
            val builder = Request.Builder().url(url).post(body.toRequestBody(jsonMedia))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val call = client.newCall(builder.build())
            val producer = this          // ProducerScope：关 channel / 把异常递给收集端

            val reader = launch(Dispatchers.IO) {
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val text = resp.body?.string().orEmpty()
                            // ⚠️ **先抹后截**（与 post 同款）：上游可能把 Authorization 原样回显。
                            throw IllegalStateException(
                                "LLM HTTP ${resp.code}: ${HeaderRedaction.redact(text, headers).take(500)}"
                            )
                        }
                        val src = resp.body?.source()
                            ?: throw IOException("LLM HTTP ${resp.code}: response body is null")
                        // ⚠️ **必须用 Reader 读**：块边界可能劈开一个多字节字符。
                        val rd = InputStreamReader(src.inputStream(), Charsets.UTF_8)
                        val buf = CharArray(CHUNK_CHARS)
                        while (true) {
                            val n = rd.read(buf)
                            if (n < 0) break
                            // send 返回 Unit（通道关掉时抛异常）⇒ 由下面的 catch 收口。
                            producer.send(String(buf, 0, n))
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // 把异常作为 channel 的关闭原因递出去 ⇒ 收集端看到的是**原始异常**
                    // （直接抛的话会被父作用域的 CancellationException 盖掉）。
                    producer.close(e)
                } finally {
                    producer.close()
                }
            }

            try {
                awaitClose {
                    // 取消 ⇒ 立刻 cancel ⇒ 底层 socket 关闭 ⇒ 阻塞中的 read 立刻醒。
                    call.cancel()
                }
            } finally {
                // ⚠️ 这个 finally 是**取消路径的兜底**：万一取消发生在进入 awaitClose 之前
                //   （awaitClose 里的 check(isActive) 会直接抛），清理也必须照样发生，
                //   否则被取消的协程会卡在阻塞读里，整个 collect 永远返回不了。
                reader.cancel()
                call.cancel()
            }
        }
}

/** 一次流式读取的块大小（字符数）。 */
private const val CHUNK_CHARS = 4096
