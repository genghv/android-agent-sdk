package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把一段对话**压成一段稳定摘要** —— 靠真的调一次模型 API。
 *
 * ## 压缩请求怎么发（dsh `compaction-basic/summarizer.ts` 的做法）
 *
 * ```
 * 稳定前缀（逐字重放） + 被遮蔽区（原样） + 一条 user 压缩指令
 * ```
 *
 * 三个要点，缺一个就白压：
 *
 * 1. **被遮蔽区原样进请求**（不 render 成文本）。
 *    把消息渲染成一段「[用户] …」的文本，等于告诉模型「这是转述，不是原话」——
 *    内容细节被改写，摘要质量下降；而逐字重放的话，**上游前缀缓存能命中**
 *    （这一段和主请求里的那段是**同样的字节**）。
 * 2. **工具定义与主请求逐字一致**（`tools` 原样带上）。
 *    ⚠️ 送 `emptyList()` 的话，网关拼出来的请求体和主请求**不是同一串字节**，
 *    压缩这次调用就**不吃缓存**；工具段一变，缓存从那儿起全部作废。
 * 3. **压缩指令是最后一条 user message**。
 *    指令放最前面的话，它会顶在稳定前缀的最外侧 —— 每次压缩的指令只要差一个字，
 *    后面整段前缀就全废了。放最后，它只影响这条请求的尾巴。
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
     * @param messages **被遮蔽区**（旧的那些）—— 原样进请求，不渲染成文本。
     * @param targetChars 摘要的目标长度（写进提示词当软约束）。
     * @param replayPrefix 要逐字重放的稳定前缀（system 段）。
     *   上一轮摘要在主请求里本来就是一个 system 段，**由它带进来**，
     *   不要再往 user 消息里塞一份 —— 那会变成「同一段内容出现两遍」，
     *   而且指令字符串一变，前缀就断了。
     * @param tools 与主请求**完全相同**的工具定义，原样带上（否则这次调用不吃缓存）。
     * @param sessionId 会话标识；能拿到就带（服务端据此复核前缀缓存还剩多少）。
     * @return 摘要正文；失败时返回 null（调用方要能容忍压缩失败）。
     */
    suspend fun summarize(
        messages: List<ChatMessage>,
        targetChars: Int,
        replayPrefix: List<ChatMessage> = emptyList(),
        tools: List<JsonObject> = emptyList(),
        sessionId: String? = null,
    ): String? {
        if (messages.isEmpty()) return null

        val request = ChatRequest(
            model = model,
            // 顺序不能改：前缀 → 被遮蔽区 → 指令（指令必须是最后一条）。
            messages = replayPrefix + messages + listOf(UserMessage(buildInstruction(targetChars))),
            // ⚠️ 必须与主请求逐字一致，否则压缩这次调用不吃缓存。
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens,
            // 网关据此识别压缩请求，并用后端提示词库里的正文替换这条指令；
            // 直连 DeepSeek 时该字段被忽略（官方会忽略未知字段）。
            extra = buildJsonObject { put("compact", true) },
            sessionId = sessionId,
        )
        return runCatching { llm.complete(request) }
            .getOrNull()
            ?.let { it.content?.trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 本地兜底的压缩指令。
     *
     * 写死在 SDK 里，保证「没有后端提示词库」时也能工作（直连 DeepSeek 就是这种情况）。
     *
     * ⚠️ 同一 targetChars 下这段字符串必须**逐字相同** ——
     * 它会进请求，差一个字就多一次缓存失效。所以它只是「一个模板 + 一个数字」，
     * 里面不许有时间戳、随机数、UUID 之类会变的东西。
     */
    private fun buildInstruction(targetChars: Int): String = """
        请把上面这段对话压缩成一段**稳定的摘要**。

        这份摘要之后会代替原始对话，放在每次请求的最前面。
        所以它必须**逐字稳定**：
        - 不写时间戳、不写「用户说 / 助手回答」这类对话痕迹；
        - 不写寒暄、不写客套；
        - 不写每轮都会变的数字和状态。

        只写下面这五类，每条一行，写不出来的不写：
        - 事实：用户提到的具体信息（名字、路径、数值、配置）
        - 结论：已经确定下来的判断或结果
        - 决定：定下来的做法，以及被否掉的做法
        - 待办：还没做完的事
        - 偏好：用户的习惯、口径、忌讳

        控制在 $targetChars 字符以内。只输出摘要正文，不要任何解释或标题。
    """.trimIndent()
}
