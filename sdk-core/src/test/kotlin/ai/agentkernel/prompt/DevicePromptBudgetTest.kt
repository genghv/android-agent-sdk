package ai.agentkernel.prompt

import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.device.okJson
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.workspace.Quota
import ai.agentkernel.workspace.WorkspacePolicy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「这台设备等价」的提示词预算回归。
 *
 * ## 为什么要有这条
 *
 * 有的服务把客户端 system 段**硬截断到 2000 字符**，
 * 而被砍掉的是提示词**尾巴** —— 正好是「能力清单」和「工作区维护规则」这两段最要命的内容。
 * 所以提示词长度不是「 nice to know」，是**能不能用**的开关。
 *
 * ## 这条测试怎么代替真机
 *
 * 真机量长度要 App 进程自己跑（`run-as` 那条路给假阳性，而且要占用用户的手机）。
 * 所以这里用**真机同款输入**在 JVM 上复刻一遍：
 * - 路径 = 真机包名的 filesDir（`/data/user/0/<pkg>/files`）；
 * - 命令清单 = 真机探测到的白名单命中集（白名单去掉这台机器上没有的两个）；
 * - 能力清单 = 那 14 个命令的 usage 字符串（6 个 termux-* + 8 个 agent-*）；
 *   ★ 必须是**真机真实注册的那几条** —— 真机注册了记忆与文本能力，
 *   这里就也得列上；少列一条 = 测出来的长度比真机短，「真机装不下」会假绿。
 * - 配额策略 = 宿主默认（设备剩余空间的 5%）。
 *
 * 交叉校验：这套输入在**旧环境段**下算出 **2126 字符**，与锚点一致 ⇒
 * 复刻是忠实的，于是这里算出的数字可以直接当真机值用。
 *
 * 真机上的权威数字仍然看 Demo 自检打进 logcat 的那行（`| prompt=… chars`）。
 */
class DevicePromptBudgetTest {

    private class Fake(
        override val name: String,
        override val origin: CapabilityOrigin,
        override val usage: String,
        // ★ 短用法要**与真实实现逐字一致**（AgentMemoryCapabilities.shortUsage /
        //   AgentTextCapabilities.shortUsage 那边各有单测锁着）——
        //   这里写错了，这条预算测试就会测出一个真机上不存在的长度。
        override val shortUsage: String = usage,
    ) :
        DeviceCapability {
        override fun run(args: List<String>): String = okJson { }
    }

    /** 真机上注册的全部能力（顺序 = 注册顺序）。 */
    private val capabilities = DeviceCapabilityRegistry(
        listOf(
            Fake("torch", CapabilityOrigin.TERMUX, "termux-torch on|off"),
            Fake("vibrate", CapabilityOrigin.TERMUX, "termux-vibrate [-d milliseconds]"),
            Fake("notification", CapabilityOrigin.TERMUX, "termux-notification -t title -c content"),
            Fake("clipboard-get", CapabilityOrigin.TERMUX, "termux-clipboard-get"),
            Fake("clipboard-set", CapabilityOrigin.TERMUX, "termux-clipboard-set <text>"),
            Fake("battery-status", CapabilityOrigin.TERMUX, "termux-battery-status"),
            Fake("workspace", CapabilityOrigin.INTERNAL, "agent-workspace"),
            Fake("cleanup", CapabilityOrigin.INTERNAL, "agent-cleanup [--dry-run|--apply|--purge-trash]"),
            Fake("quota", CapabilityOrigin.INTERNAL, "agent-quota show|set <大小，如 200MB|1GB|不限>|reset"),
            Fake("schedule", CapabilityOrigin.INTERNAL, "agent-schedule list|add \"<cron>\" \"<动作>\"|remove <id>|due"),
            Fake("http-get", CapabilityOrigin.INTERNAL, "agent-http-get <url|相对路径> [--header \"K: V\"] [--timeout 15000]"),
            Fake("http-post", CapabilityOrigin.INTERNAL, "agent-http-post <url|相对路径> [--header \"K: V\"] [--data \"{...}\"]"),
            // ★ 下面两条是**记忆开着时**才会注册的（宿主不启用记忆就别注册）。
            //   它们必须在这份假清单里 —— 少了它们，这条预算测试就测不到真机的真实长度，
            //   而真机上恰恰是它们把提示词顶到了预算边缘。
            Fake("memory", CapabilityOrigin.INTERNAL, "agent-memory <list|get <id>|add \"<正文>\"|edit <id> \"<正文>\"|patch <id> <补丁规格>|delete <id>|search \"<关键词>\">", "agent-memory <list|get|add|edit|patch|delete|search>"),
            Fake("text", CapabilityOrigin.INTERNAL, "agent-text <read <相对路径>|patch <相对路径> <补丁规格>>", "agent-text <read|patch>"),
        )
    )

    /** 真机（Android 16 / 小米）探测到的白名单命中集：白名单去掉这台机器没有的 zip 与 wget。 */
    private val deviceInventory = ShellCommandInventory.Result(
        ShellCommandInventory.WHITELIST.map { group ->
            ShellCommandInventory.Group(
                group.title,
                group.commands.filter { it != "zip" && it != "wget" },
            )
        }.filter { it.commands.isNotEmpty() }
    )

    private val layout = PrefixLayout("/data/user/0/com.agentkernel.demo/files")

