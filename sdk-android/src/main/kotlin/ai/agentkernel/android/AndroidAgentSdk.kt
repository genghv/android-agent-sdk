package ai.agentkernel.android

import ai.agentkernel.android.shizuku.NoopShizukuBridge
import ai.agentkernel.android.shizuku.ShizukuBridge
import ai.agentkernel.archive.ArchiveStore
import ai.agentkernel.context.ContextInspector
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.kernel.KernelEvent
import ai.agentkernel.kernel.TurnResult
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.ReasoningWire
import ai.agentkernel.memory.AgentMemoryCapabilities
import ai.agentkernel.memory.ContextMemory
import ai.agentkernel.memory.ContextPolicy
import ai.agentkernel.memory.MemoryStore
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.prompt.AgentSystemPrompt
import ai.agentkernel.prompt.ShellCommandInventory
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.text.AgentTextCapabilities
import ai.agentkernel.tools.Tool
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.CommandExecutor
import ai.agentkernel.tools.exec.ShellTool
import ai.agentkernel.workspace.WorkspaceGuard
import ai.agentkernel.workspace.WorkspacePolicy
import android.content.Context
import android.util.Log
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
 * - `systemPrompt = "…"` → 用你这段；
 * - `promptFile = …` → 宿主自己的**提示词文件**（替代 [extraNotes]；读不到就退回 [extraNotes]）。
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
     * 客户端 system 段的**字符上限**（**参与协调**，不只是体检）。
     *
     * 实测：有些网关会把客户端 system 段**硬截到固定长度**，超了**悄悄砍尾巴** ——
     * 砍掉的正好是排在最后的那几段（工作区告警、会话摘要就排在那儿）。
     * 直连 DeepSeek 官方没这个上限 → 传 null。
     *
     * ⚠️ **超了不会报错，只会被截**。所以本门面**主动协调**：
     * 砍得动的只有**长期记忆 digest**（它可以用 `agent-memory search` 取回来），
     * 而提示词 / 会话摘要 / 工作区告警**一律不砍** ——
     * 砍了那叫「模型看不见」，不是「少带点」。
     * 连不可砍的部分都超了 ⇒ 走 [SystemBudget.overflow] **报出来**，不再静默。
     *
     * 默认 6000（与我们网关的默认上限一致）；换网关就按它的**实际上限**传。
     */
    private val systemBudgetLimitChars: Int? = DEFAULT_SYSTEM_BUDGET_LIMIT_CHARS,
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
    /**
     * 宿主自己的**提示词文件**（可选）。存在就从文件读，替代 [extraNotes]。
     * 文件超长会被**截断并记一条日志**（提示词超限会被网关静默砍尾巴）。
     *
     * ## 为什么是「文件」而不是又一个字符串参数
     *
     * 宿主自己的提示词（网关地址、内部接口约定、房规…）通常**很长** ——
     * 长到塞进源码里既难读又难改。所以给它一条路径，让宿主能自己维护那份文本。
     *
     * ## 三条规矩
     *
     * - **优先级**：`systemPrompt` > 提示词文件 > [extraNotes] ——
     *   宿主自己拼的提示词是最终版，拼接它只会把提示词改坏；
     * - **读不到不炸**：文件不存在 / 读不了 ⇒ 退回 [extraNotes]（少一段提示词而已）；
     * - **超长会截**：上限是 [systemBudgetLimitChars] 的一半（默认 3000 字符），
     *   截了会在 [SystemBudget.promptFileTruncated] 里**留痕** ——
     *   因为提示词超限只会被网关**静默砍尾巴**，没人知道就等于没发生。
     */
    private val promptFile: File? = null,
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
        else {
            // ★ 容量上限必须接到**真正写盘的那两个实例**上 ——
            //   applyLimits 是实例内的**内存态**（不落盘），接在别的实例上等于没接。
            //   下面这两个是唯一会被 ContextMemory 写进去的实例；
            //   companion 里的 archiveStore(context) 是给宿主翻历史用的**另一个实例**（只读）。
            val store = MemoryStore(File(layout.varDir, MEMORY_DIR))
            store.applyLimits(
                policy.memoryMaxEntryChars,
                policy.memoryMaxTotalChars,
                policy.maxMemoryEntries,
            )
            val archive = ArchiveStore(File(layout.varDir, ARCHIVE_DIR))
            archive.applyLimits(policy.archiveMaxSegments, policy.archiveMaxChars)
            ContextMemory(
                llm = llm,
                model = model,
                store = store,
                policy = policy,
                archive = archive,
            )
        }
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
        //
        // ★ 先**协调字符预算**（唯一能砍的是长期记忆 digest），再拼前缀 ——
        //   协调必须在 prepare() **之前**做：压缩请求要**逐字重放**同一个前缀，
        //   digest 一旦两处不一样，压缩那次调用就吃不到上游缓存。
        val parts = coordinatePrefix()
        val prompt = parts.prompt
        val digest = parts.digest
        val priorSummary = parts.priorSummary
        val alert = parts.alert

        // 压缩请求要**逐字重放**的稳定前缀：和主请求的稳定前缀同源、同样顺序。
        //   上一轮摘要在里面 —— 压缩时它原样重放，就不用再往 user 消息里塞一份。
        val replayPrefix = ArrayList<ChatMessage>(4)
        prompt?.let { replayPrefix += SystemMessage(it) }
        digest?.let { replayPrefix += SystemMessage(it) }
        priorSummary?.let { replayPrefix += SystemMessage(SUMMARY_HEADER + it) }
        alert?.let { replayPrefix += SystemMessage(it) }

        // 已经知道的前缀长度（用来判断这次要不要压）。
        // ⚠️ 告警、摘要标题、**摘要预留位**都**要算进去** —— 少了它们就低估了前缀，
        //    于是「其实超容量了」被算成「没超」，该压的时候不压。
        val knownChars = parts.plannedChars

        // 超容量就在这里**压一次**；闲置到点就在这里**归档成记忆**（然后开新会话）。
        // 压缩请求必须带上**与主请求逐字相同**的工具定义，否则那次调用不吃缓存。
        val plan = memory?.prepare(
            history = messages,
            prefixChars = knownChars,
            replayPrefix = replayPrefix,
            tools = tools.schemas(),
        )

        // ★ 这里拼的才是**真正要发出去**的前缀，顺序同上（摘要换成 plan.summary）。
        val prefix = ArrayList<ChatMessage>(5)
        prompt?.let { prefix += SystemMessage(it) }
        digest?.let { prefix += SystemMessage(it) }
        plan?.summary?.let { prefix += SystemMessage(SUMMARY_HEADER + it) }
        alert?.let { prefix += SystemMessage(it) }

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
     * 会话**归档**仓库（null = 没启用记忆 ⇒ 不会有归档）。
     *
     * 摘要**不删原文** —— 被摘要掉的那一段落成一段归档，段与段之间有链。
     * 前端照常显示历史，就是靠它：
     *
     * ```
     * // 首屏：最新的一段
     * val first = store.list(limit = 1)
     * // 往上滚：游标传当前最早那段，只取更早的一段
     * val older = store.list(limit = 1, before = first.last().id)
     * ```
     *
     * 宿主也可以自己开一个（见 [archiveStore]）—— 路径一样，读的是同一份文件。
     */
    fun archive(): ArchiveStore? = memory?.archiveStore()

    /**
     * 客户端 system 段的**长度体检**（数字与 [runTurn] 实际发出的**同一份**）。
     *
     * 网关会把客户端 system 段**截到固定长度**（实测），超了**悄悄砍尾巴**。
     * 系统提示词本来就占了大半，再加一条告警、摘要、记忆就可能超 ——
     * 超了等于**那几段根本到不了模型**（模型看不见，却没人知道）。
     * 这个数必须看得见。
     *
     * ## 三种状态
     *
     * | 字段 | 含义 |
     * |---|---|
     * | [SystemBudget.totalChars] | 这一轮**实际**会发出去多少字符 |
     * | [SystemBudget.over] | 实际超了 ⇒ 网关会截（要处理） |
     * | [SystemBudget.overflow] | **把记忆砍光也装不下** ⇒ 提示词本身太大（要改配置） |
     *
     * @param limitChars 网关的 system 段上限；null = 不检查（直连官方 API）。
     */
    fun systemBudget(limitChars: Int? = systemBudgetLimitChars): SystemBudget {
        val parts = coordinatePrefix(limitChars)
        return SystemBudget(
            promptChars = parts.promptChars,
            alertChars = parts.alertChars,
            totalChars = parts.totalChars,
            limitChars = limitChars,
            memoryChars = parts.digestChars,
            summaryChars = parts.summaryChars,
            reservedSummaryChars = parts.summaryReserve,
            promptFileTruncated = parts.promptFileTruncated,
        )
    }

    /**
     * 协调这一轮稳定前缀的**字符预算**。
     *
     * 客户端 system 段有上限，而超限的代价是**静默截断** ——
     * 排在最后的那几段（工作区告警、会话摘要、长期记忆）直接消失，模型看不见。
     * 所以必须在**发出去之前**把总长压回上限内，而不是发出去让人截。
     *
     * ## 砍谁
     *
     * | 部分 | 能砍吗 | 为什么 |
     * |---|---|---|
     * | 设备提示词 | ❌ | 砍了模型不知道自己是谁、能干什么 |
     * | 会话摘要 | ❌ | 它是压缩的产物，砍了 = 丢历史 |
     * | 工作区告警 | ❌ | 砍了模型看不到「该清工作区」 |
     * | 长期记忆 digest | ✅ | **可以用 `agent-memory search` 取回** —— 砍掉只是这一轮少带点 |
     *
     * ⇒ **优先砍记忆 digest**：它是唯一可再生的部分。
     *
     * ## 为什么摘要要「**预留**」而不是按现有长度算
     *
     * 摘要**什么时候出现**不由我们决定（压缩触发与否取决于历史长度），
     * 而 digest 的截断必须在 `prepare()` **之前**定下来 ——
     * 否则重放前缀（压缩请求用）与最终前缀（主请求用）里的 digest 不一样，
     * **压缩那次调用就吃不到上游缓存**。
     *
     * ⇒ 只能按 [ContextPolicy.summaryTargetChars] **保守预留**一个摘要位。
     * 这不会浪费预算：预留只在「本来就不够装摘要」时才起作用，
     * 而那种情况下不预留就一定超限。
     */
    private fun coordinatePrefix(limitChars: Int? = systemBudgetLimitChars): PrefixParts {
        val resolved = resolvePrompt()
        val prompt = resolved.prompt
        val alert = workspaceGuard?.alert()
        val mem = memory
        val priorSummary = mem?.sessionSummary()

        // 摘要位：
        //   · 已经有摘要 ⇒ 按它算（取「实际长度」与「目标长度」的**较大者**，
        //     因为下一轮压缩出来的新摘要可能比旧的长）；
        //   · 还没有摘要但压缩开着 ⇒ 保守预留一个目标长度的位；
        //   · 记忆没启用 / 压缩关着 ⇒ 不预留（不可能凭空多出摘要）。
        val summaryReserve = when {
            mem == null -> 0
            priorSummary != null ->
                SUMMARY_HEADER.length + maxOf(priorSummary.length, mem.policy.summaryTargetChars)
            mem.policy.compressionEnabled -> SUMMARY_HEADER.length + mem.policy.summaryTargetChars
            else -> 0
        }

        // 分隔符：网关把多条 system 段用**空行**拼起来，空行也占 2 个字符。
        val plannedSegments =
            (if (prompt != null) 1 else 0) +
                (if (mem != null) 1 else 0) + // 假设记忆会在（预算不够时它会被砍掉）
                (if (summaryReserve > 0) 1 else 0) +
                (if (alert != null) 1 else 0)
        val separatorChars = if (plannedSegments > 0) (plannedSegments - 1) * 2 else 0

        val fixedChars = (prompt?.length ?: 0) + summaryReserve + (alert?.length ?: 0) + separatorChars

        // 记忆能拿多少：剩下的全是它的，但**它自己也有上限**（policy.memoryDigestChars）。
        val digestBudget = when {
            mem == null -> 0
            limitChars == null -> mem.policy.memoryDigestChars
            else -> (limitChars - fixedChars)
                .coerceAtLeast(0)
                .coerceAtMost(mem.policy.memoryDigestChars)
        }

        return PrefixParts(
            prompt = prompt,
            digest = mem?.digest(digestBudget),
            priorSummary = priorSummary,
            alert = alert,
            summaryReserve = summaryReserve,
            promptFileTruncated = resolved.promptFileTruncated,
        )
    }

    /**
     * 本轮稳定前缀的各部分（**协调后**的最终形态）。
     *
     * 内部记账用 —— 宿主要看数字走 [systemBudget]。
     */
    private data class PrefixParts(
        val prompt: String?,
        val digest: String?,
        val priorSummary: String?,
        val alert: String?,
        /** 协调时为「会话摘要」预留的字符数（标题 + 摘要正文）。 */
        val summaryReserve: Int,
        /** 提示词文件被截断了吗？（true = 只用了一部分） */
        val promptFileTruncated: Boolean = false,
    ) {
        val promptChars: Int get() = prompt?.length ?: 0
        val digestChars: Int get() = digest?.length ?: 0
        val summaryChars: Int get() = priorSummary?.let { SUMMARY_HEADER.length + it.length } ?: 0
        val alertChars: Int get() = alert?.length ?: 0

        /** 这一轮**实际**会发出去的总字符数（含分段之间的空行）。 */
        val totalChars: Int get() = joinChars(promptChars, digestChars, summaryChars, alertChars)

        /**
         * 协调后的「保证值」：摘要按**预留位**算。
         *
         * 用它去判「会不会超限」才准 —— 因为摘要可能这一轮才出现。
         */
        val plannedChars: Int
            get() = joinChars(promptChars, digestChars, summaryReserve, alertChars)

        private companion object {
            /**
             * 多段 system 拼起来的总字符数。
             *
             * 网关用**空行**拼 system 段 ⇒ 每多一段就多 2 个字符。
             */
            fun joinChars(vararg lens: Int): Int {
                val kept = lens.filter { it > 0 }
                if (kept.isEmpty()) return 0
                return kept.sum() + (kept.size - 1) * 2
            }
        }
    }

    /** 最终生效的系统提示词；`""` 表示不加。 */
    fun resolveSystemPrompt(): String? = resolvePrompt().prompt

    /**
     * 解析这一轮的提示词（连带「提示词文件被截断了没有」）。
     *
     * ## ★ 优先级（只有一条，别搞反）
     *
     * `systemPrompt`（宿主自己拼的）> **提示词文件** > `extraNotes`。
     *
     * - 宿主自己拼的提示词是**最终版** —— 往里再拼任何东西只会把它改坏；
     * - 提示词文件是宿主自己的**长文本**，存在就顶替 [extraNotes]；
     * - 都没有 ⇒ 用 [extraNotes]。
     */
    private fun resolvePrompt(): PromptResolution {
        systemPrompt?.let { return PromptResolution(it.ifBlank { null }, promptFileTruncated = false) }
        val notes = resolveHostNotes()
        return PromptResolution(
            prompt = buildSystemPrompt(
                executor = executor,
                capabilities = capabilities,
                policy = workspaceGuard?.effectivePolicy()?.first ?: WorkspacePolicy(),
                commandDetail = commandDetail,
                extraNotes = notes.text,
            ),
            promptFileTruncated = notes.truncated,
        )
    }

    /**
     * 宿主补充说明的**最终形态**：提示词文件优先，其次 [extraNotes]。
     *
     * ★ **读不到不炸**：宿主给了个坏路径（没权限 / 还没生成）不该让整个 SDK 起不来 ——
     *   退回 [extraNotes] 就行，提示词少一段而已。
     * ★ **空文件等于没有**：否则一个刚创建的空文件会把宿主写好的 [extraNotes] 顶掉。
     */
    private fun resolveHostNotes(): HostNotes {
        val file = promptFile ?: return HostNotes(extraNotes, truncated = false)
        val raw = runCatching {
            if (!file.isFile) null else file.readText()
        }.getOrNull() ?: return HostNotes(extraNotes, truncated = false)
        val text = raw.trim()
        if (text.isEmpty()) return HostNotes(extraNotes, truncated = false)
        // 截断上限 = system 上限的一半：提示词还要装设备提示词、摘要、告警，
        // 给宿主一半是「它最多能占多少」的直观约定。
        val limit = systemBudgetLimitChars?.div(2)
        // limit <= 0 ⇒ 「不检查上限」⇒ 不截（截成空反而把内容丢了）。
        if (limit == null || limit <= 0 || text.length <= limit) {
            return HostNotes(text, truncated = false)
        }
        Log.w(LOG_TAG, "提示词文件超长（${text.length} 字符），已截到 $limit 字符：${file.name}")
        return HostNotes(text.take(limit), truncated = true)
    }

    /** 提示词解析的结果（正文 + 「提示词文件被截断了没有」）。 */
    private data class PromptResolution(
        val prompt: String?,
        val promptFileTruncated: Boolean,
    )

    /** 宿主补充说明的最终形态（正文 + 「被截断了没有」）。 */
    private data class HostNotes(
        val text: String,
        val truncated: Boolean,
    )

    companion object {
        /** 日志 tag（本门面自己打的那几条警告）。 */
        private const val LOG_TAG = "AgentKernelSdk"

        /** 记忆在 agent 私有根下的目录名（`<filesDir>/agent/var/memory/`）。 */
        const val MEMORY_DIR = "memory"

        /**
         * 客户端 system 段的**默认**字符上限。
         *
         * 与我们网关的 `client_system_max_chars` 默认值一致（6000）。
         *
         * ★ **别再改回 2000**：真机设备提示词本身就占 1883 字符，老值 2000 会把
         * 长期记忆 digest / 会话摘要 / 工作区告警**全砍掉** ——
         * 而网关不报错（只是悄悄砍尾巴）⇒ agent 写了记忆、模型压根没看见。
         *
         * 网关那一侧的值是**可调参数**（后端 AI 用 `ai_gateway_set` 改，3 秒生效）。
         * 换网关就按它的**实际上限**传 [AndroidAgentSdk.systemBudgetLimitChars]。
         */
        const val DEFAULT_SYSTEM_BUDGET_LIMIT_CHARS = 6000

        /** 会话归档在 agent 私有根下的目录名（`<filesDir>/agent/var/archive/`）。 */
        const val ARCHIVE_DIR = "archive"

        /**
         * 打开会话归档仓库（`<filesDir>/agent/var/archive/`）。
         *
         * 宿主/前端自己开一个，用来翻历史 —— 和 SDK 内部写的**是同一份文件**
         * （路径由同一个常量算出来，不会各写各的）。
         *
         * ```
         * val store = AndroidAgentSdk.archiveStore(context)
         * val latest = store.list(limit = 1).firstOrNull()
         * ```
         */
        fun archiveStore(context: Context): ArchiveStore =
            ArchiveStore(File(PrefixLayout(context.filesDir.path).varDir, ARCHIVE_DIR))

        /**
         * 长期记忆命令（`agent-memory`）—— 宿主组装能力表时用这个。
         *
         * 为什么给工厂、而不是让宿主自己拼路径：记忆目录**必须**和 SDK 内部那份
         * [ContextMemory] 用的是同一个（`<filesDir>/agent/var/memory/`）。
         * 宿主自己拼 `PrefixLayout(...)` 的话，一旦改了 agent 根目录名就对不上 ——
         * 对不上的后果是「agent 写进 A 目录、SDK 从 B 目录读」，
         * 表现成「明明写了记忆，却一条都不进提示词」，极难查。
         *
         * ```
         * val caps = DeviceCapabilityRegistry(
         *     defaultDeviceCapabilities(context) +
         *         AndroidAgentSdk.memoryCapabilities(context).capabilities
         * )
         * ```
         *
         * ⚠️ 记忆能力**只在宿主启用了记忆（`memoryPolicy`）时才有意义**：
         * 宿主没启用记忆的话，agent 写进去的记忆没人读（不会进提示词），
         * 纯属白写。所以别无条件注册。
         */
        fun memoryCapabilities(context: Context): AgentMemoryCapabilities =
            AgentMemoryCapabilities(
                MemoryStore(File(PrefixLayout(context.filesDir.path).varDir, MEMORY_DIR))
            )

        /**
         * 文本编辑能力（`agent-text`）的工厂。
         *
         * 路径与 SDK 内部一致（agent 私有根）—— 理由和 [memoryCapabilities] 一样：
         * 宿主自己拼路径的话，一旦改了 agent 根目录名就对不上，
         * 对不上的后果是「agent 改的是 A 目录、宿主看的是 B 目录」。
         *
         * ```
         * val caps = DeviceCapabilityRegistry(
         *     defaultDeviceCapabilities(context, text = AndroidAgentSdk.textCapabilities(context))
         * )
         * ```
         *
         * ★ 沙箱根 = agent 私有根（含 `var/memory/`、`var/archive/`）。
         * 这条能力**只在这个根下面**生效，`..`、绝对路径、符号链接逃逸一律拒绝。
         */
        fun textCapabilities(context: Context): AgentTextCapabilities =
            AgentTextCapabilities(File(PrefixLayout(context.filesDir.path).agentRoot))

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
            /**
             * 思考强度编码（2 的幂，见 [ai.agentkernel.llm.openai.ReasoningStrength]）。
             *
             * null = **不带**这个字段，由对端用它自己的默认档位。
             */
            reasoningStrength: Int? = null,
            /**
             * 思考强度的**线格式**（见 [ai.agentkernel.llm.openai.ReasoningWire]）。
             *
             * 默认按地址自动选：DeepSeek 官方 ⇒ 官方形状（`thinking`）；
             * 其它地址（自建网关）⇒ 网关形状（`reasoning_strength`）。
             */
            reasoningWire: ReasoningWire = if (baseUrl == ChatCompletionsClient.DEEPSEEK_BASE_URL) {
                ReasoningWire.DEEPSEEK
            } else {
                ReasoningWire.GATEWAY
            },
            onEvent: (KernelEvent) -> Unit = {},
        ): AndroidAgentSdk {
            val executor = if (capabilities == null) {
                AndroidCommandExecutor.forContext(context)
            } else {
                AndroidCommandExecutor.forContext(context, capabilities)
            }
            return AndroidAgentSdk(
                llm = ChatCompletionsClient.deepSeek(
                    apiKey = apiKey,
                    transport = OkHttpTransport(),
                    baseUrl = baseUrl,
                    reasoningWire = reasoningWire,
                ),
                model = model,
                executor = executor,
                maxTurns = maxTurns,
                onEvent = onEvent,
                maxTokens = maxTokens,
                temperature = temperature,
                commandDetail = commandDetail,
                memoryPolicy = memoryPolicy,
                reasoningStrength = reasoningStrength,
            )
        }
    }
}

