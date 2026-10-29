package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.HeaderRedaction
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **凭据明文不许跟着错误文本走** —— 上游回显 `Authorization` 的那道口子。
 *
 * ## 为什么要这条
 *
 * 上游报错的响应体会被拼进异常消息，而这条异常消息**会回灌给模型**
 * （当工具结果 / 错误反馈）⇒ 上游一旦把 `Authorization` 原样回显，
 * **用户的第三方 API key 就进了模型上下文** ——
 * 那正是「凭据加密」这套设计要防的事，在错误路径上被绕过去了。
 *
 * （同型事故真实发生过：把解密结果直接塞进 `fetch` 头，密钥含 CR/LF 时
 *   底层库的 TypeError 原文带着**整个头值**，兜底 catch 又原样回显 ⇒ 回显明文密钥。）
 *
 * ## 这组测试守的是什么
 *
 * - 上游错误体里**回显的凭据**不许出现在异常消息里；
 * - **`Bearer <token>` 只回显 token**（不带前缀）也要被抹；
 * - **普通头**（`Content-Type` / `Accept`）不许被抹（抹了会把报错文本弄坏）；
 * - 异常**类型**不变（宿主的 `catch` 不受影响），但**不保留原异常对象**
 *   （原对象的消息里可能有明文 ⇒ 挂在 cause 上等于又留了一份）。
 */
class HeaderRedactionTest {

    /** 一条假凭据（**绝不用真 key**）。 */
    private val secret = "sk-redaction-selftest-not-a-real-key"

    private val authHeaders = mapOf("Authorization" to "Bearer $secret")

    // ────────────── 单元：抹除规则本身 ──────────────

    @Test
    fun `Authorization 的值会被抹掉`() {
        val text = "LLM HTTP 401: bad key $secret"
        val out = HeaderRedaction.redact(text, authHeaders)
        assertFalse(out.contains(secret), "凭据不许留在文本里：$out")
        assertTrue(out.contains(HeaderRedaction.MASK), "要留个占位说明抹过：$out")
        assertTrue(out.contains("LLM HTTP 401"), "其余内容原样保留：$out")
    }

    @Test
    fun `只回显 token 本身（不带 Bearer 前缀）也要被抹`() {
        // 上游常常只把 token 回显出来，不带 "Bearer "。
        val text = "invalid api key: $secret"
        val out = HeaderRedaction.redact(text, authHeaders)
        assertFalse(out.contains(secret), "凭据不许留在文本里：$out")
    }

    @Test
    fun `普通头不会被抹（抹了会把报错文本弄坏）`() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
        )
        val text = "unexpected content-type application/json"
        val out = HeaderRedaction.redact(text, headers)
        assertTrue(out.contains("application/json"), "普通头的值要原样保留：$out")
    }

    @Test
    fun `头名不像凭据的值不会被抹`() {
        val headers = mapOf("X-Trace-Id" to "trace-abcdef123456")
        val text = "trace-abcdef123456"
        val out = HeaderRedaction.redact(text, headers)
        assertTrue(out.contains("trace-abcdef123456"), "不像凭据的头不该被抹：$out")
    }

    @Test
    fun `自定义头名里的凭据也会被抹`() {
        val headers = mapOf("X-Api-Key" to secret)
        val out = HeaderRedaction.redact("key=$secret", headers)
        assertFalse(out.contains(secret), "X-Api-Key 里的凭据也要抹：$out")
    }

    @Test
    fun `太短的值不抹（避免误伤正常文本）`() {
        val headers = mapOf("X-Token" to "ab")
        val out = HeaderRedaction.redact("about", headers)
        assertTrue(out.contains("about"), "太短的值不该被抹：$out")
    }

    // ────────────── 客户端级：错误文本不许带明文 ──────────────

    /**
     * 模拟传输实现：上游非 2xx ⇒ `error(...)` ⇒ IllegalStateException（错误体里回显了凭据）。
     *
     * ⚠️ ⚠️ **属性名不许叫 `body`** —— `post(...)` 的形参也叫 `body`，
     *   **形参会遮蔽属性** ⇒ `$body` 取到的是**请求体**（聊天 JSON），
     *   而不是这里要回显的凭据 ⇒ 用例变成**空转的**（断言恒真，测不出任何东西）。
     *   （2026-10-29 实踩：反向验证时「拆掉客户端接线」9 条**全绿**，
     *     才暴露这个遮蔽 —— 空转的用例比没有用例更坏，它给人「已覆盖」的错觉。）
     */
    private class EchoingTransport(private val echoed: String) : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
            error("LLM HTTP 401: invalid api key $echoed")
    }

    /** 返回一个**缺 choices** 的响应体（片段会进异常消息）。 */
    private class BadShapeTransport(private val payload: String) : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String = payload
    }

    private fun request() = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(
            SystemMessage("你是助手。"),
            UserMessage("你好"),
        ),
    )

    @Test
    fun `上游回显的凭据不许出现在异常消息里`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            secret,
            EchoingTransport(secret),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeStreaming(request())
        }
        val m = e.message!!
        assertFalse(m.contains(secret), "异常消息里不许有凭据明文：$m")
        // 诊断信息不能因此全丢。
        assertTrue(m.contains("LLM HTTP 401"), "状态码要保留：$m")
        assertTrue(m.contains("请求形状"), "形状摘要要保留：$m")
    }

    @Test
    fun `异常类型不变、且不保留原异常对象（cause 为空）`() = runBlocking {
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            secret,
            EchoingTransport(secret),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeNonStreaming(request())
        }
        assertEquals("java.lang.IllegalStateException", e::class.java.name)
        // ⚠️ 原异常的消息里可能有明文 ⇒ 不能挂在 cause 上（宿主一打日志就漏出去）。
        assertNull(e.cause, "不许把原异常挂在 cause 上：${e.cause}")
    }

    @Test
    fun `响应体片段里的凭据也会被抹`() = runBlocking {
        // 上游回了 200，但内容缺 choices ⇒ 报错时会把响应片段带出去。
        val body = """{"error":"bad key $secret"}"""
        val client = ChatCompletionsClient(
            "https://api.deepseek.com/v1",
            secret,
            BadShapeTransport(body),
        )

        val e = assertFailsWith<IllegalStateException> {
            client.completeNonStreaming(request())
        }
        val m = e.message!!
        assertFalse(m.contains(secret), "响应片段里不许带凭据明文：$m")
        assertTrue(m.contains("响应缺少 choices"), "诊断信息要保留：$m")
    }
}
