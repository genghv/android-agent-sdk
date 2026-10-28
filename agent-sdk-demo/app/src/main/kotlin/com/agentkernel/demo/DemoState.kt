package com.agentkernel.demo

import ai.agentkernel.android.AndroidAgentSdk
import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.android.OkHttpTransport
import ai.agentkernel.android.device.AgentHttpCapabilities
import ai.agentkernel.android.device.AgentWorkspaceCapabilities
import ai.agentkernel.android.device.defaultDeviceCapabilities
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.kernel.KernelEvent
import ai.agentkernel.kernel.TurnResult
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.EffortCatalog
import ai.agentkernel.llm.openai.EffortCatalogClient
import ai.agentkernel.llm.openai.LlmAuth
import ai.agentkernel.prompt.AgentSystemPrompt
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.UserMessage
import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一次执行的结果。 */
data class CommandResult(
    val title: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
    val verdict: Verdict,
) {
    val output: String get() = stdout.ifBlank { stderr }
}

/** 自检的一项。 */
data class CheckItem(
    val title: String,
    val detail: String,
    val command: String,
    val verdict: Verdict,
    val output: String,
    val elapsedMs: Long,
)

/** 对话里的一行。 */
sealed interface ChatLine {
    data class User(val text: String) : ChatLine
    data class Assistant(val text: String) : ChatLine
    /** 模型下发并执行了一条命令 —— **这是「模型能驱动设备能力」的关键证据**。 */
    data class ToolCall(val command: String, val output: String, val exitCode: Int) : ChatLine
    data class System(val text: String) : ChatLine
    /**
     * **缓存与前缀状态** —— 这轮请求的用量、前缀有没有被动过。
     *
     * 单列一行是因为它是「上下文检测」的**唯一权威读数**：
     * 上游是前缀缓存，命中与否决定成本与延迟；而前缀被动过 = 布局有 bug。
     */
    data class Metrics(val text: String) : ChatLine
}

/**
 * 演示台的状态与动作。
 *
 * 所有设备动作都**真的走 SDK**（同一个 `AndroidCommandExecutor` + 同一个能力注册表），
 * 没有另写一套「演示专用」的假实现 —— 演示台跑通 = SDK 跑通。
 *
 * ★ **它是 [AndroidViewModel]，不是普通类** —— 原因见 [startChatAutorun]：
 * agent 循环必须跑在**活过 activity 重建**的 scope 里。
 */
class DemoState(app: Application) : AndroidViewModel(app) {

    private val context: Context = app

    /** 配置存 SharedPreferences —— 运行时填，**绝不打包进 APK**。 */
    private val prefs = context.getSharedPreferences("demo_gateway", Context.MODE_PRIVATE)

    /**
     * 工作区能力：隔离工作区 + 配额 + 清理。
     *
     * 超配额时 [workspaceGuard] 会把一条**可执行**的告警注入给 agent，
     * 让它自己去 `agent-cleanup` —— 而不是我们替它删东西。
     */
    val workspace = AgentWorkspaceCapabilities(context)

