package ai.agentkernel.workspace

import ai.agentkernel.prefix.PrefixLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工作区（隔离 + 配额 + 清理 + 告警）的测试 —— 纯 JVM，不需要设备。
 *
 * 这一组是「垃圾自己做处理、超限提醒 agent 自己维护」那条需求的护栏。
 */
class WorkspaceTest {

    private fun tempLayout(): PrefixLayout {
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-ws-${System.nanoTime()}")
        return PrefixLayout(base.absolutePath)
    }

    private fun write(layout: PrefixLayout, relative: String, size: Int): File {
        val f = File(layout.agentRoot, relative)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(size) { 'x'.code.toByte() })
        return f
    }

    // ---- 隔离 ----

    @Test
    fun `agent 的整个世界收在一棵子树里`() {
        val filesDir = "/data/data/com.example.app/files"
        val layout = PrefixLayout(filesDir)

        assertEquals("$filesDir/agent", layout.agentRoot)
        assertEquals("$filesDir/agent/home", layout.home)
        assertEquals("$filesDir/agent/usr", layout.prefix)
        assertEquals("$filesDir/agent/usr/tmp", layout.tmp)
        assertEquals("$filesDir/agent/trash", layout.trash)
        assertEquals("$filesDir/agent/var", layout.varDir)
    }

    @Test
    fun `agent 的 HOME 不再和宿主文件平级`() {
        val filesDir = "/data/data/com.example.app/files"
        val layout = PrefixLayout(filesDir)
        assertTrue(
            layout.home != "$filesDir/home",
            "HOME 必须挪进 agent 子树，否则 agent 和宿主 App 的文件平级",
        )
        assertTrue(layout.home.startsWith(layout.agentRoot + "/"))
    }

    @Test
    fun `可以退回平级布局`() {
        val filesDir = "/data/data/com.example.app/files"
        val layout = PrefixLayout(filesDir, agentRootName = "")
        assertEquals("$filesDir/home", layout.home)
    }

    // ---- 通配符 ----

    @Test
    fun `通配符匹配`() {
        assertTrue(globMatch("*.log", "app.log"))
        assertTrue(globMatch("*.log", "APP.LOG") == false, "默认区分大小写")
        assertTrue(globMatch("core.*", "core.123"))
        assertTrue(globMatch("*", "anything"))
        assertFalse(globMatch("*.log", "app.txt"))
        assertTrue(listOf("*.a", "*.b").matchesAny("x.b"))
    }

    // ---- 分类 ----

    @Test
    fun `只有命中垃圾规则的才算垃圾其余保留`() {
        val layout = tempLayout()
        write(layout, "home/keep.py", 100)
        write(layout, "home/scratch.log", 200)
        write(layout, "home/data.json", 300)

        val inspector = WorkspaceInspector(layout)
        val plan = inspector.plan(WorkspacePolicy())

        assertEquals(1, plan.count, "只有 .log 命中垃圾规则：${plan.candidates.map { it.path }}")
        assertTrue(plan.candidates[0].path.endsWith("scratch.log"))
        assertTrue(plan.candidates[0].reason.isNotBlank(), "每条都要能解释为什么删")
    }

    @Test
    fun `keep 标记保护整个目录`() {
        val layout = tempLayout()
        write(layout, "home/sub/.agent-keep", 0)
        write(layout, "home/sub/scratch.log", 200)

        val plan = WorkspaceInspector(layout).plan(WorkspacePolicy())
        assertEquals(0, plan.count, "有 .agent-keep 标记的目录整体不动")
    }

    @Test
    fun `usr 与 var 目录受保护`() {
        val layout = tempLayout()
        write(layout, "usr/bin/app.log", 100)
        write(layout, "var/app.log", 100)
        write(layout, "home/app.log", 100)

        val plan = WorkspaceInspector(layout).plan(WorkspacePolicy())
        assertEquals(1, plan.count, "运行时和状态目录不能被清")
        // 路径分隔符跟平台走，所以只查目录名，别写死 "/home/"。
        assertTrue(plan.candidates[0].path.contains("home"), "该清的是 home 里的那个")
    }

    @Test
    fun `占用统计按顶层目录分组`() {
        val layout = tempLayout()
        write(layout, "home/a.log", 500)
        write(layout, "home/b.log", 300)
        write(layout, "var/x.json", 200)

        val report = WorkspaceInspector(layout).inspect(WorkspacePolicy())
        val home = report.categories.first { it.name == "home" }
        assertEquals(800L, home.bytes)
        assertEquals(2, home.files)
        assertEquals(1000L, report.usageBytes)
    }

    // ---- 清理 ----

    @Test
    fun `清理先进回收站不是直接抹掉`() {
        val layout = tempLayout()
        val target = write(layout, "home/scratch.log", 500)
        write(layout, "home/keep.py", 100)

        val cleaner = WorkspaceCleaner(layout)
        val result = cleaner.apply(WorkspacePolicy())

        assertEquals(1, result.count)
        assertEquals(500L, result.freedBytes)
        assertFalse(target.exists(), "原文件应该被挪走")
        assertTrue(File(layout.trash).listFiles()?.isNotEmpty() == true, "应该进了回收站")
        assertTrue(File(layout.home, "keep.py").exists(), "有用的必须留住")
    }

    @Test
    fun `回收站超龄的会被彻底清掉`() {
        val layout = tempLayout()
        write(layout, "home/scratch.log", 500)
        WorkspaceCleaner(layout).apply(WorkspacePolicy())

        // 把回收站里的东西改成很久以前
        File(layout.trash).listFiles()?.forEach { it.setLastModified(0L) }

        val result = WorkspaceCleaner(layout).purgeTrash(WorkspacePolicy(trashMaxAgeDays = 1))
        assertEquals(1, result.count, "超龄的回收站条目要能被彻底清掉")
        assertEquals(0, File(layout.trash).listFiles()?.size ?: 0)
    }

    // ---- 配额 ----

    @Test
    fun `配额按设备剩余空间折算所以不是写死的`() {
        assertNull(Quota.Unlimited.resolve(1_000_000))
        assertEquals(2000L, Quota.PercentOfFreeSpace(10.0).resolve(20_000L))
        assertEquals(1000L, Quota.Bytes(1000).resolve(999_999L))
    }

    @Test
    fun `超配额会产生带命令的告警`() {
        val layout = tempLayout()
        write(layout, "home/big.bin", 5000)

        val guard = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(1000)))
        val alert = guard.alert()

        assertNotNull(alert, "超配额必须提醒 agent")
        assertTrue(alert.contains("agent-cleanup"), "告警要给出能直接照做的命令：\n$alert")
        assertTrue(alert.contains("工作区告警"))
    }

    @Test
    fun `没超配额就不打扰`() {
        val layout = tempLayout()
        write(layout, "home/small.log", 10)
        val guard = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(10_000_000)))
        assertNull(guard.alert(), "没超限就别插告警，白白占模型上下文")
    }

    @Test
    fun `接近配额就提醒而不是等爆了`() {
        val layout = tempLayout()
        write(layout, "home/a.bin", 900)
        val guard = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(1000), warnRatio = 0.85))
        assertNotNull(guard.alert(), "用到 90% 就该提醒")
    }

    @Test
    fun `告警文本必须逐字稳定（会随占用变的数字会打断前缀缓存）`() {
        // 两个工作区的占用**完全不同**，但都超配额 → 告警必须一字不差。
        //
        // 为什么这么严：告警是拼在**稳定前缀**里的（系统提示词后面）。
        // 以前告警写的是「占用 12.3MB（37%）」这种带数字的句子 ——
        // 数字每轮都变 → 前缀每轮都被打断 → **缓存命中率从第 3 轮起归零**。
        // 告警只要说清「该清」和「怎么清」，具体数字让 agent 自己查。
        val layoutA = tempLayout()
        write(layoutA, "home/a.bin", 5_000)
        val layoutB = tempLayout()
        write(layoutB, "home/b.bin", 900_000)

        val alertA = WorkspaceGuard(layoutA, WorkspacePolicy(quota = Quota.Bytes(1000))).alert()
        val alertB = WorkspaceGuard(layoutB, WorkspacePolicy(quota = Quota.Bytes(1000))).alert()

        assertNotNull(alertA)
        assertNotNull(alertB)
        assertEquals(alertA, alertB, "告警里不能有随占用变的东西 —— 那会让前缀缓存每轮失效")
        assertTrue(alertA.none { it.isDigit() }, "告警里不该出现数字：\n$alertA")
    }

    // ---- 配额可由 agent 自己改 ----

    @Test
    fun `agent 改的配额会持久化并覆盖宿主默认值`() {
        val layout = tempLayout()
        val guard = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(1000)))

        assertEquals("host", guard.effectivePolicy().second)
        guard.savePolicy(guard.effectivePolicy().first.copy(quota = Quota.Bytes(7777)))
        assertEquals("agent", guard.effectivePolicy().second)
        assertEquals(7777L, guard.effectivePolicy().first.quota.resolve(0L))

        // 新进程（新的 store）也要读得到
        val reopened = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(1000)))
        assertEquals(7777L, reopened.effectivePolicy().first.quota.resolve(0L))

        reopened.resetPolicy()
        assertEquals("host", reopened.effectivePolicy().second)
    }

    @Test
    fun `配额策略文件坏了也不能让 agent 挂掉`() {
        val layout = tempLayout()
        write(layout, "var/workspace-policy.json", 20)
        File(layout.varDir, "workspace-policy.json").writeText("{ 这不是 json")
        val guard = WorkspaceGuard(layout, WorkspacePolicy(quota = Quota.Bytes(1000)))
        assertEquals("host", guard.effectivePolicy().second, "坏文件要退回宿主默认值")
    }
}
