package ai.agentkernel

import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.kernel.TurnStop
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.JdkHttpTransport
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.ShellTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 端到端验证 —— 这份自测补的是原来**完全没有覆盖**的那一层。
 *
 * 原来那份手工自测里，模型端是 `FakeLlm`（直接返回构造好的对象），
 * 所以下面这些**一次都没被跑过**：
 * - 真的发 HTTP 请求、真的带 Authorization；
 * - 真的按 OpenAI 线格式解析响应（含 `tool_calls[].function.arguments` 是**原始 JSON 字符串**这件事）；
 * - 真的把工具结果回填进**下一次**请求。
 *
 * 这三件事恰恰是这套 SDK 存在的理由 ——「像云端内核一样负责处理 API 返回的指令」。
 * 所以这里用 [MockLlmServer] 起一个本地 OpenAI 兼容服务，跑**完整一轮**：
 *
 *   第 1 轮：服务端回 tool_calls(命令) → 内核执行 → 回填
 *   第 2 轮：请求里带上 tool 结果 → 服务端回最终文本 → 内核收尾
 *
 * mock 用裸 `ServerSocket` 写（不是 `com.sun.net.httpserver`），因为后者在 Android 上不存在 ——
 * 这套 mock 将来要能原样搬到真机 instrumented test 里跑。
 */
class EndToEndTest {

    @Test
    fun `端到端：HTTP → 内核 → 真实命令 → 回填 → 收尾`() = runBlocking {
        val marker = "sdk-e2e-marker"
        val server = MockLlmServer { round, _ ->
            if (round == 1) {
                MockLlmServer.Reply(toolCallBody("call_e2e_1", "bash", """{"command":"echo $marker"}"""))
            } else {
                MockLlmServer.Reply(finalBody("命令执行完成，输出为 $marker"))
            }
        }
        server.use {
            val kernel = kernelFor(server)
            val result = kernel.runTurn(listOf(UserMessage("跑一条命令")))

            // ── 收尾 ──
            assertTrue(result.stopReason == TurnStop.COMPLETED, "e2e: 正常收尾 COMPLETED")
            assertTrue(result.finalText?.contains(marker) == true, "e2e: finalText 是服务端最后那句话")

            // ── 命令真的跑了 ──
            val toolMsg = result.messages.filterIsInstance<ToolMessage>().first()
            assertTrue(toolMsg.toolCallId == "call_e2e_1", "e2e: tool_call_id 与请求对齐")
            assertTrue(toolMsg.content.contains("exitCode: 0"), "e2e: 执行结果含退出码")
            assertTrue(toolMsg.content.contains(marker), "e2e: 命令真的被执行了，stdout 被捕获")

            // ── 音轨 ──
            assertTrue(result.messages.size == 4, "e2e: 音轨 = user+assistant+tool+assistant")

            // ── 第一个请求：带工具 schema、带鉴权 ──
            val first = server.requestBodies[0]
            assertTrue(first.contains("\"tools\""), "e2e: 请求带上了工具 schema")
            assertTrue(first.contains("\"name\":\"bash\""), "e2e: 工具 schema 里有 bash")
            assertTrue(first.contains("\"model\":\"mock-model\""), "e2e: 请求带了 model")
            val authed = server.requestHeaders[0].entries.any {
                it.key.equals("Authorization", ignoreCase = true) && it.value == "Bearer test-key"
            }
            assertTrue(authed, "e2e: 请求带了 Authorization: Bearer")

            // ── 第二个请求：工具结果真的回填进去了 ──
            val second = server.requestBodies[1]
            assertTrue(second.contains("\"role\":\"tool\""), "e2e: 第二次请求带上了 tool 消息")
            assertTrue(second.contains("\"tool_call_id\":\"call_e2e_1\""), "e2e: tool_call_id 对齐")
            assertTrue(second.contains(marker), "e2e: 工具结果内容回填进了下一次请求")
        }
    }

    @Test
    fun `端到端：流式 SSE 的 tool_calls 分片能聚合成可执行的命令`() = runBlocking {
        val server = MockLlmServer { _, _ ->
            MockLlmServer.Reply(
                sseToolCallBody(
                    id = "call_stream_1",
                    name = "bash",
                    fragments = listOf("""{"command":"echo """, "sdk-stream-marker\"}"),
                ),
                contentType = "text/event-stream",
            )
        }
        server.use {
            val client = ChatCompletionsClient(server.baseUrl, "test-key", JdkHttpTransport())
            val message = client.completeStreaming(
                ChatRequest(model = "mock-model", messages = listOf(UserMessage("hi")))
            )
            val call = message.toolCalls.single()
            assertTrue(call.id == "call_stream_1", "sse: id 取首片")
            assertTrue(call.name == "bash", "sse: name 取首片")
            assertTrue(
                call.argsOrEmpty()["command"]!!.jsonPrimitive.content == "echo sdk-stream-marker",
                "sse: arguments 碎片拼成完整 JSON",
            )
        }
    }

