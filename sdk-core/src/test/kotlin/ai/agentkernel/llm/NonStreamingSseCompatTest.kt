package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **非流式入口吃 SSE** 的护栏（v1.1.27）。
 *
 * ## 这条护栏在守什么
 *
 * `completeNonStreaming()` 是宿主**显式**调用才走的那条路（少数后端只支持非流式）。
 * 它原先**只认整块 JSON** ⇒ 对端若无视 `stream:false`、照样吐 SSE ⇒
 * 直接把 SSE 文本当 JSON 解析 ⇒ `JsonDecodingException` ⇒ **显式调它的宿主直接崩**。
 *
 * ⇒ 修法：非流式路径**也先判一次响应形状**，是 SSE 就按流式那样聚合。
 * **默认路径（`complete()` → 流式）一个字都没动**。
 *
 * ## 谁会踩到（实测过，不是推断）
 *
 * - **DeepSeek 官方 API**：认 `stream:false`（回整块 JSON）⇒ ❌ 踩不到；
 * - **自备后端**（OpenAI 兼容服务）：认（`body.stream === true` 才走流式）⇒ ❌ 踩不到；
 * - **只支持流式的第三方 OpenAI 兼容后端**：不认 ⇒ ✅ 会踩（且必须宿主**显式调**非流式）。
 *
 * ⇒ 这条护栏守的是**公开 SDK 给第三方用户用**的那条路。
 *
 * ## 为什么聚合口径要逐条钉
 *
 * 聚合必须与流式路径**完全一致**：分片内容、工具调用的 `arguments` 分片、思维链、
 * `finish_reason`、用量 —— 一个都不能丢，**更不能被提前返回截断**。
 */
class NonStreamingSseCompatTest {

    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        var calls: Int = 0
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            calls++
            return response
        }
    }

    /**
     * 对端无视 `stream:false`、照样吐出来的 SSE：
     * 两片内容 + 思维链 + 工具调用（`arguments` 分两片）+ `finish_reason` + 用量帧 + `[DONE]`。
     */
    private val sseResponse = """
        data: {"id":"1","choices":[{"index":0,"delta":{"content":"你"}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{"content":"好"}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{"reasoning_content":"想了一下"}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"pwd","arguments":"{"}}]}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"path\":\"/tmp\"}"}}]}}]}

        data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

        data: {"id":"1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

        data: [DONE]

    """.trimIndent()

    /** 老路：对端**认** `stream:false`、回整块 JSON（DeepSeek 官方 / 我们自己后端都是这样）。 */
    private val jsonResponse = """
        {"id":"1","object":"chat.completion","created":1,"model":"deepseek-chat",
         "choices":[{"index":0,"message":{"role":"assistant","content":"在","reasoning_content":"想了一下"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":2000,"completion_tokens":50,"total_tokens":2050,
                  "prompt_cache_hit_tokens":1800,"prompt_cache_miss_tokens":200}}
    """.trimIndent()

    private fun request() = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(UserMessage("你好")),
    )

    @Test
    fun `非流式入口遇到 SSE：不再抛异常，内容分片聚合完整`() = runBlocking {
        val transport = FakeTransport(sseResponse)
        val client = ChatCompletionsClient("https://x.example/v1", transport)

        val msg = client.completeNonStreaming(request())

        // ★ 两片内容必须**拼起来**（不是只拿到第一片，也不是被提前返回截断）。
        assertEquals("你好", msg.content)
        // 我们确实发的是非流式请求（是**对端**不听话），别把这层语义改掉。
        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非流式入口遇到 SSE：工具调用的 arguments 分片要拼成完整 JSON`() = runBlocking {
        val transport = FakeTransport(sseResponse)
        val client = ChatCompletionsClient("https://x.example/v1", transport)

        val msg = client.completeNonStreaming(request())

        val calls = msg.toolCalls
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("pwd", calls[0].name)
        // 两片拼起来才是完整 JSON —— 只拼一半就是「工具跑不起来」。
        assertEquals("""{"path":"/tmp"}""", calls[0].arguments)
    }

    @Test
    fun `非流式入口遇到 SSE：思维链与 finish_reason 都带出来`() = runBlocking {
        val transport = FakeTransport(sseResponse)
        val client = ChatCompletionsClient("https://x.example/v1", transport)

        val msg = client.completeNonStreaming(request())

        assertEquals("想了一下", msg.reasoningContent)
        assertEquals("tool_calls", msg.finishReason)
    }

    @Test
    fun `非流式入口遇到 SSE：用量照常解析`() = runBlocking {
        val transport = FakeTransport(sseResponse)
        val client = ChatCompletionsClient("https://x.example/v1", transport)

        val msg = client.completeNonStreaming(request())

        val usage = assertNotNull(msg.usage)
        assertEquals(10, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertEquals(15, usage.totalTokens)
    }

    @Test
    fun `非流式入口遇到整块 JSON：老路行为一字不变`() = runBlocking {
        val transport = FakeTransport(jsonResponse)
        val client = ChatCompletionsClient("https://x.example/v1", transport)

        val msg = client.completeNonStreaming(request())

        assertEquals("在", msg.content)
        assertEquals("想了一下", msg.reasoningContent)
        assertEquals("stop", msg.finishReason)
        val usage = assertNotNull(msg.usage)
        assertEquals(1800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
    }

    @Test
    fun `默认路径 complete() 仍然走流式：body 里 stream=true 且结果照旧`() = runBlocking {
        val transport = FakeTransport(sseResponse)
        val client = ChatCompletionsClient("https://x.example/v1", "sk-test", transport)

        val msg = client.complete(request())

        // ★ 默认路径**没被这次改动碰到**：仍然发 stream:true。
        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("你好", msg.content)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `公开 API 零破坏：公开构造函数的参数类型序列一个字节没变`() {
        // ★ 加参数会改 JVM 签名 ⇒ 已编译、链接旧版 SDK 的宿主直接 NoSuchMethodError
        //   ⇒ 源码级兼容 ≠ 二进制兼容 ⇒ 用反射把公开构造函数的形状钉死。
        val shapes = ChatCompletionsClient::class.java.constructors
            .filterNot { it.isSynthetic }
            .map { c -> c.parameterTypes.map { it.name } }
            .sortedBy { it.size }

        assertEquals(
            listOf(
                listOf(
                    "java.lang.String",
                    "java.lang.String",
                    "ai.agentkernel.llm.openai.Transport",
                    "java.util.Map",
                    "ai.agentkernel.llm.openai.ReasoningWire",
                ),
                listOf(
                    "java.lang.String",
                    "ai.agentkernel.llm.openai.Transport",
                    "ai.agentkernel.llm.openai.LlmAuth",
                    "java.util.Map",
                    "java.lang.String",
                    "ai.agentkernel.llm.openai.ReasoningWire",
                ),
            ),
            shapes,
        )
        // ★ 不许出现新类型（尤其 Boolean）—— 那是「加了个开关」的典型痕迹。
        assertFalse(
            shapes.any { s -> s.any { it == "java.lang.Boolean" } },
            "公开构造函数不许新增 Boolean 参数（会改 JVM 签名 ⇒ 打断老宿主）",
        )
        assertTrue(shapes.isNotEmpty(), "公开构造函数一个都没了？那更糟")
    }
}
