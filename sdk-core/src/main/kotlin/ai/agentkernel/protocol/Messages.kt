package ai.agentkernel.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat Completions 风格的消息模型。
 *
 * 边界说明（用户口径）：
 * - 内核**不负责**把跨轮历史拼起来、不负责压缩、不负责 system prompt 组装 —— 那些由宿主完成，
 *   内核只接收「已经拼好的 messages」。
 * - 但**协议级**的消息形状（role / tool_calls / tool_call_id）属于内核职责，必须留在这里，
 *   因为它们是和 LLM API 对话的线格式。
 */
sealed interface ChatMessage {
    val role: String
    fun toJson(): JsonObject
}

data class SystemMessage(val content: String) : ChatMessage {
    override val role get() = "system"
    override fun toJson(): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", content)
    }
}

data class UserMessage(val content: String) : ChatMessage {
    override val role get() = "user"
    override fun toJson(): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", content)
    }
}

/**
 * assistant 回合：可能带文本、也可能带 tool_calls（或两者都有）。
 *
 * ## 为什么 `content` 序列化成 "" 而不是 null
 *
 * 照抄 DeepSeek 官方线格式那份**拿 live 流校验过**的序列化器，assistant 的 content
 * **永远给字符串**：
 * - 有些后端直接拒 null；
 * - 「只有思考、既无正文又无 tool_calls」的回合，live API 会 400
 *   （`content or tool_calls must be set`）；而这条消息会留在会话日志里，
 *   一旦是 null 就会把后面每一轮都带崩。
 */
data class AssistantMessage(
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 思考模式下的思维链（deepseek `reasoning_content`）。
     *
     * ## 官方规则：**带 tools 的请求，历史里每一条 assistant 的思维链都要回传**
     *
     * —— 包括**模型那一回合没有调工具**的回合。漏回传 ⇒ 上游 **400**
     * （`The reasoning_content in the thinking mode must be passed back to the API`）。
     *
     * ⚠️ 判据是「**这次请求带不带 tools**」，**不是**「这条 assistant 带没带 tool_calls」——
     * 这两件事不一样，混了就会漏掉「模型先答了一段文字、下一轮才调工具」这种最常见的会话形状。
     *
     * ## 为什么「有就带」而不是「按需带」
     *
     * **不带 tools 的请求回传思维链是无害的**（上游会忽略、不拼进上下文）；
     * 而漏带是要 400 的 ⇒ **宁可多带，不可漏带**。
     * 附带好处：线格式与归档那份（[ChatMessageCodec]）**保持一致**，不会出现
     * 「归档里有思维链、回传时没了」这种两套口径。
     */
    val reasoningContent: String? = null,
    /**
     * 上游的结束原因：`stop` / `tool_calls` / `length`。
     *
     * **'length' = 答案被 max_tokens 截断了**（思考链把额度用光，正文只剩半句）。
     * 这时 `content` 是**不完整**的 —— 宿主看到 'length' 就该知道要重问或缩短，
     * 别把半句当成模型的完整回答。
     */
    val finishReason: String? = null,
    /**
     * 本次请求的 token 用量（命中/未命中缓存都含在里面）。
     *
     * null = 上游没给（老后端、或服务不报用量）。
     * **别把 null 当成「没命中」** —— 那两回事：前者是「不知道」，后者是「确实没命中」。
     */
    val usage: Usage? = null,
    /**
     * **服务端视角**的前缀体检报告（后端回的 `prefix` 字段）。
     *
     * null = 服务端没给（不是 OpenAI 标准字段，只有支持它的后端会回）。
     * 有了它，宿主就能知道「缓存没全命中」是**谁**造成的 ——
     * 服务端拼的头部（平台提示词/技能/工具定义）App 本地看不见。
     * ⚠️ 这是**响应侧**字段，不参与 [toJson]（回传时会原样丢弃）。
     */
    val prefix: PrefixReport? = null,
) : ChatMessage {
    override val role get() = "assistant"
    override fun toJson(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content ?: "")
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray { toolCalls.forEach { add(it.toJson()) } })
        }
        // ★ 思维链**只要有就回传** —— 不管这条 assistant 带没带 tool_calls。
        //   上游的判据是「**请求**带不带 tools」，不是「**这条消息**带没带 tool_calls」
        //   （见 [reasoningContent] 的说明）：按后者判断会漏掉
        //   「模型先答了一段文字、下一轮才调工具」这种形状 ⇒ 上游 400。
        //   空串/纯空白不算「有」—— 解析层已经把空思维链归一成 null（见 BlockAssembler.reasoning）。
        reasoningContent?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
    }
}

/**
 * 工具执行结果回填。toolCallId 必须与触发它的 ToolCall.id 对齐，否则 API 侧会报错。
 */
data class ToolMessage(
    val toolCallId: String,
    val content: String,
) : ChatMessage {
    override val role get() = "tool"
    override fun toJson(): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        // 空输出也要有内容（生产序列化器的做法：'(no output)'）—— 后端对空 tool content 有意见。
        put("content", content.ifEmpty { "(no output)" })
    }
}