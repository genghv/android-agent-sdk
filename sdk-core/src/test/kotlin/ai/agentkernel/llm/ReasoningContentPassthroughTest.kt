package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.LlmAuth
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessageCodec
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
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
import kotlin.test.assertTrue

/**
 * **思维链回传**（`reasoning_content`）—— 直连厂商时的 400 防线。
 *
 * ## 厂商规则（DeepSeek 官方口径）
 *
 * > 带 `tools` 的请求，历史里**每一条 assistant 的 `reasoning_content` 都要回传** ——
 * > **包括模型那一回合没有调工具的回合**；
 * > 漏回传 ⇒ **400**（`The reasoning_content in the thinking mode must be passed back to the API`）。
 * > 反过来，**不带 tools 的请求回传它是无害的**（上游会忽略、不拼进上下文）。
 *
 * ## 这组测试守的是什么
 *
 * 判据是「**这次请求带不带 tools**」，**不是**「**这条 assistant** 带没带 tool_calls」。
 * 这两件事混起来，就会漏掉最常见的会话形状：
 *
 * ```
 * 第 1 轮：模型想了、答了一段文字（**没有** tool_calls，但**有** reasoning_content）
 * 第 2 轮：模型这次调了工具 ⇒ 这次请求带 tools
 *          ⇒ 历史里第 1 轮那条 assistant 的思维链**必须**还在，否则 400
 * ```
 *
 * ⚠️ 旧实现按「该条 assistant 有没有 tool_calls」判断 ⇒ 第 1 轮的思维链被丢掉
 * ⇒ 直连厂商时在这个形状上 **400**。
 */
class ReasoningContentPassthroughTest {

    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return response
        }
    }

    private val okResponse = """
        {"id":"1","object":"chat.completion","created":1,"model":"deepseek-chat",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好","reasoning_content":"想了一下"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun toolSchema() = """
        [{"type":"function","function":{"name":"bash","description":"跑命令",
          "parameters":{"type":"object","properties":{}}}}]
    """.trimIndent()

    /** 一条**纯文本**、但带思维链的 assistant 回合（模型这一回合没有调工具）。 */
    private val textTurnWithReasoning = AssistantMessage(
        content = "先说结论：可以。",
        reasoningContent = "先看看目录里有什么",
    )

    /** 一条调了工具的 assistant 回合。 */
    private val toolTurn = AssistantMessage(
        content = "",
        toolCalls = listOf(ToolCall(id = "call_1", name = "bash", arguments = """{"command":"ls"}""")),
        reasoningContent = "先列一下目录",
    )

    // ================= 线格式（单条消息）=================

    @Test
    fun `纯文本回合的思维链也要回传`() {
        val json = textTurnWithReasoning.toJson()
        assertEquals("先看看目录里有什么", json["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("tool_calls"), "这条回合本来就没有工具调用")
    }

    @Test
    fun `带 tool_calls 的回合思维链照旧回传`() {
        val json = toolTurn.toJson()
        assertEquals("先列一下目录", json["reasoning_content"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("tool_calls"))
    }

    @Test
    fun `没有思维链就不带这个字段`() {
        val json = AssistantMessage(content = "普通回答").toJson()
        assertFalse(json.containsKey("reasoning_content"), "没有思维链就别凭空造一个字段")
    }

    @Test
    fun `空串思维链也不带`() {
        // 上游给空串 = 没有思维链；发一个空字段没意义，还可能让上游当成「有但内容空」。
        val json = AssistantMessage(content = "普通回答", reasoningContent = "").toJson()
        assertFalse(json.containsKey("reasoning_content"))
    }

    // ================= 请求体（整段会话）=================

    @Test
    fun `带 tools 的请求里 历史中那条纯文本回合的思维链不能丢`() = runBlocking {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            transport,
            LlmAuth.bearer("sk-test"),
        )

        client.completeStreaming(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(
                    SystemMessage("你是助手。"),
                    UserMessage("看下目录"),
                    textTurnWithReasoning,
                    toolTurn,
                    ToolMessage(toolCallId = "call_1", content = "a.txt"),
                ),
                tools = Json.parseToJsonElement(toolSchema()).jsonArray.map { it.jsonObject },
            )
        )

        val messages = Json.parseToJsonElement(assertNotNull(transport.lastBody))
            .jsonObject["messages"]!!.jsonArray
        // 第 3 条 = 那条「纯文本但有思维链」的 assistant。
        val third = messages[2].jsonObject
        assertEquals("assistant", third["role"]!!.jsonPrimitive.content)
        assertEquals(
            "先看看目录里有什么",
            third["reasoning_content"]!!.jsonPrimitive.content,
            "★ 带 tools 的请求里，这条回合的思维链必须还在 —— 丢了上游就 400",
        )
    }

    @Test
    fun `不带 tools 的请求也要带上思维链（多带无害 漏带要 400）`() = runBlocking {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            transport,
            LlmAuth.bearer("sk-test"),
        )

        client.completeStreaming(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(
                    UserMessage("你好"),
                    textTurnWithReasoning,
                ),
            )
        )

        val messages = Json.parseToJsonElement(assertNotNull(transport.lastBody))
            .jsonObject["messages"]!!.jsonArray
        assertEquals(
            "先看看目录里有什么",
            messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
            "★ 上游会忽略它，但漏带的代价是 400 ⇒ 一律带上",
        )
    }

    // ================= 与归档那份保持一致 =================

    @Test
    fun `归档那份与线格式口径一致（不能一个留一个丢）`() {
        val archived = ChatMessageCodec.toJson(textTurnWithReasoning)
        assertEquals(
            "先看看目录里有什么",
            archived["reasoning_content"]!!.jsonPrimitive.content,
            "归档早就保留了思维链；线格式要是丢，两套口径就对不上了",
        )
        assertEquals(
            textTurnWithReasoning.toJson()["reasoning_content"],
            archived["reasoning_content"],
            "线格式与归档必须是同一份内容",
        )
    }

    @Test
    fun `归档反序列化后思维链还在（回放历史时不会丢）`() {
        val archived = ChatMessageCodec.toJson(textTurnWithReasoning)
        val back = assertNotNull(ChatMessageCodec.fromJson(archived)) as AssistantMessage
        assertEquals("先看看目录里有什么", back.reasoningContent)
    }
}
