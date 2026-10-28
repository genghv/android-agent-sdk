package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage

/**
 * 上下文压缩与长期记忆的**编排者** —— 宿主持有它，门面每轮调一次。
 *
 * ## 它做两件事
 *
 * **① 超容量 → 压一次。**
 * 请求总字符数超过 [ContextPolicy.maxContextChars] 时，把**旧的**历史压成一段摘要，
 * 摘要放进**稳定前缀**，最近的历史原样保留。
 *
 * ⚠️ 为什么摘要放前缀而不是直接删掉旧历史：
 * 上游是前缀缓存，**裁头 = 前缀重置 = 一次全 miss**。
 * 压成摘要放进前缀，改动只发生一次，改完重新焐热就又稳了。
 *
 * **② 闲置 → 归档成记忆。**
 * 用户上一轮说完就走了、隔了很久才回来 —— 这一段就是「上一个话题」。
 * 把这一段总结成一条**长期记忆**落盘，然后**开一个新会话**：
 * 过去的事由记忆摘要带着（进前缀），当前会话从零开始。
 *
 * ## 它不做什么
 *
 * - 不替宿主决定历史怎么存 —— 它只把「该发什么」算好给门面；
 * - 不删任何东西 —— 记忆是**追加**的，磁盘上是全文；
 * - 不需要新工具 —— 记忆就是 `<agentRoot>/var/memory/` 下的普通文件，
 *   agent 用已有的终端能力 `cat` 就能读。
 *
 * ## 线程安全
 *
 * 不是线程安全的（一个会话一个实例，串行用）。要并发就自己加锁。
 */
