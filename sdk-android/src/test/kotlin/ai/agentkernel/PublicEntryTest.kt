package ai.agentkernel

import ai.agentkernel.android.AndroidAgentSdk
import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.JdkHttpTransport
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.prompt.AgentSystemPrompt
import ai.agentkernel.protocol.UserMessage
import ai.agentkernel.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **面向外部使用者的入口**能不能用 —— 也就是「拿到 SDK、填个 DeepSeek key 就能跑」那条路。
 *
 * 这组测试锁的是**参数真的进了请求体**，不是「代码里写了这么个参数」。
 * 断言的对象是 mock 服务端**实际收到的东西** —— 因为「传了但没生效」是这类改造
 * 最常见的静默失败（比如 maxTokens 加了字段但 buildBody 忘了拼）。
 */
class PublicEntryTest {

    // ── 一、参数要真的发到上游 ───────────────────────────────────

    @Test
    fun `max_tokens 会发到上游请求体`() = runBlocking {
        val server = MockLlmServer { _, _ -> MockLlmServer.Reply(TEXT_REPLY) }
        server.use {
            kernelFor(server, maxTokens = 2048).runTurn(listOf(UserMessage("hi")))
            val body = Json.parseToJsonElement(server.requestBodies.single()).jsonObject
            assertEquals(
                "2048",
                body["max_tokens"]!!.jsonPrimitive.content,
                "参数: maxTokens 要变成请求体里的 max_tokens",
            )
        }
    }

    @Test
    fun `不设 maxTokens 时请求体里没有这个字段`() = runBlocking {
        // 不带这个字段 = 让服务端用它自己的默认值。
        // 硬塞一个 0 或者 -1 反而会让某些服务端直接报错。
        val server = MockLlmServer { _, _ -> MockLlmServer.Reply(TEXT_REPLY) }
        server.use {
            kernelFor(server).runTurn(listOf(UserMessage("hi")))
            val body = Json.parseToJsonElement(server.requestBodies.single()).jsonObject
            assertFalse(
                body.containsKey("max_tokens"),
                "参数: 没设 maxTokens 就不该发 max_tokens 字段",
            )
        }
    }

    @Test
    fun `temperature 与自定义透传字段都会发出去`() = runBlocking {
        val server = MockLlmServer { _, _ -> MockLlmServer.Reply(TEXT_REPLY) }
        server.use {
            val kernel = AgentKernel(
                llm = ChatCompletionsClient(server.baseUrl, "test-key", JdkHttpTransport()),
                tools = ToolRegistry(emptyList()),
                model = "mock-model",
                temperature = 0.3,
                extra = buildJsonObject { put("thinking", buildJsonObject { put("type", "enabled") }) },
            )
            kernel.runTurn(listOf(UserMessage("hi")))
            val body = Json.parseToJsonElement(server.requestBodies.single()).jsonObject
            assertEquals("0.3", body["temperature"]!!.jsonPrimitive.content, "参数: temperature 要发出去")
            assertEquals(
                "enabled",
                body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
                "参数: extra 里的自定义字段要原样透传",
            )
        }
    }

    // ── 二、DeepSeek 官方那条路 ───────────────────────────────────

    @Test
    fun `DeepSeek 官方地址常量是 api-deepseek-com-v1`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            ChatCompletionsClient.DEEPSEEK_BASE_URL,
            "入口: DeepSeek 官方地址写错了",
        )
    }

    @Test
    fun `deepSeek 便捷构造走标准 chat completions 路径且带 Bearer 头`() = runBlocking {
        val server = MockLlmServer { _, _ -> MockLlmServer.Reply(TEXT_REPLY) }
        server.use {
            val client = ChatCompletionsClient.deepSeek(
                apiKey = "sk-test",
                transport = JdkHttpTransport(),
                baseUrl = server.baseUrl,
            )
            val kernel = AgentKernel(
                llm = client,
                tools = ToolRegistry(emptyList()),
                model = "deepseek-chat",
            )
            kernel.runTurn(listOf(UserMessage("hi")))
            assertEquals(
                "/v1/chat/completions",
                server.requestPaths.single(),
                "入口: 请求路径要落在 baseUrl + /chat/completions",
            )
            val auth = server.requestHeaders.single().entries.first { it.key.equals("Authorization", true) }
            assertEquals("Bearer sk-test", auth.value, "入口: 鉴权头要是 Authorization: Bearer <key>")
        }
    }

    // ── 三、系统提示词要自带完整（不依赖任何后端注入）─────────────

    @Test
    fun `默认系统提示词用完整命令清单而不是瘦身档`() {
        val executor = executorFor()
        val full = AndroidAgentSdk.buildSystemPrompt(executor)
        val core = AndroidAgentSdk.buildSystemPrompt(
            executor = executor,
            commandDetail = AgentSystemPrompt.CommandDetail.CORE,
        )
        assertTrue(full != null && core != null, "提示词: 两个档位都要能生成")
        assertTrue(
            full!!.length > core!!.length,
            "提示词: 默认(FULL)必须比瘦身档(CORE)长 —— 完整清单才是默认",
        )
        assertTrue(
            full.contains("## 你能用的命令"),
            "提示词: 必须自带命令清单段落（不能靠后端注入）",
        )
        assertTrue(
            full.contains("## 你**不能**做的事") || full.contains("你**不能**做的事"),
            "提示词: 必须自带能力边界段落",
        )
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    private fun kernelFor(
        server: MockLlmServer,
        maxTokens: Int? = null,
    ): AgentKernel = AgentKernel(
        llm = ChatCompletionsClient(server.baseUrl, "test-key", JdkHttpTransport()),
        tools = ToolRegistry(emptyList()),
        model = "mock-model",
        maxTokens = maxTokens,
    )

    private fun executorFor(): AndroidCommandExecutor {
        val shell = CoreSelfTest.localShell()
        assertTrue(shell != null, "本机需要能找到 bash")
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-public-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        return AndroidCommandExecutor(shellPath = shell!!, layout = PrefixLayout(base.path))
    }

    private companion object {
        /** 一条纯文本回复（无 tool_calls）—— 内核收到就收尾。 */
        const val TEXT_REPLY = """
            {
              "id": "chatcmpl-mock",
              "object": "chat.completion",
              "created": 0,
              "model": "mock-model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "答完了" },
                  "finish_reason": "stop"
                }
              ]
            }
        """
    }
}
