package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.ReasoningWire
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「思考强度」**线格式**的契约测试。
 *
 * ## 为什么这组测试必须有
 *
 * 我们的主契约是**强度编码**（2 的幂），但 `reasoning_strength` 是**我们网关的私有字段**：
 * 网关按该模型的目录，把编码翻译成厂商档位。
 *
 * **直连厂商官方时，网关那一层根本不存在** ⇒ 编码没人翻译 ⇒
 * 厂商要么忽略这个未知字段、要么 400。
 * 多数厂商**忽略**未知字段 ⇒ **不报错、用户拖了滑块以为设了、其实没变** ——
 * 这比报错更糟（报错至少有人知道）。
 *
 * ⚠️ 这条缺陷之前**完全没有测试覆盖**：
 * 旧测试只验证了「**没设**强度时不带 `reasoning_strength`」，
 * 而「**设了**强度 + 直连」这个组合是盲区 ——
 * 也就是说，**只有真出事时才会被发现**。
 *
 * ⇒ 这里把三件事钉死：
 * 1. **网关形状**（默认）：`reasoning_strength` = 整数编码 —— 既有调用方零影响；
 * 2. **厂商形状**：只发厂商认识的字段，**绝不发** `reasoning_strength`；
 * 3. **强度没设就什么都不发**（三种形状都一样）。
 */
class ReasoningWireTest {

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

    private fun request(strength: Int?, extra: JsonObject = JsonObject(emptyMap())) = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(SystemMessage("你是助手。"), UserMessage("你好")),
        reasoningStrength = strength,
        extra = extra,
    )

    private fun bodyOf(transport: FakeTransport): JsonObject =
        Json.parseToJsonElement(assertNotNull(transport.lastBody)).jsonObject

    private suspend fun send(
        wire: ReasoningWire?,
        strength: Int?,
        baseUrl: String = "https://api.deepseek.com/v1",
        extra: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val transport = FakeTransport(okResponse)
        val client = if (wire == null) {
            ChatCompletionsClient(baseUrl, transport)
        } else {
            ChatCompletionsClient(baseUrl, transport, reasoningWire = wire)
        }
        client.completeStreaming(request(strength, extra))
        return bodyOf(transport)
    }

    // ---------- 网关形状（默认，既有调用方零影响）----------

    @Test
    fun `默认是网关形状：强度编码原样下发`() = runBlocking {
        val body = send(wire = null, strength = 8)

        assertEquals(8, body["reasoning_strength"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("thinking"), "网关形状不该发厂商字段")
        assertFalse(body.containsKey("reasoning_effort"), "网关形状不该发厂商字段")
    }

    // ---------- 厂商形状 ----------

    @Test
    fun `DeepSeek 形状：只发 thinking，绝不发 reasoning_strength`() = runBlocking {
        val body = send(wire = ReasoningWire.DEEPSEEK, strength = 8)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertFalse(
            body.containsKey("reasoning_strength"),
            "★ 直连厂商时 `reasoning_strength` 是没人翻译的私有字段 ⇒ 发出去只会被忽略或 400",
        )
        assertFalse(body.containsKey("reasoning_effort"), "DeepSeek 不认这个字段")
    }

    @Test
    fun `DeepSeek 形状：关闭思考发 disabled`() = runBlocking {
        val body = send(wire = ReasoningWire.DEEPSEEK, strength = 1)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("disabled", thinking["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenAI 形状：发字符串档位，绝不发 reasoning_strength`() = runBlocking {
        val body = send(wire = ReasoningWire.OPENAI, strength = 8)

        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_strength"), "★ 同上：厂商不认这个字段")
        assertFalse(body.containsKey("thinking"), "OpenAI 不认这个字段")
    }

    @Test
    fun `OpenAI 形状：取上限内最高的已知档（只向下）`() = runBlocking {
        // 强度是**上限**：取 ≤ 上限里该厂商已知档位中最高的那个。
        assertEquals("minimal", send(wire = ReasoningWire.OPENAI, strength = 1)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("low", send(wire = ReasoningWire.OPENAI, strength = 2)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("medium", send(wire = ReasoningWire.OPENAI, strength = 4)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", send(wire = ReasoningWire.OPENAI, strength = 8)["reasoning_effort"]!!.jsonPrimitive.content)
        // OpenAI 没有比 high 更高的档 ⇒ 16/32 落到 high（与网关那套「只向下」一致）。
        assertEquals("high", send(wire = ReasoningWire.OPENAI, strength = 16)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", send(wire = ReasoningWire.OPENAI, strength = 32)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    // ---------- 强度没设 ----------

    @Test
    fun `强度没设就什么都不发（三种形状都一样）`() = runBlocking {
        for (wire in ReasoningWire.entries) {
            val body = send(wire = wire, strength = null)
            assertFalse(body.containsKey("reasoning_strength"), "$wire 不该发这个字段")
            assertFalse(body.containsKey("thinking"), "$wire 不该发这个字段")
            assertFalse(body.containsKey("reasoning_effort"), "$wire 不该发这个字段")
        }
    }

    // ---------- deepSeek 工厂按地址自动选形状 ----------

    @Test
    fun `deepSeek 工厂：官方地址自动用官方形状`() = runBlocking {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient.deepSeek(apiKey = "sk-test", transport = transport)

        client.completeStreaming(request(8))

        val body = bodyOf(transport)
        assertTrue(body.containsKey("thinking"), "官方地址 ⇒ 官方形状（thinking）")
        assertFalse(body.containsKey("reasoning_strength"), "官方地址不该发网关私有字段")
    }

    @Test
    fun `deepSeek 工厂：换成自建网关地址则保持网关形状`() = runBlocking {
        val transport = FakeTransport(okResponse)
        val client = ChatCompletionsClient.deepSeek(
            apiKey = "sk-test",
            transport = transport,
            baseUrl = "https://our-gateway.example/v1",
        )

        client.completeStreaming(request(8))

        val body = bodyOf(transport)
        assertEquals(8, body["reasoning_strength"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("thinking"), "自建网关 ⇒ 网关形状（与以前一样）")
    }

    // ---------- 宿主的 extra 仍然能覆盖 ----------

    @Test
    fun `宿主的 extra 仍然最后生效（能覆盖也能加厂商私有字段）`() = runBlocking {
        val body = send(
            wire = ReasoningWire.OPENAI,
            strength = 8,
            extra = buildJsonObject { put("reasoning_effort", "custom") },
        )

        assertEquals("custom", body["reasoning_effort"]!!.jsonPrimitive.content)
    }
}
