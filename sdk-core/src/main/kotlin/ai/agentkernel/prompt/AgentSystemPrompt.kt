package ai.agentkernel.prompt

import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.workspace.WorkspacePolicy
import ai.agentkernel.workspace.formatBytes

/**
 * 系统提示词 —— **写死的环境说明书**。
 *
 * 模型看不见这台手机：它不知道自己在哪儿、工作目录是哪儿、哪些命令能用、
 * 哪些事**做不了**。这份提示词就是把这些事实一次性讲清楚，
 * 免得它靠试错去发现（试错既慢又会留下一堆垃圾）。
 *
 * ## 为什么内容是「生成」出来的而不是一段死文本
 *
 * 路径、可用命令清单、配额规则都是**运行时才知道**的东西：
 * - 路径取决于宿主的 `filesDir`（宿主包名各不相同）；
 * - 命令清单取决于注册了哪些能力；
 * - 配额规则取决于生效策略（宿主注入 or agent 自己改过）。
 *
 * 所以这里是「**模板写死 + 事实填进去**」：结构固定，内容随运行环境变。
 *
 * ## 边界要写死
 *
 * 装应用 / 点屏幕 / 改系统设置这些特权能力**故意不做**。
 * 不写进提示词，模型会一次次去试 `pm install`、`input tap`，每次都失败、
 * 每次都在工作区里留下垃圾。写清楚「没有这个能力」，比让它撞墙便宜得多。
 */
object AgentSystemPrompt {

    /**
     * 命令清单在提示词里写多少。
     *
     * 为什么要分两档：**很多后端对客户端 system 段有字数上限**，
     * 实测有后端硬截断到 2000 字符（`clientSystem.trim().slice(0, 2000)`）——
     * 而被砍掉的是提示词**尾部**，正好是「能力清单」和「工作区维护规则」这两段最要命的内容。
     *
     * - [CORE]（默认）只写**高频核心**命令（仍然白名单 ∩ 实存），完整清单放技能文档里按需查；
     * - [FULL] 写全部探测到的命令。
     */
    enum class CommandDetail {
        /** 只列核心命令 —— 提示词要装进后端预算时用这个。 */
        CORE,

        /** 列出全部探测到的命令。 */
        FULL,
    }

