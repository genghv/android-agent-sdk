package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * 这次摘要调用的**总**上限（毫秒，**含重试**）；null = 不设超时。
     *
     * 默认 60s：摘要是一次 `maxTokens = 1024` 的压缩调用，
     * **卡住比失败更糟** —— 失败还能退回「不压缩」，卡住会把整轮拖住。
     *
     * ⚠️ **别调得比传输层上限（120s：OkHttp callTimeout / Jdk 每请求 timeout）大** ——
     * 那样永远轮不到我们超时（传输层先报），这个旋钮就形同虚设。
     */
    private val timeoutMillis: Long? = 60_000,
    /**
     * 失败留痕：任何失败都带着**可辨识**的原因调它
     * （[SummarizationTimeoutException] / [SummarizationFailedException]）。
     *
     * ⚠️ **每次失败都调一次**（重试过程中的中间失败也算）—— 留痕要能看出「试了几次」。
     *
     * null = 不留痕（默认；既有调用方零影响）。
     *
     * ## 宿主自己接线
     * ```
     * ContextMemory(
     *     llm = llm, model = model, store = store, policy = policy,
     *     summarizer = ConversationSummarizer(llm, model, onFailure = { e -> myLog(e) }),
     * )
     * ```
     */
    private val onFailure: ((Throwable) -> Unit)? = null,
    /**
     * 重试次数。**默认 0（关）**。
     *
     * ⚠️ 默认关的理由：**重试 = 多一次模型调用 = 多花用户的钱**，
     * 而摘要失败**并不致命**（可以退回「不压缩」）⇒ 没理由替用户默认多花钱。
     */
    private val retries: Int = 0,
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
     * @return 摘要正文；**失败时返回 null**（调用方要能容忍压缩失败）。
     *
     * ## 失败时会发生什么
     *
     * | 情况 | 返回 | 留痕 |
     * |---|---|---|
     * | 成功 | 摘要正文 | —— |
     * | 模型报错 | null | [SummarizationFailedException] |
     * | 模型返回空 | null | [SummarizationFailedException] |
     * | 超时（总预算用尽） | null | [SummarizationTimeoutException] |
     * | `messages` 为空 | null | ——（没有内容可压，不是失败） |
     *
     * ⚠️ **返回 null 的契约没变** —— `ContextMemory` 的两处调用点
     * （压缩 / 归档摘要）靠它降级成「不压缩」⇒ 这里**不能**改成抛异常。
     * 失败改由 [onFailure] 留痕（宿主自己接到日志/事件出口上）。
     *
     * ⚠️ **外层取消不是失败**：调用方把这一轮撤了时，
     * 取消异常**原样抛出**（不当成「摘要失败」，也不返回 null 冒充成功降级）。
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

        // ★ 总预算（含重试）：deadline 一次算好，之后每次尝试都按**剩下的**来
        //   —— 预算不是每次尝试重开的（否则重试几次就等于拖几倍时间）。
        val deadline = timeoutMillis?.let { System.currentTimeMillis() + it }
        var attempt = 0
        while (true) {
            val remaining = deadline?.let { it - System.currentTimeMillis() }
            if (remaining != null && remaining <= 0L) {
                // 总预算用尽 ⇒ 到此为止。
                // ⚠️ 已经报过失败就别再报一次（一次 summarize 只报一条失败原因）。
                if (attempt == 0) {
                    fail(SummarizationTimeoutException("摘要超时（总上限 ${timeoutMillis}ms 已用尽）"))
                }
                return null
            }

            val assistant: AssistantMessage? = try {
                if (remaining == null) {
                    llm.complete(request)
                } else {
                    // ★ 用 withTimeoutOrNull 而不是 withTimeout：
                    //   只有「**自己**的」超时才返回 null；
                    //   外层协程的取消（宿主把这一轮撤了）会**原样抛出去**，
                    //   不会被误当成「我们超时」吞掉。
                    withTimeoutOrNull(remaining) { llm.complete(request) }
                }
            } catch (e: CancellationException) {
                // 外层取消 —— 原样抛出，**不**当成摘要失败（也不返回 null 冒充降级）。
                throw e
            } catch (e: Exception) {
                // 模型调用报错（HTTP 错 / IO 错 / **传输层自己报的超时**）⇒ 可重试。
                val failure = SummarizationFailedException("摘要失败：${SummarizationErrorText.safe(e)}")
                // ★ 只搬栈，不留原异常对象（同 ChatCompletionsClient：原异常里可能有凭据明文）。
                failure.stackTrace = e.stackTrace
                fail(failure)
                if (!retry(attempt)) return null
                attempt++
                backoff()
                continue
            }

            if (assistant == null) {
                // 本次尝试超时。
                fail(SummarizationTimeoutException("摘要超时（总上限 ${timeoutMillis}ms）"))
                // ⚠️ 超时**可重试**，但我们的超时就是**总预算** ⇒ 预算已经没了 ⇒ 实际不会再试
                //   （再试也是超时的命，而且会突破总上限）。
                //   传输层自己报的超时（SocketTimeoutException 等）走上面那条「模型报错」
                //   的路子 ⇒ **那才是真的会重试的超时**。
                if (!retry(attempt) || !hasBudgetLeft(deadline)) return null
                attempt++
                backoff()
                continue
            }

            val content = assistant.content?.trim()
            if (content.isNullOrEmpty()) {
                // 模型返回空 ⇒ 也当失败（可重试）。
                fail(SummarizationFailedException("摘要失败：模型返回了空内容"))
                if (!retry(attempt)) return null
                attempt++
                backoff()
                continue
            }
            return content
        }
    }

    /** 还能不能再试一次（重试次数 = [retries]，0 = 不重试）。 */
    private fun retry(attempt: Int): Boolean = attempt < retries

    /** 总预算还剩不剩（[deadline] 为 null = 不设超时 ⇒ 永远还剩）。 */
    private fun hasBudgetLeft(deadline: Long?): Boolean {
        val d = deadline ?: return true
        return d - System.currentTimeMillis() > 0L
    }

    /**
     * 重试之间的**短退避**（50ms）。
     *
     * ⚠️ 不能睡死（那样总预算会被睡没）；50ms 足够躲开「上游正在抖」的瞬间，
     * 又不至于把总预算吃掉。
     */
    private suspend fun backoff() {
        delay(RETRY_BACKOFF_MILLIS)
    }

    /**
     * 失败留痕：把**可辨识**的原因交给宿主。
     *
     * ⚠️ 回调自己抛异常**不能**改掉摘要的返回契约（失败仍返回 null）
     * ⇒ 吞掉它（只保留取消 —— 那是外层的意思，不是回调的意思）。
     */
    private fun fail(failure: SummarizationException) {
        val hook = onFailure ?: return
        try {
            hook(failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 回调自己的问题不该把摘要变成「抛异常」⇒ 忽略。
        }
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

    private companion object {
        /** 重试之间的退避（毫秒）。≤100ms：躲得开抖动，又不会把总预算睡没。 */
        const val RETRY_BACKOFF_MILLIS: Long = 50L
    }
}
