package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.EffortCatalogClient
import ai.agentkernel.llm.openai.EffortCatalogException
import ai.agentkernel.llm.openai.LlmAuth
import ai.agentkernel.llm.openai.ReasoningStrength
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 思考强度契约测试。
 *
 * ## 这组测试锁的是什么
 *
 * **强度编码（2 的幂）才是契约**，`key` / `index` 不是：
 * - `key`（`low` / `high` / …）**换模型就变** —— `deepseek-flash` 没有 medium，
 *   同一个编号在不同模型上指不同档；
 * - `index` 只是显示顺序。
 *
 * 所以：请求体里发的是 `reasoning_strength`（数值），**清单解析出来的 strength 才是能存的东西**。
 *
 * ## 为什么这些要有测试
 *
 * 强度编码传错**不会立刻报错**，可能被后端悄悄降级（只向下）——
 * 于是「用户选 high，实际跑 low」没人发现。所以编码本身必须严格：
 * 不是 2 的幂 → **当场拒绝**，别把一个错的值发出去。
 */
class ReasoningStrengthTest {

    // ---------- 编码本身 ----------

    @Test
    fun `强度编码常量对得上契约`() {
        assertEquals(1, ReasoningStrength.OFF.value, "1 = 关闭思考")
        assertEquals(2, ReasoningStrength.LOW.value, "2 = low")
        assertEquals(4, ReasoningStrength.MEDIUM.value, "4 = medium")
        assertEquals(8, ReasoningStrength.HIGH.value, "8 = high")
        assertEquals(16, ReasoningStrength.MAX.value, "16 = max")
        assertEquals(32, ReasoningStrength.XHIGH.value, "32 = xhigh")
    }

    @Test
    fun `不是 2 的幂的强度编码当场被拒`() {
        // 3、5、6、7…… 都不是 2 的幂 —— 传出去必然对不上任何档位。
        for (bad in listOf(3, 5, 6, 7, 9, 12, 100)) {
            assertFailsWith<IllegalArgumentException>("强度编码 $bad 应被拒") {
                ReasoningStrength(bad)
            }
        }
    }

    @Test
    fun `非正的强度编码当场被拒`() {
        assertFailsWith<IllegalArgumentException> { ReasoningStrength(0) }
        assertFailsWith<IllegalArgumentException> { ReasoningStrength(-8) }
    }

    @Test
    fun `bits 是同值的二进制形态`() {
        assertEquals("0b00001", ReasoningStrength.OFF.bits)
        assertEquals("0b00010", ReasoningStrength.LOW.bits)
        assertEquals("0b00100", ReasoningStrength.MEDIUM.bits)
        assertEquals("0b01000", ReasoningStrength.HIGH.bits)
        assertEquals("0b10000", ReasoningStrength.MAX.bits)
        assertEquals("0b100000", ReasoningStrength.XHIGH.bits)
    }

    // ---------- 清单解析 ----------

    /** 服务端真实返回的报文（默认模型 deepseek-flash）。 */
    private val realCatalog = """
        {"model":"deepseek-flash","levels":[
          {"index":1,"strength":1, "bits":"0b00001","key":"off", "label":"关闭思考","hint":"不思考,直接回答,最快最省","kind":"off"},
          {"index":2,"strength":2, "bits":"0b00010","key":"low", "label":"低",     "hint":"简短思考",          "kind":"effort"},
          {"index":3,"strength":8, "bits":"0b01000","key":"high","label":"高",     "hint":"深度思考(官方默认)","kind":"effort"},
          {"index":4,"strength":16,"bits":"0b10000","key":"max", "label":"最高",   "hint":"最深度思考,最慢",    "kind":"effort"}],
         "default_index":3,"default_strength":8,"default_key":"high"}
    """.trimIndent()

    private fun client(raw: String = realCatalog) = EffortCatalogClient(
        baseUrl = "http://x/v1",
        transport = object : ai.agentkernel.llm.openai.GetTransport {
            override suspend fun get(url: String, headers: Map<String, String>): String = raw
        },
        auth = LlmAuth.bearer("t"),
    )

    @Test
    fun `清单能解析出各档与默认值`() = runBlocking {
        val catalog = client().fetch()
        assertEquals("deepseek-flash", catalog.model)
        assertEquals(listOf(1, 2, 8, 16), catalog.levels.map { it.strength })
        assertEquals(8, catalog.defaultStrength)
        assertEquals("high", catalog.defaultKey)
        assertTrue(catalog.supportsThinking)
    }

