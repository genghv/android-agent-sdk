package ai.agentkernel.memory

import ai.agentkernel.archive.ArchiveSegmentMeta
import ai.agentkernel.archive.ArchiveStore
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.serialization.json.JsonObject

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
 * **② 闲置 → 归档成记忆（写当天的「每日摘要槽」）。**
 * 用户上一轮说完就走了、隔了很久才回来 —— 这一段就是「上一个话题」。
 * 把这一段总结成一条**长期记忆**落盘，然后**开一个新会话**：
 * 过去的事由记忆摘要带着（进前缀），当前会话从零开始。
 *
 * ★ 落盘的位置是**当天的每日摘要槽**，不是「再追加一条」：
 * 同一天再闲置一次，后来的摘要**替换**当天那条 ⇒ 一天永远只有一条。
 * 跨天则是新的一天的槽位（老那天的还在磁盘上，只是换了一天）。
 *
 * ## ★ 摘要**不删原文**
 *
 * 被摘要掉的那一段，**原文照旧落盘** —— 只是一段归档
 * （见 [ai.agentkernel.archive.ArchiveStore]）。
 *
 * | | 内容 | 进提示词？ | 用途 |
 * |---|---|---|---|
 * | **会话摘要** | 压缩出来的一句结论 | ✅ 进 | 模型「记得」之前聊过什么 |
 * | **长期记忆** | 跨话题的结论摘要（闲置归档写的是**当天的每日摘要槽**） | ✅ 进（预算内） | 模型记得「之前聊过什么」 |
 * | **归档段** | 被摘要掉的**对话原文** | ❌ 不进 | **前端照常显示那些对话** |
 *
 * ⇒ 摘要是给**模型**看的，归档是给**用户**看的。
 * 界面要能翻出那些对话，翻的就是归档段（段与段之间有链，前端一段一段加载）。
 *
 * ## 它不做什么
 *
 * - 不替宿主决定历史怎么存 —— 它只把「该发什么」算好给门面；
 * - 不删对话原文 —— 磁盘上留着全文（原文进归档段）；
 * - 不追加闲置摘要 —— 闲置归档写的是**每日摘要槽**（同一天后来的替换前面的）；
 * - 不给 agent 加新工具 —— 记忆就是 `<agentRoot>/var/memory/` 下的普通文件，
 *   agent 用已有的终端能力 `cat` 就能读；要增删改查另有**一条**命令
 *   `agent-memory`（见 [AgentMemoryCapabilities]）。
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
    /**
     * 会话**分段归档**的仓库（可选）。
     *
     * 摘要**不删原文** —— 被摘要掉的那一段会在这里落成一段归档，
     * 前端照常能翻、能显示（见 [ai.agentkernel.archive.ArchiveStore]）。
     *
     * null = 不归档（那被摘要掉的原文就只剩宿主自己那份历史了）。
     */
    private val archive: ArchiveStore? = null,
) {
    /** 本会话已折叠进前缀的摘要（压缩出来的）。 */
    private var sessionSummary: String? = null

    /** 上一轮活动的时刻。 */
    private var lastActivityAt: Long = 0L

    /**
     * 长期记忆摘要（进前缀的那一块）。没有记忆时是 null。
     *
     * @param maxChars 这一块**最多占多少字符**；<= 0 = 不带记忆。
     *   默认用 [ContextPolicy.memoryDigestChars]。
     *
     *   门面（[ai.agentkernel.android.AndroidAgentSdk]）会按「客户端 system 段
     *   还剩多少字符」把它调小 —— 长期记忆是**唯一可再生**的部分
     *   （`agent-memory search` 能随时取回），所以超预算时先砍它。
     */
    fun digest(maxChars: Int = policy.memoryDigestChars): String? = store.digest(maxChars)

    /** 本会话的压缩摘要（进前缀的那一块）。没有时是 null。 */
    fun sessionSummary(): String? = sessionSummary

    /** 记忆仓库（宿主要自己翻旧记忆时用）。 */
    fun memoryStore(): MemoryStore = store

    /**
     * 会话**归档**仓库（前端翻历史用）。
     *
     * 摘要掉的原文在这里，按段存、段与段之间有链，
     * 前端首屏取最新一段、往上滚一段一段取（见 [ai.agentkernel.archive.ArchiveStore.list]）。
     */
    fun archiveStore(): ArchiveStore? = archive

    /**
     * 准备这一轮要发什么。
     *
     * @param history 宿主持有的会话历史（**包含**本轮用户消息）。
     * @param prefixChars 本轮稳定前缀大概占多少字符（系统提示词、记忆块……）。
     * @param replayPrefix 要在压缩请求里**逐字重放**的稳定前缀（system 段）。
     *   上一轮摘要就在这里面 —— 它在主请求里本来就是一个 system 段，
     *   所以压缩时原样带上即可，**不要**再往 user 消息里塞一份。
     * @param tools 与主请求**完全相同**的工具定义，原样带给压缩请求
     *   （工具段不一致 = 压缩这次调用不吃上游缓存）。
     * @return 这一轮该怎么发。
     */
    suspend fun prepare(
        history: List<ChatMessage>,
        prefixChars: Int = 0,
        replayPrefix: List<ChatMessage> = emptyList(),
        tools: List<JsonObject> = emptyList(),
    ): ContextPlan {
        val now = clock()

        // ① 闲置归档：先看是不是「换了一个话题」。
        var working = history
        var archived: SessionArchive? = null
        var segment: ArchiveSegmentMeta? = null
        if (policy.archiveEnabled && isIdle(now)) {
            val arch = archiveSession(working, now)
            archived = arch
            if (arch != null) {
                // 归档 = 开新会话：过去的话交给记忆，当前这轮从零开始。
                val lastUser = working.indexOfLast { it is UserMessage }
                working = if (lastUser >= 0) working.drop(lastUser) else emptyList()
                sessionSummary = null
                // ★ 原文**同时**落一段归档 —— 记忆是「结论」，归档是「原文」。
                //   前端要照常显示那些对话，翻的就是这一段。
                segment = archive?.append(
                    kind = ArchiveSegmentMeta.KIND_IDLE,
                    title = arch.title,
                    summary = arch.entry.summary,
                    messages = arch.session,
                )
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
                        replayPrefix = replayPrefix,
                        tools = tools,
                    )
                    if (!produced.isNullOrBlank()) {
                        summary = produced
                        sessionSummary = produced
                        // ★ 被摘要掉的原文**落一段归档** —— 摘要是给模型看的，
                        //   归档是给用户看的：界面照样能翻出那些对话。
                        segment = archive?.append(
                            kind = ArchiveSegmentMeta.KIND_COMPACT,
                            title = titleOf(produced),
                            summary = produced,
                            messages = working.take(split),
                        )
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
            archived = archived?.entry,
            archivedSegment = segment,
            estimatedChars = prefixChars + charsOf(working),
        )
    }

    /**
     * 宿主主动触发一次归档（比如 App 回到前台、用户按了「结束这话题」）。
     *
     * ★ 和闲置归档是**同一件事** —— 同样写**当天的每日摘要槽**
     *   （同一天手动归档几次也只留一条，后来的替换前面的）。
     *
     * @return 归档出来的那条记忆；没有可归档的内容时是 null。
     */
    suspend fun archiveNow(history: List<ChatMessage>): MemoryEntry? {
        val archived = archiveSession(history, clock())
        if (archived != null) {
            sessionSummary = null
            archived.session?.let { sess ->
                archive?.append(
                    kind = ArchiveSegmentMeta.KIND_IDLE,
                    title = archived.title,
                    summary = archived.entry.summary,
                    messages = sess,
                )
            }
        }
        return archived?.entry
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
     *
     * ★ 落盘位置 = **当天的每日摘要槽**（[MemoryStore.upsertDaily]）：
     * 同一天归档几次都只有一条（后来的替换前面的）。
     *
     * @return 记忆条目 + 被归档的**原文**（原文还要再落一份到归档段里）。
     */
    private suspend fun archiveSession(history: List<ChatMessage>, now: Long): SessionArchive? {
        val lastUser = history.indexOfLast { it is UserMessage }
        if (lastUser <= 0) return null
        val session = history.take(lastUser)
        if (session.isEmpty()) return null

        val summary = summarizer.summarize(session, policy.summaryTargetChars) ?: return null
        val title = titleOf(summary).ifBlank { "一次对话" }

        // ★ 写**当天的每日摘要槽**（不是再追加一条）：
        //   同一天再闲置一次 ⇒ 后来的摘要**替换**当天那条 ⇒ 一天永远只有一条；
        //   跨天 ⇒ 新的一天、新的槽位（老那天的还在磁盘上）。
        //   dateKey 由 [MemoryStore.dailyKey] 按归档时刻算出来。
        val entry = store.upsertDaily(store.dailyKey(now), title, summary)
        return SessionArchive(entry = entry, title = title, session = session)
    }

    /** 拿摘要的第一行当标题（最多 60 字）。 */
    private fun titleOf(summary: String): String =
        summary.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("- ")
            ?.take(60)
            .orEmpty()

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
        var split = i
        // ★ 切分点**不能落在工具结果消息上**。
        //
        // 历史里 assistant(带 tool_calls) 后面紧跟着 tool 消息（内核回填工具结果）。
        // 切分点正好落在那条 tool 上时：
        //   · 压缩请求重放的前缀**以一条没被回答的 tool_calls 结尾** —— 上游直接 400
        //     （「tool_calls 必须有对应的 tool 消息」），压缩这一步自己就炸了；
        //   · 压完之后主请求里那条 tool 消息**失去了它的 assistant(tool_calls)** ——
        //     同样 400，而且是**每一轮**都 400（历史留在会话日志里，坏了就一直坏）。
        //
        // 所以切分点要**往前挪**，把「工具调用 + 工具结果」这一组整体留在同一侧。
        // 挪到头（split=0）就放弃这次压缩 —— 发一个超预算但**合法**的请求，
        // 比发一个会让上游 400 的请求强。
        while (split > 0 && messages[split] is ToolMessage) split--
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
 * 一次闲置归档的结果：**记忆条目** + 被归档的**原文**。
 *
 * 记忆给模型看（跨话题的结论，落盘位置是当天的每日摘要槽），
 * 原文给用户看（前端照常显示那些对话）。
 */
private data class SessionArchive(
    val entry: MemoryEntry,
    val title: String,
    val session: List<ChatMessage>,
)

/**
 * 这一轮该怎么发（见 [ContextMemory.prepare]）。
 *
 * @param history 实际发给内核的历史（可能已压缩、已归档）。
 * @param summary 要放进**稳定前缀**的会话摘要；null = 没有。
 * @param compacted 历史被改写了吗（改写了宿主就要用 [history] 替换自己那份）。
 * @param archived 本轮归档出来的那条记忆；null = 没归档。
 * @param archivedSegment 本轮落盘的**归档段**（被摘要掉的原文就在这儿）；null = 没有。
 * @param estimatedChars 估算的本次请求总字符数。
 */
data class ContextPlan(
    val history: List<ChatMessage>,
    val summary: String?,
    val compacted: Boolean,
    val archived: MemoryEntry?,
    val archivedSegment: ArchiveSegmentMeta? = null,
    val estimatedChars: Int,
)