class ContextMemory(
    /** 用来做压缩/摘要的模型客户端（和主对话同一个也行）。 */
    llm: LlmClient,
    /** 模型名。 */
    model: String,
    /** 记忆落盘的地方。 */
    private val store: MemoryStore,
    /** 旋钮。 */
    val policy: ContextPolicy = ContextPolicy(),
    /** 摘要器（想换实现就自己传）。 */
    private val summarizer: ConversationSummarizer = ConversationSummarizer(llm, model),
    /** 时钟（测试可以注入假的）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** 本会话已折叠进前缀的摘要（压缩出来的）。 */
    private var sessionSummary: String? = null

    /** 上一轮活动的时刻。 */
    private var lastActivityAt: Long = 0L

    /** 长期记忆摘要（进前缀的那一块）。没有记忆时是 null。 */
    fun digest(): String? = store.digest(policy.memoryDigestChars)

    /** 本会话的压缩摘要（进前缀的那一块）。没有时是 null。 */
    fun sessionSummary(): String? = sessionSummary

    /** 记忆仓库（宿主要自己翻旧记忆时用）。 */
    fun memoryStore(): MemoryStore = store

    /**
     * 准备这一轮要发什么。
     *
     * @param history 宿主持有的会话历史（**包含**本轮用户消息）。
     * @param prefixChars 本轮稳定前缀大概占多少字符（系统提示词、记忆块……）。
     * @return 这一轮该怎么发。
     */
    suspend fun prepare(history: List<ChatMessage>, prefixChars: Int = 0): ContextPlan {
        val now = clock()

        // ① 闲置归档：先看是不是「换了一个话题」。
        var working = history
        var archived: MemoryEntry? = null
        if (policy.archiveEnabled && isIdle(now)) {
            archived = archiveSession(working, now)
            if (archived != null) {
                // 归档 = 开新会话：过去的话交给记忆，当前这轮从零开始。
                val lastUser = working.indexOfLast { it is UserMessage }
                working = if (lastUser >= 0) working.drop(lastUser) else emptyList()
                sessionSummary = null
            }
        }
        lastActivityAt = now

        // ② 超容量压缩：把旧历史压成摘要，原文只留最近一段。
        var summary = sessionSummary
        var compacted = false
        if (policy.compressionEnabled && policy.maxContextChars > 0 && working.size > 1) {
            val historyChars = charsOf(working)
            if (prefixChars + historyChars > policy.maxContextChars) {
                val split = splitIndex(working, policy.keepRecentChars)
                if (split > 0) {
                    val produced = summarizer.summarize(
                        messages = working.take(split),
                        targetChars = policy.summaryTargetChars,
                        previousSummary = summary,
                    )
                    if (!produced.isNullOrBlank()) {
                        summary = produced
                        sessionSummary = produced
                        working = working.drop(split)
                        compacted = true
                    }
                }
            }
        }

        return ContextPlan(
            history = working,
            summary = summary,
            compacted = compacted,
            archived = archived,
            estimatedChars = prefixChars + charsOf(working),
        )
    }

    /**
     * 宿主主动触发一次归档（比如 App 回到前台、用户按了「结束这话题」）。
     *
     * @return 归档出来的那条记忆；没有可归档的内容时是 null。
     */
    suspend fun archiveNow(history: List<ChatMessage>): MemoryEntry? {
        val entry = archiveSession(history, clock())
        if (entry != null) sessionSummary = null
        return entry
    }

    /** 距离上一次活动过了多久（毫秒）；从没活动过就是 0。 */
    fun idleMillis(): Long {
        if (lastActivityAt == 0L) return 0L
        return (clock() - lastActivityAt).coerceAtLeast(0L)
    }

    // ---- 内部 ----

    private fun isIdle(now: Long): Boolean {
        if (policy.idleArchiveMillis <= 0) return false
        if (lastActivityAt == 0L) return false
        return now - lastActivityAt >= policy.idleArchiveMillis
    }

    /**
     * 把「当前这一段会话」总结成一条记忆落盘。
     *
     * 「这一段」= 最后一条用户消息**之前**的所有内容。
     * （最后那条用户消息是本轮的输入，不属于要归档的旧话题。）
     */
    private suspend fun archiveSession(history: List<ChatMessage>, now: Long): MemoryEntry? {
        val lastUser = history.indexOfLast { it is UserMessage }
        if (lastUser <= 0) return null
        val session = history.take(lastUser)
        if (session.isEmpty()) return null

        val summary = summarizer.summarize(session, policy.summaryTargetChars) ?: return null
        val title = summary.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("- ")
            ?.take(60)
            .orEmpty()

        return store.append(
            MemoryEntry(
                id = MemoryStore.newId(now),
                createdAt = now,
                title = title.ifBlank { "一次对话" },
                summary = summary,
            )
        )
    }

    /**
     * 从尾部往前数，攒够 [keepRecentChars] 就停 —— 返回该切分的位置。
     *
     * 切分点之前的进摘要，之后的原样保留。
     * 返回 0 = 没有可压的（全部都算「最近」）。
     */
    private fun splitIndex(messages: List<ChatMessage>, keepRecentChars: Int): Int {
        if (keepRecentChars <= 0) return 0
        var acc = 0
        var i = messages.size - 1
        while (i >= 0) {
            acc += charsOf(messages[i])
            if (acc >= keepRecentChars) break
            i--
        }
        // i 是「最近段」的最后一条；要压的是它前面的。
        val split = i
        return if (split <= 0) 0 else split
    }

    private fun charsOf(messages: List<ChatMessage>): Int = messages.sumOf { charsOf(it) }

    private fun charsOf(message: ChatMessage): Int =
        when (message) {
            is UserMessage -> message.content.length
            is AssistantMessage ->
                (message.content?.length ?: 0) +
                    message.toolCalls.sumOf { it.name.length + it.argsOrEmpty().toString().length }
            is ToolMessage -> message.content.length
            is SystemMessage -> message.content.length
            else -> 0
        }
}

/**
 * 这一轮该怎么发（见 [ContextMemory.prepare]）。
 *
 * @param history 实际发给内核的历史（可能已压缩、已归档）。
 * @param summary 要放进**稳定前缀**的会话摘要；null = 没有。
 * @param compacted 历史被改写了吗（改写了宿主就要用 [history] 替换自己那份）。
 * @param archived 本轮归档出来的那条记忆；null = 没归档。
 * @param estimatedChars 估算的本次请求总字符数。
 */
data class ContextPlan(
    val history: List<ChatMessage>,
    val summary: String?,
    val compacted: Boolean,
    val archived: MemoryEntry?,
    val estimatedChars: Int,
)