    @Test
    fun `strength 能定位到档位，key 不能当契约用`() = runBlocking {
        val catalog = client().fetch()
        // 按强度找得到。
        assertEquals("high", catalog.levelOf(8)?.key)
        // ★ 同一个 key 在不同模型上可能对应不同强度 ——
        //   所以只提供按 strength 定位，不提供按 key 定位。
        assertNull(catalog.levelOf(4), "deepseek-flash 没有 medium 这一档")
    }

    @Test
    fun `off 档能被认出来`() = runBlocking {
        val catalog = client().fetch()
        assertTrue(catalog.levelOf(1)!!.isOff)
        assertTrue(!catalog.levelOf(2)!!.isOff)
    }

    @Test
    fun `清单缺 levels 就报错，不编一份空的糊弄`() = runBlocking {
        val e = assertFailsWith<EffortCatalogException> {
            client("""{"model":"x"}""").fetch()
        }
        assertTrue(e.message!!.contains("levels"), "报错要说清缺什么：${e.message}")
    }

    @Test
    fun `清单里的非法强度编码当场被拒`() = runBlocking {
        val e = assertFailsWith<EffortCatalogException> {
            client("""{"model":"x","levels":[{"strength":3,"key":"low","label":"低","hint":"","kind":"effort","index":1}]}""").fetch()
        }
        assertTrue(e.message!!.contains("强度"), "报错要点名强度：${e.message}")
    }

    @Test
    fun `清单里的非法 default_strength 当场被拒`() = runBlocking {
        val e = assertFailsWith<EffortCatalogException> {
            client("""{"model":"x","levels":[],"default_strength":5}""").fetch()
        }
        assertTrue(e.message!!.contains("default_strength"), "报错要说清是哪个字段：${e.message}")
    }

    @Test
    fun `levels 为空 = 该模型不支持思考`() = runBlocking {
        val catalog = client("""{"model":"x","levels":[],"default_strength":null}""").fetch()
        assertTrue(!catalog.supportsThinking, "空清单 = 不支持思考")
        assertNull(catalog.defaultStrength)
    }

    // ⚠️ 这里必须显式写 `: Unit`。
    //
    // 写成 `fun x() = runBlocking { assertFailsWith<...> { ... } }` 时，
    // 表达式体的最后一个表达式是 `assertFailsWith` 的返回值（那个异常对象），
    // 于是 Kotlin 把方法推断成**返回异常对象** ⇒ 编译出的 JVM 方法不是 `void`
    // ⇒ **JUnit 4 直接不认这条测试，既不执行也不报错** ——
    // 套件照样全绿，但这条断言从来没跑过。
    //
    // 同理，任何 `@Test` 的表达式体若以非 Unit 结尾，都会这样静默消失。
    // `TestVoidReturnTest` 会把整个测试源码集扫一遍，挡住这类漏网。
    @Test
    fun `清单不是合法 JSON 时报错`(): Unit = runBlocking {
        assertFailsWith<EffortCatalogException> { client("not json").fetch() }
    }

    // ---------- 请求体 ----------

    private class RecordingTransport : Transport {
        var body: String = ""
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            this.body = body
            return """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        }
    }

    private fun bodyOf(strength: Int?): String = runBlocking {
        val transport = RecordingTransport()
        val client = ChatCompletionsClient("http://x/v1", transport, LlmAuth.bearer("t"))
        client.completeNonStreaming(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(UserMessage("hi")),
                reasoningStrength = strength,
            )
        )
        transport.body
    }

    @Test
    fun `指定强度时请求体带 reasoning_strength`() {
        val body = Json.parseToJsonElement(bodyOf(8)).jsonObject
        assertEquals("8", body["reasoning_strength"]!!.jsonPrimitive.content, "请求体要带强度编码")
    }

    @Test
    fun `不指定强度就不带这个字段（服务端用默认档位）`() {
        val body = Json.parseToJsonElement(bodyOf(null)).jsonObject
        assertNull(body["reasoning_strength"], "不指定就别带这个字段")
    }

    @Test
    fun `强度编码进请求体时不会被当成字符串`() {
        // 是**数值** 8，不是 "8" —— 后端按数值解析。
        val body = Json.parseToJsonElement(bodyOf(2)).jsonObject
        assertEquals("2", body["reasoning_strength"]!!.jsonPrimitive.content)
    }
}