    /**
     * HTTP 能力：调内部数据 API。
     *
     * 地址与 token **由宿主注入** —— agent 下发的命令里只会出现相对路径，
     * token 不进转录、不进日志、不进模型上下文。
     */
    val http = AgentHttpCapabilities(
        baseUrlProvider = { gatewayUrl },
        defaultHeadersProvider = {
            if (gatewayToken.isBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer $gatewayToken")
        },
    )

    /** 能力注册表：与《接入指南》里宿主该用的完全一样。 */
    val capabilities: DeviceCapabilityRegistry = DeviceCapabilityRegistry(
        defaultDeviceCapabilities(context, workspace = workspace, http = http)
    )

    /** 执行器：也是宿主该用的那个入口。 */
    val executor: AndroidCommandExecutor = AndroidCommandExecutor.forContext(context, capabilities)

    /**
     * 模型会看到的那份系统提示词（**不接模型也能生成**）。
     *
     * 把它摆在界面上，是为了让「提示词写死」这件事**看得见、可核对** ——
     * 特别是里面那份命令清单：是不是这台设备上真实存在的命令，一眼就能验。
     */
    var systemPrompt by mutableStateOf("")
        private set

    /**
     * 提示词里的命令清单写多少 —— **自检与实际对话必须用同一个值**。
     *
     * 这不是洁癖：两者一旦不一致，自检那条「提示词·命令清单与真机一致」就变成
     * **验了一份模型根本没看到的提示词** —— 自检会显示 ✅，而真机上的提示词早被服务端截断了。
     * （真机实测踩过：自检显示 2209 字符、实际对话用的是 1809。）
     *
     * Demo 取 **CORE**（1809 字符）：它是「在会把 system 段截断到 2000 的服务上也装得下」的
     * **安全值** —— 换成哪个服务都不会被砍掉提示词尾部。
     * 直连 DeepSeek 官方没有这个上限，那边可以用 SDK 的默认值 FULL（2209 字符）。
     */
    private val commandDetail = AgentSystemPrompt.CommandDetail.CORE

    init {
        systemPrompt = AndroidAgentSdk.buildSystemPrompt(
            executor = executor,
            capabilities = capabilities,
            policy = workspace.guard.effectivePolicy().first,
            commandDetail = commandDetail,
        ) ?: ""
    }

    // ---- 网关配置（运行时填，不打包进 APK） ----

    var gatewayUrl by mutableStateOf(prefs.getString(KEY_URL, "") ?: "")
    var gatewayToken by mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "")
    var gatewayModel by mutableStateOf(prefs.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat")
        private set

    // ---- 思考强度（**强度编码 = 契约**） ----

    /**
     * 服务端给的档位清单；null = 还没拉。
     *
     * 清单**只能问服务端拿**（`GET {baseUrl}/efforts`）—— 档位是模型级的且会变
     * （`deepseek-flash` 就没有 medium），自己抄一份表迟早对不上。
     */
    var effortCatalog by mutableStateOf<EffortCatalog?>(null)
        private set

    /** 拉清单失败的原因（**明确报出来**，不编一份本地默认表糊弄用户）。 */
    var effortCatalogError by mutableStateOf<String?>(null)
        private set

    /**
     * 当前选中的**强度编码**（2 的幂）。null = 不带这个字段，用服务端默认档位。
     *
     * ★ **持久化的是 strength，不是 key / index** ——
     * `key` 换模型就变（`deepseek-flash` 没有 medium，同一个编号在不同模型上指不同档），
     * `index` 只是显示顺序。strength 跨模型稳定，用户存的值换模型后仍然有效。
     */
    var reasoningStrength by mutableStateOf(prefs.getInt(KEY_STRENGTH, 0).takeIf { it > 0 })
        private set

    /** 选一档（传 strength）。传 null = 用服务端默认。 */
    fun selectReasoningStrength(strength: Int?) {
        reasoningStrength = strength
        prefs.edit().putInt(KEY_STRENGTH, strength ?: 0).apply()
        log("EFFORT", "思考强度设为 ${strength ?: "(服务端默认)"}")
    }

    /**
     * 拉服务端档位清单。
     *
     * 拉不到就**如实报错**（[effortCatalogError]），不返回一份本地默认表 ——
     * 悄悄塞一份本地默认 = 用户以为选的是服务端支持的档，实际不是。
     */
    fun loadEffortCatalog() {
        if (gatewayUrl.isBlank()) {
            effortCatalogError = "先填网关地址"
            return
        }
        DemoRuntime.startOnce("efforts") {
            effortCatalogError = null
            try {
                val catalog = EffortCatalogClient(
                    baseUrl = gatewayUrl,
                    transport = OkHttpTransport(),
                    auth = LlmAuth.bearer(gatewayToken),
                ).fetch()
                effortCatalog = catalog
                log(
                    "EFFORT",
                    "清单: model=${catalog.model} levels=${catalog.levels.map { "${it.strength}:${it.key}" }} " +
                        "default=${catalog.defaultStrength}",
                )
                // 第一次拉到清单、用户还没选过时 → 落到服务端默认档。
                if (reasoningStrength == null && catalog.defaultStrength != null) {
                    selectReasoningStrength(catalog.defaultStrength)
                }
            } catch (e: Exception) {
                effortCatalogError = e.message ?: "未知错误"
                log("EFFORT", "拉清单失败：${e.message}")
            }
        }
    }

    // ---- 能力台 ----

    val capabilityResults = mutableStateListOf<CommandResult>()

    // ---- 自检 ----

    val checks = mutableStateListOf<CheckItem>()
    var selfCheckRunning by mutableStateOf(false)

    // ---- 对话 ----

    val chat = mutableStateListOf<ChatLine>()
    var chatBusy by mutableStateOf(false)

    /**
     * 跨轮历史（**线格式**的消息轨，不是 UI 文本）。
     *
     * 为什么不能从 [chat] 的文本重建：assistant 回合可能带 `tool_calls` 与思维链
     * （`reasoning_content`）—— 只留正文的话，下一轮模型看到的是「已经答完了」，
     * 而上游（DeepSeek 思考模式）要求带 tool_calls 的回合**原样回传**，
     * 少一项就是 400。所以这里直接留内核产出的**完整消息轨**。
     */
    private val kernelHistory = mutableListOf<ChatMessage>()

    /** tool_call id → 模型下发的命令原文（UI 的「执行命令」用）。 */
    private val toolCommands = mutableMapOf<String, String>()

    /**
     * 上下文体检器 —— **跨轮复用同一个实例**。
     *
     * ★ 前缀缓存断在**轮与轮之间**：上一轮的历史被改了就全废。
     *   检测器如果每轮新建，每次都以为「第一次请求」，
     *   于是永远报「前缀完好」——**该报的漂移一个都发现不了**。
     *   所以这个对象属于**会话**，不属于某一次请求。
     */
    private val contextInspector = ai.agentkernel.context.ContextInspector()

    /**
     * 会话标识（发给网关的 `session_id`）。
     *
     * 一个会话一个 id，**开新对话就换一个** ——
     * 服务端靠它记住「这个会话上一次请求长什么样」，从而独立复核前缀有没有被改；
     * 换了 id，服务端那边的基线就对不上（会当成新会话），报告也就失去对照意义。
     */
    private var sessionId: String = newSessionId()

    private fun newSessionId(): String = "demo-" + java.util.UUID.randomUUID().toString().take(8)

    /**
     * 本轮每个 assistant 回合的 `finish_reason`。
     *
     * 记它是因为**「length」= 被 max_tokens 截断 = 答案不完整**，
     * 而网关原先回传的是写死的 `stop` —— 半句答案会被当成完整交付。
     */
    private val turnFinishReasons = mutableListOf<String>()

    /** autorun 的调用序号 —— 用来判断「同一轮被跑了两次」这类问题。 */
    private var chatAutorunSeq = 0

    // ---- 手电筒（开关，不是「只能开」） ----

    /**
     * 手电筒当前状态。
     *
     * `CameraManager` **没有公开的 torch 开关状态查询 API**（只有 `FLASH_INFO_AVAILABLE` 这个能力位），
     * 所以状态只能由本 App 自己跟踪。
     *
     * ⇒ 能力台那张卡片必须是**开关**：点一下开、再点一下关。
     * 写死成 `termux-torch on` 的话，用户点了多少次都只会开，永远关不掉。
     */
    var torchOn by mutableStateOf(false)

    /** 点手电筒卡片：按当前状态决定开还是关。 */
    suspend fun toggleTorch(): CommandResult {
        val turningOn = !torchOn
        val r = runCommand("手电筒", if (turningOn) "termux-torch on" else "termux-torch off")
        if (r.verdict == Verdict.PASS) torchOn = turningOn
        return r
    }

    /**
     * 启动时兜底关一次。
     *
     * 万一上次 App 被杀时灯是亮的（没走到清理逻辑），这里把它关掉 ——
     * 不能把用户的手机留在亮着灯的状态。
     */
    suspend fun ensureTorchOff() {
        val r = runCommand("手电筒", "termux-torch off")
        if (r.verdict == Verdict.PASS) torchOn = false
    }

    /**
     * 手电筒**开关**自检 —— 走的是按钮点的那**同一个函数**（[toggleTorch]），不是另写一套。
     *
     * 为什么必须有这条独立路径：
     * 上一版「能开不能关」查出来是 Demo 卡片把命令**写死成 `termux-torch on`**，
     * 改成 toggle 之后，就必须有一条**不依赖人点屏幕**的验证办法 ——
     * 真机上撞过车：自动 tap 会跟正在用手机的人抢屏（通知栏抢焦点、别的 App 被切前台）。
     *
     * 三刀：基线关 → 开 → 关。**第二刀（关）就是当初坏掉的那一刀**，所以它才是判据。
     *
     * 每一步都写 logcat（tag `AgentSdkDemo`），脚本可直接抓：
     * ```
     * adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_torch true
     * adb logcat -d -s AgentSdkDemo
     * ```
     */
    suspend fun runTorchToggleCheck(): List<CheckItem> {
        val items = mutableListOf<CheckItem>()

        // 基线：先把状态归零，否则第一刀方向就是错的。
        val base = runCommand("手电筒 · 基线关", "termux-torch off")
        torchOn = false
        items += CheckItem(
            title = "手电筒 · 基线关",
            detail = "先归零，保证下面第一刀是「开」",
            command = "termux-torch off",
            verdict = base.verdict,
            output = base.output,
            elapsedMs = base.elapsedMs,
        )
        log("CHECK", "${base.verdict.emoji} 手电筒·基线关 | exit=${base.exitCode} | ${base.output}")

        // 第一刀：应该开。
        val on = toggleTorch()
        val onOk = on.verdict == Verdict.PASS && torchOn
        items += CheckItem(
            title = "手电筒 · 开",
            detail = "点一下 = 开（toggle 第一次）",
            command = on.command,
            verdict = if (onOk) Verdict.PASS else Verdict.FAIL,
            output = on.output,
            elapsedMs = on.elapsedMs,
        )
        log(
            "CHECK",
            "${if (onOk) "✅" else "❌"} 手电筒·开 | exit=${on.exitCode} | " +
                "state=${if (torchOn) "ON" else "OFF"} | ${on.output}"
        )

        // 第二刀：应该关 —— **当初坏的就是这一刀**。
        val off = toggleTorch()
        val offOk = off.verdict == Verdict.PASS && !torchOn
        items += CheckItem(
            title = "手电筒 · 关",
            detail = "再点一下 = 关（当初坏的就是这一步）",
            command = off.command,
            verdict = if (offOk) Verdict.PASS else Verdict.FAIL,
            output = off.output,
            elapsedMs = off.elapsedMs,
        )
        log(
            "CHECK",
            "${if (offOk) "✅" else "❌"} 手电筒·关 | exit=${off.exitCode} | " +
                "state=${if (torchOn) "ON" else "OFF"} | ${off.output}"
        )

        // 收尾：不管结果如何，灯必须灭。
        ensureTorchOff()

        return items
    }

    init {
        if (gatewayUrl.isBlank()) {
            // 给个默认地址，省得打字；token 留空由用户填自己的 key。
            gatewayUrl = "https://api.deepseek.com/v1"
        }
    }

    fun saveGateway() {
        prefs.edit()
            .putString(KEY_URL, gatewayUrl)
            .putString(KEY_TOKEN, gatewayToken)
            .putString(KEY_MODEL, gatewayModel)
            .apply()
    }

    /** 跑一条命令（能力命令或普通 shell 都行），走真正的 SDK 执行器。 */
    suspend fun runCommand(title: String, command: String): CommandResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val r = executor.execute(command)
            val elapsed = System.currentTimeMillis() - started
            CommandResult(
                title = title,
                command = command,
                exitCode = r.exitCode,
                stdout = r.stdout.trim(),
                stderr = r.stderr.trim(),
                elapsedMs = elapsed,
                verdict = judge(r.exitCode, r.stdout),
            )
        }

