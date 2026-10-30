package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **DeepSeek 直连模式**的兼容性护栏。
 *
 * ## 为什么这组测试必须有
 *
 * 交付形态有两种接法，**两种都必须能用**：
 * 1. **直连 DeepSeek**：传 `baseUrl` + `apiKey` 就跑 —— 线格式就是 DeepSeek 原生那套
 *    （`POST /chat/completions`，`messages` 里带 `role:"system"`）；
 * 2. **接自建服务**：同一套线格式，服务端做映射（模型固定、提示词来自后端、限频、缓存可观测）。
 *
 * SDK 这几年一直在加「后端侧」的能力（会话标识、服务端前缀报告……）。
 * **每加一个后端字段，都可能悄悄改掉直连 DeepSeek 时发出去的请求体** ——
 * 那是「发布版适配 DeepSeek API」这条承诺的地基。
 *
 * 所以这里**把请求体的键集合钉死**：以后谁往 body 里塞了新字段，这条测试就红。
 */
class DeepSeekDirectCompatTest {

    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return response
        }
    }

    /** DeepSeek 原生形状的响应：**没有** `prefix` 这个字段。 */
    private val deepSeekResponse = """
        {"id":"1","object":"chat.completion","created":1,"model":"deepseek-chat",
         "choices":[{"index":0,"message":{"role":"assistant","content":"在","reasoning_content":"想了一下"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":2000,"completion_tokens":50,"total_tokens":2050,
                  "prompt_cache_hit_tokens":1800,"prompt_cache_miss_tokens":200}}
    """.trimIndent()

    private fun request(sessionId: String? = null) = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(
            SystemMessage("你是助手。"),
            UserMessage("你好"),
        ),
        sessionId = sessionId,
    )

    @Test
    fun `直连 DeepSeek：请求体的键集合不能被后端字段污染`() = runBlocking {
        val transport = FakeTransport(deepSeekResponse)
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            transport,
            ai.agentkernel.llm.openai.LlmAuth.bearer("sk-test"),
        )

        client.completeStreaming(request())

        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        val keys = body.keys.sorted()
        // 没有 tools / temperature / max_tokens / reasoning_strength 时，body 就只该有这几个。
        assertEquals(listOf("messages", "model", "stream", "stream_options"), keys)
        // ★ session_id 只对自建服务有意义 —— 直连时**不许**出现。
        assertFalse(body.containsKey("session_id"), "直连 DeepSeek 不该带 session_id")
    }

    @Test
    fun `直连 DeepSeek：system 走 messages 的第 0 条（DeepSeek 原生形状）`() = runBlocking {
        val transport = FakeTransport(deepSeekResponse)
        val client = ChatCompletionsClient("https://api.deepseek.com/v1", transport)

        client.completeStreaming(request())

        val messages = Json.parseToJsonElement(assertNotNull(transport.lastBody))
            .jsonObject["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你是助手。", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `直连 DeepSeek：响应没有 prefix 字段时不能崩，且 prefix 就是 null`() = runBlocking {
        val transport = FakeTransport(deepSeekResponse)
        val client = ChatCompletionsClient("https://api.deepseek.com/v1", transport)

        val msg = client.completeStreaming(request())

        assertEquals("在", msg.content)
        assertEquals("想了一下", msg.reasoningContent)
        assertEquals("stop", msg.finishReason)
        // 用量照常解析（缓存命中数是 DeepSeek 原生就有的）。
        val usage = assertNotNull(msg.usage)
        assertEquals(1800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
        // ★ 没有就是没有 —— **不兜底成「前缀完好」**（那是谎报）。
        assertNull(msg.prefix, "服务端没给 prefix 就该是 null")
    }

    @Test
    fun `只有显式设了 sessionId 才会带上 session_id（默认不污染直连）`() = runBlocking {
        val transport = FakeTransport(deepSeekResponse)
        val client = ChatCompletionsClient("https://api.deepseek.com/v1", transport)

        client.completeStreaming(request(sessionId = "sess-1"))

        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        assertTrue(body.containsKey("session_id"))
        assertEquals("sess-1", body["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `思考强度不设就不带（直连时也别塞未知字段）`() = runBlocking {
        val transport = FakeTransport(deepSeekResponse)
        val client = ChatCompletionsClient("https://api.deepseek.com/v1", transport)

        client.completeStreaming(request())

        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        assertFalse(body.containsKey("reasoning_strength"))
    }
}
