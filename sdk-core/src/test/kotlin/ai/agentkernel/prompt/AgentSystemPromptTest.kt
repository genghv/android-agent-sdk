package ai.agentkernel.prompt

import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.device.okJson
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.workspace.Quota
import ai.agentkernel.workspace.WorkspacePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 系统提示词的测试。
 *
 * 提示词是**写死**的 —— 所以它的每一段都得有护栏：
 * 环境路径、命令清单、能力边界、维护规则，少一段模型就会去撞墙。
 */
class AgentSystemPromptTest {

    private class Fake(override val name: String, override val origin: CapabilityOrigin) : DeviceCapability {
        override val usage: String = "${if (origin == CapabilityOrigin.TERMUX) "termux-" else "agent-"}$name"
        override fun run(args: List<String>): String = okJson { }
    }

    private val layout = PrefixLayout("/data/data/com.example.app/files")

    private val capabilities = DeviceCapabilityRegistry(
        listOf(
            Fake("torch", CapabilityOrigin.TERMUX),
            Fake("cleanup", CapabilityOrigin.INTERNAL),
        )
    )

    @Test
    fun `提示词里要有工作区根和环境变量名 不重复写子路径`() {
        val text = AgentSystemPrompt.build(layout, capabilities)
        assertTrue(text.contains(layout.agentRoot), "要写真实的工作区根：\n$text")
        // 子路径一律用环境变量拼 —— 这些变量在 shell 里是真设了的
        assertTrue(text.contains("\$AGENT_ROOT"), "要写 AGENT_ROOT")
        assertTrue(text.contains("\$PREFIX"), "要写 PREFIX")
        assertTrue(text.contains("\$AGENT_TRASH"), "要写 AGENT_TRASH")
        // 绝对子路径不重复出现（那正是字数预算的大头）
        assertFalse(text.contains(layout.home), "HOME 用相对写法，不重复绝对路径：\n$text")
        assertFalse(text.contains(layout.prefix), "PREFIX 用相对写法")
        assertFalse(text.contains(layout.trash), "回收站用相对写法")
    }

    @Test
    fun `核心模式要装得进网关的 2000 字符预算`() {
        // 用真机上那种长度的路径测，别用短路径骗自己
        val realLayout = PrefixLayout("/data/user/0/com.agentkernel.demo/files")
        val inv = inventoryOf("ls", "cp", "mv", "rm", "mkdir", "find", "du", "df", "pwd", "touch")
        val core = AgentSystemPrompt.build(
            realLayout,
            capabilities,
            shellInventory = inv,
            policy = WorkspacePolicy(quota = Quota.Bytes(200 * 1024 * 1024)),
        )
        assertTrue(
            core.length < 2000,
            "核心模式必须装进网关 system 段（实测有网关硬截断到 2000），现在 ${core.length}：\n$core",
        )
    }

    @Test
    fun `两类命令要分开介绍`() {
        val text = AgentSystemPrompt.build(layout, capabilities)
        assertTrue(text.contains("termux-torch"), "要列 Termux 对齐的命令")
        assertTrue(text.contains("agent-cleanup"), "要列内部自制命令")
        assertTrue(
            text.contains("不在 Termux:API 里"),
            "要明确告诉模型内部命令的来历，否则它会去别处找：\n$text",
        )
    }

    @Test
    fun `能力边界要写死`() {
        val text = AgentSystemPrompt.build(layout, capabilities)
        assertTrue(text.contains("装应用"), "要写明装应用这类特权操作不提供")
        assertTrue(text.contains("点屏幕"), "要点屏不提供")
        assertTrue(text.contains("改系统设置"), "改系统设置不提供")
    }

    @Test
    fun `工作区规则要进提示词`() {
        val text = AgentSystemPrompt.build(
            layout,
            capabilities,
            WorkspacePolicy(quota = Quota.Bytes(200 * 1024 * 1024)),
        )
        assertTrue(text.contains("agent-cleanup"), "要告诉 agent 怎么清理")
        assertTrue(text.contains("agent-quota"), "要告诉 agent 配额能自己改")
        assertTrue(text.contains("200.0 MB") || text.contains("200 MB"), "要写真实配额值")
        assertTrue(text.contains(".agent-keep"), "要说明怎么保护目录")
    }

    @Test
    fun `写文件跑脚本的用法要给全`() {
        val text = AgentSystemPrompt.build(layout, capabilities)
        assertTrue(text.contains(">"), "要说明重定向怎么写")
        assertTrue(text.contains("heredoc"), "要多行写法")
        assertTrue(text.contains("sh "), "要说明跑脚本必须显式给解释器")
    }

    @Test
    fun `宿主补充说明要拼进去`() {
        val text = AgentSystemPrompt.build(layout, capabilities, extraNotes = "宿主接口基地址：https://host.example/api")
        assertTrue(text.contains("https://host.example/api"))
    }