    /**
     * 一键自检 —— **自动监测**。
     *
     * 逐项跑，每项都记下：命令、退出码、输出、判定、耗时。
     *
     * 结果**同时写进 logcat**（tag `AgentSdkDemo`）：这样不用盯着屏幕也能拿到权威结果，
     * 自动化脚本（`am start --ez autorun_selfcheck true` + `logcat -d -s AgentSdkDemo`）可以直接用。
     */
    suspend fun runSelfCheck() {
        selfCheckRunning = true
        checks.clear()
        try {
            // 1) 能力逐个跑
            for (spec in CapabilityCatalog.all) {
                val r = runCommand(spec.title, spec.command)
                checks += CheckItem(
                    title = spec.title,
                    detail = spec.subtitle,
                    command = spec.command,
                    verdict = r.verdict,
                    output = r.output,
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${r.verdict.emoji} ${spec.title} | exit=${r.exitCode} | ${r.elapsedMs}ms | ${r.output}")
            }
            // 手电筒测完要关掉 —— 不能把用户的手机留在亮着灯的状态。
            runCommand("关手电筒", "termux-torch off")

            // 2) 普通 shell 命令（证明 shell 本身也是通的）
            for (spec in CapabilityCatalog.shellSamples) {
                val r = runCommand(spec.title, spec.command)
                // 普通命令没有 ok:true 的 JSON，按**退出码**判。
                // 注意：判定要用**重算过的**，不能用 r.verdict（那是按能力契约算的）。
                val verdict = if (r.exitCode == 0) Verdict.PASS else Verdict.FAIL
                checks += CheckItem(
                    title = "普通命令 · ${spec.title}",
                    detail = spec.subtitle,
                    command = spec.command,
                    verdict = verdict,
                    output = r.output,
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${verdict.emoji} 普通命令·${spec.title} | exit=${r.exitCode} | ${r.elapsedMs}ms | ${r.output}")
            }
            // 3) 文件基础操作（写 / 读 / 删 / 跑脚本）
            for (spec in CapabilityCatalog.fileOps) {
                val r = runCommand(spec.title, spec.command)
                // 普通命令按**退出码**判：写文件本来就没有输出，不能因为「没输出」判不合格。
                val verdict = if (r.exitCode == 0) Verdict.PASS else Verdict.FAIL
                checks += CheckItem(
                    title = "文件 · ${spec.title}",
                    detail = spec.subtitle,
                    command = spec.command,
                    verdict = verdict,
                    output = r.output.ifBlank { "（无输出，退出码 0）" },
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${verdict.emoji} 文件·${spec.title} | exit=${r.exitCode} | ${r.elapsedMs}ms | ${r.output.ifBlank { "(no output)"} }")
            }

            // 4) 我们内部拟 Linux 自制的命令（agent-*）
            //
            //    ⚠️ 自检**不能自己造垃圾** —— 「加一条定时任务」每跑一次就在设备上多留一条。
            //    所以：记下它返回的 id，循环结束后**自己删掉**，
            //    顺便把 `agent-schedule remove` 也验了（不然 remove 永远没被真机验过）。
            var addedScheduleId: String? = null
            for (spec in CapabilityCatalog.internalCommands) {
                val r = runCommand(spec.title, spec.command)
                // 网络类命令不通属于「受外部限制」，如实报告，不算不合格。
                val verdict = if (spec.key == "http-get" && r.verdict != Verdict.PASS) {
                    Verdict.WARN
                } else {
                    r.verdict
                }
                if (spec.key == "schedule-add") {
                    addedScheduleId = Regex("\"id\":\"([^\"]+)\"").find(r.output)?.groupValues?.get(1)
                }
                checks += CheckItem(
                    title = "内部命令 · ${spec.title}",
                    detail = spec.subtitle,
                    command = spec.command,
                    verdict = verdict,
                    output = r.output.ifBlank { "（无输出，退出码 ${r.exitCode}）" },
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${verdict.emoji} 内部命令·${spec.title} | exit=${r.exitCode} | ${r.output}")
            }
            // 自检不留痕：把刚才加的那条定时任务删掉。
            addedScheduleId?.let { id ->
                val rm = runCommand("定时任务 · 删掉自检加的那条", "agent-schedule remove $id")
                // 必须看 removed:true —— 退出码 0 但 removed:false 意味着**没删掉**，
                // 那正是本项目最讨厌的「谎报成功」。
                val removed = rm.output.contains("\"removed\":true")
                checks += CheckItem(
                    title = "内部命令 · 自检不留垃圾",
                    detail = "自检加的定时任务自己删掉 —— 反复跑自检不会在设备上越堆越多",
                    command = "agent-schedule remove $id",
                    verdict = if (removed) Verdict.PASS else Verdict.FAIL,
                    output = rm.output.ifBlank { "（无输出，退出码 ${rm.exitCode}）" },
                    elapsedMs = rm.elapsedMs,
                )
                log("CHECK", "${if (removed) "✅" else "❌"} 内部命令·自检不留垃圾 | id=$id | ${rm.output}")
            }

            // 5) 配额闭环：agent 自己把配额改小 → 工作区立刻变成「超限」→ 改回默认
            //    （这正是「超限注入告警让 agent 自己维护」那条链路的可观测部分）
            //
            //    ⚠️ 阈值**不能写死**成 1KB：工作区可能是干净的（占用几百字节），
            //    那时 1KB 根本不算超限，这条就会**时好时坏**。
            //    正确做法：先量当前占用，再把配额压到它的一半 —— 与占用无关，永远成立。
            val beforeSet = runCommand("配额 · 量当前占用", "agent-workspace")
            val usageBytes = Regex("\"usage_bytes\":(\\d+)")
                .find(beforeSet.output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val targetQuota = maxOf(1L, usageBytes / 2)
            val setSmall = runCommand("配额 · 改小", "agent-quota set $targetQuota")
            val afterSet = runCommand("配额 · 体检", "agent-workspace")
            val reset = runCommand("配额 · 改回默认", "agent-quota reset")
            val overQuota = afterSet.output.contains("\"over_quota\":true")
            checks += CheckItem(
                title = "配额 · 改小后应超限",
                detail = "agent 自己改配额 → 工作区判定超限 → 告警才会发出去",
                command = "agent-quota set $targetQuota → agent-workspace → agent-quota reset",
                verdict = if (overQuota && reset.verdict == Verdict.PASS) Verdict.PASS else Verdict.FAIL,
                output = afterSet.output.ifBlank { "（无输出）" },
                elapsedMs = setSmall.elapsedMs + afterSet.elapsedMs + reset.elapsedMs,
            )
            log(
                "CHECK",
                "${if (overQuota) "✅" else "❌"} 配额·改小后应超限 | usage=${usageBytes}B set=$targetQuota " +
                    "set=${setSmall.exitCode} reset=${reset.exitCode} | $overQuota",
            )

            // 6) 环境探针：私有目录能不能执行二进制 —— 决定「能不能预装 Python」
            for (spec in CapabilityCatalog.envProbes) {
                val r = runCommand(spec.title, spec.command)
                val canExec = r.exitCode == 0
                checks += CheckItem(
                    title = "环境 · ${spec.title}",
                    detail = if (canExec) {
                        "私有目录可以执行二进制 —— 预装 Python / Termux 运行时可行"
                    } else {
                        "私有目录不能执行二进制（noexec）—— 预装 Python 得换路子"
                    },
                    command = spec.command,
                    verdict = if (canExec) Verdict.PASS else Verdict.WARN,
                    output = r.output.ifBlank { "（无输出，退出码 ${r.exitCode}）" },
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${if (canExec) "✅" else "⚠️"} 环境·${spec.title} | exit=${r.exitCode} | ${r.output}")
            }

            // 7) 提示词与真机一致：提示词是模型唯一的「环境说明书」——
            //    它列的命令必须在这台设备上真的存在，而且真的能跑。
            //    （存在 ≠ 能跑，所以第二步还要实跑一遍。）
            //
            //    ⚠️ 清单为空**不算通过** —— 那是探测本身坏了，不是「一条命令都没有」。
            //    空清单曾经伪装成 ✅（0 缺失 / 0 抽样），是典型的「谎报成功」。
            val claimed = AndroidAgentSdk.systemShellInventory().allCommands()
            // 独立复核：逐个 stat。
            // ⚠️ 必须 stat 不能列目录：Android 的 /system/bin 是 drwxr-x--x，
            //    普通 App 没有读权限，File.list() 必然返回 null（真机踩过：清单探出 0 条）。
            //    （复核用的是同一套 stat 机制，但代码是独立写的；
            //     真正独立的证据是下面**实跑**那几条 —— 「存在」不等于「能跑」。）
            val reallyThere = setOf("/system/bin", "/system/xbin")
            val missing = claimed.filterNot { cmd ->
                reallyThere.any { dir -> File(dir, cmd).exists() }
            }
            val probes = listOf(
                "echo" to "echo probe",
                "date" to "date +%Y",
                "df" to "df -h .",
                "awk" to "awk 'BEGIN{print 1}'",
                "pwd" to "pwd",
            ).filter { it.first in claimed }
            val probeResults = probes.map { (name, cmd) -> name to runCommand("提示词探样 · $name", cmd) }
            val probeFailed = probeResults.filter { it.second.exitCode != 0 }
            val promptOk = claimed.isNotEmpty() && missing.isEmpty() && probeFailed.isEmpty()
            checks += CheckItem(
                title = "提示词 · 命令清单与真机一致",
                detail = if (claimed.isEmpty()) {
                    "探测没探到任何命令 —— 探测本身坏了（不是「设备上没命令」）"
                } else {
                    "提示词列了 ${claimed.size} 个命令；独立复核缺失 ${missing.size} 个；" +
                        "抽样实跑 ${probeResults.size - probeFailed.size}/${probeResults.size} 通过"
                },
                command = probeResults.joinToString("   ") { "${it.first} → ${it.second.command}" },
                verdict = if (promptOk) Verdict.PASS else Verdict.FAIL,
                output = buildString {
                    appendLine("清单（${claimed.size}）：${claimed.joinToString(" ")}")
                    if (missing.isNotEmpty()) appendLine("缺失：${missing.joinToString(" ")}")
                    probeResults.forEach { (name, r) ->
                        val firstLine = r.output.trim().lineSequence().firstOrNull().orEmpty()
                        appendLine("$name → exit=${r.exitCode} $firstLine")
                    }
                    // 提示词有多长 —— 有的服务会把 system 段**截断到 2000 字符**，
                    // 超了就砍尾巴（工作区规则那几段正好在最后）。这个数字得看得见。
                    val promptLen = systemPrompt.length
                    val fullLen = AndroidAgentSdk.buildSystemPrompt(
                        executor = executor,
                        capabilities = capabilities,
                        policy = workspace.guard.effectivePolicy().first,
                        commandDetail = ai.agentkernel.prompt.AgentSystemPrompt.CommandDetail.FULL,
                    )?.length ?: -1
                    appendLine("提示词长度：核心模式 $promptLen 字符 / 完整模式 $fullLen 字符" +
                        "（服务 system 段上限 2000 → " +
                        if (promptLen > 2000) "⚠️ 核心模式也会被截断！" else "核心模式装得下）")
                }.trim(),
                elapsedMs = probeResults.sumOf { it.second.elapsedMs },
            )
            log(
                "CHECK",
                "${if (promptOk) "✅" else "❌"} 提示词·命令清单与真机一致 | claimed=${claimed.size} " +
                    "missing=${missing.size} probe=${probeResults.size - probeFailed.size}/${probeResults.size} " +
                    // 提示词有多长 —— 有的服务把客户端 system 段**截断到 2000 字符**，
                    // 超了砍尾巴，而砍掉的正好是最后面的工作区规则。这个数字必须看得见。
                    "| prompt=${systemPrompt.length} chars (service system cap=2000 → " +
                    if (systemPrompt.length > 2000) "会被截断!)" else "装得下)",
            )

            // 8) 白名单只是「提示词里写什么」，**不是**「执行时限制什么」。
            //
            //    设备上 /system/bin 有 434 个可执行文件，提示词里只介绍了 82 个 ——
            //    剩下 350 多个**照样能跑**。能力路由只接管「注册过的能力命令」，
            //    其余原样丢给 /system/bin/sh。
            //
            //    判据：**退出码不是 127**（127 = command not found = 路由/白名单拒了）。
            //    权限不够会是 1 或别的码 + 明确报错 —— 那是 Android 说的，不是我们说的。
            val outsideProbes = listOf(
                // toybox 不在提示词白名单里（白名单只有 sh，没有 toybox），但设备上一定有
                "toybox" to "toybox",
                // dumpsys 也不在白名单里；能不能用是 Android 的权限说了算
                "dumpsys battery" to "dumpsys battery",
            )
            val outsideResults = outsideProbes.map { (title, cmd) ->
                title to runCommand("白名单外 · $title", cmd)
            }
            val notFound = outsideResults.filter { it.second.exitCode == 127 }
            val toyboxOk = outsideResults.first { it.first == "toybox" }.second.exitCode == 0
            checks += CheckItem(
                title = "白名单外 · 设备命令照样能跑",
                detail = "提示词只介绍了 82 个命令，设备上有 434 个；" +
                    "**白名单只管「告诉模型什么」，不管「拦不让你跑」**。" +
                    "（退出码 127 才是被拒；权限不够是别的码 + 明确报错。）",
                command = outsideProbes.joinToString("   ") { it.second },
                verdict = if (toyboxOk && notFound.isEmpty()) Verdict.PASS else Verdict.FAIL,
                output = buildString {
                    outsideResults.forEach { (title, r) ->
                        // 只取首行前 160 字 —— toybox 的 applet 清单有几千字，全打出来没法看
                        val firstLine = r.output.trim().lineSequence().firstOrNull().orEmpty().take(160)
                        appendLine("$title → exit=${r.exitCode} $firstLine")
                    }
                    if (notFound.isNotEmpty()) {
                        appendLine("被拒（127 command not found）：${notFound.joinToString(" ") { it.first }}")
                    }
                }.trim(),
                elapsedMs = outsideResults.sumOf { it.second.elapsedMs },
            )
            log(
                "CHECK",
                "${if (toyboxOk && notFound.isEmpty()) "✅" else "❌"} 白名单外·设备命令照样能跑 | " +
                    outsideResults.joinToString(" | ") {
                        val first = it.second.output.trim().lineSequence().firstOrNull().orEmpty().take(120)
                        "${it.first}=exit${it.second.exitCode} $first"
                    },
            )

            // 9) 权限现状
            for (perm in listOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.VIBRATE,
            )) {
                val granted = context.checkSelfPermission(perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                checks += CheckItem(
                    title = "权限 · ${perm.substringAfterLast('.')}",
                    detail = if (granted) "已授权" else "未授权（对应能力会明确失败，不会静默）",
                    command = "pm check",
                    verdict = if (granted) Verdict.PASS else Verdict.WARN,
                    output = if (granted) "granted" else "not granted",
                    elapsedMs = 0,
                )
                log("CHECK", "${if (granted) "✅" else "⚠️"} 权限·${perm.substringAfterLast('.')} | ${if (granted) "granted" else "not granted"}")
            }
            val pass = checks.count { it.verdict == Verdict.PASS }
            val warn = checks.count { it.verdict == Verdict.WARN }
            val fail = checks.count { it.verdict == Verdict.FAIL }
            log("SUMMARY", "合计 ${checks.size} 项：✅ $pass · ⚠️ $warn · ❌ $fail")
        } finally {
            selfCheckRunning = false
        }
    }

    /** 让模型驱动设备能力 —— 走 SDK 内核的完整闭环。 */
    suspend fun sendToModel(userText: String) {
        if (gatewayUrl.isBlank()) {
            chat += ChatLine.System("先在「对话」页填好网关地址和 token")
            return
        }
        chatBusy = true
        try {
            val client = ChatCompletionsClient(
                baseUrl = gatewayUrl,
                transport = OkHttpTransport(),
                // ★ 用 token 鉴权；线格式仍然是 OpenAI 那套，一个字没改。
                //   （DeepSeek 官方就是 Bearer + 你的 key；换成自建服务换成它自己的头名。）
                auth = LlmAuth.bearer(gatewayToken),
            )
            val sdk = AndroidAgentSdk(
                llm = client,
                model = gatewayModel,
                executor = executor,
                // ★ 系统提示词由 SDK 生成（环境 / 命令清单 / 能力边界 / 工作区规则），
                //   这里不用手写 —— 手写的版本会跟实际注册的能力对不上。
                // ★ 命令清单用 CORE：有些服务会把客户端 system 段硬截断到 2000 字符，
                //   完整清单(2209)装不下、尾部会被砍掉；直连 DeepSeek 官方没有这个限制，
                //   那边的默认值就是 FULL。
                // ★ 用 DemoState.commandDetail —— 和自检那份**同一个值**（否则自检在验另一份）。
                commandDetail = commandDetail,
                // ★ 思考强度：传**强度编码**（2 的幂，跨模型稳定）。
                //   null = 不带这个字段，由服务端用它的默认档位。
                //   它是「上限」：服务端取「强度 ≤ 给的值」里该模型能达到的最高档（只向下）。
                reasoningStrength = reasoningStrength,
                // ★ 工作区守卫：超配额时自动把告警注入这一轮，让 agent 自己清理。
                workspaceGuard = workspace.guard,
                // ★ 体检器跨轮复用（检测轮与轮之间的前缀漂移）。
                inspector = contextInspector,
                // ★ 会话标识：让服务端也能独立复核前缀（头部是服务端拼的，本地看不见）。
                sessionId = sessionId,
                onEvent = { event ->
                    when (event) {
                        // 模型下发了哪些命令 —— 记下来，工具结果回来时好对上。
                        is KernelEvent.Assistant -> {
                            val msg = event.message
                            // 结束原因必须打出来：'length' = 被 max_tokens 截断 → 答案不完整。
                            // 网关原先把它写死成 'stop'，于是半句答案被当完整交付（谎报成功）。
                            log(
                                "CHAT",
                                "回合: finish_reason=${msg.finishReason ?: "(null)"} | " +
                                    "tool_calls=${msg.toolCalls.size} | 正文长度=${msg.content?.length ?: 0}",
                            )
                            msg.finishReason?.let { turnFinishReasons += it }
                            // 服务端视角的前缀报告：网关那一侧的头部（平台提示词/技能/工具定义）
                            // 本地一条都看不见，只有它能说清「缓存为什么没全中」。
                            msg.prefix?.let { p ->
                                log(
                                    "PFX",
                                    "会话=${p.session} 有基线=${p.tracked} 前缀完好=${p.preserved} " +
                                        "断点=${p.firstChangedIndex} 公共前缀=${p.commonPrefixMessages}条/" +
                                        "${p.commonPrefixChars}字符 新增=${p.addedMessages}条 " +
                                        "头=${p.headChars}字符 工具=${p.toolsChars}字符 " +
                                        "头部变化=${if (p.headChanged.isEmpty()) "无" else p.headChanged.joinToString("/")}",
                                )
                            }
                            msg.toolCalls.forEach { call ->
                                toolCommands[call.id] = call.arguments
                            }
                        }
                        is KernelEvent.ToolResult -> {
                            val cmd = toolCommands.remove(event.toolCallId) ?: "(命令)"
                            chat += ChatLine.ToolCall(cmd, event.output, exitCodeOf(event.output))
                        }
                        // 上下文体检：前缀有没有被动过。被动过 = 缓存从那条起全废。
                        is KernelEvent.Context -> {
                            val r = event.report
                            log(
                                "CTX",
                                "请求#${r.requestIndex} 消息=${r.messageCount} 公共前缀=${r.commonPrefixMessages} " +
                                    "新增=${r.addedMessages} 变了=${r.changedMessages} " +
                                    "断点=${r.firstChangedIndex ?: "-"} " +
                                    (if (r.prefixPreserved) "前缀✅" else "前缀❌被改") +
                                    (if (r.overBudget) " 超预算⚠️" else ""),
                            )
                        }
                        else -> Unit
                    }
                },
            )
            // system 段预算体检：超了网关会**悄悄截断**，排在最后的工作区告警就丢了 ——
            // 模型看不到那条告警，却没人知道。这种静默故障必须显式报出来。
            sdk.systemBudget().let { b ->
                if (b.over) {
                    log(
                        "CTX",
                        "⚠️ system 段超限 prompt=${b.promptChars} alert=${b.alertChars} " +
                            "合计=${b.totalChars} > ${b.limitChars}（网关会截断，告警可能到不了模型）",
                    )
                    chat += ChatLine.System(
                        "system 段超限（${b.totalChars}/${b.limitChars} 字符）—— 网关会截断，工作区告警可能到不了模型。",
                    )
                } else {
                    log(
                        "CTX",
                        "system 段 ${b.totalChars}/${b.limitChars} 字符（prompt=${b.promptChars} alert=${b.alertChars}）",
                    )
                }
            }
            val history = buildList<ChatMessage> {
                addAll(kernelHistory)
                add(UserMessage(userText))
            }
            val result = sdk.runTurn(history)
            // 历史留**完整**消息轨（含 tool_calls 与思维链），下一轮才接得上。
            //
            // ★ SDK 的 runTurn 返回的 messages **不含**系统提示词与工作区告警
            //   （那是每次请求重新注入的脚手架，不是对话）。存进去的话每轮会多一份
            //   1800 字符的提示词，前缀从此对不齐 —— 缓存再也命中不了。
            kernelHistory.clear()
            kernelHistory.addAll(result.messages)
            val text = result.finalText ?: "(没有正文)"
            chat += ChatLine.Assistant(text)
            reportCacheAndContext(result)
        } catch (e: Exception) {
            chat += ChatLine.System("调用失败：${e.message}")
            // 堆栈一起打出来：只打 message 的话，「coroutine scope left the composition」
            // 这类**取消类**异常看不出是在哪一步被掐断的。
            log("CHAT", "调用失败堆栈: ${android.util.Log.getStackTraceString(e)}")
        } finally {
            chatBusy = false
        }
    }

    /** 把工具执行过程也记进对话（让「模型驱动设备」看得见）。 */
    fun noteToolCall(command: String, output: String, exitCode: Int) {
        chat += ChatLine.ToolCall(command, output, exitCode)
    }

    /**
     * 把这一轮的**缓存命中**与**前缀状态**摆出来。
     *
     * 这是「上下文检测」在 App 侧的样子，两条读数来源不同，别混：
     * - **命中率**来自上游 `usage`（服务端给的**权威数字**，不是本地估的）；
     * - **前缀状态**来自 SDK 的体检（**哪一条**被改了）。
     *
     * 上游没透传 usage 时如实说「看不到」—— 不拿 0% 糊弄（那是谎报）。
     */
    private fun reportCacheAndContext(result: TurnResult) {
        val usage = result.usage
        val ctx = result.context
        val pfx = result.prefix
        val text = buildString {
            append("缓存 ")
            if (usage == null) {
                append("· 上游没报用量（看不到命中率 —— 服务端没透传 usage）")
            } else {
                append("命中 ${usage.cacheHitTokens}/${usage.promptTokens} token")
                append("（${(usage.cacheHitRatio * 100).toInt()}%）")
                append(" · 未命中 ${usage.cacheMissTokens} · 输出 ${usage.completionTokens}")
            }
            if (ctx != null) {
                append("\n前缀 ")
                append(
                    if (ctx.prefixPreserved) {
                        "✅ 未被动过"
                    } else {
                        "❌ 第 ${ctx.firstChangedIndex} 条被改了（缓存从那儿起全废）"
                    }
                )
                append(" · 消息 ${ctx.messageCount} 条 · 公共前缀 ${ctx.commonPrefixMessages} 条")
            }
            if (pfx != null) {
                append("\n服务端前缀 ")
                append(
                    when {
                        !pfx.tracked -> "首轮（还没有基线可比）"
                        !pfx.preserved -> "❌ 第 ${pfx.firstChangedIndex} 条断了"
                        pfx.headChanged.isNotEmpty() -> "⚠️ 头部变了：${pfx.headChanged.joinToString("/")}"
                        else -> "✅ 完好"
                    }
                )
                append(" · 公共前缀 ${pfx.commonPrefixMessages} 条 · 头 ${pfx.headChars} 字符 · 工具 ${pfx.toolsChars} 字符")
            }
        }
        chat += ChatLine.Metrics(text)
        log(
            "CACHE",
            "hit=${usage?.cacheHitTokens ?: -1} prompt=${usage?.promptTokens ?: -1} " +
                "ratio=${usage?.let { (it.cacheHitRatio * 100).toInt() } ?: -1}% " +
                "miss=${usage?.cacheMissTokens ?: -1} out=${usage?.completionTokens ?: -1} " +
                "prefix_ok=${ctx?.prefixPreserved} changed_at=${ctx?.firstChangedIndex ?: "-"} " +
                "msgs=${ctx?.messageCount ?: -1} " +
                "srv_tracked=${pfx?.tracked} srv_ok=${pfx?.preserved} " +
                "srv_break=${pfx?.firstChangedIndex ?: "-"} srv_common=${pfx?.commonPrefixMessages ?: -1} " +
                "srv_head=${pfx?.headChars ?: -1} srv_headchanged=${pfx?.headChanged?.joinToString("/") ?: "-"}",
        )
    }

    /**
     * 命令行触发的**端到端对话**（自动化 / 回归用）。
     *
     * 走的是**和「对话」页发送按钮完全同一个函数** [sendToModel] ——
     * 所以它验的是真闭环：模型下发命令 → 这台手机真的执行 → 结果回喂 → 模型据结果作答。
     *
     * 为什么不用 `input tap` 去点发送按钮：真机上撞过车（抢屏、滚动后坐标错位）。
     * 给 App 加一条命令行入口、让它调**同一个函数**，结果写 logcat，脚本直接抓。
     *
     * ```
     * adb shell am start -n com.agentkernel.demo/.MainActivity \
     *     --es autorun_chat "用 bash 跑 pwd" \
     *     --es gateway_url "http://127.0.0.1:8080/v1" \
     *     --es gateway_token "<token>"
     * adb logcat -d -s AgentSdkDemo
     * ```
     */
    /**
     * 跑一次命令行触发的对话。
     *
     * ★ **必须用进程级 scope**（[DemoRuntime.scope]），不能用 composition scope
     * 也不能用 `viewModelScope` —— activity 被 finish 时两者都会被取消，
     * **正在跑的 agent 循环就中途夭折了**，而且是静默失败（详见 [DemoRuntime]）。
     */
    fun startChatAutorun(text: String) {
        val started = DemoRuntime.startOncePerProcess("chat:$text") { runChatAutorun(text) }
        if (!started) {
            log("CHAT", "[autorun] 同样内容的一轮还在跑，忽略这次重复触发（activity 重建导致）")
        }
    }

    /** 跑一次命令行触发的自检（scope 与幂等语义同 [startChatAutorun]）。 */
    fun startSelfCheck() {
        DemoRuntime.startOncePerProcess("selfcheck") { runSelfCheck() }
    }

    /**
     * 命令行触发的**多轮**对话（自动化用）。
     *
     * ## 为什么必须有多轮
     *
     * 缓存命中率**只有在多轮里才看得出来**：
     * 第 1 轮必然大量未命中（缓存还没建起来），第 2 轮起才逐步命中。
     * 只跑一轮，看到的永远是「没命中」——说明不了前缀稳不稳。
     *
     * 走的是**和对话页发送按钮完全同一个函数** [sendToModel]，
     * 所以验的是真闭环（真网关 + 真模型 + 真历史累积）。
     *
     * ```
     * adb shell am start -n com.agentkernel.demo/.MainActivity \
     *     --es autorun_chat_multi "问题一|问题二|问题三" \
     *     --es gateway_url "http://127.0.0.1:43080/api-app/ai" \
     *     --es gateway_token "<token>"
     * ```
     */
    fun startChatMultiAutorun(prompts: List<String>) {
        val key = prompts.joinToString("|")
        val started = DemoRuntime.startOncePerProcess("chatmulti:$key") { runChatMultiAutorun(prompts) }
        if (!started) {
            log("CHAT", "[autorun] 多轮对话还在跑，忽略这次重复触发")
        }
    }

    /** 跑一轮多轮对话（scope 与幂等语义同 [startChatAutorun]）。 */
    suspend fun runChatMultiAutorun(prompts: List<String>) {
        chat.clear()
        // 历史也要清：多轮对话是**一段完整会话**，接着上一段跑会把两段混在一起
        // （而且请求量翻倍，容易顶到限频）。
        kernelHistory.clear()
        turnFinishReasons.clear()
        // 新的一段对话 → 换会话 id（否则服务端的基线会串到上一段去，报告失真）。
        sessionId = newSessionId()
        log("CACHE", "=== 多轮对话开始：${prompts.size} 轮 · 会话 ${sessionId} ===")
        prompts.forEachIndexed { index, prompt ->
            chat += ChatLine.User(prompt)
            sendToModel(prompt)
            log("CACHE", "=== 第 ${index + 1}/${prompts.size} 轮结束 ===")
        }
        log("CHAT", "finishReasons=$turnFinishReasons")
    }

    /** 跑一次命令行触发的手电筒自检（scope 与幂等语义同 [startChatAutorun]）。 */
    fun startTorchCheck() {
        DemoRuntime.startOncePerProcess("torchcheck") {
            checks.clear()
            checks += runTorchToggleCheck()
        }
    }

    suspend fun runChatAutorun(text: String) {
        val seq = ++chatAutorunSeq
        chat.clear()
        turnFinishReasons.clear()
        chat += ChatLine.User(text)
        log("CHAT", ">>> [#$seq state=@${System.identityHashCode(this)}] $text")
        sendToModel(text)
        chat.forEach { line ->
            when (line) {
                is ChatLine.User -> log("CHAT", "你: ${line.text}")
                is ChatLine.Assistant -> log("CHAT", "模型: ${line.text}")
                is ChatLine.ToolCall -> log(
                    "CHAT",
                    "执行: ${line.command} | exit=${line.exitCode} | " +
                        line.output.replace('\n', ' ').take(400),
                )
                is ChatLine.System -> log("CHAT", "提示: ${line.text}")
                is ChatLine.Metrics -> log("CACHE", line.text.replace('\n', ' '))
            }
        }
        // 完整性判据：只要有一回合是 'length'（被 max_tokens 截断），这一轮就**不算完整**。
        // 把它显式打进日志，脚本/CI 直接读，不用人眼看答案长短。
        val truncated = turnFinishReasons.any { it == "length" }
        log(
            "CHAT",
            "finishReasons=$turnFinishReasons" + if (truncated) "   <-- ❌ 有截断，答案不完整" else "   <-- ✅ 答案完整",
        )
    }

    /**
     * 运行时注入网关配置（命令行 / 自动化用）。
     *
     * 仍然是**运行时**的：token 不进 APK、不进代码、不进日志。
     *
     * @param strength 思考强度的**强度编码**（2 的幂）。null = 不带，用服务端默认。
     */
    fun applyGatewayConfig(url: String?, token: String?, model: String?, strength: Int? = null) {
        if (!url.isNullOrBlank()) gatewayUrl = url
        if (!token.isNullOrBlank()) gatewayToken = token
        if (!model.isNullOrBlank()) gatewayModel = model
        if (strength != null) selectReasoningStrength(strength)
        saveGateway()
    }

    /** 从内核回填的文本里取退出码（render() 第一行就是 `exitCode: N`）；取不到就是 -1（未知，不瞎填）。 */
    private fun exitCodeOf(rendered: String): Int =
        Regex("exitCode: (-?\\d+)").find(rendered)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_MODEL = "model"

        /** 思考强度的**强度编码**（不是 key / index —— 那两个换模型就失效）。 */
        const val KEY_STRENGTH = "reasoning_strength"
    }
}

/** 自检结果写 logcat（tag 固定，方便脚本抓）。 */
private fun log(tag: String, message: String) {
    android.util.Log.i("AgentSdkDemo", "[$tag] $message")
}

/**
 * **进程级**运行时：agent 循环的 scope + 幂等守卫。
 *
 * ★ **为什么不能只用 composition scope / ViewModel scope**：
 *
 * | scope | activity 被重建时 | activity 被 finish 时 |
 * |---|---|---|
 * | `LaunchedEffect` / `rememberCoroutineScope`（composition scope） | 协程被取消 | 协程被取消 |
 * | `viewModelScope`（ViewModel） | **能活下来**（配置变化时 ViewModel 保留） | **被取消**（ViewModel 被 clear） |
 * | **进程级 scope（本对象）** | **能活下来** | **能活下来** |
 *
 * 真机实测踩到过：agent 循环跑在 composition scope 上时，
 * activity 一被重建（`am start --activity-clear-task` 走的是 **finish** 不是配置变化），
 * 循环就**中途夭折** —— 而且是**静默失败**：只抛一句
 * `The coroutine scope left the composition`（或 `Job was cancelled`），
 * 看起来像「模型没说话」，实际是被人掐断了。
 *
 * ⇒ **宿主跑 agent 循环要用进程级 scope**（Application scope / WorkManager），
 * 这是接入指南里写死的一条硬约束。
 *
 * ★ **幂等守卫**：activity 被 finish 后重建时，系统会用**同一个 intent** 重建它，
 * 于是同一条 autorun 会被触发两次 → 两轮 agent 循环并发跑、互相干扰。
 * [startOnce] 按 key 挡住「上一轮还在跑时的重复触发」，跑完自动放行下一次。
 */
internal object DemoRuntime {
    /** 进程级 scope —— 活过 activity 销毁。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在跑的 key —— 同一 key 重复触发只跑一次。 */
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 已经跑完的 key —— 「一次进程只跑一次」的入口用（见 [startOncePerProcess]）。 */
    private val done = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 起一个「同 key 只跑一次」的后台任务。
     *
     * @return true = 这次真的起了；false = 已有同 key 的任务在跑，本次被忽略。
     */
    fun startOnce(key: String, block: suspend () -> Unit): Boolean {
        if (!running.add(key)) return false
        scope.launch {
            try {
                block()
            } finally {
                running.remove(key)
            }
        }
        return true
    }

    /**
     * 起一个「**同 key 一次进程只跑一次**」的后台任务。
     *
     * ## 为什么 [startOnce] 不够
     *
     * [startOnce] 只挡「**正在跑**时的重复触发」，跑完就放行。
     * 但 activity 被重建时，系统会用**同一个 intent** 重建它 →
     * 同一条 autorun 会被**再触发一次**（上一轮已经跑完了，守卫已经放行）。
     *
     * 真机实测踩到过：8 轮多轮对话跑完后，activity 重建又跑了一遍 8 轮 ——
     * 结果是**两段对话串在同一条历史里**（第二轮带着第一轮的 16 条消息继续），
     * 而且把请求量直接顶到限频（429）。
     *
     * 所以命令行触发的入口要用这个：**跑过就记住，不再跑第二次**。
     *
     * @return true = 这次真的起了；false = 同 key 已经跑过或在跑。
     */
    fun startOncePerProcess(key: String, block: suspend () -> Unit): Boolean {
        if (!done.add(key)) return false
        if (!running.add(key)) {
            done.remove(key)
            return false
        }
        scope.launch {
            try {
                block()
            } finally {
                running.remove(key)
            }
        }
        return true
    }
}

/** 私有目录（拟 Linux 根）的绝对路径，展示用。 */
fun Context.prefixPath(): String = File(filesDir, "usr").path