    private val policy = WorkspacePolicy(quota = Quota.PercentOfFreeSpace(5.0))

    @Test
    fun `核心模式要装得进网关的 2000 字符预算`() {
        val core = AgentSystemPrompt.build(
            layout,
            capabilities,
            policy,
            deviceInventory,
            AgentSystemPrompt.CommandDetail.CORE,
        )
        println("[device-prompt] CORE = ${core.length} 字符（预算 2000）")
        assertTrue(
            core.length < 2000,
            "核心模式必须装进网关 system 段，现在 ${core.length} 字符，超了 ${core.length - 2000}",
        )
    }

    @Test
    fun `完整模式仍然更长（它是可选档，不是默认档）`() {
        val full = AgentSystemPrompt.build(
            layout,
            capabilities,
            policy,
            deviceInventory,
            AgentSystemPrompt.CommandDetail.FULL,
        )
        val core = AgentSystemPrompt.build(
            layout,
            capabilities,
            policy,
            deviceInventory,
            AgentSystemPrompt.CommandDetail.CORE,
        )
        println("[device-prompt] CORE = ${core.length} / FULL = ${full.length} 字符")
        assertTrue(full.length > core.length, "完整模式应该更长：core=${core.length} full=${full.length}")
    }

    @Test
    fun `复刻是忠实的：旧环境段下算出来应当等于锚点`() {
        // 锚点见 [OLD_ENV_ANCHOR]：它锁的是整段提示词，改文案就要重量一次。
        // 用同一套复刻输入，把环境段换回旧写法，算出来必须还是锚点 ——
        // 对得上，说明这套输入（路径 / 命令清单 / 能力清单 / 策略）复刻忠实。
        val root = "/data/user/0/com.agentkernel.demo/files/agent"
        val oldEnv = buildString {
            appendLine("## 你的环境")
            appendLine("- 宿主 App 的私有目录：/data/user/0/com.agentkernel.demo/files")
            appendLine("- 你的工作区（隔离区）：$root")
            appendLine("- HOME = $root/home  ← 你的工作目录，shell 的默认 cwd，命令都在这儿跑")
            appendLine("- PREFIX = $root/usr  ← 拟 Linux 根（你的隔离区）")
            appendLine("- TMPDIR = $root/usr/tmp")
            appendLine("- AGENT_TRASH = $root/trash  ← 删除先进这里，不是真删")
            appendLine("- PATH = $root/usr/bin:/system/bin:/system/xbin")
            appendLine()
        }
        // ⚠️ 这里必须与 AgentSystemPrompt 的环境段**逐字一致** ——
        // 对不上 `replace` 就成了 no-op，下面那条断言会误报成「复刻不忠实」。
        val newEnv = buildString {
            appendLine("## 你的环境")
            appendLine("- \$AGENT_ROOT = $root（你只能写这儿）")
            appendLine("- \$HOME = \$AGENT_ROOT/home ← shell 默认 cwd，命令在这跑")
            appendLine("- \$PREFIX = \$AGENT_ROOT/usr ← 拟 Linux 根；\$TMPDIR = \$PREFIX/tmp")
            appendLine("- \$AGENT_TRASH = \$AGENT_ROOT/trash ← 删除先进这儿，不是真删")
            appendLine("- \$AGENT_VAR = \$AGENT_ROOT/var（memory/ 记忆，archive/ 归档）")
            appendLine("- PATH = \$PREFIX/bin:/system/bin:/system/xbin")
            appendLine()
        }
        val core = AgentSystemPrompt.build(
            layout,
            capabilities,
            policy,
            deviceInventory,
            AgentSystemPrompt.CommandDetail.CORE,
        )
        val asIfOldEnv = core.replace(newEnv, oldEnv)
        println("[device-prompt] 旧环境段复刻 = ${asIfOldEnv.length}（锚点 $OLD_ENV_ANCHOR）")
        assertTrue(
            asIfOldEnv.length == OLD_ENV_ANCHOR,
            "复刻与锚点对不上：旧环境段下算出 ${asIfOldEnv.length}，锚点是 $OLD_ENV_ANCHOR",
        )
    }

    companion object {
        /**
         * 把**旧环境段**换回当前提示词后，算出来的字符数（锚点）。
         *
         * 对得上，说明这套复刻输入（路径 / 命令清单 / 能力清单 / 策略）忠实。
         *
         * ⚠️ 这个锚点锁的是**整段提示词**，不只是环境段：
         * 提示词正文改一个字（某段标题改短、换个说法），它就得重新量一次 ——
         * 否则文案一变这条断言就误报，而它本来要抓的是「复刻输入不忠实」。
         *
         * ★ 换环境段**本身不影响这个锚点**：环境段会被整段换成 `oldEnv` 再量，
         * 所以加一行 `$AGENT_VAR` 只是把 `newEnv` 一起加长，两边抵消。
         * 真正动锚点的是**环境段以外**的正文（比如能力段多一条命令）。
         *
         * 历史：真机首次量到 2057；提示词正文改写后为 2045；
         * 能力清单补齐 `agent-memory` / `agent-text` 两条（真机注册了什么，这里就得列什么）后为 2126。
         *
         * ★ **改动这份清单后一定要重量**：清单少一条 = 测出来的长度比真机短，
         *   于是「真机其实装不下」这条测试会假绿 —— 这比测试红更糟。
         */
        private const val OLD_ENV_ANCHOR = 2126
    }
}
