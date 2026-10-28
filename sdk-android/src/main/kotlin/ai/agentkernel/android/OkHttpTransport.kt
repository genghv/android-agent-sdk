package ai.agentkernel.android

import ai.agentkernel.llm.openai.GetTransport
import ai.agentkernel.llm.openai.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Android 上的 HTTP 传输（OkHttp 实现）。用法：ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()) */
class OkHttpTransport(
    timeoutSeconds: Long = 120L,
) : Transport, GetTransport {

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
                if (!resp.isSuccessful) error("LLM HTTP ${resp.code}: ${text.take(500)}")
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
                if (!resp.isSuccessful) error("LLM HTTP ${resp.code}: ${text.take(500)}")
                text
            }
        }
}