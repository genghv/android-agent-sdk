package ai.agentkernel.android

import ai.agentkernel.android.shizuku.NoopShizukuBridge
import ai.agentkernel.android.shizuku.ShizukuBridge
import ai.agentkernel.context.ContextInspector
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.kernel.KernelEvent
import ai.agentkernel.kernel.TurnResult
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.memory.ContextMemory
import ai.agentkernel.memory.ContextPolicy
import ai.agentkernel.memory.MemoryStore
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.prompt.AgentSystemPrompt
import ai.agentkernel.prompt.ShellCommandInventory
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.tools.Tool
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.CommandExecutor
import ai.agentkernel.tools.exec.ShellTool
import ai.agentkernel.workspace.WorkspaceGuard
import ai.agentkernel.workspace.WorkspacePolicy
import android.content.Context
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 门面：一行把「内核 + 设备能力」组装好。
 *
 * 典型用法：
 * ```
 * val sdk = AndroidAgentSdk(
 *     llm = ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()),
 *     model = "deepseek-chat",
 *     executor = AndroidCommandExecutor.forContext(context),
 * )
 * val assistant = sdk.register(MyAndroidTool()).runTurn(
 *     listOf(SystemMessage("你是手机上的智能体"), UserMessage("看下当前目录有什么"))
 * )
 * ```
 *
 * [executor] 必填：命令执行器要知道「拟 Linux 根」在宿主 App 的哪个目录，
 * 这只能由宿主的 Context 给出（见 [AndroidCommandExecutor.forContext]），
 * 写死路径对任何真实宿主都是错的。
 *
 * ## 系统提示词
 *
 * 模型看不见这台手机 —— 它不知道自己在哪儿、工作目录是哪儿、哪些命令能用、
 * 哪些事做不了。所以本门面**默认会补一段系统提示词**（见 [AgentSystemPrompt]）：
 * 路径、可用命令清单、能力边界、工作区维护规则，一次讲清楚。
 *
 * - `systemPrompt = null`（默认）→ 用生成的；
 * - `systemPrompt = ""` → **不加**（你自己拼）；
 * - `systemPrompt = "…"` → 用你这段。
 *
 * 注意：history（含历史 / 压缩结果）仍由**调用方**拼好传入，这不是内核职责。
 */
