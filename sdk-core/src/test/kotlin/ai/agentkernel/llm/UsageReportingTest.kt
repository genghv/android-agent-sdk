package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用量（**含缓存命中数**）的解析契约测试。
 *
 * ## 为什么这组测试必须有
 *
 * 前缀缓存是**服务端自动**的：客户端不声明断点，服务端按「已持久化的前缀单元」匹配。
 * **客户端唯一能看到命中情况的窗口就是响应里的 `usage`**。
 *
 * 不解析它 = 缓存变成黑盒 = 「命中率 99%」这种话没人能验证。
 * 而解析错了更坏：把「未命中」当「总量」，算出来的命中率会 > 100% 或者完全错 ——
 * 那等于**谎报**缓存效果。
 */
class UsageReportingTest {

    /** 记录请求体的假传输。 */
    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return response
        }
    }

    private fun request() = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(UserMessage("你好")),
    )

    // ---------- 非流式 ----------

    @Test
    fun `非流式：解析出命中与未命中，prompt_tokens 是总量`() = runBlocking {
        val transport = FakeTransport(
            """
            {"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":2000,"completion_tokens":50,"total_tokens":2050,
                      "prompt_cache_hit_tokens":1800,"prompt_cache_miss_tokens":200}}
            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val msg = client.completeNonStreaming(request())
        val usage = assertNotNull(msg.usage, "响应里带了 usage 就必须解析出来")

        assertEquals(2000, usage.promptTokens)
        assertEquals(1800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(0.9, usage.cacheHitRatio, 0.0001)
    }

    @Test
    fun `非流式：只给 cached_tokens 的形状也要认（OpenAI 兼容网关的另一种写法）`() = runBlocking {
        val transport = FakeTransport(
            """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1000,"completion_tokens":10,"total_tokens":1010,
                      "prompt_tokens_details":{"cached_tokens":750}}}
            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val usage = assertNotNull(client.completeNonStreaming(request()).usage)
        assertEquals(750, usage.cacheHitTokens)
        assertEquals(250, usage.cacheMissTokens)
        assertEquals(0.75, usage.cacheHitRatio, 0.0001)
    }

    @Test
    fun `非流式：prompt_tokens 报的是未命中口径时，要归一成总量`() = runBlocking {
        // 内核的 disjoint 约定：inputTokens 是**未缓存**的输入，命中部分单独报。
        // 有些网关就这么报 —— 直接拿来算命中率会算错。
        val transport = FakeTransport(
            """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":200,"completion_tokens":10,"total_tokens":210,
                      "prompt_cache_hit_tokens":1800}}
            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val usage = assertNotNull(client.completeNonStreaming(request()).usage)
        assertEquals(2000, usage.promptTokens, "总量 = 未命中 + 命中")
        assertEquals(1800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
        assertEquals(0.9, usage.cacheHitRatio, 0.0001)
    }

    @Test
    fun `上游没给 usage 时是 null，不是假的 0（不知道 ≠ 没有）`() = runBlocking {
        val transport = FakeTransport(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}]}"""
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        assertNull(client.completeNonStreaming(request()).usage, "没上报用量就该是 null")
    }

    // ---------- 流式 ----------

    @Test
    fun `流式：用量帧（choices 空数组）也要收到`() = runBlocking {
        val transport = FakeTransport(
            """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":2000,"completion_tokens":50,"total_tokens":2050,"prompt_cache_hit_tokens":1800,"prompt_cache_miss_tokens":200}}

            data: [DONE]

            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val msg = client.completeStreaming(request())
        val usage = assertNotNull(msg.usage, "流式最后一帧的 usage 必须收到")

        assertEquals(2000, usage.promptTokens)
        assertEquals(1800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
        assertEquals(0.9, usage.cacheHitRatio, 0.0001)
        assertEquals("stop", msg.finishReason)
        assertEquals("你好", msg.content)
    }

    @Test
    fun `流式：要显式向服务端要用量，否则根本拿不到缓存命中数`() = runBlocking {
        val transport = FakeTransport("data: [DONE]\n\n")
        val client = ChatCompletionsClient("https://example.invalid", transport)

        client.completeStreaming(request())
        val body = assertNotNull(transport.lastBody, "请求体应该被记下来")
        assertTrue(body.contains("stream_options"), "流式请求必须带 stream_options")
        assertTrue(body.contains("include_usage"), "必须带 include_usage=true")
    }
}
