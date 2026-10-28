package ai.agentkernel

import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.kernel.TurnStop
import ai.agentkernel.prefix.BootstrapInstaller
import ai.agentkernel.prefix.PrefixEnvironment
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.prefix.PrefixPlatform
import ai.agentkernel.llm.BlockAssembler
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.llm.StreamDelta
import ai.agentkernel.llm.ToolCallDelta
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.CommandExecutor
import ai.agentkernel.tools.exec.ExecResult
import ai.agentkernel.tools.exec.ShellTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 内核自测 —— 原来是一份手工 `main()`（`selftest/SelfTest.kt`，需要 661MB 的
 * `.toolchain/` 才能跑，交付时属于「待修」状态）。
 *
 * 这里把它**逐条**搬进 Gradle 的 JVM unit test：
 * - 断言一条没少（共 34 条，原样保留中文描述，便于对照旧结论）；
 * - 不用 kotlinc、不用 661MB 工具链，`gradlew :sdk-android:testDebugUnitTest` 直接跑。
 *
 * 覆盖面：内核循环、流式分片聚合、命令提取、未知工具容错、maxTurns、
 * 真实命令执行、bootstrap 安装器、prefix 环境变量。
 *
 * **没覆盖的**（那是另一个文件的事）：真实 HTTP / 真实 OpenAI 协议 / 真实服务端交互
 * —— 见 [EndToEndTest]。
 */
class CoreSelfTest {

    @Test
    fun `assembler：分片按 index 聚合`() {
        val asm = BlockAssembler()
        asm.accept(StreamDelta(toolCall = ToolCallDelta(index = 0, id = "c1", name = "bash", argumentsFragment = "{\"comm")))
        asm.accept(StreamDelta(content = "thinking..."))
        asm.accept(StreamDelta(toolCall = ToolCallDelta(index = 0, argumentsFragment = "and\":\"ls\"}")))
        val call = asm.toolCalls().single()
        assertTrue(call.id == "c1", "assembler: id 保留首片")
        assertTrue(call.name == "bash", "assembler: name 保留首片")
        assertTrue(
            call.argsOrEmpty()["command"]!!.jsonPrimitive.content == "ls",
            "assembler: arguments 按 index 拼成合法 JSON",
        )
        assertTrue(asm.content() == "thinking...", "assembler: content 同步累积")

        val a2 = BlockAssembler()
        a2.accept(StreamDelta(toolCall = ToolCallDelta(index = 0, id = "x", name = "bash", argumentsFragment = "{}")))
        a2.accept(StreamDelta(toolCall = ToolCallDelta(index = 1, id = "y", name = "bash", argumentsFragment = "{}")))
        assertTrue(a2.toolCalls().map { it.id } == listOf("x", "y"), "assembler: 多 tool_call 按首现顺序")
    }

    @Test
    fun `protocol：消息线格式`() {
        val msg = AssistantMessage(
            content = null,
            toolCalls = listOf(ToolCall("c1", "bash", "{\"command\":\"ls\"}")),
        )
        val j = msg.toJson()
        assertTrue(j["role"]!!.jsonPrimitive.content == "assistant", "protocol: assistant.role=assistant")
        assertTrue(j["tool_calls"]!!.jsonArray.size == 1, "protocol: assistant.tool_calls 长度=1")
        assertTrue(
            j["tool_calls"]!!.jsonArray[0].let { it.toString().contains("\"c1\"") },
            "protocol: tool_call id 一致",
        )
    }

    @Test
    fun `kernel：单轮循环能跑通并正确回填`() = runBlocking {
        val exec = FakeExecutor()
        val registry = ToolRegistry(listOf(ShellTool(exec)))
        val llm = FakeLlm(
            listOf(
                AssistantMessage(content = null, toolCalls = listOf(ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))),
                AssistantMessage(content = "all done"),
            )
        )
        val kernel = AgentKernel(llm, registry, model = "test", maxTurns = 8)
        val history = listOf(UserMessage("run echo"))
        val res = kernel.runTurn(history)

        assertTrue(res.stopReason == TurnStop.COMPLETED, "kernel: 正常收尾 COMPLETED")
        assertTrue(res.finalText == "all done", "kernel: finalText 回传")
        assertTrue(exec.lastCommand == "echo hi", "kernel: command 被提取并执行")
        assertTrue(res.messages.size == 4, "kernel: 音轨长度 = user+assistant+tool+assistant")
        assertTrue(res.appended(history).size == 3, "kernel: appended 只含本轮新增")