class AndroidAgentSdk(
    private val llm: LlmClient,
    model: String,
    private val executor: CommandExecutor,
    private val shizuku: ShizukuBridge = NoopShizukuBridge(),
    /**
     * 设备能力表（可选）。
     *
     * 默认从 [executor] 里自动取（它是 [AndroidCommandExecutor] 时），
     * 这样「能力能执行」和「模型看得见能力」永远是同一份，不会对不上。
     */
    private val capabilities: DeviceCapabilityRegistry? = (executor as? AndroidCommandExecutor)?.capabilities,
    maxTurns: Int = 32,
    onEvent: (KernelEvent) -> Unit = {},
    /** 系统提示词；null = 用生成的，"" = 不加。 */
    private val systemPrompt: String? = null,    /**
     * 工作区守卫（可选）。
     *
     * 传了之后，**每一轮开始前**都会检查工作区占用；
     * 接近或超过配额时，把一条可执行的告警作为 system 消息插进这一轮 ——
     * 让 agent 自己看到、自己去清理（而不是去替它删东西）。
     */
    private val workspaceGuard: WorkspaceGuard? = null,
    /** 宿主补充说明（内部接口约定、网关地址等），会拼进默认系统提示词。 */
    private val extraNotes: String = "",
    /**
     * 每次请求的 token 上限（`max_tokens`）。null = 不带这个字段。
     *
     * ⚠️ **思考型模型会把推理 token 也算进这个额度** —— 给小了正文就只剩半句。
     * 想省 token 就给个够正文写完的数（如 2048），别给几十。
     */
    private val maxTokens: Int? = null,
    /** 采样温度。null = 不带。 */
    private val temperature: Double? = null,
    /**
     * 透传给上游的任意 JSON 字段（厂商扩展参数，例如自定义的思考开关）。
     *
     * OpenAI 线格式之外的参数走这里；**同名标准参数不要往这儿塞**
     * （`max_tokens` / `temperature` / `tools` 已经有专门的入参）。
     */
    private val extraRequest: JsonObject = JsonObject(emptyMap()),
    /**
     * 系统提示词里的命令清单写多少。
     *
     * 默认 [AgentSystemPrompt.CommandDetail.FULL]（**完整清单**）——
     * 直连 DeepSeek 官方这类服务没有 system 段字数上限，列全更有用。
     *
     * 如果你的网关对客户端 system 段有字数上限（实测有网关硬截断到 2000 字符），
     * 就显式传 [AgentSystemPrompt.CommandDetail.CORE] —— 瘦身后装得下，
     * 完整清单仍在技能文档里按需查。
     */
    private val commandDetail: AgentSystemPrompt.CommandDetail = AgentSystemPrompt.CommandDetail.FULL,
    /**
     * 思考强度编码（发给上游的 `reasoning_strength`）。null = 不带，由服务端用默认档位。
     *
     * 编码是 **2 的幂**（1=关闭思考 / 2=low / 4=medium / 8=high / 16=max），
     * 跨模型稳定 —— 用户存 strength，换模型不用改。
     * 它是**上限**：服务端取「强度 ≤ 你给的值」里该模型能达到的最高档（只向下）。
     *
     * ⚠️ **合法档位要问服务端**（`EffortCatalogClient` 拉清单），别自己抄一份 ——
     * `deepseek-flash` 就没有 medium，抄错了用户会选到一个发出去就 400 的档。
     */
    private val reasoningStrength: Int? = null,
    /**
     * 客户端 system 段的长度上限（**只用于体检**，不参与截断）。
     *
     * 实测：有些服务会把客户端 system 段**硬截到 2000 字符**，超了悄悄砍尾巴 ——
     * 砍掉的正好是排在最后的那段（工作区告警就排在那儿）。
     * 直连 DeepSeek 官方没这个上限 → 传 null。
     */
    private val systemBudgetLimitChars: Int? = 2000,
    /**
     * 上下文体检器（比对「这一轮发出去的」和「上一轮发出去的」）。
     *
     * ★ **要跨轮检测，宿主必须复用同一个实例** ——
     * 换句话说：**这个对象属于宿主，不属于某一次对话**。
     *
     * 原因：前缀缓存断在**轮与轮之间**（上一轮的历史被改了就全废）。
     * 如果每轮新建一个检测器，它每次都以为「这是第一次请求」，
     * 于是永远报「前缀完好」——**该报的漂移一个都发现不了**。
     *
     * ```
     * // 宿主持有，跨轮复用
     * private val inspector = ContextInspector()
     * val sdk = AndroidAgentSdk(..., inspector = inspector)
     * ```
     */
    private val inspector: ContextInspector = ContextInspector(),
    /**
     * 会话标识（发给网关的 `session_id`）。null = 不带。
     *
     * 服务端靠它记住「这个会话上一次请求长什么样」，从而**独立**复核
     * 「这一轮是不是上一轮的追加扩展」—— 也就是上游前缀缓存还剩多少。
     * 换句话说：[inspector] 查的是「**调用方**有没有改坏前缀」，
     * 这个 id 让服务端能查「**服务端**有没有改坏前缀」（平台提示词、技能、工具定义）。
     *
     * 一个会话一个 id，**换会话要换 id**（否则两段对话会被当成一段，报告失真）。
     */
    private val sessionId: String? = null,
    /**
     * 自带的 [ContextMemory]（上下文压缩 + 长期记忆）。null = 用 [memoryPolicy] 自动建一个。
     */
    memory: ContextMemory? = null,
    /**
     * 上下文压缩与长期记忆的**开关 + 旋钮**。
     *
     * 传一个 [ContextPolicy] 就启用；null = 不启用（历史只增不减）。
     *
     * - 启用后：**请求超过容量**会自动调一次模型把旧历史压成摘要，
     *   摘要进**稳定前缀**；**闲置超过阈值**会把这一段会话归档成长期记忆（落盘）。
     * - 记忆落在 `<filesDir>/agent/var/memory/` —— 普通文件，
     *   agent 用已有的终端能力 `cat` 就能读，**不需要新工具**。
     * - 全部旋钮见 [ContextPolicy]（容量、保留长度、闲置时长、记忆预算……）。
     */
    memoryPolicy: ContextPolicy? = null,
) {
    private val tools = ToolRegistry(listOf(ShellTool(executor, capabilities = capabilities)))

    /**
     * 上下文压缩 + 长期记忆的执行者（null = 没启用）。
     *
     * ★ **要跨轮复用同一个实例** —— 它持有「本会话的压缩摘要」和「上次活动时刻」，
     * 换实例等于把压缩状态和闲置计时都清零。
     */
    private val memory: ContextMemory? = memory ?: memoryPolicy?.let { policy ->
        val layout = (executor as? AndroidCommandExecutor)?.layout
        if (layout == null) null
        else ContextMemory(
            llm = llm,
            model = model,
            store = MemoryStore(File(layout.varDir, MEMORY_DIR)),
            policy = policy,
        )
    }

    /**
     * 这台设备上**真实存在**的系统命令清单（只探测一次）。
     *
     * 提示词里列的命令必须是真的 —— 列一个这台机器上没有的命令，
     * 模型就会一次次去试，每次失败还要在工作区里留垃圾。
     * toybox 的子命令集合跟系统版本走，所以清单只能在运行时探，不能写死。
     *
     * 故意**不探** `PREFIX/bin`（拟 Linux 根）：它在宿主 App 的私有目录里，
     * 而 Android 对私有目录是 noexec 的 —— 那里的 ELF 执行不了（真机实测 exit=126）。
     * 列进去就是骗模型。
     */
    private val shellInventory: ShellCommandInventory.Result get() = systemShellInventory()

    private val kernel = AgentKernel(
        llm = llm,
        tools = tools,
        model = model,
        maxTurns = maxTurns,
        onEvent = onEvent,
        maxTokens = maxTokens,
        temperature = temperature,
        extra = extraRequest,
        reasoningStrength = reasoningStrength,
        inspector = inspector,
        sessionId = sessionId,
    )

    /** 注册额外工具（如 Android 结构化操作、无障碍、浏览器等）。 */
    fun register(tool: Tool): AndroidAgentSdk {
        tools.register(tool)
        return this
    }

    /** 暴露当前特权桥，供工具按需调用。 */
    fun shizuku(): ShizukuBridge = shizuku

    /** 暴露工作区守卫，供宿主自己查占用。 */
    fun workspace(): WorkspaceGuard? = workspaceGuard

    /**
     * 跑一轮。
     *
     * 消息顺序：**稳定前缀（系统提示词 → 工作区告警）→ 调用方给的历史**。
     *
     * ## ★ 告警为什么在**前缀**里，不在历史末尾
     *
     * 上游是**前缀缓存**（DeepSeek Context Caching on Disk）：只有**逐字相同**的前缀才命中。
     * 告警如果放在历史末尾，它每轮都落在「旧历史」与「新内容」的交界处 ——
     * 那一轮的缓存从它开始就全废了，命中率直接从 99% 掉到个位数。
     *
     * 放进前缀、并且内容**逐字稳定**（见 [WorkspaceGuard.alert]：不写每轮变的数字），
     * 它就只是前缀的一部分，永远命中；而「该清工作区」是条**常驻规则**，
     * 放在最前面语义上也没问题。
     *
     * ## ★ 注入的内容**不进**返回的会话轨
     *
     * [TurnResult.messages] 只有「调用方的历史 + 本轮新增」。
     * 否则宿主把它存回历史后，下一轮又拼一次系统提示词 ——
     * 每轮多一份 1800 字符的提示词，前缀从此对不齐，缓存永远命中不了。
     */
    suspend fun runTurn(messages: List<ChatMessage>): TurnResult {
        // ★ 稳定前缀的**顺序是固定的**：提示词 → 长期记忆 → 会话摘要 → 工作区告警。
        //   顺序一变 = 前缀变了 = 缓存全废。
        val prompt = resolveSystemPrompt()
        val digest = memory?.digest()
        // 已经知道的前缀长度（用来判断这次要不要压）。
        val knownChars = (prompt?.length ?: 0) + (digest?.length ?: 0) +
            (memory?.sessionSummary()?.length ?: 0)

        // 超容量就在这里**压一次**；闲置到点就在这里**归档成记忆**（然后开新会话）。
        val plan = memory?.prepare(messages, prefixChars = knownChars)

        val prefix = ArrayList<ChatMessage>(5)
        prompt?.let { prefix += SystemMessage(it) }
        digest?.let { prefix += SystemMessage(it) }
        plan?.summary?.let { prefix += SystemMessage(SUMMARY_HEADER + it) }
        workspaceGuard?.alert()?.let { prefix += SystemMessage(it) }

        // plan.history 可能已经压缩过 / 归档过。
        // 宿主把 result.messages 存回去，存的就是**压缩后**的那份 —— 历史不会越滚越大。
        val history = plan?.history ?: messages
        return kernel.runTurn(history, prefix = prefix)
    }

    /**
     * 上下文压缩 + 长期记忆的执行者；null = 没启用。
     *
     * 宿主可以拿它做**手动**归档（比如用户按了「这个话题结束了」）：
     * ```
     * sdk.memory()?.archiveNow(history)
     * ```
     * 也能拿它的 [ContextMemory.memoryStore] 去翻归档里的旧记忆。
     */
    fun memory(): ContextMemory? = memory

    /**
     * 客户端 system 段的**长度体检**。
     *
     * 网关会把客户端 system 段**截到 2000 字符**（实测），超了**悄悄砍尾巴**。
     * 系统提示词本来就占了大半，再加一条告警就可能超 ——
     * 超了等于**告警根本到不了模型**（模型看不到那条告警，却没人知道）。
     * 这个数必须看得见。
     *
     * @param limitChars 网关的 system 段上限；null = 不检查。
     */
    fun systemBudget(limitChars: Int? = systemBudgetLimitChars): SystemBudget {
        val promptChars = resolveSystemPrompt()?.length ?: 0
        val alertChars = workspaceGuard?.alert()?.length ?: 0
        // 网关把多条 system 段用空行拼起来，所以分隔符也占地方。
        val total = promptChars + alertChars + if (alertChars > 0) 2 else 0
        return SystemBudget(promptChars, alertChars, total, limitChars)
    }

    /** 最终生效的系统提示词；`""` 表示不加。 */
    fun resolveSystemPrompt(): String? {
        systemPrompt?.let { return it.ifBlank { null } }
        return buildSystemPrompt(
            executor = executor,
            capabilities = capabilities,
            policy = workspaceGuard?.effectivePolicy()?.first ?: WorkspacePolicy(),
            commandDetail = commandDetail,
            extraNotes = extraNotes,
        )
    }

    companion object {
        /** 记忆在 agent 私有根下的目录名（`<filesDir>/agent/var/memory/`）。 */
        const val MEMORY_DIR = "memory"

        /** 会话摘要进前缀时的标题。 */
        private const val SUMMARY_HEADER = "## 之前的对话摘要（压缩后的历史）\n"

        private val SYSTEM_SHELL_INVENTORY: ShellCommandInventory.Result by lazy {
            ShellCommandInventory.available(listOf(File("/system/bin"), File("/system/xbin")))
        }

        /** 这台设备上真实存在的系统命令清单（全局只探一次）。 */
        fun systemShellInventory(): ShellCommandInventory.Result = SYSTEM_SHELL_INVENTORY

        /**
         * **不接模型**也能拿到「模型会看到的那份系统提示词」。
         *
         * 宿主想在界面上展示、调试、核对它时用 —— 比如确认清单里列的命令
         * 在这台设备上真的存在、路径对不对。
         *
         * @param commandDetail 命令清单写多少（默认 [AgentSystemPrompt.CommandDetail.FULL]，
         *   即完整清单；只有当你的网关对 system 段有字数上限时才改用
         *   [AgentSystemPrompt.CommandDetail.CORE]）
         * @return null = 拿不到布局（执行器不是 [AndroidCommandExecutor]）
         */
        fun buildSystemPrompt(
            executor: CommandExecutor,
            capabilities: DeviceCapabilityRegistry? = (executor as? AndroidCommandExecutor)?.capabilities,
            policy: WorkspacePolicy? = null,
            commandDetail: AgentSystemPrompt.CommandDetail = AgentSystemPrompt.CommandDetail.FULL,
            extraNotes: String = "",
        ): String? {
            val layout = (executor as? AndroidCommandExecutor)?.layout ?: return null
            return AgentSystemPrompt.build(
                layout = layout,
                capabilities = capabilities,
                policy = policy,
                shellInventory = SYSTEM_SHELL_INVENTORY,
                commandDetail = commandDetail,
                extraNotes = extraNotes,
            )
        }

        /**
         * **一行接上 DeepSeek 官方**（不需要自建网关）。
         *
         * 这是**面向外部使用者**的入口：地址用 DeepSeek 官方、鉴权用 `Authorization: Bearer`、
         * 执行器按宿主 Context 装配、系统提示词由 SDK 自己生成（**完整自带**，
         * 不依赖任何后端提示词注入）。
         *
         * ```
         * val sdk = AndroidAgentSdk.deepSeek(
         *     context = context,
         *     apiKey = BuildConfig.DEEPSEEK_KEY,   // 只在宿主 App 里存，绝不打包进 SDK
         * )
         * val result = sdk.runTurn(listOf(UserMessage("看下当前目录有什么")))
         * ```
         *
         * @param model DeepSeek 模型名。默认 `deepseek-chat`（不思考、快）；
         *   想要推理能力就用 `deepseek-reasoner`（⚠️ 推理 token 也吃 `max_tokens` 额度）。
         */
        fun deepSeek(
            context: Context,
            apiKey: String,
            model: String = "deepseek-chat",
            baseUrl: String = ChatCompletionsClient.DEEPSEEK_BASE_URL,
            capabilities: DeviceCapabilityRegistry? = null,
            maxTokens: Int? = null,
            temperature: Double? = null,
            maxTurns: Int = 32,
            commandDetail: AgentSystemPrompt.CommandDetail = AgentSystemPrompt.CommandDetail.FULL,
            /**
             * 上下文压缩 + 长期记忆的旋钮。传了就启用；null = 不启用。
             *
             * ```
             * val sdk = AndroidAgentSdk.deepSeek(
             *     context = context,
             *     apiKey = key,
             *     memoryPolicy = ContextPolicy(maxContextChars = 24_000),
             * )
             * ```
             */
            memoryPolicy: ContextPolicy? = null,
            onEvent: (KernelEvent) -> Unit = {},
        ): AndroidAgentSdk {
            val executor = if (capabilities == null) {
                AndroidCommandExecutor.forContext(context)
            } else {
                AndroidCommandExecutor.forContext(context, capabilities)
            }
            return AndroidAgentSdk(
                llm = ChatCompletionsClient.deepSeek(apiKey = apiKey, transport = OkHttpTransport(), baseUrl = baseUrl),
                model = model,
                executor = executor,
                maxTurns = maxTurns,
                onEvent = onEvent,
                maxTokens = maxTokens,
                temperature = temperature,
                commandDetail = commandDetail,
                memoryPolicy = memoryPolicy,
            )
        }
    }
}

/**
 * 客户端 system 段的**长度体检**（见 [AndroidAgentSdk.systemBudget]）。
 *
 * 超了不会报错 —— 只会被网关**悄悄截断**，而截掉的那段（工作区告警）模型就再也看不到了。
 * 所以这个数必须**看得见**，别让它变成一个没人发现的静默故障。
 */
data class SystemBudget(
    /** 系统提示词的字符数。 */
    val promptChars: Int,
    /** 工作区告警的字符数（没有告警时为 0）。 */
    val alertChars: Int,
    /** 两条拼起来（算上分隔用的空行）的总字符数。 */
    val totalChars: Int,
    /** 网关的 system 段上限；null = 不知道上限（直连官方 API）。 */
    val limitChars: Int?,
) {
    /** 是否会超限。 */
    val over: Boolean get() = limitChars != null && totalChars > limitChars

    /** 还剩多少字符（null = 不知道上限）。 */
    val remainingChars: Int? get() = limitChars?.let { it - totalChars }
}