    /**
     * 生成系统提示词。
     *
     * @param layout 拟 Linux 布局（提供真实路径）
     * @param capabilities 能力注册表（提供真实命令清单）；null = 不列能力
     * @param policy 生效的工作区策略（配额/保留规则）；null = 不讲工作区
     * @param shellInventory 运行时探测到的、这台设备上**确实存在**的 shell 命令；
     *   null = 用一份静态默认清单（只在拿不到设备信息时用）
     * @param commandDetail 命令清单写多少（默认 [CommandDetail.CORE]）
     * @param extraNotes 宿主自己的补充说明（后端地址、内部接口约定等）
     */
    fun build(
        layout: PrefixLayout,
        capabilities: DeviceCapabilityRegistry? = null,
        policy: WorkspacePolicy? = null,
        shellInventory: ShellCommandInventory.Result? = null,
        commandDetail: CommandDetail = CommandDetail.CORE,
        extraNotes: String = "",
    ): String = buildString {
        appendLine("你运行在一台 Android 手机上 —— 更准确地说，是宿主 App 里的一个「拟 Linux」沙箱。")
        appendLine()
        // 环境段只写**一次**绝对路径（工作区根），子路径一律用环境变量拼。
        //
        // 为什么：后端的 system 段有字数上限（实测有后端硬截断到 2000 字符），
        // 而每条绝对路径都要重复 40+ 字符 —— 写七遍就是三百多字符，
        // 砍掉的正好是「能力清单」和「工作区维护规则」这两段更要命的内容。
        //
        // 用 `$AGENT_ROOT/home` 这种写法不是省事，是**更好用**：
        // 这些变量在 shell 里是真设了的（见 PrefixEnvironment），
        // 模型写 `$AGENT_ROOT/xxx` 直接就能跑，比背一串绝对路径更不容易错。
        appendLine("## 你的环境")
        appendLine("- \$AGENT_ROOT = ${layout.agentRoot}（你只能写这儿）")
        appendLine("- \$HOME = \$AGENT_ROOT/home ← shell 默认 cwd，命令在这跑")
        appendLine("- \$PREFIX = \$AGENT_ROOT/usr ← 拟 Linux 根；\$TMPDIR = \$PREFIX/tmp")
        appendLine("- \$AGENT_TRASH = \$AGENT_ROOT/trash ← 删除先进这儿，不是真删")
        // 为什么提 var：长期记忆与会话归档都落在这儿，不说模型就找不到 ——
        // 找不到就会在工作区里堆一堆「自己的记忆」，而那正是该由记忆命令管的地方。
        // ★ 这一行每个字符都算预算（核心模式只剩几十字符余量），所以只说「哪个目录是什么」，
        //   完整目录结构让模型自己 `ls $AGENT_VAR` 看。
        appendLine("- \$AGENT_VAR = \$AGENT_ROOT/var（memory/ 记忆，archive/ 归档）")
        appendLine("- PATH = \$PREFIX/bin:/system/bin:/system/xbin")
        appendLine()
        appendLine("## 你能用的命令")
        appendLine("### 1) 标准 shell（Android 自带 toybox，不用装任何东西）")
        val inventory = shellInventory?.takeIf { it.commandCount > 0 }
        val shown = when (commandDetail) {
            CommandDetail.CORE -> inventory?.let { ShellCommandInventory.core(it) }
            CommandDetail.FULL -> inventory
        }
        if (shown != null && shown.commandCount > 0) {
            appendLine(ShellCommandInventory.render(shown))
            appendLine(
                if (commandDetail == CommandDetail.CORE) {
                    "（上面是**高频核心**命令，也都是**这台设备上实测存在**的；" +
                        "完整清单见技能 android-capability。不在清单里的别瞎试。）"
                } else {
                    "（上面这份是**这台设备上实测存在**的命令；不在这份清单里的就别去试。）"
                }
            )
        } else {
            appendLine("ls cat echo grep sed awk cut sort head tail wc find du df chmod rm cp mv mkdir")
            appendLine("tar unzip gzip xxd base64 dd stat date sleep timeout tee tr")
            appendLine("curl（HTTP 请求，支持 -H/-d/--max-time）  nc（原始 TCP）  ping")
        }
        appendLine()
        appendLine("写文件、读文件、删文件、跑脚本都靠这些就够了：")
        appendLine("- 写：`echo \"内容\" > 文件名`；多行用 heredoc：`cat > 文件名 << 'EOF' … EOF`")
        appendLine("- 读：`cat 文件名`；跑脚本：`sh 脚本.sh`（**必须显式给解释器**）")
        appendLine("- 删：`rm -f 文件名`")
        if (commandDetail == CommandDetail.FULL) {
            // 只有「完整清单」模式才教它用原生 curl。
            // 瘦身后的模式**故意不给 curl 用法** —— 免得模型为了调内部接口就写
            // `curl -H 'Authorization: Bearer …'`，那会把凭证写进命令历史和转录；
            // 正确做法是走 `agent-http-get` / `agent-http-post`（鉴权由宿主注入，命令里没有 token）。
            if (inventory == null || inventory.contains("curl")) {
                appendLine("- HTTP：`curl -s -H 'X: v' 'http://…'`（`-d` 发 body，`--max-time` 设超时）")
            }
            if (inventory == null || inventory.contains("awk")) {
                appendLine("- `awk` 是 toybox 的**简化版**，别指望 GNU awk 的功能。")
            }
        }
        appendLine()
        if (capabilities != null) {
            // ★ 核心模式用**短用法**：完整参数形态一行能到上百字符（agent-memory 那种），
            //   而核心模式存在的意义就是「装进两千字符的后端预算」——
            //   写详细了装不下，删掉这条能力更糟（模型压根不知道它存在）。
            //   完整形态让模型按需问命令（能力失败时会把完整用法回给它）或查技能文档。
            val byOrigin = capabilities.describeByOrigin(short = commandDetail == CommandDetail.CORE)
            val termux = byOrigin[CapabilityOrigin.TERMUX].orEmpty()
            val internal = byOrigin[CapabilityOrigin.INTERNAL].orEmpty()
            if (termux.isNotEmpty()) {
                appendLine("### 2) 设备能力（命令名与 Termux:API **逐字对齐**，你按 Termux 的习惯写就认）")
                termux.forEach { appendLine("- $it") }
                appendLine()
            }
            if (internal.isNotEmpty()) {
                appendLine("### 3) SDK 自制的命令（不在 Termux:API 里，别去别处找）")
                internal.forEach { appendLine("- $it") }
                // ★ 这里**不**再额外解释「记忆用 agent-memory 改、文本用 agent-text 改」——
                //   能力清单里本来就有这两条命令（`- agent-memory …` / `- agent-text …`），
                //   模型看得见就够知道了；再写一遍纯多花几十字符，
                //   而核心模式的预算只剩几十字符。
                //   行级补丁的用法细节在技能文档里（skills/agent-memory/SKILL.md）。
                appendLine()
            }
        }
        appendLine("## 你**不能**做的事")
        appendLine("- 装应用、点屏幕、改系统设置 —— 这些是特权操作，本环境**不提供**，别去试。")
        appendLine("- 你没有 root，也没有别的 App 的数据；本环境只覆盖你自己的工作区。")
        appendLine()
        if (policy != null) {
            val quota = policy.quota
            val quotaText = when (quota) {
                is ai.agentkernel.workspace.Quota.Unlimited -> "不限"
                is ai.agentkernel.workspace.Quota.Bytes -> formatBytes(quota.bytes)
                is ai.agentkernel.workspace.Quota.PercentOfFreeSpace -> "设备剩余空间的 ${quota.percent}%"
            }
            appendLine("## 工作区维护规则（**这条会直接影响你的行为**）")
            appendLine("- 工作区配额：$quotaText（用 `agent-quota show` 看当前生效值，`agent-quota set 200MB` 改）。")
            appendLine("- **你产生的东西你自己负责清理**，别指望有人替你收拾。")
            appendLine("- 占用接近或超过配额时，你会收到一条 `[工作区告警]`，")
            appendLine("  按告警里给的命令照做（`agent-cleanup --dry-run` → `--apply`）。")
            appendLine("- 默认保留：${policy.keepPatterns.take(8).joinToString("、")} 这类源码与数据。")
            appendLine("- 目录里放一个空文件 `${policy.keepMarkerName}`，整个目录就不会被动。")
            appendLine("- 删除默认进回收站，不是直接抹掉。")
            appendLine()
        }
        if (extraNotes.isNotBlank()) {
            appendLine("## 宿主补充说明")
            appendLine(extraNotes.trim())
            appendLine()
        }
        append("用命令去做事，别只口头描述。")
    }
}
