package ai.agentkernel.kernel

import ai.agentkernel.context.ContextReport
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.PrefixReport
import ai.agentkernel.protocol.Usage

/** 本轮终止原因（对齐主流 agent 框架的终止 kind，此处只保留单轮相关的最小子集）。 */
enum class TurnStop {
    /** 模型不再请求工具，正常收尾。 */
    COMPLETED,

    /** 达到 maxTurns 上限，被强制打断。 */
    MAX_TURNS,
}

/**
 * 一轮的产出。
 *
 * @param messages 会话音轨 = 本次传入的 history + 本轮新增的 assistant/tool 消息。
 *                 **不含**内核注入的稳定前缀与易变尾巴（那是一次性的脚手架，不是对话）。
 *                 宿主可把它持久化，或喂给下一轮（跨轮拼接由宿主决定，内核不管）。
 * @param stopReason 终止原因。
 * @param finalText 最终 assistant 文本（可能为 null，例如只调工具不说话）。
 * @param usage 本轮**全部**请求的 token 用量之和（含缓存命中数）。
 * @param context 最近一次请求的上下文体检报告（前缀有没有被动过）。
 * @param prefix **服务端视角**的前缀体检报告（后端回的 `prefix`）。
 *   与 [context] 互补：[context] 查本地这段历史，[prefix] 查服务端拼的头部
 *   （平台提示词/技能/工具定义）—— 那一段 App 本地看不见。
 * @param added 本轮**新增**的消息（assistant + 工具回填）。
 *   单独拎出来，是因为 [messages] 里的历史**可能已经被压缩过** ——
 *   这时不能再用「历史条数」去切「本轮新增」（条数对不上）。
 */
data class TurnResult(
    val messages: List<ChatMessage>,
    val stopReason: TurnStop,
    val finalText: String?,
    val usage: Usage? = null,
    val context: ContextReport? = null,
    val prefix: PrefixReport? = null,
    val added: List<ChatMessage> = emptyList(),
) {
    /**
     * 本轮新增的消息（去掉传入 history 的部分）。
     *
     * 压缩场景下 [messages] 的历史部分已经不是传进来的那份了，
     * 所以这里优先用 [added]（内核直接给的），拿不到才退回按条数切。
     */
    fun appended(history: List<ChatMessage>): List<ChatMessage> =
        if (added.isNotEmpty()) added else messages.drop(history.size)
}

/** 内核事件，供宿主做日志 / 流式 UI。 */
sealed interface KernelEvent {
    data class Request(val turn: Int) : KernelEvent

    data class Assistant(val message: AssistantMessage) : KernelEvent

    data class ToolResult(val toolCallId: String, val output: String) : KernelEvent

    /**
     * 上下文体检结果（每次请求一条）。
     *
     * 宿主拿它做「上下文检测」：
     * `report.prefixPreserved == false` → **前缀被改坏了**，缓存从
     * `report.firstChangedIndex` 那条起全废；
     * `report.overBudget == true` → 该裁历史了（裁之前想清楚：裁头 = 前缀重置 = 一次全 miss）。
     */
    data class Context(val report: ContextReport) : KernelEvent
}
