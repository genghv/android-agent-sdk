package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.StreamingTransport
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **真流式**的契约测试（客户端这一侧）。
 *
 * ## 这组测试为什么必须有
 *
 * 老路是「**假流式**」：传输一次性拿到整个 HTTP body ⇒ 客户端必须等 body **整个结束**
 * 才开始解析 ⇒ **服务端早就把 `[DONE]` 发出来了，客户端还在干等**。
 *
 * ⇒ 修法是**可选**的 [StreamingTransport]：传输逐块吐 ⇒ 客户端见到 `[DONE]` **立刻返回**。
 *
 * ## 最关键的那条用例
 *
 * 「**发完 `[DONE]` 之后上游流不结束**（服务端不关连接）⇒ 客户端**仍要返回**」。
 * ⇒ 这条用例专门盯住「取消上游」这件事：只是不等 body 不算修好，
 * 得**真的把上游取消掉**才不算卡。
 */
class TrueStreamingTest {

    /** 老路：只实现 [Transport]（一次性拿整块）。 */
    private class WholeBodyTransport(private val body: String) : Transport {
        var postCalls = 0
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            postCalls++
            return this.body
        }
    }

    /**
     * 真流式：实现 [StreamingTransport]。
     *
     * ★ `endless = true` 时，发完所有块**故意不结束**（`awaitCancellation()`）⇒ 模拟
     * 「服务端发完 `[DONE]` 却不关连接」这种最难缠的情况。
     *
     * ⚠️ `endless = false` 才是「服务端回一整块普通 JSON」的真实形态 ——
     * 普通响应体**有结束**（Content-Length / chunked 收尾）⇒ 流会自己结束。
     * （老路 `post` 同样要等 body 结束 ⇒ 行为一致，不是回归。）
     */
    private class EndlessStreamTransport(
        private val chunks: List<String>,
        private val endless: Boolean = true,
    ) : Transport, StreamingTransport {
        var postCalls = 0
        var streamCalls = 0

        /** 上游流**真的被取消**了吗（而不是自己结束了）。 */
        @Volatile
        var cancelled = false

        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            postCalls++
            return chunks.joinToString("")
        }

        override fun postStream(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): Flow<String> = flow {
            streamCalls++
            try {
                chunks.forEach { emit(it) }
                if (endless) {
                    // ★ 故意不结束 ⇒ 只有「客户端主动取消」才能让这次收集返回。
                    awaitCancellation()
                }
            } finally {
                cancelled = true
            }
        }
    }

    private val sseBody = """
        : hb

        data: {"choices":[{"index":0,"delta":{"content":"你"}}]}

        data: {"choices":[{"index":0,"delta":{"content":"好"}}]}

        data: [DONE]

    """.trimIndent()

    private fun request() = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(SystemMessage("你是助手。"), UserMessage("你好")),
    )

    private fun client(transport: Transport) =
        ChatCompletionsClient("https://api.deepseek.com/v1", transport)

    // ---------- 真流式 ----------

    @Test
    fun `真流式：见 DONE 立刻返回，不等 body 结束`() = runBlocking {
        val transport = EndlessStreamTransport(listOf(sseBody))

        // ★ withTimeout 兜底：客户端要是卡住，这条用例会**失败**而不是把测试挂死。
        val msg = withTimeout(5_000) {
            client(transport).completeStreaming(request())
        }

        assertEquals("你好", msg.content)
        assertEquals(1, transport.streamCalls, "★ 传输实现了 StreamingTransport ⇒ 必须走真流式")
        assertEquals(0, transport.postCalls, "★ 真流式下不该再走整块那条路")
        assertTrue(transport.cancelled, "★ 见 [DONE] 后必须**取消上游流**（不是等它自己结束）")
    }

    @Test
    fun `真流式：帧被劈成半行也能拼回来`() = runBlocking {
        val half = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\""
        val rest = "}}]}\n"
        val transport = EndlessStreamTransport(
            listOf(half, rest, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n", "data: [DONE]\n"),
        )

        val msg = withTimeout(5_000) { client(transport).completeStreaming(request()) }

        assertEquals("你好", msg.content, "跨块的半行必须缓冲后拼回来")
    }

    @Test
    fun `真流式：心跳注释帧被跳过`() = runBlocking {
        val transport = EndlessStreamTransport(
            listOf(": hb\n", ": hb\n", sseBody),
        )

        val msg = withTimeout(5_000) { client(transport).completeStreaming(request()) }

        assertEquals("你好", msg.content, "心跳是注释帧（`:` 开头）⇒ 不该当数据喂进去")
    }

    @Test
    fun `真流式：服务端不理会 stream 仍回一整块 JSON 时也能解析`() = runBlocking {
        val whole = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"整块"},"finish_reason":"stop"}]}
        """.trimIndent()
        // ⚠️ 普通响应体**有结束**（Content-Length / chunked 收尾）⇒ 流会自己结束；
        //   老路 `post` 同样要等 body 结束 ⇒ 行为一致，不是回归。
        val transport = EndlessStreamTransport(listOf(whole), endless = false)

        val msg = withTimeout(5_000) { client(transport).completeStreaming(request()) }

        assertEquals("整块", msg.content, "★ 流式路径也得兜住「服务端不理会 stream:true」")
    }

    // ---------- 老路（只实现 Transport）必须一字不变 ----------

    @Test
    fun `老路：传输只实现 Transport 时走整块解析，行为不变`() = runBlocking {
        val transport = WholeBodyTransport(sseBody)

        val msg = withTimeout(5_000) { client(transport).completeStreaming(request()) }

        assertEquals("你好", msg.content)
        assertEquals(1, transport.postCalls, "没实现 StreamingTransport ⇒ 照旧走整块")
    }

    @Test
    fun `老路：非 SSE 的一整块 JSON 照旧能解析`() = runBlocking {
        val whole = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"整块"},"finish_reason":"stop"}]}
        """.trimIndent()
        val transport = WholeBodyTransport(whole)

        val msg = withTimeout(5_000) { client(transport).completeStreaming(request()) }

        assertEquals("整块", msg.content)
    }
}
