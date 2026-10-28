package ai.agentkernel.llm

import ai.agentkernel.protocol.PrefixReport
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.Usage

/**
 * 流式 tool_calls 的**按 index 聚合**器。
 *
 * 为什么必须有它（这是 OpenAI 流式 tool_calls 的既定语义）：
 * 流式响应里一次 tool_call 会被拆成多个 delta 分片，每个分片带一个 `index`，
 * id / function.name 可能只在首个分片出现，function.arguments 则被切成若干字符串碎片。
 * 只有把同一 index 的碎片按到达顺序拼起来，arguments 才是合法 JSON。
 *
 * 顺序用 order 列表保持首现次序，不依赖 map 迭代顺序。
 */
class BlockAssembler {
    private val order = mutableListOf<Int>()
    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val args = mutableMapOf<Int, StringBuilder>()
    private val contentBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()
    /** 最后一帧带的 finish_reason（'length' = 被 max_tokens 截断，答案不完整）。 */
    private var finishReason: String? = null
    /** 用量（只在带 usage 的那一帧出现，通常是最后一帧）。 */
    private var usage: Usage? = null
    /** 服务端的前缀体检报告（与 usage 同一帧）。 */
    private var prefix: PrefixReport? = null

    fun accept(delta: StreamDelta) {
        delta.content?.let { contentBuf.append(it) }
        delta.reasoning?.let { reasoningBuf.append(it) }
        delta.finishReason?.let { finishReason = it }
        delta.usage?.let { usage = it }
        delta.prefix?.let { prefix = it }
        val frag = delta.toolCall ?: return

        val i = frag.index
        if (i !in ids) {
            order += i
            ids[i] = ""
            names[i] = ""
            args[i] = StringBuilder()
        }
        frag.id?.let { ids[i] = it }
        frag.name?.let { names[i] = it }
        frag.argumentsFragment?.let { args[i]!!.append(it) }
    }

    fun content(): String? = contentBuf.toString().ifEmpty { null }

    /**
     * 上游的结束原因。
     *
     * **'length' 是要紧信号**：答案被 max_tokens 截断了（思考链把额度用光，
     * 正文只剩半句）。宿主看到它就该重问或缩短，不能把半句当完整回答。
     */
    fun finishReason(): String? = finishReason

    /**
     * 思考模式的思维链（deepseek `reasoning_content`）。
     *
     * 要单独留着，因为带 tool_calls 的回合必须把它原样回传，否则思考链断掉。
     */
    fun reasoning(): String? = reasoningBuf.toString().ifEmpty { null }

    /**
     * 本次请求的 token 用量。
     *
     * 流式里用量在**最后一帧**（`stream_options.include_usage` 才有），
     * 所以它可能比 [finishReason] 晚到一帧 —— 这里取最后一次见到的。
     */
    fun usage(): Usage? = usage

    /**
     * 服务端的前缀体检报告。
     *
     * 与 [usage] 在同一帧（网关把它们一起发），所以取最后一次见到的。
     */
    fun prefix(): PrefixReport? = prefix

    fun toolCalls(): List<ToolCall> = order.map { i ->
        ToolCall(
            id = ids[i].orEmpty(),
            name = names[i].orEmpty(),
            arguments = args[i]!!.toString().ifEmpty { "{}" },
        )
    }
}

/** 一个流式分片：文本增量、思维链增量，和/或 tool_call 增量。 */
data class StreamDelta(
    val content: String? = null,
    /** 思考模式下的思维链增量（deepseek `reasoning_content`）。 */
    val reasoning: String? = null,
    val toolCall: ToolCallDelta? = null,
    /**
     * 上游的结束原因（只出现在最后一帧）。
     *
     * **'length' = 被 max_tokens 截断** —— 内容不完整，宿主必须知道。
     */
    val finishReason: String? = null,
    /**
     * 用量（整份请求的，不是这一帧的）。
     *
     * OpenAI 的形状是把它放在**最后一帧的根上**（`choices` 为空数组），
     * 所以它不属于任何一个 delta —— 单独立一个字段，不跟 content/reasoning 混。
     */
    val usage: Usage? = null,
    /**
     * 服务端的前缀体检报告（挂在**帧的根上**，与 [usage] 同一帧）。
     *
     * 不是 OpenAI 标准字段 —— 只有支持它的网关会回。没有就是没有，不猜。
     */
    val prefix: PrefixReport? = null,
)

/** tool_call 分片：index 是聚合键；其余字段可能只在部分分片里出现。 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String? = null,
)