package ai.agentkernel.archive

/**
 * 一段**会话归档**的元信息（列表里显示的那部分）。
 *
 * ## 这是什么
 *
 * 对话被**摘要**掉的那一段，**原文仍然留着** —— 只是不再每轮都发给模型。
 * 归档段就是它的存放处。
 *
 * ## 和长期记忆的区别
 *
 * | | 内容 | 进提示词？ | 用途 |
 * |---|---|---|---|
 * | **归档段**（本文件） | 对话**原文**，结构化 | ❌ 不进 | 前端翻历史、检索 |
 * | **长期记忆**（`var/memory/`） | 跨话题的**结论摘要** | ✅ 进（预算内） | 模型记得「之前聊过什么」 |
 *
 * 归档原文**不进提示词** —— 一段原文动辄几千字符，进提示词就把预算顶爆了。
 *
 * @param id 稳定标识（同时是文件名的一部分）。
 * @param createdAt 归档时刻（epoch 毫秒）。
 * @param kind 这一段是怎么来的：`compact`（容量压缩时摘要掉的）/ `idle`（闲置归档的）。
 * @param title 一句话标题（给列表看）。
 * @param summary 这一段的摘要（压缩时产出的那句）；可能为 null。
 * @param prevId **更早**的一段；null = 最早的那段。
 * @param nextId **更晚**的一段；null = 最新的一段。
 * @param messageCount 这一段有多少条消息。
 * @param chars 这一段正文多少字符。
 */
data class ArchiveSegmentMeta(
    val id: String,
    val createdAt: Long,
    val kind: String,
    val title: String,
    val summary: String?,
    val prevId: String?,
    val nextId: String?,
    val messageCount: Int,
    val chars: Int,
) {
    companion object {
        /** 容量压缩时产生的段。 */
        const val KIND_COMPACT = "compact"

        /** 闲置归档时产生的段。 */
        const val KIND_IDLE = "idle"
    }
}

/**
 * 一段归档的**完整内容**：元信息 + 原始消息。
 *
 * 消息是**结构化**的（不是渲染成文本）—— 前端要照常显示 tool 调用、思维链、截断原因。
 */
data class ArchiveSegment(
    val meta: ArchiveSegmentMeta,
    val messages: List<ai.agentkernel.protocol.ChatMessage>,
)