    @Test
    fun `失败路径：命令非零退出时退出码回填给模型`() = runBlocking {
        val shell = CoreSelfTest.localShell()
        assumeTrue("本机未找到 bash，跳过", shell != null)
        val executor = AndroidCommandExecutor(shellPath = shell!!, layout = testLayout())
        val out = executor.execute("exit 3")
        assertTrue(out.exitCode == 3, "失败路径: 退出码原样回传")
        assertTrue(out.render().contains("exitCode: 3"), "失败路径: render 里能看出失败")
    }

    @Test
    fun `超时路径：命令卡住时按 timeout 截断`() = runBlocking {
        val shell = CoreSelfTest.localShell()
        assumeTrue("本机未找到 bash，跳过", shell != null)
        val executor = AndroidCommandExecutor(shellPath = shell!!, layout = testLayout())
        // read 是 bash 内建命令，读 stdin 会一直等 —— 用来模拟「卡住的命令」。
        val out = executor.execute("read x", timeoutMs = 800L)
        assertTrue(out.timedOut, "超时路径: 标记为 timedOut")
        assertTrue(out.exitCode == -1, "超时路径: 退出码用 -1")
        assertTrue(out.render().contains("(timed out)"), "超时路径: render 里能看出超时")
    }

    // ── 辅助 ───────────────────────────────────────────────────

    private fun kernelFor(server: MockLlmServer): AgentKernel {
        val shell = CoreSelfTest.localShell()
        assumeTrue("本机未找到 bash，跳过端到端", shell != null)
        val client = ChatCompletionsClient(server.baseUrl, "test-key", JdkHttpTransport())
        val executor = AndroidCommandExecutor(shellPath = shell!!, layout = testLayout())
        return AgentKernel(
            llm = client,
            tools = ToolRegistry(listOf(ShellTool(executor))),
            model = "mock-model",
            maxTurns = 8,
        )
    }

    /** 造一个可用的 prefix 目录（HOME / TMPDIR 要真实存在，否则写重定向会失败）。 */
    private fun testLayout(): PrefixLayout {
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-e2e-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        return PrefixLayout(base.path)
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 普通（非流式）响应：一个 tool_call。 */
    private fun toolCallBody(id: String, name: String, arguments: String): String = """
        {
          "id": "chatcmpl-mock",
          "object": "chat.completion",
          "created": 0,
          "model": "mock-model",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": null,
                "tool_calls": [
                  {
                    "id": ${jsonString(id)},
                    "type": "function",
                    "function": {
                      "name": ${jsonString(name)},
                      "arguments": ${jsonString(arguments)}
                    }
                  }
                ]
              },
              "finish_reason": "tool_calls"
            }
          ]
        }
    """.trimIndent()

    /** 普通（非流式）响应：最终文本。 */
    private fun finalBody(text: String): String = """
        {
          "id": "chatcmpl-mock",
          "object": "chat.completion",
          "created": 0,
          "model": "mock-model",
          "choices": [
            {
              "index": 0,
              "message": { "role": "assistant", "content": ${jsonString(text)} },
              "finish_reason": "stop"
            }
          ]
        }
    """.trimIndent()

    /**
     * 流式响应：tool_calls 的 arguments 被拆成多个分片。
     * 第一片带 id / name，后续片只有 arguments —— 这正是 BlockAssembler 要解决的情况。
     */
    private fun sseToolCallBody(id: String, name: String, fragments: List<String>): String {
        val sb = StringBuilder()
        sb.append("data: ").append("""{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""").append("\n\n")
        fragments.forEachIndexed { i, frag ->
            val head = if (i == 0) {
                ""","id":${jsonString(id)},"type":"function","function":{"name":${jsonString(name)},"arguments":${jsonString(frag)}}"""
            } else {
                ""","function":{"arguments":${jsonString(frag)}}"""
            }
            sb.append("data: ")
                .append("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0$head}]}}]}""")
                .append("\n\n")
        }
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }
}