    @Test
    fun `命令清单按探测结果写 不写死`() {
        // 模拟一台「只有 ls 和 cat」的机器
        val inventory = ShellCommandInventory.Result(
            listOf(
                ShellCommandInventory.Group("文件与目录", listOf("ls")),
                ShellCommandInventory.Group("文本处理", listOf("cat")),
            )
        )
        val text = AgentSystemPrompt.build(layout, capabilities, shellInventory = inventory)
        assertTrue(text.contains("实测存在"), "要告诉模型这份清单是实测的：\n$text")
        assertTrue(text.contains("ls"))
        assertTrue(text.contains("cat"))
        // 没探测到的命令不能出现在清单段里
        val shellSection = text.substringAfter("标准 shell").substringBefore("### 2)")
        assertFalse(shellSection.contains("curl"), "这台机器上没有 curl，不能列：\n$shellSection")
        assertFalse(shellSection.contains("awk"), "这台机器上没有 awk，不能列：\n$shellSection")
    }

    @Test
    fun `没给探测结果时用静态清单兜底`() {
        val text = AgentSystemPrompt.build(layout, capabilities, shellInventory = null)
        assertTrue(text.contains("toybox"), "兜底清单也要说清是系统自带的")
        assertTrue(text.contains("curl"))
        assertFalse(text.contains("实测存在"), "兜底时不能说『实测』")
    }

    // ---- 瘦身（为有 system 段预算的网关准备） ----

    private fun inventoryOf(vararg names: String): ShellCommandInventory.Result =
        ShellCommandInventory.Result(
            listOf(
                ShellCommandInventory.Group("文件与目录", names.toList()),
                ShellCommandInventory.Group("文本处理", listOf("cat", "echo", "awk", "grep", "sed")),
                ShellCommandInventory.Group("网络", listOf("curl", "nc")),
                ShellCommandInventory.Group("杂项", listOf("strace", "vmstat", "top")),
            )
        )

    @Test
    fun `核心模式比完整模式短很多`() {
        val inv = inventoryOf("ls", "cp", "mv", "rm", "mkdir", "find", "du", "df", "pwd", "touch")
        val core = AgentSystemPrompt.build(layout, capabilities, shellInventory = inv)
        val full = AgentSystemPrompt.build(
            layout, capabilities, shellInventory = inv,
            commandDetail = AgentSystemPrompt.CommandDetail.FULL,
        )
        assertTrue(
            core.length < full.length,
            "核心模式应该更短：core=${core.length} full=${full.length}",
        )
    }

    @Test
    fun `核心模式只列核心命令 完整模式全列`() {
        val inv = inventoryOf("ls", "cp", "mv", "rm", "mkdir", "find", "du", "df", "pwd", "touch")
        val core = AgentSystemPrompt.build(layout, capabilities, shellInventory = inv)
        val full = AgentSystemPrompt.build(
            layout, capabilities, shellInventory = inv,
            commandDetail = AgentSystemPrompt.CommandDetail.FULL,
        )
        // strace / vmstat / top 不是核心命令 —— 核心模式不该出现
        assertFalse(core.contains("strace"), "核心模式不该列非核心命令")
        assertFalse(core.contains("vmstat"))
        assertFalse(core.contains("top"))
        // 完整模式应该都留着
        assertTrue(full.contains("strace"), "完整模式应保留全部命令")
        assertTrue(full.contains("vmstat"))
        assertTrue(full.contains("top"))
        // 核心命令两种模式都有
        assertTrue(core.contains("ls"))
        assertTrue(core.contains("awk"))
    }

    @Test
    fun `瘦身不能砍掉关键段落`() {
        val inv = inventoryOf("ls", "cp", "mv", "rm", "mkdir", "find", "du", "df", "pwd", "touch")
        val text = AgentSystemPrompt.build(
            layout, capabilities, shellInventory = inv,
            policy = WorkspacePolicy(quota = Quota.Bytes(200 * 1024 * 1024)),
        )
        // 这四段是**砍不得**的：砍了模型就既不知道能力、也不知道垃圾谁清
        assertTrue(text.contains(layout.agentRoot), "工作区根必须留")
        assertTrue(text.contains("\$HOME"), "HOME 必须留")
        assertTrue(text.contains("termux-torch"), "能力清单必须留")
        assertTrue(text.contains("agent-cleanup"), "内部命令清单必须留")
        assertTrue(text.contains("装应用"), "能力边界必须留")
        assertTrue(text.contains("agent-cleanup"), "工作区规则必须留")
        assertTrue(text.contains(".agent-keep"))
    }

    @Test
    fun `瘦身模式不教原生 curl 以免凭证进转录`() {
        val inv = inventoryOf("ls", "cp", "mv", "rm", "mkdir", "find", "du", "df", "pwd", "touch")
        val core = AgentSystemPrompt.build(layout, capabilities, shellInventory = inv)
        assertFalse(
            core.contains("curl -s -H"),
            "瘦身后不该教 `curl -H` —— 那会把 token 写进命令历史；该走 agent-http-*",
        )
        val full = AgentSystemPrompt.build(
            layout, capabilities, shellInventory = inv,
            commandDetail = AgentSystemPrompt.CommandDetail.FULL,
        )
        assertTrue(full.contains("curl -s -H"), "完整模式保留原生 curl 用法")
    }
}
