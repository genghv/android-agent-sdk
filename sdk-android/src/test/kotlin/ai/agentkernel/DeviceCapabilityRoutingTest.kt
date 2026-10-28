package ai.agentkernel

import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.device.okJson
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.tools.exec.ShellTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 能力路由接进执行器之后的验证。
 *
 * 这一层验证的是**执行器**的行为（不是 Android API 本身）：
 * - 整条能力命令**不该起子进程**（不走 shell）；
 * - 复合命令里的能力调用**要被改写**、结果要回到 stdout；
 * - 能力失败要给出**非零退出码**；
 * - 能力清单要出现在工具描述里 —— 否则模型根本不会去写这些命令。
 *
 * Android API 那一层（CameraManager / Vibrator / …）在设备上验，
 * 这里用假能力替身，保证逻辑部分不依赖设备。
 */
class DeviceCapabilityRoutingTest {

    private class FakeCapability(
        override val name: String,
        private val fail: Boolean = false,
    ) : DeviceCapability {
        override val usage: String = "termux-$name"
        var calls = 0
        override fun run(args: List<String>): String {
            calls++
            if (fail) throw CapabilityFailure("模拟失败")
            return okJson { put("args", args.joinToString(" ")) }
        }
    }

    private fun executor(vararg caps: DeviceCapability): AndroidCommandExecutor {
        val base = File(System.getProperty("java.io.tmpdir"), "cap-exec-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        val shell = CoreSelfTest.localShell()
        return AndroidCommandExecutor(
            shellPath = shell ?: "sh",
            layout = PrefixLayout(base.path),
            capabilities = DeviceCapabilityRegistry(caps.toList()),
        )
    }

    @Test
    fun `整条能力命令在进程内执行`() = runBlocking {
        val cap = FakeCapability("torch")
        val out = executor(cap).execute("termux-torch on")
        assertEquals(0, out.exitCode, "能力成功应给 0 退出码")
        assertTrue(out.stdout.contains("\"ok\":true"), "stdout 应是能力返回的 JSON：${out.stdout}")
        assertTrue(out.stdout.contains("on"), "参数要传下去：${out.stdout}")
        assertEquals(1, cap.calls, "能力只该被调用一次")
    }

    @Test
    fun `能力失败给非零退出码`() = runBlocking {
        val cap = FakeCapability("torch", fail = true)
        val out = executor(cap).execute("termux-torch on")
        assertEquals(1, out.exitCode, "能力失败要给非零退出码")
        assertTrue(out.stdout.contains("\"ok\":false"), "失败也要是 JSON：${out.stdout}")
    }

    @Test
    fun `复合命令里的能力调用会被执行并把结果带回 stdout`() = runBlocking {
        val cap = FakeCapability("torch")
        val out = executor(cap).execute("termux-torch on && echo done")
        assertEquals(0, out.exitCode, "退出码应为 0；stdout=${out.stdout}；stderr=${out.stderr}")
        assertTrue(out.stdout.contains("\"ok\":true"), "能力结果要回到 stdout：${out.stdout}")
        assertTrue(out.stdout.contains("done"), "命令链其余部分要照常执行：${out.stdout}")
        assertEquals(1, cap.calls)
    }

    @Test
    fun `没注册的能力名照常走 shell`() = runBlocking {
        // 没有注册表 → 完全不受影响，仍然是普通命令
        val base = File(System.getProperty("java.io.tmpdir"), "cap-exec-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        val shell = CoreSelfTest.localShell()
        val out = AndroidCommandExecutor(shellPath = shell ?: "sh", layout = PrefixLayout(base.path))
            .execute("echo plain-command")
        assertEquals(0, out.exitCode)
        assertTrue(out.stdout.contains("plain-command"), "普通命令要照常跑：${out.stdout}")
    }

    @Test
    fun `能力清单要出现在工具描述里否则模型不会用`() {
        val caps = DeviceCapabilityRegistry(
            listOf(
                FakeCapability("torch"),
                FakeCapability("battery-status"),
            )
        )
        val tool = ShellTool(executor(), capabilities = caps)
        assertTrue(
            tool.description.contains("termux-torch"),
            "工具描述要列出能力命令：${tool.description}",
        )
        assertTrue(
            tool.description.contains("termux-battery-status"),
            "工具描述要列出能力命令：${tool.description}",
        )
    }

    @Test
    fun `没有能力时工具描述不乱加东西`() {
        val tool = ShellTool(executor())
        assertTrue(
            !tool.description.contains("termux-"),
            "没注册能力就不该出现 termux 清单：${tool.description}",
        )
    }
}