        val toolMsg = res.messages.filterIsInstance<ToolMessage>().firstOrNull()
        assertTrue(toolMsg?.toolCallId == "call_1", "kernel: 回填 tool_call_id 对齐")
        assertTrue(toolMsg?.content?.contains("hello") == true, "kernel: 回填内容含执行结果")
    }

    @Test
    fun `kernel：未知工具不打断循环`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                AssistantMessage(toolCalls = listOf(ToolCall("c", "nope", "{}"))),
                AssistantMessage(content = "recovered"),
            )
        )
        val kernel = AgentKernel(llm, ToolRegistry(), model = "t", maxTurns = 8)
        val res = kernel.runTurn(listOf(UserMessage("x")))
        val tm = res.messages.filterIsInstance<ToolMessage>().first()
        assertTrue(tm.content.contains("未知工具"), "kernel: 未知工具回可读错误不崩")
        assertTrue(res.finalText == "recovered", "kernel: 未知工具后仍能收尾")
    }

    @Test
    fun `kernel：maxTurns 触顶即停`() = runBlocking {
        val llm = FakeLlm(
            List(5) { AssistantMessage(toolCalls = listOf(ToolCall("c", "bash", "{\"command\":\"echo\"}"))) }
        )
        val kernel = AgentKernel(llm, ToolRegistry(listOf(ShellTool(FakeExecutor()))), model = "t", maxTurns = 3)
        val res = kernel.runTurn(listOf(UserMessage("loop")))
        assertTrue(res.stopReason == TurnStop.MAX_TURNS, "kernel: maxTurns 触顶 MAX_TURNS")
    }

    @Test
    fun `executor：真实进程无 PTY 执行路径`() = runBlocking {
        // 用本机 bash 冒充设备 shell，验证 AndroidCommandExecutor 的无 PTY 执行路径真的能跑。
        val bash = localShell()
        assumeTrue("本机未找到 bash，跳过真实命令执行", bash != null)

        val executor = AndroidCommandExecutor(shellPath = bash!!, layout = PrefixLayout(tempDir()))
        val out = executor.execute("echo hello-from-device")
        assertTrue(out.exitCode == 0, "real: 退出码 0")
        assertTrue(out.stdout.contains("hello-from-device"), "real: stdout 捕获")
        assertTrue(out.render().contains("exitCode: 0"), "real: render 含 exitCode 段")
    }

    @Test
    fun `installer：bootstrap 安装五步走`() {
        val base = "${tempDir()}/agentkernel-selftest-${System.nanoTime()}"
        val layout = PrefixLayout(base)
        val plat = RecordingPlatform()
        BootstrapInstaller(plat).install(buildBootstrapZip(), layout)

        assertTrue(
            layout.staging in plat.deleted && layout.prefix in plat.deleted,
            "installer: 先清残留 staging+prefix",
        )
        assertTrue(
            plat.chmods.any { it.first == "${layout.staging}/bin/hello" && it.second == 0b111000000 },
            "installer: bin/ 下可执行文件 chmod 0700",
        )
        assertTrue(plat.chmods.none { it.first.endsWith("keep.txt") }, "installer: 普通文件不 chmod")
        assertTrue(plat.symlinks.size == 1, "installer: SYMLINKS.txt 解析出 1 条软链")
        assertTrue(plat.symlinks[0].first == "../bin/busybox", "installer: 软链目标正确")
        assertTrue(
            plat.symlinks[0].second == "${layout.staging}/bin/ls",
            "installer: 软链按 staging 定位",
        )
        assertTrue(
            plat.renames.single() == (layout.staging to layout.prefix),
            "installer: 原子 rename staging→prefix",
        )
        val extracted = java.io.File("${layout.staging}/bin/hello")
        assertTrue(
            extracted.exists() && extracted.readText().contains("echo hi"),
            "installer: 文件真被解压出来",
        )
    }

    @Test
    fun `env：prefix 环境变量`() {
        val layout = PrefixLayout("/files")
        val env = PrefixEnvironment.build(layout)
        assertTrue(env["PREFIX"] == layout.prefix, "env: PREFIX 指向 usr")
        assertTrue(env["PATH"] == layout.bin, "env: PATH 含 bin")
        assertTrue(!env.containsKey("LD_LIBRARY_PATH"), "env: Android7+ 不设 LD_LIBRARY_PATH")
        val legacy = PrefixEnvironment.build(layout, androidLegacy = true)
        assertTrue(legacy["PATH"]!!.contains("applets"), "env: 老版本 PATH 含 applets")
        assertTrue(legacy["LD_LIBRARY_PATH"] == layout.lib, "env: 老版本设 LD_LIBRARY_PATH")
    }

    // ── 测试替身 ───────────────────────────────────────────────

    private class FakeExecutor : CommandExecutor {
        var lastCommand: String? = null
        override suspend fun execute(command: String, timeoutMs: Long): ExecResult {
            lastCommand = command
            return ExecResult(exitCode = 0, stdout = "hello\n", stderr = "")
        }
    }

    private class FakeLlm(private val responses: List<AssistantMessage>) : LlmClient {
        private var i = 0
        override suspend fun complete(request: ChatRequest): AssistantMessage {
            val r = responses[i.coerceAtMost(responses.size - 1)]
            i++
            return r
        }
    }

    /** 记录型平台实现：ensureDirectory 真建目录（否则写文件会失败），其余只记录调用。 */
    private class RecordingPlatform : PrefixPlatform {
        val dirs = mutableListOf<String>()
        val chmods = mutableListOf<Pair<String, Int>>()
        val symlinks = mutableListOf<Pair<String, String>>()
        val renames = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        override fun ensureDirectory(path: String) { dirs += path; java.io.File(path).mkdirs() }
        override fun chmod(path: String, mode: Int) { chmods += path to mode }
        override fun symlink(linkTarget: String, linkPath: String) { symlinks += linkTarget to linkPath }
        override fun rename(from: String, to: String) { renames += from to to }
        override fun deleteRecursively(path: String) { deleted += path }
        override fun exists(path: String): Boolean = java.io.File(path).exists()
    }

    /** 造一个最小 bootstrap zip：一个可执行、一个普通文件、一个 SYMLINKS.txt（用 ← 分隔）。 */
    private fun buildBootstrapZip(): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("bin/hello"))
            z.write("#!/bin/sh\necho hi\n".toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("opt/keep.txt"))
            z.write("data".toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("SYMLINKS.txt"))
            z.write("../bin/busybox\u2190bin/ls\n".toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    companion object {
        /** 本机 bash（Windows 优先 Git Bash）；找不到就跳过「真实命令」那组断言。 */
        fun localShell(): String? = listOf(
            "C:/Program Files/Git/bin/bash.exe",
            "C:/Program Files/Git/usr/bin/bash.exe",
            "/bin/bash",
            "/usr/bin/bash",
        ).firstOrNull { java.io.File(it).exists() }

        fun tempDir(): String = System.getProperty("java.io.tmpdir") ?: "C:/Windows/Temp"
    }
}
