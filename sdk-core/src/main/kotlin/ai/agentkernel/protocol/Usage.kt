package ai.agentkernel.protocol

/**
 * 一次模型请求的 token 用量。
 *
 * ## 为什么必须有它
 *
 * 前缀缓存（DeepSeek Context Caching on Disk）是**服务端自动**的：客户端不声明缓存断点，
 * 服务端按「已持久化的前缀单元」匹配。想知道缓存到底生效没有、命中了多少，
 * **唯一的口径就是响应里的 `usage`** —— 客户端不解析它，就永远只能靠猜，
 * 「命中率 99%」这种话也就无从验证。
 *
 * ## 字段口径（对齐 DeepSeek 官方的 `usage`）
 *
 * | 字段 | 线格式 | 含义 |
 * |---|---|---|
 * | [promptTokens] | `prompt_tokens` | 本次输入总量（**含**缓存命中部分） |
 * | [completionTokens] | `completion_tokens] | 输出量 |
 * | [totalTokens] | `total_tokens` | 合计 |
 * | [cacheHitTokens] | `prompt_cache_hit_tokens`（或 `prompt_tokens_details.cached_tokens`） | 命中缓存的量 |
 * | [cacheMissTokens] | `prompt_cache_miss_tokens` | 未命中（本次新算）的量 |
 *
 * ⚠️ **两种口径别混**：
 * - 这里的 [promptTokens] 是**总量**（OpenAI/DeepSeek 的原生口径）；
 * - 而内核 `TokenUsage.inputTokens` 是**未缓存**的量（内核的 disjoint 约定：缓存命中要减出来）。
 * 两者**不能相加**。计费输入 = 命中 + 未命中 = 本类的 [promptTokens]。
 *
 * ## [cacheHitRatio] 怎么读
 *
 * 官方口径是 **best-effort**，不保证 100%：
 * - 多轮对话形态（官方案例一）：第 2 轮起就能命中大部分前缀；
 * - 长文问答形态（官方案例二）：前两轮不命中，公共前缀被持久化后**第 3 轮**才命中。
 */
data class Usage(
    /** 输入总量（含缓存命中部分）。 */
    val promptTokens: Int = 0,
    /** 输出量。 */
    val completionTokens: Int = 0,
    /** 合计。 */
    val totalTokens: Int = 0,
    /** 命中缓存的量。 */
    val cacheHitTokens: Int = 0,
    /** 未命中（本次新算）的量。 */
    val cacheMissTokens: Int = 0,
) {
    /**
     * 缓存命中率（0..1）。
     *
     * 分母优先用 [promptTokens]（总量口径）；上游只给命中/未命中两个数时退回
     * `命中 + 未命中`。算出来超过 1 的会被夹回 1 —— 宁可保守也不谎报。
     */
    val cacheHitRatio: Double
        get() {
            if (promptTokens > 0) return (cacheHitTokens.toDouble() / promptTokens).coerceIn(0.0, 1.0)
            val fallback = cacheHitTokens + cacheMissTokens
            return if (fallback > 0) (cacheHitTokens.toDouble() / fallback).coerceIn(0.0, 1.0) else 0.0
        }

    /** 本次请求**新增**的（未命中）输入占比 —— 「这次为什么没全命中」。 */
    val cacheMissRatio: Double
        get() = 1.0 - cacheHitRatio

    /**
     * 累加 —— 一个 agent 回合里会发**多次**请求（工具循环），回合用量是它们的和。
     */
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
    )

    companion object {
        /** 零用量（还没拿到响应时的初值）。 */
        val ZERO: Usage = Usage()
    }
}
