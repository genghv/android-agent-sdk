package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **上游失败时的请求形状摘要** —— 只记数量与布尔。
 *
 * ## 为什么要有
 *
 * 上游 400 时，日志里只有厂商那句话，**看不出我们这次发了什么形状**：
 * 是我们漏带了思维链？还是历史里本来就没有？还是上游别的问题？
 * ⇒ 附一份形状摘要（消息数 / assistant 条数 / 带思维链条数 / 带工具调用条数 /
 * 工具结果条数 / 本次带不带 tools / 思维链总字符数），
 * **下次复发能直接对形状，不用再靠猜**。
 *
 * ## 这组测试守的是什么
 *
 * - **摘要里只有数量与布尔** —— 正文、工具参数、思维链内容**一个字都不许进**；
 * - **原异常类型与原消息都不变** —— 宿主的 `catch` 与日志匹配不能被这次增强打断。
 */
class RequestShapeSummaryTest {

    /** 模拟两个传输实现的真实行为：上游非 2xx ⇒ `error(...)` ⇒ IllegalStateException。 */
    private class FailingTransport(private val message: String) : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
            error(message)
    }

    private class OkTransport : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return """
                {"id":"1","object":"chat.completion","created":1,"model":"deepseek-chat",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """.trimIndent()
        }
    }

    private fun toolSchema() = """
        [{"type":"function","function":{"name":"bash","description":"跑命令",
          "parameters":{"type":"object","properties":{}}}}]
    """.trimIndent()

    /** 一条**纯文本但带思维链**的 assistant 回合。 */
    private val textTurn = AssistantMessage(
        content = "先说结论：可以。",
        reasoningContent = "这段思维链的正文 SECRET-COT-TEXT",
    )

    /** 一条调了工具的 assistant 回合。 */
    private val toolTurn = AssistantMessage(
        content = "",
        toolCalls = listOf(ToolCall(id = "call_1", name = "bash", arguments = """{"command":"ls"}""")),
        reasoningContent = "另一段思维链 SECRET-COT-2",
    )

    private fun request(withTools: Boolean) = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(
            SystemMessage("你是助手。"),
            UserMessage("用户问的话 SECRET-USER-TEXT"),
            textTurn,
            toolTurn,
            ToolMessage(toolCallId = "call_1", content = "SECRET-TOOL-OUTPUT"),
        ),
        tools = if (withTools) {
            Json.parseToJsonElement(toolSchema()).jsonArray.map { it.jsonObject }
        } else {
            emptyList()
        },
    )

    @Test
    fun `上游 400 时异常信息里带得上形状摘要`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            FailingTransport("LLM HTTP 400: The reasoning_content in the thinking mode must be passed back to the API"),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeStreaming(request(withTools = true))
        }
        val m = e.message!!

        // 原消息必须原样保留（宿主的日志匹配不能被打断）。
        assertTrue(m.contains("LLM HTTP 400"), "原错误信息必须保留：$m")
        assertTrue(m.contains("must be passed back to the API"), "厂商原话必须保留：$m")
        // 形状摘要：各项对得上。
        assertTrue(m.contains("请求形状"), "要带形状摘要：$m")
        assertTrue(m.contains("消息 5"), "消息数对：$m")
        assertTrue(m.contains("assistant 2"), "assistant 条数对：$m")
        assertTrue(m.contains("带思维链 2"), "带思维链条数对：$m")
        assertTrue(m.contains("带工具调用 1"), "带工具调用条数对：$m")
        assertTrue(m.contains("工具结果 1"), "工具结果条数对：$m")
        assertTrue(m.contains("tools 有"), "本次带不带 tools 要说清：$m")
    }

    @Test
    fun `形状摘要里不许出现任何正文`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            FailingTransport("LLM HTTP 400: boom"),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeStreaming(request(withTools = true))
        }
        val m = e.message!!

        for (secret in listOf("SECRET-COT-TEXT", "SECRET-COT-2", "SECRET-USER-TEXT", "SECRET-TOOL-OUTPUT")) {
            assertFalse(m.contains(secret), "形状摘要不许带正文（$secret）：$m")
        }
        // 思维链只记**总字符数**（尺寸，不是内容）。
        assertTrue(m.contains("思维链总字符"), "思维链只记字符数：$m")
    }

    @Test
    fun `本次不带 tools 时摘要里要写「tools 无」`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            FailingTransport("LLM HTTP 400: boom"),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeStreaming(request(withTools = false))
        }
        assertTrue(e.message!!.contains("tools 无"), "本次没带 tools 就该写「tools 无」：${e.message}")
    }

    @Test
    fun `异常类型不变（宿主的 catch 不受影响）`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            FailingTransport("LLM HTTP 500: boom"),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeNonStreaming(request(withTools = true))
        }
        // 两个传输实现都用 error(...) ⇒ 上游错误就是 IllegalStateException ⇒ 增强后仍是它。
        assertEquals("java.lang.IllegalStateException", e::class.java.name)
    }

    @Test
    fun `成功路径不受影响（不抛、也不带摘要）`() = runBlocking {
        val transport = OkTransport()
        val client = ChatCompletionsClient("https://api.deepseek.com/v1", transport)

        val msg = client.completeStreaming(request(withTools = true))
        assertEquals("好", msg.content)
        // 请求体照旧只按线格式组装，没有多出「形状」字段。
        val body = transport.lastBody!!
        assertFalse(body.contains("请求形状"), "请求体里不该多出形状摘要：$body")
    }
}
