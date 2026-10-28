package ai.agentkernel.llm

import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import ai.agentkernel.tools.Tool
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.ExecResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「前缀稳定」这条链的契约测试：**会话标识上行 + 服务端报告下行 + 两条压住分子的规矩**。
 *
 * ## 为什么这组测试必须有
 *
 * 前缀缓存的命中率是 `命中 ≈ 1 − 每轮新增 / 上下文总量`，
 * 要让它稳，就得同时守住两端：
 *
 * - **分母端（头要稳）**：服务端拼的头部（平台提示词/技能/工具定义）App 看不见，
 *   所以要有「服务端视角」的报告 —— 这是那条不变量的服务端对应。
 * - **分子端（工具 schema 要稳）**：工具列表的**顺序**一变，
 *   `tools` 字段的字节就变了，上游前缀缓存当场清零。
 *
 * 而「每轮新增」里的工具输出是可以压的 —— 它会永久留在历史里。
 */
class PrefixStabilityTest {

    private class FakeTransport(private val response: String) : Transport {
        var lastBody: String? = null
        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return response
        }
    }

    private fun request(sessionId: String? = null) = ChatRequest(
        model = "deepseek-chat",
        messages = listOf(UserMessage("你好")),
        sessionId = sessionId,
    )

    // ---------- 会话标识上行 ----------

    @Test
    fun `请求体要带 session_id（服务端靠它记住上一次请求长什么样）`() = runBlocking {
        val transport = FakeTransport(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}]}"""
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        client.completeNonStreaming(request("sess-42"))
        val body = assertNotNull(transport.lastBody)

        assertTrue(body.contains("session_id"), "请求体必须带 session_id")
        assertTrue(body.contains("sess-42"))
    }

    @Test
    fun `没给 session_id 时不要瞎编一个`() = runBlocking {
        val transport = FakeTransport(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}]}"""
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        client.completeNonStreaming(request(null))
        val body = assertNotNull(transport.lastBody)

        assertFalse(body.contains("session_id"), "没有会话标识就不该带这个字段")
    }

    // ---------- 服务端报告下行 ----------

    @Test
    fun `非流式：解析服务端的前缀体检报告`() = runBlocking {
        val transport = FakeTransport(
            """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}],
             "prefix":{"session":"sid:s1","tracked":true,"preserved":false,"firstChangedIndex":3,
                       "commonPrefixMessages":3,"commonPrefixChars":420,"addedMessages":2,
                       "headChars":2900,"toolsChars":800,"headChanged":["platform"]}}
            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val prefix = assertNotNull(client.completeNonStreaming(request()).prefix, "服务端给了 prefix 就必须解析出来")

        assertEquals("sid:s1", prefix.session)
        assertTrue(prefix.tracked)
        assertFalse(prefix.preserved, "前缀断了要如实说")
        assertEquals(3, prefix.firstChangedIndex)
        assertEquals(3, prefix.commonPrefixMessages)
        assertEquals(420, prefix.commonPrefixChars)
        assertEquals(2, prefix.addedMessages)
        assertEquals(2900, prefix.headChars)
        assertEquals(800, prefix.toolsChars)
        assertEquals(listOf("platform"), prefix.headChanged)
        assertFalse(prefix.headStable)
    }

    @Test
    fun `流式：前缀报告跟 usage 同一帧（在 DONE 之前）`() = runBlocking {
        val transport = FakeTransport(
            """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":2000,"prompt_cache_hit_tokens":1900,"prompt_cache_miss_tokens":100},"prefix":{"session":"sid:s2","tracked":true,"preserved":true,"firstChangedIndex":-1,"commonPrefixMessages":5,"commonPrefixChars":700,"addedMessages":1,"headChars":2900,"toolsChars":800,"headChanged":[]}}

            data: [DONE]

            """.trimIndent()
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        val msg = client.completeStreaming(request())
        val prefix = assertNotNull(msg.prefix, "流式的 prefix 帧必须收到")

        assertTrue(prefix.preserved)
        assertEquals(5, prefix.commonPrefixMessages)
        assertTrue(prefix.headStable)
        assertEquals(1900, assertNotNull(msg.usage).cacheHitTokens)
    }

    @Test
    fun `服务端没给 prefix 时是 null，不是「假定完好」（不知道 ≠ 没问题）`() = runBlocking {
        val transport = FakeTransport(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"在"},"finish_reason":"stop"}]}"""
        )
        val client = ChatCompletionsClient("https://example.invalid", transport)

        assertNull(client.completeNonStreaming(request()).prefix, "没检查过就不能说完好")
    }

    // ---------- 工具 schema 必须字节级稳定 ----------

    private fun stub(name: String) = object : Tool {
        override val name = name
        override val description = "工具 $name"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun execute(arguments: JsonObject): String = "ok"
    }

    @Test
    fun `工具 schema 按名字字典序（注册顺序变了也不影响字节）`() {
        val registry = ToolRegistry(listOf(stub("zebra"), stub("alpha"), stub("mango")))

        val names = registry.schemas().map { schema ->
            schema["function"]!!.toString()
        }

        // 顺序必须是字典序，而不是注册顺序。
        val first = registry.schemas().first()["function"]!!.toString()
        assertTrue(first.contains("alpha"), "字典序第一个应该是 alpha，实际是 $first")
        assertEquals(3, names.size)
    }

    @Test
    fun `同一组工具、不同注册顺序 → schema 序列完全一样（字节级稳定）`() {
        val a = ToolRegistry(listOf(stub("alpha"), stub("beta"), stub("gamma"))).schemas()
        val b = ToolRegistry(listOf(stub("gamma"), stub("alpha"), stub("beta"))).schemas()

        assertEquals(a.map { it.toString() }, b.map { it.toString() }, "注册顺序不能影响发给上游的字节")
    }

    // ---------- 工具输出上限（压住「每轮新增」）----------

    @Test
    fun `工具输出超长时截断，但保头保尾并且明说被截了`() {
        val result = ExecResult(
            exitCode = 0,
            stdout = "HEAD-MARKER\n" + "x".repeat(5000) + "\nTAIL-MARKER",
            stderr = "",
        )

        val text = result.render(1200)

        assertTrue(text.length < 6000, "截断后不该还是原文")
        assertTrue(text.contains("HEAD-MARKER"), "头要保留")
        assertTrue(text.contains("TAIL-MARKER"), "尾要保留（错误信息常在最后一行）")
        assertTrue(text.contains("已截断"), "必须明说被截断了 —— 不能让模型以为输出完整")
    }

    @Test
    fun `输出不长时原样返回，不加任何标记`() {
        val result = ExecResult(exitCode = 0, stdout = "短输出", stderr = "")

        val text = result.render(1200)

        assertTrue(text.contains("短输出"))
        assertFalse(text.contains("已截断"))
    }
}
