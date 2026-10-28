package ai.agentkernel.context

import ai.agentkernel.protocol.ChatMessage

/**
 * 一次请求的**上下文体检报告** —— 「上下文检测」写在这里。
 *
 * ## 它回答什么问题
 *
 * 前缀缓存只认**逐字相同**的前缀。所以每次请求前都要回答两个问题：
 *
 * 1. **哪些消息变了？** —— 变了就是断点，缓存从那里开始全部作废。
 * 2. **这次多出来多少？** —— 多出来的部分是「必然未命中」的（它本来就是新的）。
 *
 * 只看服务端的命中率是不够的：命中低可能是「缓存还没建起来」（第 1-2 轮正常），
 * 也可能是**我们自己把前缀改坏了**（第 3 轮起突然掉到 0）。
 * 这两者的区别就是 [prefixPreserved]：前缀没被改却没命中 = 缓存还没建好；
 * 前缀被改了 = **我们的布局有 bug**。
 *
 * ## 口径说明（不要误读）
 *
 * - 字符数是**客观量**（直接量的），不是 token 估算 —— SDK 没有分词器，就不编一个 token 数；
 * - [prefixRatio] 是**字符口径的代理指标**，真实命中率以服务端 `usage` 为准；
 * - 指纹用的是消息的**线上序列化**（`toJson()`），即「实际发出去的那份」，
 *   不是 UI 上的显示文本。
 */
data class ContextReport(
    /** 第几次请求（从 1 开始）。 */
    val requestIndex: Int,
    /** 本次请求的消息条数。 */
    val messageCount: Int,
    /** 与上一次请求**逐字相同**的前缀条数。 */
    val commonPrefixMessages: Int,
    /** 公共前缀的字符数。 */
    val commonPrefixChars: Int,
    /** 本次请求全部消息的字符数。 */
    val totalChars: Int,
    /** 第一条与上次**不同**的消息下标；null = 整段前缀逐字相同（第一次请求时也是 null）。 */
    val firstChangedIndex: Int?,
    /** 相对上次**内容变了**（或被删掉）的消息条数。 */
    val changedMessages: Int,
    /** 相对上次**新增**的消息条数。 */
    val addedMessages: Int,
    /** 是否第一次请求（没有对照）。 */
    val firstRequest: Boolean,
    /** 是否超出宿主给的预算（条数或字符数）。 */
    val overBudget: Boolean,
) {
    /**
     * 前缀是否**只增不改**。
     *
     * 这是前缀缓存能命中的前提：老消息一个字都不能动。
     * 为 false 时，看 [firstChangedIndex] —— 那就是被改坏的位置。
     */
    val prefixPreserved: Boolean get() = changedMessages == 0

    /** 公共前缀占本次请求的比例（字符口径，0..1）。 */
    val prefixRatio: Double
        get() = if (totalChars > 0) commonPrefixChars.toDouble() / totalChars else 0.0

    /** 本次**必然未命中**的部分（新内容 + 断点之后的部分）的字符数。 */
    val newChars: Int get() = (totalChars - commonPrefixChars).coerceAtLeast(0)
}

/**
 * 上下文体检器 —— 逐次请求比对「上一轮发了什么」和「这一轮发了什么」。
 *
 * ## 怎么用
 *
 * 内核每次请求前都会调 [observe]，所以宿主不用自己接：
 * ```
 * val report = sdk.lastContextReport()   // 最近一次请求的体检结果
 * if (!report.prefixPreserved) {
 *     // 第 report.firstChangedIndex 条消息变了 → 缓存从那儿起全废
 * }
 * ```
 *
 * ## 预算
 *
 * [maxMessages] / [maxChars] 是**提醒线**，不是硬限制：超了只是 [ContextReport.overBudget]
 * 为 true，由宿主决定怎么裁。SDK **不自己丢消息** —— 丢对话内容是宿主的决定权。
 */
class ContextInspector(
    /** 消息条数预算（0 = 不限）。 */
    private val maxMessages: Int = 0,
    /** 字符数预算（0 = 不限）。 */
    private val maxChars: Int = 0,
) {
    /** 上一次请求的消息指纹（线上序列化）。 */
    private var previous: List<String> = emptyList()

    /** 请求计数。 */
    private var index = 0

    /**
     * 记录一次请求并出报告。
     *
     * @param messages 本次**实际发出**的消息（内核在组装好请求之后调用）。
     */
    fun observe(messages: List<ChatMessage>): ContextReport {
        index++
        val prints = messages.map { it.toJson().toString() }
        val chars = prints.map { it.length }

        val first = index == 1
        var common = 0
        if (!first) {
            val limit = minOf(previous.size, prints.size)
            while (common < limit && previous[common] == prints[common]) common++
        }

        // 「变了」= 逐条比对真的不一样了的那几条。
        // ⚠️ 不能简单地把「首个差异之后的所有」都算成变了 ——
        // 那样「只改了一条、后面原样」会被算成「后面全变了」，误报成大面积漂移。
        val overlap = minOf(previous.size, prints.size)
        var changed = 0
        for (i in 0 until overlap) if (previous[i] != prints[i]) changed++
        // 上轮有、这轮没了 —— 也算「变了」（历史被裁短 = 前缀断了）。
        if (previous.size > prints.size) changed += previous.size - prints.size
        val added = (prints.size - previous.size).coerceAtLeast(0)

        val commonChars = chars.take(common).sum()
        val totalChars = chars.sum()
        val over = (maxMessages > 0 && prints.size > maxMessages) ||
            (maxChars > 0 && totalChars > maxChars)

        // 「什么都没变」= 公共前缀吃掉了所有重叠部分，且没删东西（新增不算「变了」）。
        val nothingChanged = changed == 0
        val report = ContextReport(
            requestIndex = index,
            messageCount = prints.size,
            commonPrefixMessages = common,
            commonPrefixChars = commonChars,
            totalChars = totalChars,
            firstChangedIndex = if (nothingChanged) null else common,
            changedMessages = changed,
            addedMessages = added,
            firstRequest = first,
            overBudget = over,
        )

        previous = prints
        return report
    }

    /** 清空对照基准（换会话时调）。 */
    fun reset() {
        previous = emptyList()
        index = 0
    }
}
