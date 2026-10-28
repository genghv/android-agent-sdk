package ai.agentkernel.memory

/**
 * 上下文压缩与记忆的**全部旋钮**。
 *
 * 宿主想改行为，改这里就行 —— SDK 不把这些数字写死在代码里。
 *
 * ## 怎么理解这几个数
 *
 * SDK **没有分词器**，所以所有容量都按**字符数**算（客观量，不编 token 估算）。
 * 中文大约 1 字符 ≈ 0.6 token，英文接近 1 字符 ≈ 0.25 token。
 *
 * | 参数 | 作用 | 默认 |
 * |---|---|---|
 * | [maxContextChars] | 一次请求的**总字符预算**（提示词 + 历史 + 记忆）。超了就压缩 | 24,000 |
 * | [keepRecentChars] | 压缩时**原样保留**的最近历史字符数（其余被压成摘要） | 6,000 |
 * | [summaryTargetChars] | 摘要的**目标长度**（给压缩器当「写多少」的上限） | 1,200 |
 * | [idleArchiveMillis] | 闲置多久算「这次话题结束了」 | 30 分钟 |
 * | [memoryDigestChars] | 长期记忆进提示词的预算（超了只带最近几条） | 1,500 |
 * | [maxMemoryEntries] | 最多留多少条记忆 | 100 |
 *
 * ## 为什么默认是 24,000 字符
 *
 * DeepSeek 官方两个模型的上下文都是 64K token。留 24,000 字符（约 15K token）
 * 当工作区，既离上限很远（不会 400），又足够聊很久才需要压一次。
 * 想更省就调小 [maxContextChars]；想留更久就调大。
 *
 * ⚠️ 调小 [maxContextChars] 时注意 [keepRecentChars] 也要跟着调小：
 * 保留的原文比预算还大的话，压缩等于没压。
 */
data class ContextPolicy(
    /** 一次请求的总字符预算；超过就触发一次压缩。<= 0 = 不压缩。 */
    val maxContextChars: Int = 24_000,

    /** 压缩时原样保留的最近历史字符数。 */
    val keepRecentChars: Int = 6_000,

    /** 摘要的目标字符数。 */
    val summaryTargetChars: Int = 1_200,

    /** 闲置多久算「这次话题结束了」；到期就把会话归档成记忆。<= 0 = 不归档。 */
    val idleArchiveMillis: Long = 30L * 60L * 1000L,

    /** 长期记忆进提示词的字符预算。 */
    val memoryDigestChars: Int = 1_500,

    /** 最多保留多少条记忆条目（超了丢最旧的）。 */
    val maxMemoryEntries: Int = 100,

    /** 要不要做「闲置归档成记忆」。 */
    val archiveEnabled: Boolean = true,

    /** 要不要做「超容量自动压缩」。 */
    val compressionEnabled: Boolean = true,
) {

    companion object {
        /** 什么都不做（只保留历史，不压缩、不归档）。 */
        val OFF = ContextPolicy(
            maxContextChars = 0,
            keepRecentChars = 0,
            idleArchiveMillis = 0,
            archiveEnabled = false,
            compressionEnabled = false,
        )
    }
}