/**
 * 客户端 system 段的**长度体检**（见 [AndroidAgentSdk.systemBudget]）。
 *
 * 超了不会报错 —— 只会被网关**悄悄截断**，而截掉的那段（工作区告警、会话摘要、
 * 长期记忆）模型就再也看不到了。所以这个数必须**看得见**，
 * 别让它变成一个没人发现的静默故障。
 *
 * @param promptChars 系统提示词的字符数。
 * @param alertChars 工作区告警的字符数（没有告警时为 0）。
 * @param totalChars 这一轮**实际**会发出去的总字符数（含分段之间的空行）。
 * @param limitChars 网关的 system 段上限；null = 不知道上限（直连官方 API）。
 * @param memoryChars 长期记忆 digest 的字符数（协调后实际带上的）。
 * @param summaryChars 会话摘要（标题 + 正文）的字符数。
 * @param reservedSummaryChars 协调时为「会话摘要」**预留**的字符数
 *   （摘要可能这一轮才出现，所以要提前留位）。
 * @param promptFileTruncated 提示词文件被截断了吗？（true = 内容超了上限，只用了一部分）。
 */
data class SystemBudget(
    val promptChars: Int,
    val alertChars: Int,
    val totalChars: Int,
    val limitChars: Int?,
    /** 长期记忆 digest 的字符数（协调后实际带上的）。 */
    val memoryChars: Int = 0,
    /** 会话摘要（标题 + 正文）的字符数。 */
    val summaryChars: Int = 0,
    /** 协调时为「会话摘要」预留的字符数（摘要可能这一轮才出现，所以要提前留位）。 */
    val reservedSummaryChars: Int = 0,
    /** 提示词文件被截断了吗？（true = 内容超了上限，只用了一部分） */
    val promptFileTruncated: Boolean = false,
) {
    /** 是否会超限（按**实际**发出的字符数算）。 */
    val over: Boolean get() = limitChars != null && totalChars > limitChars

    /** 还剩多少字符（null = 不知道上限）。 */
    val remainingChars: Int? get() = limitChars?.let { it - totalChars }

    /**
     * **把长期记忆砍光也装不下**吗？
     *
     * true = 提示词 / 摘要 / 告警这三样加起来就超了 ——
     * 这不是「记忆太多」，是**提示词本身太大**，要改配置（换 `commandDetail`、
     * 精简 `extraNotes`、或把上限调大）。
     *
     * ⚠️ 与 [over] 的区别：[over] 可能只是记忆挤掉了（SDK 已经优先砍它）；
     * [overflow] 是**砍完还超** —— 这时候得有人动手改配置。
     */
    val overflow: Boolean get() = limitChars != null && (totalChars - memoryChars) > limitChars

    /**
     * 协调后的**保证值**：摘要按预留位算。
     *
     * 判「会不会超限」用这个更准 —— 摘要可能这一轮才出现，
     * 按实际长度算会低估。
     */
    val plannedChars: Int get() = totalChars - summaryChars + reservedSummaryChars
}
