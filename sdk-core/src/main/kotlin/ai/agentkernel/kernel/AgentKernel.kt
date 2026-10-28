package ai.agentkernel.kernel

import ai.agentkernel.context.ContextInspector
import ai.agentkernel.context.ContextReport
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.PrefixReport
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.Usage
import ai.agentkernel.tools.ToolRegistry

/**
 * 内核：agent 单轮循环。这是「内核↔API 搭配」的全部内容，保持薄、可测、无 Android 依赖。
 *
 * 循环（对应主流 agent-loop 的 kick→turn→step，但压到最小）：
 *   1. 用当前 messages + tools schema 发请求；
 *   2. 拿回 assistant 消息（可能是文本，也可能带 tool_calls）；
 *   3. 若没有 tool_calls → 收尾，返回 COMPLETED；
 *   4. 若有 → 逐个执行工具：提取参数 → 跑 → 回填 role:"tool" 消息；
 *   5. 回到 1，直到收尾或触顶。
 *
 * 明确**不属于**内核：
 * - 跨轮历史拼接 / 压缩 / system prompt 组装（宿主做，内核只吃拼好的 messages）；
 * - 业务数据加工（宿主/工具自己做）；
 * - 终端交互（无 PTY）。
 *
 * ## 稳定前缀（prefix）与易变尾巴（suffix）
 *
 * [runTurn] 的消息分成三段，顺序**不能随便换**：
 *
 * ```
 * prefix（稳定）  +  history（只增不改）  +  suffix（易变）
 * ```
 *
 * - **prefix**：系统提示词、工作区告警这类**每次都一样**的内容 → 必须放最前；
 * - **history**：对话历史 → 只增不改（改了就把前缀打断）；
 * - **suffix**：本轮临时内容 → 放最后。
 *
 * 这么排是因为上游是**前缀缓存**（DeepSeek Context Caching on Disk）：
 * 只有**逐字相同**的前缀才命中。prefix 里放一个每轮变的数字，
 * 缓存就会从那儿起全部作废 —— 这正是「命中率掉到 0」的典型原因。
 *
 * ⚠️ prefix / suffix **不进返回的会话轨**（[TurnResult.messages] 只有 history + 本轮新增）。
 * 否则它们会被宿主存进历史、下一轮又被拼一次 —— 每轮多一份系统提示词，
 * 前缀从此对不齐，缓存永远命中不了。
 */
class AgentKernel(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val model: String,
    private val maxTurns: Int = 32,
    private val onEvent: (KernelEvent) -> Unit = {},
    /**
     * 每次请求的 token 上限（`max_tokens`）。null = 不带这个字段，由服务端用默认值。
     *
     * ⚠️ 思考型模型会把**推理 token 也算进这个额度**：给小了正文就没了（答案只剩半句）。
     */
    private val maxTokens: Int? = null,
    /** 采样温度。null = 不带。 */
    private val temperature: Double? = null,
    /** 透传给上游的任意 JSON 字段（厂商扩展参数）。 */
    private val extra: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    /**
     * 思考强度编码（发给上游的 `reasoning_strength`）。null = 不带，由服务端用默认档位。
     *
     * 编码是 **2 的幂**（1=关闭思考 / 2=low / 4=medium / 8=high / 16=max），
     * 跨模型稳定 —— 用户存 strength，换模型不用改。
     * 它是**上限**：服务端取「强度 ≤ 你给的值」里该模型能达到的最高档（只向下）。
     * 合法档位要问服务端（[ai.agentkernel.llm.openai.EffortCatalogClient]），别自己抄。
     */
    private val reasoningStrength: Int? = null,
    /**
     * 上下文体检器（逐次请求比对前缀有没有被动过）。
     *
     * 它是「上下文检测」的执行者：命中低的时候，靠它区分
     * 「缓存还没建起来」与「**自己把前缀改坏了**」。
     */
    private val inspector: ContextInspector = ContextInspector(),
    /**
     * 会话标识（发给上游的 `session_id`）。null = 不带。
     *
     * 服务端靠它记住「这个会话上一次请求长什么样」，从而**独立**复核
     * 「这一轮是不是上一轮的追加扩展」—— 也就是上游前缀缓存还剩多少。
     * 服务端也是把 sessionId 随请求带的，据此记住「这个会话上一次请求长什么样」。
     */
    private val sessionId: String? = null,
) {
    /**
     * 跑一轮。
     *
     * @param history 调用方的会话历史（只增不改）。
     * @param prefix 稳定前缀（系统提示词、告警）—— 只发出去，**不进**返回的会话轨。
     * @param suffix 易变尾巴 —— 只发出去，**不进**返回的会话轨。
     */
    suspend fun runTurn(
        history: List<ChatMessage>,
        prefix: List<ChatMessage> = emptyList(),
        suffix: List<ChatMessage> = emptyList(),
    ): TurnResult {
        val transcript = (prefix + history + suffix).toMutableList()
        val appended = mutableListOf<ChatMessage>()
        var turn = 0
        var usage = Usage.ZERO
        var report: ContextReport? = null
        /** 服务端视角的前缀体检报告（最近一次请求的）。 */
        var prefixReport: PrefixReport? = null

        while (true) {
            turn++
            if (turn > maxTurns) {
                return TurnResult(
                    history + appended,
                    TurnStop.MAX_TURNS,
                    lastAssistantText(appended),
                    usage,
                    report,
                    prefixReport,
                    added = appended,
                )
            }

            onEvent(KernelEvent.Request(turn))
            val request = ChatRequest(
                model = model,
                messages = transcript.toList(),
                tools = tools.schemas(),
                temperature = temperature,
                maxTokens = maxTokens,
                reasoningStrength = reasoningStrength,
                extra = extra,
                sessionId = sessionId,
            )
            // 上下文体检：这一轮发出去的，和上一轮比，前缀有没有被动过。
            val ctx = inspector.observe(request.messages)
            report = ctx
            onEvent(KernelEvent.Context(ctx))
            val assistant = llm.complete(request)
            assistant.usage?.let { usage += it }
            // 服务端那一侧的前缀结论（头部是服务端拼的，本地查不到）。
            assistant.prefix?.let { prefixReport = it }
            transcript += assistant
            appended += assistant
            onEvent(KernelEvent.Assistant(assistant))

            if (assistant.toolCalls.isEmpty()) {
                return TurnResult(history + appended, TurnStop.COMPLETED, assistant.content, usage, report, prefixReport, added = appended)
            }

            for (call in assistant.toolCalls) {
                val output = executeOne(call.name, call.argsOrEmpty())
                onEvent(KernelEvent.ToolResult(call.id, output))
                val toolMessage = ToolMessage(toolCallId = call.id, content = output)
                transcript += toolMessage
                appended += toolMessage
            }
        }
    }

    /** 单个工具执行；未知工具回一条可读错误（不打断循环，让模型自行纠正）。 */
    private suspend fun executeOne(name: String, args: kotlinx.serialization.json.JsonObject): String {
        val tool = tools[name] ?: return "error: 未知工具 `$name`，可用工具：${tools.names().joinToString()}"
        return runCatching { tool.execute(args) }
            .getOrElse { "error: 工具 `$name` 执行异常：${it.message}" }
    }

    private fun lastAssistantText(messages: List<ChatMessage>): String? =
        messages.filterIsInstance<AssistantMessage>().lastOrNull()?.content
}
