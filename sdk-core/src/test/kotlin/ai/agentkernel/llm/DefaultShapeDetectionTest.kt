package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
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
 * **默认形状判定**的契约测试：`deepSeek(...)` 工厂按**地址**自动选线格式。
 *
 * ## 规则
 *
 * **地址是 DeepSeek 官方域名** ⇒ 自动用官方形状（`thinking` ＋ `reasoning_effort`）；
 * **其它一切地址** ⇒ 保持自备后端形状（`reasoning_strength`）—— 与以前一样。
 *
 * ## 为什么从「整串相等」改成「按域名」
 *
 * 老规则是 `baseUrl == "https://api.deepseek.com/v1"`（**字符串精确相等**）⇒
 * 下面这些**全是官方地址、却全被判成自备后端形状**：
 * `https://api.deepseek.com`（不带 /v1）、带端口、带尾斜杠、大小写不同……
 *
 * ⇒ 那种调用方发的是 `reasoning_strength` ⇒ **DeepSeek 不认**（忽略或 400）
 * ⇒ 表现为「用户拖了滑块以为设了、其实没变」—— **静默不生效，比报错更糟**。
 *
 * ⚠️ **这是行为变更**：只影响「**没显式指定 `reasoningWire`** 且地址是官方域名」的调用方。
 * 显式传了 `reasoningWire` 的**一律不受影响** ⇒ 零破坏。
 */
class DefaultShapeDetectionTest {

    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return response
        }
    }

    private val okResponse = """
        {"id":"1","object":"chat.completion","created":1,"model":"deepseek-chat",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":10,"completion_tokens":1,"total_tokens":11}}
    """.trimIndent()

    private fun request(strength: Int? = 8) = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(SystemMessage("你是助手。"), UserMessage("你好")),
        reasoningStrength = strength,
    )

    /** 用给定的 baseUrl 走一遍 `deepSeek(...)` 工厂，把实际请求体抓出来。 */
    private suspend fun bodyFor(baseUrl: String): kotlinx.serialization.json.JsonObject {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient.deepSeek(
            apiKey = "sk-test",
            transport = transport,
            baseUrl = baseUrl,
        )
        client.completeStreaming(request())
        return Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
    }

    // ---------- 官方域名 ⇒ 官方形状 ----------

    @Test
    fun `官方域名（带 v1）⇒ 官方形状`() = runBlocking {
        val body = bodyFor("https://api.deepseek.com/v1")
        assertTrue(body.containsKey("thinking"), "官方域名 ⇒ 官方形状（thinking）")
        assertTrue(body.containsKey("reasoning_effort"), "官方形状：开关与强度两个独立参数都要发")
        assertFalse(body.containsKey("reasoning_strength"), "官方地址不该发扩展字段 reasoning_strength")
    }

    @Test
    fun `官方域名（不带 v1）⇒ 官方形状`() = runBlocking {
        val body = bodyFor("https://api.deepseek.com")
        assertTrue(body.containsKey("thinking"), "★ 官方域名（不带 /v1）也该是官方形状")
        assertFalse(body.containsKey("reasoning_strength"))
    }

    @Test
    fun `官方域名（带路径与尾斜杠）⇒ 官方形状`() = runBlocking {
        val body = bodyFor("https://api.deepseek.com/v1/")
        assertTrue(body.containsKey("thinking"), "★ 路径与尾斜杠不该影响域名判定")
        assertFalse(body.containsKey("reasoning_strength"))
    }

    @Test
    fun `官方域名（带端口）⇒ 官方形状`() = runBlocking {
        val body = bodyFor("https://api.deepseek.com:8443/v1")
        assertTrue(body.containsKey("thinking"), "★ 端口不该影响域名判定")
        assertFalse(body.containsKey("reasoning_strength"))
    }

    @Test
    fun `官方域名（大小写不同）⇒ 官方形状`() = runBlocking {
        val body = bodyFor("https://API.DeepSeek.COM/v1")
        assertTrue(body.containsKey("thinking"), "★ 域名判定不该大小写敏感")
        assertFalse(body.containsKey("reasoning_strength"))
    }

    // ---------- 其它地址 ⇒ 自备后端形状（与以前一样） ----------

    @Test
    fun `其它域名 ⇒ 仍是自备后端形状`() = runBlocking {
        val body = bodyFor("https://api.openai.com/v1")
        assertEquals(8, body["reasoning_strength"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("thinking"), "非官方地址 ⇒ 自备后端形状（与以前一样）")
    }

    @Test
    fun `自建后端地址 ⇒ 仍是自备后端形状`() = runBlocking {
        val body = bodyFor("https://our-gateway.example/v1")
        assertEquals(8, body["reasoning_strength"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("thinking"), "自建后端 ⇒ 自备后端形状（与以前一样）")
    }

    @Test
    fun `长得像官方但不是官方 ⇒ 仍是自备后端形状`() = runBlocking {
        // ⚠️ 「后缀像」不算官方域名 —— 只认 host **恰好**是 api.deepseek.com。
        for (url in listOf(
            "https://api.deepseek.com.evil.example/v1",
            "https://deepseek.com/v1",
            "https://my-api.deepseek.com/v1",
        )) {
            val body = bodyFor(url)
            assertTrue(
                body.containsKey("reasoning_strength"),
                "$url 不是官方域名 ⇒ 必须是自备后端形状",
            )
            assertFalse(body.containsKey("thinking"), "$url 不是官方域名 ⇒ 不该发官方形状")
        }
    }

    // ---------- 显式指定形状 ⇒ 一律不受影响（零破坏的关键） ----------

    @Test
    fun `显式指定 GATEWAY 时官方域名也不改形状`() = runBlocking {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient.deepSeek(
            apiKey = "sk-test",
            transport = transport,
            baseUrl = "https://api.deepseek.com/v1",
            reasoningWire = ai.agentkernel.llm.openai.ReasoningWire.GATEWAY,
        )
        client.completeStreaming(request())

        val body = Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject
        assertEquals(8, body["reasoning_strength"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("thinking"), "★ 显式指定的形状**一律不受影响**")
    }
}
