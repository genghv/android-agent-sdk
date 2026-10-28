package ai.agentkernel.protocol

/**
 * **服务端视角**的前缀体检报告。
 *
 * ## 为什么要有「服务端视角」这一份
 *
 * SDK 自带的 [ai.agentkernel.context.ContextInspector] 查的是**本地**的前缀：
 * 这一轮发出去的 messages 和上一轮比，有没有被改过。
 *
 * 但上游请求的**头部**是服务端拼的：
 * 平台提示词（提示词库）、技能正文、工具定义 —— App 本地**一条都看不见**。
 * 于是出现最难查的一类故障：运营改了一句提示词、App 升了一版工具 schema，
 * App 的本地检测器**全绿**，而上游的前缀缓存**已经清零**，
 * 用户只看到「命中率突然掉到 0」，没人知道是哪一处改动造成的。
 *
 * dsh 对这个问题的答案是**另起一份独立视角复核**：
 * `packages/core/agent-loop/src/invariant.ts` 里重新算一遍会话、再和实际请求逐字比，
 * 注释写得很直白 —— *the live cache cannot vouch for itself*（活着的缓存自己没法给自己作证）。
 * 网关侧那一份就是它的服务端对应（见 `adapters/agri-app-auth/src/ai-prefix.ts`）。
 *
 * ## 怎么读
 *
 * - [preserved] == false → **前缀被改了**：从 [firstChangedIndex] 那条起，上游缓存全废。
 *   先查是谁裁了历史 / 谁改了 prompt，再查命中率。
 * - [headChanged] 非空 → **头部变了**：列出的就是变了的那几段
 *   （`platform` 平台提示词 / `client` 客户端 system / `skills` 技能 / `tools` 工具定义）。
 * - [tracked] == false → 这一轮**没有基线**可比（该会话的第一次请求，或基线已过期）。
 *   这不是「前缀坏了」，别当成坏了。
 */
data class PrefixReport(
    /** 会话键（服务端给的）。 */
    val session: String = "",
    /** 有没有基线可比（false = 该会话第一次请求 / 基线过期）。 */
    val tracked: Boolean = false,
    /** 前缀是否完好（只追加 = true；被改或被裁 = false）。 */
    val preserved: Boolean = true,
    /** 第一条发生变化的消息下标（-1 = 没有变化）。 */
    val firstChangedIndex: Int = -1,
    /** 公共前缀消息条数。 */
    val commonPrefixMessages: Int = 0,
    /** 公共前缀字符数。 */
    val commonPrefixChars: Int = 0,
    /** 本轮新增消息条数。 */
    val addedMessages: Int = 0,
    /** 头部字符数（平台+客户端+技能）—— 命中分子的主要来源。 */
    val headChars: Int = 0,
    /** 工具定义字符数。 */
    val toolsChars: Int = 0,
    /** 头部哪几段变了（空 = 没变）。 */
    val headChanged: List<String> = emptyList(),
) {
    /** 头部是否变过。 */
    val headStable: Boolean get() = headChanged.isEmpty()

    /** 一句话结论，给日志用。 */
    fun summary(): String = when {
        !tracked -> "首轮(无基线)"
        !preserved -> "前缀在第 $firstChangedIndex 条断了"
        headChanged.isNotEmpty() -> "头部变了:${headChanged.joinToString("/")}"
        else -> "前缀完好(公共 $commonPrefixMessages 条)"
    }
}
