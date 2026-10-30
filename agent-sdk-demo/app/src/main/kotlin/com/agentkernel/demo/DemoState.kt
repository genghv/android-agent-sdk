package com.agentkernel.demo

import ai.agentkernel.android.AndroidAgentSdk
import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.android.OkHttpTransport
import ai.agentkernel.android.device.AgentHttpCapabilities
import ai.agentkernel.android.device.AgentWorkspaceCapabilities
import ai.agentkernel.android.device.defaultDeviceCapabilities
import ai.agentkernel.archive.ArchiveSegmentMeta
import ai.agentkernel.archive.ArchiveStore
import ai.agentkernel.credential.CredentialCipherLoader
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.kernel.KernelEvent
import ai.agentkernel.kernel.TurnResult
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.EffortCatalog
import ai.agentkernel.llm.openai.EffortCatalogClient
import ai.agentkernel.llm.openai.LlmAuth
import ai.agentkernel.llm.openai.ReasoningStrength
import ai.agentkernel.memory.ContextMemory
import ai.agentkernel.memory.ContextPolicy
import ai.agentkernel.memory.MemoryEntry
import ai.agentkernel.memory.MemoryKind
import ai.agentkernel.memory.MemoryStore
import ai.agentkernel.prompt.AgentSystemPrompt
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
// ★ 归档页用 viewModelScope 起协程（这是扩展属性，必须单独 import）。
//   少了它 Demo 直接编译不过 —— 而它挂在共享工作区上，会连累别人的构建。
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
     * 让它自己去 `agent-cleanup` —— 而不是替它删东西。
     */
    val workspace = AgentWorkspaceCapabilities(context)

    /**
     * HTTP 能力：调宿主数据 API。
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

    /**
     * 长期记忆能力（`agent-memory`）：agent 自己增删改查自己的记忆。
     *
     * 路径由 SDK 算（[AndroidAgentSdk.memoryCapabilities]）—— 别自己拼，
     * 拼错了会「agent 写进 A 目录、SDK 从 B 目录读」，表现成「写了没反应」。
     */
    val memoryCaps = AndroidAgentSdk.memoryCapabilities(context)

    /**
     * 文本文件能力（`agent-text`）：按**行**读 / 改沙箱里的文本文件。
     *
     * 路径由 SDK 算（[AndroidAgentSdk.textCapabilities]）—— 别自己拼，
     * 拼错了会「命令写进 A 目录、能力从 B 目录读」，表现成「改了没反应」。
     */
    val textCaps = AndroidAgentSdk.textCapabilities(context)

    /**
     * 凭据能力（`agent-cred`）：**只读、永远不吐明文**。
     *
     * 明文读写只走**宿主侧 API**（`setSecret` / `secretFor` / `deleteSecret`）——
     * 命令的参数会进模型上下文 ⇒ 写入**绝不做成命令**。
     */
    val credCaps = AndroidAgentSdk.credentialCapabilities(context)

    /**
     * ★ 闭源加密核心的**实现类名**（逐字，一个字都不能改）。
     *
     * 闭源产物里的实现类全名 = `com.agentsdk.cipher.KeystoreCredentialCipher`。
     * 用**类名**注册（而不是直接 new）⇒ demo **不需要编译期依赖**那个闭源产物。
     *
     * ⚠️ **没放那个 `.aar` 时**：注册照样成功，但**解析**会失败 ⇒
     * 读写凭据**安全失败**（抛异常），**绝不**退回明文。
     * ⇒ 那是「**连不上**」，与「**解不开**」是两种失败 —— 别混为一谈。
     */
    private val cipherClassName = "com.agentsdk.cipher.KeystoreCredentialCipher"

    /** 能力注册表：与《接入指南》里宿主该用的完全一样。 */
    val capabilities: DeviceCapabilityRegistry = DeviceCapabilityRegistry(
        defaultDeviceCapabilities(
            context,
            workspace = workspace,
            http = http,
            memory = memoryCaps,
            text = textCaps,
            credential = credCaps,
        )
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
     *
     * Demo 取 **CORE**：它是「在**上限被调小**的服务上也装得下」的**安全值** ——
     * 提示词本体守住 SDK 自己那条 2000 字符保险线（[PROMPT_BODY_INSURANCE_LINE]，
     * 由 `DevicePromptBudgetTest` 硬断言），余量不多，往里加东西前先量。
     * 真机实测（小米 Android 16）：CORE = **1933 字符**，余量 ≈ 67；
     * 完整模式 = 2403 字符（直连 DeepSeek 官方没有 system 段上限时用它）。
     */
    private val commandDetail = AgentSystemPrompt.CommandDetail.CORE

    // ---- 上下文压缩 + 长期记忆 ----

    /**
     * 压缩与记忆的**旋钮**。命令行可注入（回归用），默认是 SDK 的推荐值。
     *
     * - `maxContextChars`：一次请求的字符预算，超了自动压一次；
     * - `idleArchiveMillis`：闲置多久算「这个话题结束了」，到点归档成长期记忆。
     */
    var memoryPolicy by mutableStateOf(ContextPolicy())
        private set

    /** 命令行注入压缩/归档参数（回归用；null = 用默认值）。 */
    fun applyMemoryPolicy(maxChars: Int?, idleMinutes: Int?) {
        memoryPolicy = ContextPolicy(
            maxContextChars = maxChars?.takeIf { it > 0 } ?: ContextPolicy().maxContextChars,
            idleArchiveMillis = (idleMinutes?.takeIf { it > 0 } ?: 30) * 60_000L,
        )
    }

    /**
     * 客户端与记忆执行器 —— **跨轮复用**（配置没变就不重建）。
     *
     * ★ 为什么必须复用：记忆执行器身上挂着「本会话的压缩摘要」和「上次活动时刻」，
     * 每轮新建 = 每轮都当第一次见，历史被压过的事会**再压一遍**、
     * 闲置计时也会被清零。
     */
    private var cachedClient: ChatCompletionsClient? = null
    private var cachedMemory: ContextMemory? = null
    private var cachedKey: String? = null

    /** 取（必要时重建）客户端 + 记忆执行器。 */
    private fun clientAndMemory(): Pair<ChatCompletionsClient, ContextMemory> {
        val key = "$gatewayUrl|$gatewayToken|$gatewayModel|${memoryPolicy.maxContextChars}|${memoryPolicy.idleArchiveMillis}"
        val c = cachedClient
        val m = cachedMemory
        if (c != null && m != null && cachedKey == key) return c to m

        val client = ChatCompletionsClient(
            baseUrl = gatewayUrl,
            transport = OkHttpTransport(),
            // ★ 用 token 鉴权；线格式仍然是 OpenAI 那套，一个字没改。
            //   （DeepSeek 官方就是 Bearer + 你的 key；换成自建服务换成它自己的头名。）
            auth = LlmAuth.bearer(gatewayToken),
        )
        val memory = ContextMemory(
            llm = client,
            model = gatewayModel,
            // 记忆落在 agent 私有根下 —— 普通文件，agent 用终端就能读，
            // 也能用 `agent-memory` 命令增删改查（那条命令用的就是这个目录）。
            store = MemoryStore(File(executor.layout.varDir, AndroidAgentSdk.MEMORY_DIR)),
            policy = memoryPolicy,
            // ★ 摘要掉的**原文**落在这里 —— 界面照样能翻出那些对话。
            archive = archiveStore(),
        )
        cachedClient = client
        cachedMemory = memory
        cachedKey = key
        return client to memory
    }

    // ---- 凭据存储模式（**明文出口**；只在可调试构建里暴露） ----

    /**
     * ★ **明文模式**能不能用（v1.1.26 SDK 的 `registerPlaintext()`）。
     *
     * ## 判定口径
     * 看**可调试标记**（`FLAG_DEBUGGABLE`）⇒ `assembleDebug` 开着、`assembleRelease` 没有 ——
     * 正式构建里**连入口都不给**（不是「给了但藏起来」）。
     *
     * ## 为什么正式构建不给
     * 明文模式 = **拿到凭据文件就能还原 key**（且不绑 AAD、不验完整性）⇒ 只适合开发调试。
     */
    val plaintextModeAvailable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 明文模式开关（**重启后生效** —— 注册是进程级的）。 */
    var plaintextMode by mutableStateOf(prefs.getBoolean(KEY_PLAINTEXT_MODE, false))
        private set

    /**
     * 打开/关掉明文模式（**要重启应用才生效**）。
     *
     * ⚠️ 函数名**不叫** `setPlaintextMode` —— 属性 `plaintextMode` 的 setter 会生成
     * `setPlaintextMode(Z)V`，同名函数会**撞 JVM 签名**（编译期直接报 Platform declaration clash）。
     */
    fun applyPlaintextMode(enabled: Boolean) {
        if (enabled && !plaintextModeAvailable) return
        plaintextMode = enabled
        prefs.edit().putBoolean(KEY_PLAINTEXT_MODE, enabled).apply()
        log(
            "CRED",
            "明文模式开关：${if (enabled) "开（凭据未加密落盘 · 仅开发调试）" else "关"} · **重启应用后生效**",
        )
    }

    init {
        // ★ 注册闭源加密实现（**按类名**）。
        //   没有那个 `.aar` 时这里**照样成功**（注册只是记下类名）；
        //   真正解析要等到读写凭据时 —— 那时才会**安全失败**（抛异常，不退回明文）。
        // ★ v1.1.26：**明文模式是显式 opt-in**，且「已注册任何实现 ⇒ 再注册就抛」⇒
        //   两条路**二选一**（明文模式下**不**注册闭源实现）。
        if (plaintextMode && plaintextModeAvailable) {
            try {
                CredentialCipherLoader.registerPlaintext()
                log(
                    "CRED",
                    "★ 明文模式已启用：凭据**未加密**落盘（拿到凭据文件就能还原 key）· 仅开发调试",
                )
            } catch (e: Throwable) {
                log("CRED", "★ 明文模式注册失败：${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            CredentialCipherLoader.register(cipherClassName)
        }

        systemPrompt = AndroidAgentSdk.buildSystemPrompt(
            executor = executor,
            capabilities = capabilities,
            policy = workspace.guard.effectivePolicy().first,
            commandDetail = commandDetail,
        ) ?: ""
    }

    // ---- 后端配置（运行时填，不打包进 APK） ----

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
     *
     * ★ **首次进入的默认 = 高（8）**：用户口径「首次进入 = 思考开 + 高」。
     *   厂商默认也是 high（DeepSeek 官方 effort 默认 high；自备后端的默认档同样是 high）
     *   ⇒ 这里**显式落一个值**，不靠任何一方的默认值 ——
     *   「界面显示高」和「实际发出去的就是高」必须是同一件事。
     *   （老设备上存过 0 = 「服务端默认档」的保持原样，不迁移。）
     *
     * ★ **一个值就够，「开启」不必单独存**：这个编码同时表达了「开不开」和「哪一档」——
     *   `1` = 关闭思考（[ReasoningStrength.OFF]）、`2`=low、`4`=medium、`8`=high、`16`=max、`32`=xhigh。
     *   两条投送路径**都认这一个值**：直连 ⇒ 翻成 `thinking.type` + `reasoning_effort`；
     *   发给自备后端 ⇒ 翻成 `reasoning_strength`（由你的后端按「≤ 上限的最高可用档」取档）。
     *   ⇒ 宿主只管存这一个值，**两套线格式由 SDK 那一个方法各自翻译**，宿主不必分两套逻辑。
     */
    /**
     * ★ **初值 ＋ 存量迁移**（v1.1.26：从「开关 ＋ 档位」两维收成**单值**）。
     *
     * 返回 `(当前值, 上次选过的非关档位)`：
     * - 老版本本地存过 `thinking_enabled`（缺省 **true**）：
     *   - 存过 **false** ⇒ 老行为是「思考关」（发给 SDK 的编码**恒为 `1`**）⇒ 现在落 `1`；
     *   - 缺省 / true ⇒ 强度值原样用；
     * - 迁移完就把那个键**删掉** ⇒ 本地从此**只存一个编码**；
     * - 「上次选过的非关档位」优先取存下来的强度值（没有或是 `1` 就用 `8`）——
     *   它只存在**内存**里，供「开」按钮恢复（**不落盘** ⇒ 仍然只存一个编码）。
     */
    private fun seedStrength(): Pair<Int?, Int> {
        val stored = prefs.getInt(KEY_STRENGTH, ReasoningStrength.HIGH.value).takeIf { it > 0 }
        val legacyOff = prefs.contains(KEY_THINKING_ENABLED) && !prefs.getBoolean(KEY_THINKING_ENABLED, true)
        if (prefs.contains(KEY_THINKING_ENABLED)) {
            prefs.edit().remove(KEY_THINKING_ENABLED).apply()
        }
        val value = if (legacyOff) ReasoningStrength.OFF.value else stored
        val lastOn = stored?.takeIf { it != ReasoningStrength.OFF.value } ?: ReasoningStrength.HIGH.value
        return value to lastOn
    }

    private val strengthSeed = seedStrength()

    /** ★ **内存态**：上次选过的「非关」档位（供「开」按钮恢复；**不落盘**）。 */
    private var lastOnStrength by mutableStateOf(strengthSeed.second)

    /**
     * ★ **本地只存一个编码**（v1.1.26 收口；与 SDK 文档口径一致）。
     *
     * | 存的值 | 含义 | 发给 SDK |
     * |---|---|---|
     * | `1` | 关思考（[ReasoningStrength.OFF]） | `1` ⇒ 官方翻成 `thinking.type=disabled`；后端翻成 `reasoning_strength=1` |
     * | `2` / `4` / `8` / `16` / `32` | low / medium / high / max / xhigh | 原样 |
     * | `0`（没设） | 不指定 | `null` ⇒ **一个思考字段都不发** ⇒ 对端默认「开 + 高」 |
     *
     * ★ **没有第二维**：界面上的「思考：开 / 关」是**同一个数的快捷方式**（见 [selectThinkingEnabled]）。
     */
    var reasoningStrength by mutableStateOf(strengthSeed.first)
        private set

    /** 选一档（传 strength）。传 null = 用服务端默认。 */
    fun selectReasoningStrength(strength: Int?) {
        reasoningStrength = strength
        prefs.edit().putInt(KEY_STRENGTH, strength ?: 0).apply()
        // ★ 「开」按钮要能恢复**上次选过的档** ⇒ 只记在**内存**里（**不落盘** ⇒ 本地仍然只存一个编码）
        if (strength != null && strength != ReasoningStrength.OFF.value) lastOnStrength = strength
        log("EFFORT", "思考强度设为 ${strength ?: "(服务端默认)"}")
    }

    /**
     * ★ 「思考：开 / 关」是**同一个数的快捷方式**（**不是第二个字段、不是第二维**）。
     *
     * ## 为什么这么收
     * SDK 文档的口径是「**本地只存一个编码**」⇒ 界面再存一个 `thinking_enabled` 布尔就与文档对不上；
     * 而且开关与档位能组合出「关 ＋ high」这种**自相矛盾**的组合。
     *
     * ## 行为
     * - **关** ⇒ 落 `1`（OFF）：直连翻成 `thinking.type=disabled`，自备后端翻成 `reasoning_strength=1`；
     * - **开** ⇒ 落**上次选过的档位**（内存里记着；没有就用 `8` = 开 + 高，与首次进入一致）。
     *
     * ⇒ 本地**始终只有一个编码**；而「开/关」这个入口**仍然保留** ——
     * 用官方 API 时没有服务端档位清单可拉，它就是**唯一能关思考**的地方。
     */
    fun selectThinkingEnabled(enabled: Boolean) {
        if (enabled) {
            selectReasoningStrength(lastOnStrength)
        } else {
            selectReasoningStrength(ReasoningStrength.OFF.value)
        }
    }

    /** 界面上「思考开关」的样子（**由那一个数推出来**，不另存）。 */
    val thinkingOn: Boolean
        get() = reasoningStrength != ReasoningStrength.OFF.value

    /**
     * 拉服务端档位清单。
     *
     * 拉不到就**如实报错**（[effortCatalogError]），不返回一份本地默认表 ——
     * 悄悄塞一份本地默认 = 用户以为选的是服务端支持的档，实际不是。
     */
    fun loadEffortCatalog() {
        if (gatewayUrl.isBlank()) {
            effortCatalogError = "先填后端地址"
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

    /**
     * `agent-text` 的清单条目 —— **用法行照 SDK 能力给的原文**。
     *
     * 为什么清单里放的是「用法行」而不是一条具体命令：
     * 这条命令的参数是**子命令 + 相对路径 + 补丁规格**，写死一条例子反而不准；
     * 用法行才是模型真正会用到的形状（SDK 的提示词里也是这一行）。
     *
     * ★ 但它**必须真的注册**（见 [capabilities]）：注册了，
     *   提示词里才会有这一行，模型才知道 `agent-text` 存在。
     */
    val agentTextSpec = CapabilitySpec(
        key = "agent-text",
        title = "文本文件",
        subtitle = "读 / 按行改沙箱里的文本文件（agent 改文件不用重贴全文）",
        command = AGENT_TEXT_USAGE,
        icon = Icons.Filled.ContentCopy,
    )

    /**
     * 「SDK 自制命令」那份清单 = 清单文件里那批 **+ 本轮新增的 `agent-text`**。
     *
     * 能力台显示的与自检跑的是**同一份**（两份清单对不上，
     * 自检就变成在验一份界面上根本没有的东西）。
     */
    val internalCommandSpecs: List<CapabilitySpec> =
        CapabilityCatalog.internalCommands + listOf(agentTextSpec)

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
     * 会话标识（发给后端的 `session_id`）。
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
     * 而后端原先回传的是写死的 `stop` —— 半句答案会被当成完整交付。
     */
    private val turnFinishReasons = mutableListOf<String>()

    /** autorun 的调用序号 —— 用来判断「同一轮被跑了两次」这类问题。 */
    private var chatAutorunSeq = 0

    // ---- 归档历史（前端翻历史用）----

    /**
     * 归档里的一行（对应一条原始消息）。
     *
     * 归档存的是**结构化**的消息（不是渲染好的文本）——
     * 所以前端能照常区分「谁说的」「工具返回了什么」。
     */
    data class ArchiveLine(val who: String, val text: String)

    /** 一段归档（前端展示用）。 */
    data class ArchiveView(
        val id: String,
        val createdAt: Long,
        val kind: String,
        val title: String,
        val summary: String?,
        /** 更早的一段（往上滚时顺着它走）。 */
        val prevId: String?,
        /** 更晚的一段。 */
        val nextId: String?,
        val lines: List<ArchiveLine>,
    )

    /**
     * 已加载的归档段 —— **新的在前**（下标 0 = 最新的一段）。
     *
     * 列表底部是最新的一段（进页面先看它），往上滚才把**更早**的一段追加到末尾。
     * 这样「往上滚」天然对应「往时间上翻」。
     */
    val archiveSegments = mutableStateListOf<ArchiveView>()

    /** 正在加载更早的一段。 */
    var archiveLoading by mutableStateOf(false)

    /** 还有更早的段吗（false = 到头了）。 */
    var archiveHasMore by mutableStateOf(false)

    /** 归档仓库（路径与 SDK 内部那份一致）。 */
    private fun archiveStore() =
        ArchiveStore(File(executor.layout.varDir, AndroidAgentSdk.ARCHIVE_DIR))

    /**
     * 进归档页：只取**最新一段**。
     *
     * ★ 首屏**不整包拉** —— 历史可能很长，全拉进内存既慢又占地方。
     *   要看更早的就往上滚，一段一段来。
     */
    fun loadLatestArchive() {
        viewModelScope.launch {
            val store = archiveStore()
            val latest = withContext(Dispatchers.IO) { store.list(limit = 1) }
            val total = withContext(Dispatchers.IO) { store.size() }
            val views = withContext(Dispatchers.IO) { latest.map { readArchiveView(store, it) } }
            archiveSegments.clear()
            archiveSegments.addAll(views)
            archiveHasMore = total > views.size
            log(
                "ARCHIVE",
                "首屏加载 ${views.size} 段（库里共 $total 段）· 还有更早=${archiveHasMore} · " +
                    "最新=${views.firstOrNull()?.id ?: "-"}",
            )
        }
    }

    /**
     * 往上滚：加载**更早的一段**。
     *
     * 游标 = 当前已加载的**最老**那一段 —— 存储层照着链往回走，
     * 所以不会出现「跳段」或「重复」。
     */
    fun loadOlderArchive() {
        if (archiveLoading || !archiveHasMore) return
        val oldest = archiveSegments.lastOrNull() ?: return
        archiveLoading = true
        viewModelScope.launch {
            try {
                val store = archiveStore()
                val older = withContext(Dispatchers.IO) { store.list(limit = 1, before = oldest.id) }
                if (older.isEmpty()) {
                    archiveHasMore = false
                } else {
                    val view = withContext(Dispatchers.IO) { readArchiveView(store, older.first()) }
                    // 新的在前 ⇒ 更早的一段**追加到末尾**
                    archiveSegments.add(view)
                    log("ARCHIVE", "往上翻：加载更早的一段 ${view.id}（共 ${archiveSegments.size} 段已加载）")
                }
            } finally {
                archiveLoading = false
            }
        }
    }

    /** 读一段归档，转成前端要的形状。 */
    private fun readArchiveView(store: ArchiveStore, meta: ArchiveSegmentMeta): ArchiveView {
        val messages = store.read(meta.id)?.messages.orEmpty()
        return ArchiveView(
            id = meta.id,
            createdAt = meta.createdAt,
            kind = meta.kind,
            title = meta.title,
            summary = meta.summary,
            prevId = meta.prevId,
            nextId = meta.nextId,
            lines = messages.map { m ->
                when (m) {
                    is UserMessage -> ArchiveLine("你", m.content)
                    is AssistantMessage -> ArchiveLine(
                        "模型",
                        m.content ?: m.toolCalls.joinToString("\n") { "→ ${it.name}(${it.arguments})" },
                    )
                    is ToolMessage -> ArchiveLine("工具", m.content)
                    is SystemMessage -> ArchiveLine("系统", m.content)
                    else -> ArchiveLine("", "")
                }
            },
        )
    }

    // ---- 记忆页（两块：今天的摘要 / 你自己记下的事） ----

    /**
     * 今天的摘要（**每日摘要槽** —— 每天一条，新摘要会替换当天那条）。
     *
     * null = 今天还没有摘要。
     */
    var memoryDaily by mutableStateOf<MemoryEntry?>(null)
        private set

    /** agent 自己记下的**日常条目**（新的在前）。 */
    val memoryNotes = mutableStateListOf<MemoryEntry>()

    /** 记忆页上次加载的时刻（0 = 还没加载过）。 */
    var memoryLoadedAt by mutableStateOf(0L)
        private set

    /**
     * 记忆仓库（**路径与 SDK 内部那份一致** —— 同一个常量算出来的）。
     *
     * 宿主自己开一份是给「翻旧记忆」用的；它和 `agent-memory` 命令、
     * SDK 内部的 [ContextMemory] 读写的是**同一批文件**。
     */
    private fun memoryStore(): MemoryStore =
        MemoryStore(File(executor.layout.varDir, AndroidAgentSdk.MEMORY_DIR))

    /**
     * 加载记忆页的两块。
     *
     * ★ 口径**照抄 SDK 的 `MemoryStore.digest()`**：摘要 = 最新的每日槽，
     *   日常 = 追加式条目（新的在前）。
     *   界面上这两块必须和**提示词里那一块**同口径 ——
     *   否则用户看到的和模型看到的不是一回事，没法对照。
     */
    fun loadMemory() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { memoryStore().list() }
            memoryDaily = all.filter { it.kind == MemoryKind.DAILY }
                .maxWithOrNull(compareBy<MemoryEntry>({ it.createdAt }, { it.id }))
            val notes = all.filter { it.kind == MemoryKind.NOTE }
                .sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenByDescending { it.id })
            memoryNotes.clear()
            memoryNotes.addAll(notes)
            memoryLoadedAt = System.currentTimeMillis()
            log(
                "MEM",
                "记忆页加载：摘要=${memoryDaily?.id ?: "(无)"} 日常=${memoryNotes.size} 条",
            )
        }
    }

    /**
     * `agent-text` 的**真例子**（能力台那张卡片点下去跑的就是它）。
     *
     * 为什么不是直接跑卡片上那行：卡片显示的是**用法行**
     * （`agent-text <read <相对路径>|patch <相对路径> <补丁规格>>`），
     * 它不是一条可执行命令 —— 照着跑只会得到「不认识的子命令」。
     */
    suspend fun runAgentTextDemo(): CommandResult {
        val file = ".agent-text-demo.txt"
        runCommand("文本 · 准备", "cat > $file << 'EOF'\n第一行\n第二行\nEOF")
        val r = runCommand(
            "文本文件",
            "agent-text patch home/$file \"replace-lines 2 2 改过的第二行\" && agent-text read home/$file",
        )
        // 演示不留垃圾。
        runCommand("文本 · 清理", "rm -f $file")
        return r
    }

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

            // 4) SDK 自制的命令（agent-*）
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
                    title = "SDK 自制命令 · ${spec.title}",
                    detail = spec.subtitle,
                    command = spec.command,
                    verdict = verdict,
                    output = r.output.ifBlank { "（无输出，退出码 ${r.exitCode}）" },
                    elapsedMs = r.elapsedMs,
                )
                log("CHECK", "${verdict.emoji} SDK 自制命令·${spec.title} || exit=${r.exitCode} | ${r.output}")
            }
            // 自检不留痕：把刚才加的那条定时任务删掉。
            addedScheduleId?.let { id ->
                val rm = runCommand("定时任务 · 删掉自检加的那条", "agent-schedule remove $id")
                // 必须看 removed:true —— 退出码 0 但 removed:false 意味着**没删掉**，
                // 那正是本项目最讨厌的「谎报成功」。
                val removed = rm.output.contains("\"removed\":true")
                checks += CheckItem(
                    title = "SDK 自制命令 · 自检不留垃圾",
                    detail = "自检加的定时任务自己删掉 —— 反复跑自检不会在设备上越堆越多",
                    command = "agent-schedule remove $id",
                    verdict = if (removed) Verdict.PASS else Verdict.FAIL,
                    output = rm.output.ifBlank { "（无输出，退出码 ${rm.exitCode}）" },
                    elapsedMs = rm.elapsedMs,
                )
                log("CHECK", "${if (removed) "✅" else "❌"} SDK 自制命令·自检不留垃圾 | id=$id | ${rm.output}")
            }

            // 4b) `agent-text`：SDK 自制的**行级改文件**命令 —— 真读真写一个临时文件。
            //
            //    ★ 判据不是「没报错」，而是「写进去的读得回来」：
            //    两条能力调用都得回 ok:true（复合命令里能力先跑、结果由 shell 打印），
            //    只回一次说明有一条其实没成。
            //
            //    ★ 临时文件放在 home/ 下 —— `agent-text` 的相对路径是**相对沙箱根**的，
            //      而 shell 的工作目录恰好就是 home/，两条路径指的是同一个文件。
            val textProbeFile = ".agent-text-selfcheck.txt"
            val textProbeCmd =
                "agent-text patch home/$textProbeFile \"replace-lines 1 1 第一行\\n第二行\" && " +
                    "agent-text read home/$textProbeFile"
            val textProbe = runCommand("SDK 自制命令 · 文本文件", textProbeCmd)
            val okCount = Regex("\"ok\":true").findAll(textProbe.output).count()
            val textOk = textProbe.exitCode == 0 && okCount >= 2 && textProbe.output.contains("第一行")
            checks += CheckItem(
                title = "SDK 自制命令 · 文本文件",
                detail = "写一个临时文件再读回来 —— agent-text 的读与写都要真的跑通",
                command = textProbeCmd,
                verdict = if (textOk) Verdict.PASS else Verdict.FAIL,
                output = textProbe.output.ifBlank { "（无输出，退出码 ${textProbe.exitCode}）" },
                elapsedMs = textProbe.elapsedMs,
            )
            log(
                "CHECK",
                "${if (textOk) "✅" else "❌"} SDK 自制命令·文本文件 | exit=${textProbe.exitCode} | " +
                    "ok:true×$okCount | ${textProbe.output}",
            )
            // 自检不留痕：临时文件用完即删。
            runCommand("临时文件 · 删掉自检造的那个", "rm -f $textProbeFile")

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
            //    普通 App 没有读权限，File.list() 必然返回 null（真机上出现过：清单探出 0 条）。
            //    （复核用的是同一套 stat 机制，但代码是独立写的；
            //     真正独立的证据是下面**实跑**那几条 —— 「存在」不等于「能跑」。）
            // 客户端 system 段上限（与后端的 client_system_max_chars 默认值一致；那边是可调参数）。
            val segLimit = AndroidAgentSdk.DEFAULT_SYSTEM_BUDGET_LIMIT_CHARS
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
                    // 提示词有多长 —— **两条预算都得报**，只报一条会误导人：
                    //   ① 提示词本体 < 2000（SDK 自己的保险线，`DevicePromptBudgetTest` 硬断言）
                    //   ② system 段总长上限（服务端参数 client_system_max_chars，默认 6000，可调）
                    // 后端会把客户端 system 段**截到上限**，超了就砍尾巴
                    //（长期记忆 digest / 会话摘要 / 工作区告警正好排在最后）。这个数字得看得见。
                    val promptLen = systemPrompt.length
                    val fullLen = AndroidAgentSdk.buildSystemPrompt(
                        executor = executor,
                        capabilities = capabilities,
                        policy = workspace.guard.effectivePolicy().first,
                        commandDetail = ai.agentkernel.prompt.AgentSystemPrompt.CommandDetail.FULL,
                    )?.length ?: -1
                    appendLine("提示词长度：核心模式 $promptLen 字符 / 完整模式 $fullLen 字符" +
                        "（提示词本体保险线 $PROMPT_BODY_INSURANCE_LINE → " +
                        if (promptLen > PROMPT_BODY_INSURANCE_LINE) "⚠️ 核心模式超了这条线！" else "核心模式装得下；" +
                        "system 段总长上限 $segLimit → " +
                        if (promptLen > segLimit) "⚠️ 核心模式也会被截断！" else "也装得下）")
                }.trim(),
                elapsedMs = probeResults.sumOf { it.second.elapsedMs },
            )
            log(
                "CHECK",
                "${if (promptOk) "✅" else "❌"} 提示词·命令清单与真机一致 | claimed=${claimed.size} " +
                    "missing=${missing.size} probe=${probeResults.size - probeFailed.size}/${probeResults.size} " +
                    // 提示词有多长 —— **两条预算都报**：提示词本体的保险线（2000，SDK 自己守）
                    // 与 system 段总长上限（服务端参数，可调）。只报 6000 会让人以为
                    // 提示词可以随便加到 6000 —— 其实真正卡住提示词的是那条 2000 保险线。
                    "| prompt=${systemPrompt.length} chars " +
                    "(prompt-body limit=$PROMPT_BODY_INSURANCE_LINE " +
                    if (systemPrompt.length > PROMPT_BODY_INSURANCE_LINE) "超线!; " else "OK; " +
                    "client system cap=$segLimit " +
                    if (systemPrompt.length > segLimit) "会被截断!)" else "装得下)",
            )

            // 8) 白名单只是「提示词里写什么」，**不是**「执行时限制什么」。
            //
            //    设备上 /system/bin 有 434 个可执行文件，提示词里只介绍了 82 个 ——
            //    剩下 350 多个**照样能跑**。能力路由只接管「注册过的能力命令」，
            //    其余原样丢给 /system/bin/sh。
            //
            //    判据：**退出码不是 127**（127 = command not found = 路由/白名单拒了）。
            //    权限不够会是 1 或别的码 + 明确报错 —— 那是 Android 说的，不是 SDK 说的。
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
            chat += ChatLine.System("先在「对话」页填好后端地址和 token")
            return
        }
        chatBusy = true
        try {
            val (client, memory) = clientAndMemory()
            val sdk = AndroidAgentSdk(
                llm = client,
                model = gatewayModel,
                executor = executor,
                // ★ 系统提示词由 SDK 生成（环境 / 命令清单 / 能力边界 / 工作区规则），
                //   这里不用手写 —— 手写的版本会跟实际注册的能力对不上。
                // ★ 命令清单用 CORE：这里有**两条不同的预算**，别混。
                //   ① system 段总长上限（client_system_max_chars，默认 6000）—— 服务端参数，**可调**；
                //   ② 提示词本体的保险线（2000）—— SDK 自己守的（DevicePromptBudgetTest 硬断言）。
                //   完整清单(FULL，约 2400 字符)装得进 ①，但**装不进 ②** ——
                //   而真正卡住提示词的是 ②：上限随时可能被调回小值，那时尾巴（能力清单、
                //   工作区维护规则）就会被砍掉。CORE 是「两条都装得下」的安全值（实测 1933）。
                //   直连 DeepSeek 官方没有这个限制，那边可以用 SDK 的默认值 FULL。
                // ★ 用 DemoState.commandDetail —— 和自检那份**同一个值**（否则自检在验另一份）。
                commandDetail = commandDetail,
                // ★ 思考强度：传**强度编码**（2 的幂，跨模型稳定）—— **一个值，两条路径共用**。
                //   null = 不带这个字段，由服务端用它的默认档位。
                //   它是「上限」：服务端取「强度 ≤ 给的值」里该模型能达到的最高档（只向下）。
                //   `1` = 关闭思考（两侧都认：官方翻成 thinking.type=disabled，自备后端翻成 reasoning_strength=1）。
                //   其它值 ⇒ 官方翻成 thinking{enabled} + reasoning_effort，自备后端翻成 reasoning_strength。
                // ★ 本地**只存一个编码**（开关与档位是同一个数的两种入口）⇒ 发给 SDK 的也只有一个值。
                reasoningStrength = reasoningStrength,
                // ★ 工作区守卫：超配额时自动把告警注入这一轮，让 agent 自己清理。
                workspaceGuard = workspace.guard,
                // ★ 体检器跨轮复用（检测轮与轮之间的前缀漂移）。
                inspector = contextInspector,
                // ★ 记忆执行器跨轮复用（压缩摘要 + 闲置计时都挂在它身上）。
                memory = memory,
                // ★ 会话标识：让服务端也能独立复核前缀（头部是服务端拼的，本地看不见）。
                sessionId = sessionId,
                onEvent = { event ->
                    when (event) {
                        // 模型下发了哪些命令 —— 记下来，工具结果回来时好对上。
                        is KernelEvent.Assistant -> {
                            val msg = event.message
                            // 结束原因必须打出来：'length' = 被 max_tokens 截断 → 答案不完整。
                            // 后端原先把它写死成 'stop'，于是半句答案被当完整交付（谎报成功）。
                            log(
                                "CHAT",
                                "回合: finish_reason=${msg.finishReason ?: "(null)"} | " +
                                    "tool_calls=${msg.toolCalls.size} | 正文长度=${msg.content?.length ?: 0}",
                            )
                            msg.finishReason?.let { turnFinishReasons += it }
                            // 服务端视角的前缀报告：后端那一侧的头部（平台提示词/技能/工具定义）
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
            // system 段预算体检：超了后端会**悄悄截断**，排在最后的记忆/摘要/告警就丢了 ——
            // 模型看不到，却没人知道。这种静默故障必须显式报出来。
            //
            // ★ SDK 现在会**主动协调**：能砍的只有长期记忆 digest（它能用 agent-memory search 取回），
            //   提示词/会话摘要/工作区告警一律不砍 ⇒ 正常情况下 over 恒为 false。
            //   overflow 才是真问题：**把记忆砍光也装不下**（提示词本身太大，得改配置）。
            sdk.systemBudget().let { b ->
                val detail = "prompt=${b.promptChars} 记忆=${b.memoryChars} 摘要=${b.summaryChars} 告警=${b.alertChars}"
                if (b.overflow) {
                    log(
                        "CTX",
                        "⚠️ system 段装不下（砍光记忆也不够）$detail 合计=${b.totalChars} > ${b.limitChars}" +
                            "—— 提示词本身太大，要改配置（换 CORE 清单 / 精简 extraNotes / 调大上限）",
                    )
                    chat += ChatLine.System(
                        "system 段装不下（${b.totalChars}/${b.limitChars} 字符）—— 把长期记忆砍光也超，" +
                            "是提示词本身太大，需要改配置。",
                    )
                } else if (b.over) {
                    log(
                        "CTX",
                        "⚠️ system 段超限 $detail 合计=${b.totalChars} > ${b.limitChars}（后端会截断，尾部到不了模型）",
                    )
                    chat += ChatLine.System(
                        "system 段超限（${b.totalChars}/${b.limitChars} 字符）—— 后端会截断，尾部到不了模型。",
                    )
                } else {
                    log(
                        "CTX",
                        "system 段 ${b.totalChars}/${b.limitChars} 字符（$detail" +
                            "，摘要预留位=${b.reservedSummaryChars}）",
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
     * 归档**分页**自检（自动化用）。
     *
     * 验前端「往上翻」那条路的三个性质：
     * 1. **首屏只取最新一段**（不是一把全拉）；
     * 2. **一次只多一段**（小段小段地加载，不跳段、不重复）；
     * 3. **链对得上**：翻出来的每一段，`prevId`/`nextId` 都指着相邻那段。
     *
     * ★ 走的是**和滚动触发完全同一个函数** [loadOlderArchive] ——
     *   所以验的是真逻辑，不是另写一套。
     */
    fun startArchivePagingCheck() {
        DemoRuntime.startOncePerProcess("archivepaging") { runArchivePagingCheck() }
    }

    suspend fun runArchivePagingCheck() {
        val store = archiveStore()
        val total = store.size()
        log("PAGE", "库里共 $total 段")
        if (total < 2) {
            log("PAGE", "❌ 只有 $total 段，验不了分页（至少要 2 段）")
            return
        }

        fun item(title: String, ok: Boolean, detail: String) {
            val verdict = if (ok) Verdict.PASS else Verdict.FAIL
            log("PAGE", "${verdict.emoji} $title | $detail")
            checks += CheckItem(title, detail, "(归档分页)", verdict, detail, 0)
        }

        // ① 首屏：只取最新一段
        loadLatestArchive()
        var waited = 0
        while (archiveSegments.isEmpty() && waited < 100) {
            delay(100); waited++
        }
        val first = archiveSegments.firstOrNull()
        if (first == null) {
            log("PAGE", "❌ 首屏没加载出来")
            return
        }
        val newestId = store.list(limit = 1).firstOrNull()?.id
        item("首屏只取最新的一段", archiveSegments.size == 1 && first.id == newestId,
            "已加载 ${archiveSegments.size} 段 · 最新=${first.id} · 库里共 $total 段")

        // ② 一直往上翻，直到翻完
        val seen = mutableListOf(first.id)
        val views = mutableListOf(first)
        var guard = 0
        while (archiveHasMore && guard < total + 5) {
            val before = archiveSegments.size
            loadOlderArchive()
            waited = 0
            while (archiveSegments.size == before && waited < 100) {
                delay(100); waited++
            }
            val added = archiveSegments.lastOrNull() ?: break
            if (added.id in seen) break
            seen.add(added.id)
            views.add(added)
            guard++
        }
        item("一次只多一段、不跳段", seen.size == total,
            "翻了 ${seen.size - 1} 次 · 共 $total 段 · 顺序=${seen.joinToString(" ← ")}")

        item("不重复", seen.toSet().size == seen.size,
            "去重后 ${seen.toSet().size} 段 / 共 ${seen.size} 段")

        item("段链对得上", views.zipWithNext().all { (newer, older) ->
                newer.prevId == older.id && older.nextId == newer.id
            },
            views.zipWithNext().map { (newer, older) -> "${newer.id}↔${older.id}" }.joinToString(" "))
    }

    /**
     * 记忆子系统的命令行自检（自动化用）。
     *
     * 验八件事：
     * 1. **归档**：把一段历史总结成记忆 → 真的产出了一条条目；
     * 2. **落盘**：记忆正文在磁盘上（agent 用终端 `cat` 就能读）；
     * 3. **摘要块**：进提示词的那一块**带的是正文**，不是只有标题；
     * 4. **压缩**：超预算的历史被压了一次 → 摘要产出、历史变短；
     * 5. **历史变短**：压缩不是压了个寂寞；
     * 6. **跨实例**：换个执行器实例记忆还在；
     * 7. **归档段**：被摘要掉的**原文**落盘了（摘要不删原文，前端照样能翻）；
     * 8. **段链**：段与段之间 prevId/nextId 连得上（前端顺着链往上翻）。
     *
     * ⚠️ 归档与压缩都**要真的调一次模型 API**，所以必须配好模型：
     * ```
     * adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity \
     *     --ez autorun_memory true \
     *     --es gateway_url "https://api.deepseek.com/v1" \
     *     --es gateway_token "<key>"
     * adb logcat -d -s AgentSdkDemo
     * ```
     */
    fun startMemoryCheck() {
        DemoRuntime.startOncePerProcess("memorycheck") { runMemoryCheck() }
    }

    suspend fun runMemoryCheck() {
        if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
            log("MEM", "❌ 没配模型地址/token，验不了（归档与压缩都要真的调一次模型）")
            return
        }
        val (client, memory) = clientAndMemory()
        val store = memory.memoryStore()

        fun item(title: String, ok: Boolean, detail: String, elapsed: Long) {
            val verdict = if (ok) Verdict.PASS else Verdict.FAIL
            log("MEM", "${verdict.emoji} $title | $detail | ${elapsed}ms")
            checks += CheckItem(title, detail, "(记忆子系统)", verdict, detail, elapsed)
        }

        // ① 归档：把一段历史总结成一条记忆
        val t0 = System.currentTimeMillis()
        val fake = listOf(
            UserMessage("我在配一个手机端的 agent SDK"),
            AssistantMessage("先把命令执行跑通，再接模型"),
            UserMessage("好，就这么办"),
        )
        val entry = memory.archiveNow(fake)
        val t1 = System.currentTimeMillis()
        item("归档产出记忆条目", entry != null, entry?.let { "id=${it.id} 标题=${it.title}" } ?: "没产出条目", t1 - t0)

        // ② 落盘：正文在磁盘上（agent 用终端就能读）
        val body = entry?.let { store.readBody(it.id) }
        item("记忆正文落盘", body != null && body.isNotBlank(), body?.let { "正文 ${it.length} 字符" } ?: "读不到正文", 0)

        // ③ 摘要块：带的是正文，不是只有标题
        val digest = memory.digest()
        val digestOk = digest != null && entry != null && digest.contains(entry.title)
        item("记忆进摘要块", digestOk, digest?.let { "摘要块 ${it.length} 字符" } ?: "没有摘要块", 0)

        // ④ 压缩：用一份「预算很小」的策略，超预算就该被压一次
        val t2 = System.currentTimeMillis()
        val probe = ContextMemory(
            llm = client,
            model = gatewayModel,
            store = store,
            policy = ContextPolicy(
                maxContextChars = 500,
                keepRecentChars = 100,
                summaryTargetChars = 300,
                idleArchiveMillis = 0,
            ),
            // ★ 压缩掉的原文落归档（摘要不删原文）
            archive = archiveStore(),
        )
        val big = (1..20).map { UserMessage("第$it 条消息 " + "x".repeat(60)) }
        val plan = probe.prepare(big, prefixChars = 0)
        val t3 = System.currentTimeMillis()
        item("超容量自动压缩", plan.compacted && plan.summary != null,
            "压缩=${plan.compacted} 摘要长度=${plan.summary?.length ?: 0} 历史 ${big.size}→${plan.history.size} 条", t3 - t2)

        // ⑤ 压缩后历史确实变短了（不是压了个寂寞）
        item("压缩后历史变短", plan.history.size < big.size,
            "${big.size} → ${plan.history.size} 条", 0)

        // ⑥ 记忆能跨执行器保留（换个实例还在 —— 那是「落盘」的意义）
        val fresh = ContextMemory(
            llm = client,
            model = gatewayModel,
            store = MemoryStore(File(executor.layout.varDir, AndroidAgentSdk.MEMORY_DIR)),
            policy = memoryPolicy,
        )
        val digest2 = fresh.digest()
        item("记忆跨实例保留", digest2 != null && entry != null && digest2.contains(entry.title),
            digest2?.let { "新实例摘要块 ${it.length} 字符" } ?: "新实例没有摘要块", 0)

        // ⑦ 归档段：被摘要掉的原文**还在**（摘要不删原文）
        val arch = archiveStore()
        val segs = arch.list()
        val compactSeg = segs.firstOrNull { it.kind == "compact" }
        val readBack = compactSeg?.let { arch.read(it.id) }
        item("压缩掉的原文落归档段",
            readBack != null && readBack.messages.isNotEmpty(),
            "归档段数=${segs.size} 压缩段原文条数=${readBack?.messages?.size ?: 0}", 0)

        // ⑧ 段与段之间有链（前端顺着链往上翻）
        //   方向要记清：**prevId = 更早的一段**、**nextId = 更晚的一段**。
        //   segs 是「新的在前」，所以更新的那段 prevId 指向更老的那段。
        val chained = segs.size >= 2 &&
            segs[0].prevId == segs[1].id &&
            segs[1].nextId == segs[0].id
        item("归档段之间有链", chained,
            segs.joinToString(" ← ") { "${it.kind}:${it.id}" } +
                (if (chained) "" else " · 链断了"), 0)

        log("MEM", "记忆目录=${File(executor.layout.varDir, AndroidAgentSdk.MEMORY_DIR).absolutePath} 条目数=${store.list().size}")
        log("MEM", "归档目录=${arch.rootDir().absolutePath} 段数=${segs.size}")
    }

    /**
     * 行级补丁自检（`agent-memory patch` / `agent-text patch`）。
     *
     * 验四件事：
     * 1. **`agent-memory patch` 能改一行**：diff 带回行号；读回来第 2 行确实变了、**其它行没变**；
     * 2. **`agent-text patch` 能改一行**：同上；
     * 3. **失败要如实报错**：锚点找不到 ⇒ ok=false 且说清是锚点问题（不能谎报成功）；
     * 4. **路径沙箱**：`..` 不给过（不许写到 agent 根外面）。
     *
     * ★ 这些断言**全部走命令**（[runCommand]）—— 这一轮验的就是「命令能用」，
     *   直接调 SDK 内部方法绕过命令层，等于没验。
     *
     * ```
     * adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity \
     *     --ez autorun_patch true
     * adb logcat -d -s AgentSdkDemo
     * ```
     */
    fun startPatchCheck() {
        DemoRuntime.startOncePerProcess("patchcheck") { runPatchCheck() }
    }

    suspend fun runPatchCheck() {
        val results = mutableListOf<Pair<String, Boolean>>()

        fun item(name: String, ok: Boolean, detail: String) {
            results += name to ok
            logPatch(name, ok, detail)
            checks += CheckItem(
                title = name,
                detail = detail,
                command = "(行级补丁)",
                verdict = if (ok) Verdict.PASS else Verdict.FAIL,
                output = detail,
                elapsedMs = 0,
            )
        }

        // ---- ① `agent-memory patch`：改一行，其它行不动 ----
        //
        // 正文要**多行**，但命令层的参数按空白拆词（真换行会被当操作符），
        // 所以多行正文靠一次 replace-lines 铺出来：补丁规格里的 \n 表示真换行。
        val add = runCommand("记忆 · 写一条", "agent-memory add \"自检记忆第一行\"")
        val memoryId = Regex("\"id\":\"([^\"]+)\"").find(add.output)?.groupValues?.get(1)
        if (memoryId == null) {
            item("memory-patch", false, "写不进记忆：exit=${add.exitCode} ${add.output}")
        } else {
            runCommand(
                "记忆 · 铺成多行",
                "agent-memory patch $memoryId \"replace-lines 1 1 第一行\\n第二行\\n第三行\"",
            )
            val patch = runCommand(
                "记忆 · 改第 2 行",
                "agent-memory patch $memoryId \"replace-lines 2 2 改过的第二行\"",
            )
            val diff = Regex("\"diff\":\"([^\"]*)\"").find(patch.output)?.groupValues?.get(1)
            // 读回来看**真正文**：命令层的 JSON 里换行是转义的，按行比对得用真文本。
            val body = memoryStore().readBody(memoryId).orEmpty()
            val lines = body.lines()
            val got = runCommand("记忆 · 读回来", "agent-memory get $memoryId")
            val okTrue = patch.output.contains("\"ok\":true")
            val diffHasLine = diff != null && Regex("第\\s*\\d+\\s*行").containsMatchIn(diff)
            val line2 = lines.getOrNull(1)
            val othersKept = lines.getOrNull(0) == "第一行" && lines.getOrNull(2) == "第三行"
            val seen = got.output.contains("改过的第二行")
            item(
                "memory-patch",
                okTrue && diffHasLine && line2 == "改过的第二行" && othersKept && seen,
                "ok=true=$okTrue · diff=${diff ?: "(没有)"} · 第2行=$line2 · " +
                    "其它行未变=$othersKept · 读回命令看得到=$seen",
            )
            // 自检不留痕：这条记忆是自检造的，用完删掉。
            runCommand("记忆 · 删掉自检造的那条", "agent-memory delete $memoryId")
        }

        // ---- ② `agent-text patch`：改一行，其它行不动 ----
        //
        // 相对路径是**相对沙箱根**的；shell 的工作目录是 home/ ⇒ `home/x` 与 `x` 同一个文件。
        val textFile = ".patch-selfcheck.txt"
        val wrote = runCommand(
            "文本 · 造一个多行文件",
            "cat > $textFile << 'EOF'\n第一行\n第二行\n第三行\nEOF",
        )
        val textPatch = runCommand(
            "文本 · 改第 2 行",
            "agent-text patch home/$textFile \"replace-lines 2 2 改过的第二行\"",
        )
        val textDiff = Regex("\"diff\":\"([^\"]*)\"").find(textPatch.output)?.groupValues?.get(1)
        val readBack = runCommand("文本 · 读回来", "cat $textFile")
        val textLines = readBack.output.lines()
        val textOkTrue = textPatch.output.contains("\"ok\":true")
        val textDiffHasLine = textDiff != null && Regex("第\\s*\\d+\\s*行").containsMatchIn(textDiff)
        val textLine2 = textLines.getOrNull(1)
        val textOthersKept = textLines.getOrNull(0) == "第一行" && textLines.getOrNull(2) == "第三行"
        item(
            "text-patch",
            wrote.exitCode == 0 && textOkTrue && textDiffHasLine &&
                textLine2 == "改过的第二行" && textOthersKept,
            "写入=${wrote.exitCode == 0} · ok=true=$textOkTrue · diff=${textDiff ?: "(没有)"} · " +
                "第2行=$textLine2 · 其它行未变=$textOthersKept",
        )

        // ---- ③ 失败要如实报错：锚点找不到 ----
        val anchor = runCommand(
            "文本 · 锚点不存在",
            "agent-text patch home/$textFile \"replace-block|不存在的锚点|新内容\"",
        )
        val anchorFailed = anchor.exitCode != 0 && anchor.output.contains("\"ok\":false")
        val anchorExplained = anchor.output.contains("锚点")
        item(
            "text-patch-anchor-fail",
            anchorFailed && anchorExplained,
            "ok=false=$anchorFailed · 说清是锚点问题=$anchorExplained · exit=${anchor.exitCode} · " +
                anchor.output,
        )

        // ---- ④ 路径沙箱：不许写到 agent 根外面 ----
        val escape = runCommand(
            "文本 · 越界路径",
            "agent-text patch ../逃逸.txt \"replace-lines 1 1 越界\"",
        )
        val escapedFile = File(context.filesDir, "逃逸.txt")
        val escapeFailed = escape.exitCode != 0 && escape.output.contains("\"ok\":false")
        val nothingOutside = !escapedFile.exists()
        item(
            "text-patch-escape",
            escapeFailed && nothingOutside,
            "ok=false=$escapeFailed · 根外没落文件=$nothingOutside · exit=${escape.exitCode} · " +
                escape.output,
        )

        // 自检不留痕：临时文件用完即删。
        runCommand("文本 · 删掉自检造的那个", "rm -f $textFile")

        val pass = results.count { it.second }
        val fail = results.size - pass
        log("SUMMARY", "合计 ${results.size} 项：✅ $pass · ❌ $fail")
    }

    /**
     * 命令行触发的**凭据**自检（`agent-cred` 接线的验收用）。
     *
     * ## 验什么
     *
     * | # | 断言 | 说明 |
     * |---|---|---|
     * | ① | `cipher-impl` | 实现**注册了没有** ＋ **能不能解析出来** —— 「注册成功」≠「可用」 |
     * | ② | `cred-write` | 宿主侧 API 写一条**假** key（真 key 绝不进自检） |
     * | ③ | `cred-no-plaintext` | 扫 `filesDir` 全树，那串假 key **不该出现在任何文件里** |
     * | ④ | `cred-roundtrip` | 解出来 == 写进去的 ⇒ 往返一致（**不打印明文**） |
     *
     * ## 跑两次 = 验「重启后仍读得出」
     *
     * **第一次**跑：目录里没有这条凭据 ⇒ 写入（并**留着**）；
     * **重启 App 后再跑一次**：目录里已经有了 ⇒ **只读**
     * ⇒ 这就是「Keystore 密钥持久性」的证据（写进去那次的密文，重启后还解得开）；
     * 验完**删掉**（自检不留痕）。
     *
     * ```
     * adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity \
     *     --ez autorun_cred true
     * adb logcat -d -s AgentSdkDemo | Select-String 'CRED\|'
     * ```
     */
    fun startCredentialCheck() {
        DemoRuntime.startOncePerProcess("credcheck") { runCredentialCheck() }
    }

    suspend fun runCredentialCheck() {
        val results = mutableListOf<Pair<String, Boolean>>()

        fun item(name: String, ok: Boolean, detail: String) {
            results += name to ok
            logCred(name, ok, detail)
            checks += CheckItem(
                title = name,
                detail = detail,
                command = "(凭据)",
                verdict = if (ok) Verdict.PASS else Verdict.FAIL,
                output = detail,
                elapsedMs = 0,
            )
        }

        val store = AndroidAgentSdk.credentialStore(context)
        val name = "demo-selftest"
        val host = "api.deepseek.com"
        // ★ 假 key：自检**绝不用真 key**（真 key 进了 APK 或日志就是事故）。
        val plaintext = "sk-demo-selftest-not-a-real-key"

        // ---- 落点（并行核实要用的四项）----
        logCred(
            "landing",
            true,
            "package=com.agentkernel.demo" +
                " · 凭据目录=${File(context.filesDir, AndroidAgentSdk.CREDENTIAL_DIR).absolutePath}" +
                " · agent-cred=**宿主侧接口**（setSecret/secretFor/deleteSecret）＋ 只读命令（list/status）" +
                " · .aar 来源=release asset android-agent-sdk-cipher-v1.0.0-20261029.aar" +
                "（期望 sha256=f098744c71d660a4684d7138c437330ef34f19f19ab98c8f7cabd7c1d3644731）",
        )

        // ---- ① 实现可用性：注册了没有 / 能不能解析出实现 ----
        val registered = CredentialCipherLoader.isAvailable()
        val decryptable = store.isDecryptable()
        val resolved = try {
            CredentialCipherLoader.resolve()::class.java.name
        } catch (e: Throwable) {
            "解析失败：${e.javaClass.simpleName}: ${e.message}"
        }
        item(
            "cipher-impl",
            registered && decryptable,
            "registered=$registered · decryptable=$decryptable · 实现类=$resolved" +
                // ★ 审计标注：凭据**存储模式**必须出现在自检报告里（明文模式不能悄悄开着）。
                " · ★ 存储模式=" + if (CredentialCipherLoader.isPlaintextMode()) {
                    "明文（未加密 · 仅开发调试）"
                } else {
                    "加密"
                },
        )

        // ---- 第二次跑 = 只读（验「重启后仍读得出」）----
        val existed = try { store.list().contains(name) } catch (e: Throwable) { false }

        // ---- ② 写入（宿主侧 API；假 key）----
        if (!existed) {
            val wrote = try {
                credCaps.setSecret(name, host, plaintext)
                "写入成功"
            } catch (e: Throwable) {
                "写入失败：${e.javaClass.simpleName}: ${e.message}"
            }
            item("cred-write", wrote == "写入成功", wrote)
        } else {
            item("cred-write", true, "已存在（这是第二次跑）⇒ 跳过写入，本轮验的是「重启后仍读得出」")
        }

        // ---- ③ 磁盘上没有明文（扫 filesDir 全树，比只看凭据目录更严）----
        val marker = plaintext.toByteArray(Charsets.UTF_8)
        var hits = 0
        var firstHit = ""
        fun scan(dir: File) {
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) {
                    scan(f)
                } else {
                    try {
                        if (f.readBytes().containsBytes(marker)) {
                            hits++
                            if (firstHit.isEmpty()) firstHit = f.absolutePath
                        }
                    } catch (_: Throwable) {
                        // 读不到的文件不算命中 —— 但命中数照样报出来，不藏。
                    }
                }
            }
        }
        scan(context.filesDir)
        // ★ 明文模式下磁盘上**本来就有**明文 ⇒ 这一项不作不合格，但要**说清原因**（不能默默变红）。
        val plaintextOn = CredentialCipherLoader.isPlaintextMode()
        item(
            "cred-no-plaintext",
            hits == 0 || plaintextOn,
            "命中 $hits 处" + (if (firstHit.isEmpty()) "" else "（首个：$firstHit）") +
                if (plaintextOn) {
                    " · ★ 明文模式：磁盘上有明文是**预期行为**（拿到凭据文件就能还原 key）"
                } else {
                    ""
                },
        )

        // ---- ④ 读回来（往返一致；**不打印明文**）----
        var roundTripOk = false
        var roundTripDetail = ""
        try {
            val readBack = credCaps.secretFor(name)
            roundTripOk = readBack == plaintext
            roundTripDetail = if (readBack == null) {
                "读回来是 null（没有这条凭据）"
            } else if (roundTripOk) {
                "读回与写入一致（**未打印明文**）"
            } else {
                "读回与写入**不一致**（**未打印明文**）"
            }
        } catch (e: Throwable) {
            roundTripDetail = "读取失败：${e.javaClass.simpleName}: ${e.message}"
        }
        item("cred-roundtrip", roundTripOk, roundTripDetail)

        // 第二次跑完就清掉：自检不留痕（第一次那份留着，就是为了第二轮能验持久性）。
        if (existed) {
            val deleted = try { credCaps.deleteSecret(name) } catch (e: Throwable) { false }
            item("cred-cleanup", deleted, "已删除自检造的那条凭据=$deleted")
        }

        val pass = results.count { it.second }
        val fail = results.size - pass
        log("SUMMARY", "凭据自检合计 ${results.size} 项：✅ $pass · ❌ $fail")
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
     * 所以验的是真闭环（真后端 + 真模型 + 真历史累积）。
     *
     * ```
     * adb shell am start -n com.agentkernel.demo/.MainActivity \
     *     --es autorun_chat_multi "问题一|问题二|问题三" \
     *     --es gateway_url "https://api.deepseek.com/v1" \
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
     * 运行时注入后端配置（命令行 / 自动化用）。
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
        /**
         * **提示词本体的保险线** —— 与 `client_system_max_chars` 是**两条不同的预算**，别混。
         *
         * - system 段总长（提示词 + 记忆 + 摘要 + 告警）= 服务端参数 `client_system_max_chars`，
         *   默认 6000、**可调**；
         * - **提示词本体 < 2000** = SDK 自己那条保险线（`DevicePromptBudgetTest` 硬断言）——
         *   上限随时可能被调回小值，提示词自己守住这条线，尾巴才不会被砍掉
         *   （砍掉的正好是「能力清单」和「工作区维护规则」）。
         *
         * 自检里**两个数都要显示**，否则会让人以为提示词可以随便加到 6000。
         */
        const val PROMPT_BODY_INSURANCE_LINE = 2000

        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_MODEL = "model"

        /** 思考强度的**强度编码**（不是 key / index —— 那两个换模型就失效）。 */
        const val KEY_STRENGTH = "reasoning_strength"

        /**
         * ★ **已废弃**（v1.1.26 收成单值时留下的**迁移源**）：老版本的思考开关。
         *
         * 现在只用来做**一次性存量迁移**（见 `DemoState.seedStrength`）：
         * 老版本存过 `false` ⇒ 旧行为是「思考关」⇒ 迁移成编码 `1`；
         * 迁移完这个键就被**删掉** ⇒ 本地从此**只存一个编码**（[KEY_STRENGTH]）。
         */
        const val KEY_THINKING_ENABLED = "thinking_enabled"

        /**
         * ★ **明文模式**开关（v1.1.26）：凭据**不加密**落盘。
         *
         * 只在**可调试构建**里可能为 true（见 `DemoState.plaintextModeAvailable`）；
         * 改完要**重启应用**才生效（注册是进程级的）。
         */
        const val KEY_PLAINTEXT_MODE = "plaintext_mode"

        /** `agent-text` 的用法行（与 SDK 能力给的 usage 一字不差）。 */
        const val AGENT_TEXT_USAGE = "agent-text <read <相对路径>|patch <相对路径> <补丁规格>>"
    }
}

/** 自检结果写 logcat（tag 固定，方便脚本抓）。 */
private fun log(tag: String, message: String) {
    android.util.Log.i("AgentSdkDemo", "[$tag] $message")
}

/**
 * 补丁自检的一行日志 —— **机器可读**：`PATCH|名字=OK` / `PATCH|名字=FAIL:原因`。
 *
 * 为什么不用上面那个 [log]：它给的是 `[TAG] 正文`，
 * 而这一组断言要被脚本按「名字=结论」抓 —— 结论必须在**等号后面**，
 * 括号包着就没法直接切分了。
 */
private fun logPatch(name: String, ok: Boolean, detail: String) {
    val body = if (ok) "OK | $detail" else "FAIL:$detail"
    android.util.Log.i("AgentSdkDemo", "PATCH|$name=$body")
}

/**
 * 凭据自检的一行日志 —— **机器可读**：`CRED|名字=OK` / `CRED|名字=FAIL:原因`。
 *
 * 与 [logPatch] 同形但**前缀不同** ⇒ 脚本能只抓凭据那一组。
 */
private fun logCred(name: String, ok: Boolean, detail: String) {
    val body = if (ok) "OK | $detail" else "FAIL:$detail"
    android.util.Log.i("AgentSdkDemo", "CRED|$name=$body")
}

/** 这段字节里有没有 [needle]（自检扫「磁盘上有没有明文」用）。 */
private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
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
 * 真机实测发现：agent 循环跑在 composition scope 上时，
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
     * 真机实测发现：8 轮多轮对话跑完后，activity 重建又跑了一遍 8 轮 ——
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
