package ai.agentkernel.memory

import ai.agentkernel.llm.openai.HeaderRedaction

/**
 * 摘要失败的**可辨识**原因。
 *
 * ## 为什么要「可辨识」
 *
 * 摘要失败以前是**完全静默**的：`summarize()` 返回 null，调用方降级成「不压缩」。
 * 静默的问题不是「失败」，是「**没人知道发生过失败**」——
 * 用户看到的是「上下文怎么越用越贵 / 越压越慢」，而日志里一条线索都没有。
 *
 * ⇒ 现在失败有**类型**：
 * - [SummarizationTimeoutException]：**超时**（这次摘要调用超了总上限）；
 * - [SummarizationFailedException]：**模型报错**，或模型**返回空**。
 *
 * 宿主据此能分开处理：超时要调预算或降级，报错要查上游。
 *
 * ## ⚠️ 消息里**不**带原始异常的原文
 *
 * 上游的错误体里可能**回显凭据**（`Authorization` 原样吐回来），
 * 而这条消息会被宿主打进日志、再回灌给模型 ⇒ 明文 key 不能跟着走。
 *
 * ⇒ 沿用 SDK 的做法（见 `HeaderRedaction`）：**先抹后截** ——
 * 先把**凭据形状**的片段抹掉，再截到 [SummarizationErrorText.MAX_CHARS]。
 *
 * ⚠️ 也**不**把原异常挂在 cause 上 —— 挂了等于把原文又留在异常链里
 * （同 `ChatCompletionsClient` 的做法：**只搬栈**，不留原异常对象）。
 */
sealed class SummarizationException(message: String) : Exception(message)

/**
 * 摘要**超时** —— 这次摘要调用超了总上限（[ConversationSummarizer.timeoutMillis]）。
 *
 * 和 [SummarizationFailedException] 分开，是因为两者的处置不同：
 * 超时 ⇒ 上游可能只是慢（调预算 / 降级 / 重试）；报错 ⇒ 上游出问题了（查错误体）。
 */
class SummarizationTimeoutException(message: String) : SummarizationException(message)

/**
 * 摘要**失败** —— 模型调用**报错**，或模型**返回空**。
 */
class SummarizationFailedException(message: String) : SummarizationException(message)

/**
 * 失败消息的**安全文本**：**先抹后截**。
 *
 * ## 为什么自己抹，而不是交给 [HeaderRedaction]
 *
 * [HeaderRedaction.redact] 要**知道这次请求带了哪些头**才能定位凭据的值
 * —— 摘要器手里**没有**请求头（它只有 [ai.agentkernel.llm.LlmClient]）。
 *
 * ⇒ 这里抹的是**凭据的形状**（`Bearer xxx` / `sk-xxx` / `api_key=xxx`），
 * 不依赖具体值 ⇒ 宿主自定义的传输没抹干净时，**这里兜住**。
 *
 * ⚠️ 只抹**凭据形状**，不抹普通词（`invalid token` 这种报错不该被抹花）。
 */
internal object SummarizationErrorText {

    /** 抹完之后最多留这么多字（再长就是噪音，且日志会难看）。 */
    const val MAX_CHARS: Int = 200

    /** `Bearer <token>` —— 上游常常只回显 token 本身，不带前缀。 */
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._\\-+/=]{4,}")

    /** `sk-...` 这类厂商 key 的形状。 */
    private val SK_KEY = Regex("\\bsk-[A-Za-z0-9._\\-]{8,}")

    /**
     * `api_key=xxx` / `"token": "xxx"` / `Authorization: xxx` 这类「名字 = 值」。
     *
     * ⚠️ 值里**排除** `[` / `]` ⇒ 抹完留下的占位（[HeaderRedaction.MASK]）
     * 不会被自己再抹一遍（那会把它截成 `[已抹掉`）。
     */
    private val NAMED_SECRET = Regex(
        "(?i)\\b(authorization|api[-_]?key|apikey|access[-_]?token|refresh[-_]?token|" +
            "token|secret|password|passwd|credential)([\"'\\s]*[:=][\"'\\s]*)([^\\s\"',;}\\[\\]]{4,})"
    )

    /**
     * 把一个异常变成**能进日志**的一行字。
     *
     * @param original 原始异常（只取它的**类型名**与**抹过**的消息）。
     * @return 形如 `LLM HTTP 401: …（IllegalStateException）`。
     */
    fun safe(original: Throwable): String {
        val raw = original.message?.takeIf { it.isNotBlank() }.orEmpty()
        val masked = mask(raw)
        val cut = if (masked.length > MAX_CHARS) masked.take(MAX_CHARS) + "…" else masked
        val type = original.javaClass.simpleName.ifBlank { "异常" }
        return if (cut.isBlank()) type else "$cut（$type）"
    }

    /** 先抹凭据形状。 */
    private fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = SK_KEY.replace(out, HeaderRedaction.MASK)
        out = BEARER.replace(out, "Bearer ${HeaderRedaction.MASK}")
        out = NAMED_SECRET.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}${HeaderRedaction.MASK}"
        }
        return out
    }
}
