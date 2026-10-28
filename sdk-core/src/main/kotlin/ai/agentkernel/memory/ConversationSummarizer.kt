package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage

/**
 * 把一段对话**压成一段稳定摘要** —— 靠真的调一次模型 API。
 *
 * ## 为什么摘要要「稳定」
 *
 * 摘要最终会待在请求的**最前面**（稳定前缀里）。上游是前缀缓存：
 * 只有**逐字相同**的前缀才命中。所以摘要里：
 *
 * - **不能**有时间戳（「今天下午 3 点」→ 每轮都不一样 → 前缀每轮断）；
 * - **不能**有对话痕迹（「用户说…」「助手回答…」→ 纯噪音，且占字符）；
 * - **不能**有每轮变的数字。
 *
 * 只写**事实 / 结论 / 决定 / 待办 / 偏好**这五类 —— 它们在一段对话内是不变的。
 *
 * ## 成本
 *
 * 每次压缩 = **一次额外的模型请求**。这是有意的：
 * 压缩发生在「上下文已经太长」的少数时刻，而不是每轮都做。
 */
class ConversationSummarizer(
    private val llm: LlmClient,
    private val model: String,
    /** 摘要请求的 token 上限；null = 用服务端默认值。 */
    private val maxTokens: Int? = 1_024,
    /** 摘要请求要低一点 —— 摘要不该发散。null = 用服务端默认值。 */
    private val temperature: Double? = 0.2,
) {

    /**
     * 压一次。
     *
     * @param messages 要压缩的对话（旧的那些）。
     * @param targetChars 摘要的目标长度（写进提示词当软约束）。
     * @param previousSummary 上一次压缩产出的摘要（如果有）。
     *   **它会一起参与压缩** —— 否则第二次压缩会把第一次压掉的内容彻底丢掉。
     * @return 摘要正文；失败时返回 null（调用方要能容忍压缩失败）。
     */
    suspend fun summarize(
        messages: List<ChatMessage>,
        targetChars: Int,
        previousSummary: String? = null,
    ): String? {
        if (messages.isEmpty() && previousSummary.isNullOrBlank()) return null

        val instruction = SystemMessage(buildInstruction(targetChars))
        val body = buildString {
            if (!previousSummary.isNullOrBlank()) {
                appendLine("【已有的摘要】")
                appendLine(previousSummary.trim())
                appendLine()
            }
            appendLine("【要压缩的对话】")
            append(render(messages))
        }

        val request = ChatRequest(
            model = model,
            messages = listOf(instruction, UserMessage(body)),
            tools = emptyList(),           // 摘要不需要工具
            temperature = temperature,
            maxTokens = maxTokens,
        )
        return runCatching { llm.complete(request) }
            .getOrNull()
            ?.let { it.content?.trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun buildInstruction(targetChars: Int): String = """
        你在把一段对话压成**稳定的摘要**。

        这份摘要之后会作为「历史」的替身，放在每次请求的最前面。
        所以它必须**逐字稳定**：
        - 不写时间戳、不写「用户说 / 助手回答」这类对话痕迹；
        - 不写寒暄、不写客套；
        - 不写每轮会变的状态。

        只写下面这五类，每条一行，写不出来的不写：
        - 事实：用户提到的具体信息（名字、路径、数值、配置）
        - 结论：已经确定下来的判断或结果
        - 决定：定下来的做法，以及被否掉的做法
        - 待办：还没做完的事
        - 偏好：用户的习惯、口径、忌讳

        控制在 $targetChars 字符以内。只输出摘要正文，不要任何解释或标题。
    """.trimIndent()

    /** 把消息渲染成纯文本。 */
    private fun render(messages: List<ChatMessage>): String = buildString {
        for (m in messages) {
            when (m) {
                is UserMessage -> appendLine("[用户] ${m.content}")
                is AssistantMessage -> {
                    m.content?.takeIf { it.isNotBlank() }?.let { appendLine("[助手] $it") }
                    if (m.toolCalls.isNotEmpty()) {
                        appendLine("[助手] 调用工具：${m.toolCalls.joinToString { it.name }}")
                    }
                }
                is ToolMessage -> appendLine("[工具结果] ${m.content.take(400)}")
                else -> Unit
            }
        }
    }
}
