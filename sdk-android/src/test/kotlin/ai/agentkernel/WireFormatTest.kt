package ai.agentkernel

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.JdkHttpTransport
import ai.agentkernel.llm.openai.LlmAuth
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 线格式契约测试 —— 锁的是「真实网关认的形状」，不是「想象的形状」。
 *
 * 基准是 DeepSeek 官方线格式那份**拿 live 流校验过**的实现
 * （harness 仓库 `packages/llm/llm-deepseek/src/types.ts` 与同目录 `serialize.ts`，
 * 标注 "cross-checked against live streams"）。
 *
 * 这几条之所以要有测试：它们**在本地 mock 上看不出问题**——
 * mock 会照单全收。而对着真网关时，每一条都是当场 400：
 * - assistant 的 `content` 必须是字符串，给 null 有些网关直接拒；
 *   「只有思考、既无正文又无 tool_calls」的回合，live API 会 400
 *   （`content or tool_calls must be set`），而消息在会话日志里，一坏就带崩后面每一轮。
 * - **带 tools 的请求里，历史中每一条 assistant 的 `reasoning_content` 都要回传**
 *   （**包括模型那一回合没调工具的**），否则上游 400。
 * - tool 消息的 content 不能是空串。
 * - 生产链路全程 `stream: true`，非流式是没验过的路径。
 */
class WireFormatTest {

    @Test
    fun `assistant 的 content 永远是字符串，不是 null`() {
        // 既无正文又无 tool_calls（模型只在思考通道里回答过）——最容易踩 null 的一条。
        val j = AssistantMessage(content = null, toolCalls = emptyList()).toJson()
        val content = j["content"]!!
        assertTrue(content !is JsonNull, "wire: assistant.content 不能是 JSON null")
        assertTrue(content.jsonPrimitive.content == "", "wire: 空正文序列化成空串")
    }

    @Test
    fun `assistant 带 tool_calls 时回传 reasoning_content`() {
        val j = AssistantMessage(
            content = null,
            toolCalls = listOf(ToolCall("c1", "bash", "{\"command\":\"ls\"}")),
            reasoningContent = "我想先看看目录",
        ).toJson()
        assertTrue(
            j["reasoning_content"]?.jsonPrimitive?.content == "我想先看看目录",
            "wire: 带 tool_calls 的回合要回传 reasoning_content",
        )
    }

    @Test
    fun `assistant 纯文本回合也要回传 reasoning_content`() {
        // ⚠️ **这条改过**（v1.1.7）：旧版断言「纯文本回合不带 reasoning_content」，
        // 理由是「回传没用还费 token」—— 那是我们早前对厂商规则的**误读**。
        //
        // 厂商规则（官方口径）：**只要这次请求带 tools**，历史里**每一条 assistant**
        // 的 `reasoning_content` 都要原样回传 —— **包括模型那一回合没调工具的**；
        // 漏回传 ⇒ 上游 **400**
        // （`The reasoning_content in the thinking mode must be passed back to the API`）。
        //
        // 不带 tools 的请求回传它**无害**（上游会忽略、不拼进上下文）
        // ⇒ 判据是「**请求**带不带 tools」，**不是**「**这条 assistant** 带没带 tool_calls」。
        // 混了就会漏掉「模型先答了一段文字、下一轮才调工具」这个最常见的形状。
        val j = AssistantMessage(content = "答完了", reasoningContent = "这一轮的思考").toJson()
        assertTrue(
            j["reasoning_content"]?.jsonPrimitive?.content == "这一轮的思考",
            "wire: 纯文本回合的思维链也要回传（带 tools 的请求里漏了会 400）",
        )
    }

    @Test
    fun `tool 消息空内容也要有内容`() {
        val j = ToolMessage(toolCallId = "c1", content = "").toJson()
        assertTrue(
            j["content"]?.jsonPrimitive?.content == "(no output)",
            "wire: 空 tool 输出要兜底成 '(no output)'",
        )
    }

    @Test
    fun `内核发出的请求走流式`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply(
                """
                data: {"choices":[{"index":0,"delta":{"content":"好了"}}]}

                data: [DONE]

                """.trimIndent(),
                contentType = "text/event-stream",
            )
        }
        server.use {
            val client = ChatCompletionsClient(server.baseUrl, "k", JdkHttpTransport())
            client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            val body = server.requestBodies[0]
            assertTrue(body.contains("\"stream\":true"), "wire: 内核请求要带 stream:true（生产链路全程流式）")
            assertTrue(
                server.requestHeaders[0].entries.any {
                    it.key.equals("Accept", ignoreCase = true) && it.value == "text/event-stream"
                },
                "wire: 请求头要 Accept: text/event-stream",
            )
        }
    }

    @Test
    fun `服务端不理会 stream 时仍能解析整块 JSON`() = runBlocking {
        // 有些网关忽略 stream:true，仍回一整块 JSON。
        // 那不能当成「一条空流」—— 那样会静默丢掉模型说的话。
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply(
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"直接回的整块",
                "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"bash","arguments":"{\"command\":\"echo hi\"}"}}]}}]}
                """.trimIndent()
            )
        }
        server.use {
            val client = ChatCompletionsClient(server.baseUrl, "k", JdkHttpTransport())
            val msg = client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            assertTrue(msg.content == "直接回的整块", "wire: 非流式响应也要解析出正文")
            assertTrue(msg.toolCalls.size == 1, "wire: 非流式响应也要解析出 tool_calls")
            assertTrue(
                msg.toolCalls[0].argsOrEmpty()["command"]?.jsonPrimitive?.content == "echo hi",
                "wire: 非流式响应里的 arguments 也要能用",
            )
        }
    }

    @Test
    fun `流式响应里的 reasoning_content 能聚合并回传`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply(
                """
                data: {"choices":[{"index":0,"delta":{"reasoning_content":"先看看"}}]}

                data: {"choices":[{"index":0,"delta":{"reasoning_content":"当前目录"}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"bash","arguments":"{\"command\":\"ls\"}"}}]}}]}

                data: [DONE]

                """.trimIndent(),
                contentType = "text/event-stream",
            )
        }
        server.use {
            val client = ChatCompletionsClient(server.baseUrl, "k", JdkHttpTransport())
            val msg = client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            assertTrue(
                msg.reasoningContent == "先看看当前目录",
                "wire: 流式 reasoning_content 片段要按序聚合",
            )
            // 聚合出来的 CoT 必须能原样回到线上。
            val replayed = msg.toJson()
            assertTrue(
                replayed["reasoning_content"]?.jsonPrimitive?.content == "先看看当前目录",
                "wire: 聚合出来的 CoT 要能回传",
            )
        }
    }

    // ---- 换自建服务：只改鉴权与路径，线格式一个字不动 ----

    @Test
    fun `自建服务可以用自己的头名带 token`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""")
        }
        server.use {
            val client = ChatCompletionsClient(
                baseUrl = server.baseUrl,
                transport = JdkHttpTransport(),
                auth = LlmAuth.header("X-Token", "internal-token-123"),
            )
            client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            val headers = server.requestHeaders[0]
            assertTrue(
                headers.entries.any { it.key.equals("X-Token", true) && it.value == "internal-token-123" },
                "auth: 自建服务的自定义头要真的发出去，实际头=${headers.keys}",
            )
            assertTrue(
                headers.keys.none { it.equals("Authorization", true) },
                "auth: 用自定义头时不要再多带一个 Authorization，实际头=${headers.keys}",
            )
            // 线格式不变：请求体仍然是 OpenAI 那套。
            assertTrue(
                server.requestBodies[0].contains("\"messages\""),
                "auth: 换鉴权不能改线格式",
            )
        }
    }

    @Test
    fun `Bearer token 的官方写法仍然可用`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""")
        }
        server.use {
            val client = ChatCompletionsClient(server.baseUrl, "sk-xxx", JdkHttpTransport())
            client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            val headers = server.requestHeaders[0]
            assertTrue(
                headers.entries.any { it.key.equals("Authorization", true) && it.value == "Bearer sk-xxx" },
                "auth: 官方写法要仍然是 Bearer <apiKey>",
            )
        }
    }

    @Test
    fun `token 可以每次请求现取`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""")
        }
        server.use {
            var counter = 0
            val client = ChatCompletionsClient(
                baseUrl = server.baseUrl,
                transport = JdkHttpTransport(),
                auth = LlmAuth.bearerDynamic { "token-${counter++}" },
            )
            repeat(2) {
                client.complete(
                    ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
                )
            }
            assertTrue(
                server.requestHeaders[0].entries.any {
                    it.key.equals("Authorization", true) && it.value == "Bearer token-0"
                },
                "auth: 动态 token 第一次要用当次取到的值",
            )
            assertTrue(
                server.requestHeaders[1].entries.any {
                    it.key.equals("Authorization", true) && it.value == "Bearer token-1"
                },
                "auth: 动态 token 第二次要用新值（token 过期后不用重建客户端）",
            )
        }
    }

    @Test
    fun `chat 端点路径可以改`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""")
        }
        server.use {
            val client = ChatCompletionsClient(
                baseUrl = server.baseUrl,
                transport = JdkHttpTransport(),
                auth = LlmAuth.bearer("t"),
                chatPath = "/internal/turn",
            )
            client.complete(
                ai.agentkernel.protocol.ChatRequest(model = "m", messages = listOf(UserMessage("hi")))
            )
            assertEquals(
                "/v1/internal/turn",
                server.requestPaths[0],
                "auth: 自建服务的 chat 路径要能改",
            )
        }
    }
}
