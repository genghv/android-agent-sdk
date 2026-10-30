package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **容量口径** —— 编排算的字符数，要和真实请求体对得上。
 *
 * ## 这组用例盯的四件事
 *
 * | # | 口径 | 钉住它的用例 |
 * |---|---|---|
 * | ① | **工具定义段**按线上请求体**同构**计入 | `工具定义段按线上请求体同构计入容量` / `工具段也参与要不要压缩的判断` |
 * | ② | 压缩之后按**最终形态**重算 | `压缩之后按最终形态重算_新摘要比旧摘要短时估算正好下降` / `没压缩时按预留位算_保证值语义不变` |
 * | ③ | 压完复查预算 ⇒ `overBudget` 看得见 | `压完仍然超预算要看得见` / `压得下就报告没超预算` |
 * | ④ | 宿主从**公开返回值**上能看到这些数字 | `宿主能从公开返回值上看到容量口径的三个数字` |
 *
 * ## 为什么要有这一组
 *
 * 估算与真实请求体脱节 ⇒ 「该压的时候没压」「压完还以为装得下」。
 * 所以口径必须**写在用例里**（具体到预期值），而不是只写在注释里。
 *
 * ★ **反向验证**：每一处口径都有对应的「注入缺陷就红」的断言 ——
 *   · 工具段漏算 ⇒ 增量不是工具段长度；
 *   · 压缩后仍按压缩前的口径算 ⇒ 估算不会正好下降 243 字符；
 *   · `overBudget` 用压缩前的估算 ⇒ 「压得下」那条会误报成超预算。
 */
class CapacityAccountingTest {

    // ---- ① 工具定义段 -------------------------------------------------------

    @Test
    fun `工具定义段按线上请求体同构计入容量`() = runBlocking {
        val tools = listOf(bashSchema(), echoSchema())
        // 线上（ChatCompletionsClient.buildBody）的量法：buildJsonArray { tools.forEach { add(it) } }
        val expected = buildJsonArray { tools.forEach { add(it) } }.toString().length

        val mem = memory(FakeLlm(), ContextPolicy(maxContextChars = 100_000))
        val history = listOf(UserMessage("你好"), UserMessage("继续"))

        val without = mem.prepare(history, prefixChars = 100)
        val with = mem.prepare(history, prefixChars = 100, tools = tools)

        // ① 线上不带工具时请求体里**没有** tools 字段 ⇒ 这一段不存在，开销是零
        assertEquals(0, without.toolsChars, "线上不带 tools 字段时这一段不存在，开销是零")
        // ② 工具段字符数 = 与线上同构的那一段（不是逐个 schema 相加）
        assertEquals(expected, with.toolsChars, "工具段字符数必须与线上请求体同构")
        // ③ 估算的增量正好等于工具段长度
        assertEquals(
            expected,
            with.estimatedChars - without.estimatedChars,
            "带上工具定义后，估算必须正好增加工具段那么多字符",
        )
        // ④ 估算口径 = 前缀 + 工具定义段 + 正文
        assertEquals(
            100 + expected + contentChars(with.history),
            with.estimatedChars,
            "估算口径 = 前缀 + 工具定义段 + 正文",
        )
    }

    @Test
    fun `工具段也参与要不要压缩的判断`() = runBlocking {
        val tools = listOf(bashSchema())
        val expectedTools = buildJsonArray { tools.forEach { add(it) } }.toString().length
        val history = (1..10).map { UserMessage("消息$it " + "x".repeat(100)) }
        val historyChars = contentChars(history)
        // 预算卡在「正文自己装得下、加上工具段就装不下」之间
        val budget = historyChars + expectedTools - 1
        val policy = ContextPolicy(maxContextChars = budget, keepRecentChars = 200, summaryTargetChars = 200)

        val withoutTools = memory(FakeLlm(), policy).prepare(history, prefixChars = 0)
        val withTools = memory(FakeLlm(), policy).prepare(history, prefixChars = 0, tools = tools)

        // 基线：不带工具时，正文自己装得下
        assertEquals(historyChars, withoutTools.estimatedChars, "不带工具时估算就是正文长度")
        assertTrue(
            withoutTools.estimatedChars <= budget,
            "基线：正文装得下（${withoutTools.estimatedChars} <= $budget）",
        )
        assertFalse(withoutTools.compacted, "正文装得下 ⇒ 不该压缩")
        // 带上工具段就装不下了 ⇒ 该压
        assertTrue(withTools.compacted, "加上工具段就超预算了 ⇒ 该压")
        assertEquals(expectedTools, withTools.toolsChars, "工具段字符数")
    }

    // ---- ② 压缩之后按最终形态重算 --------------------------------------------

    @Test
    fun `压缩之后按最终形态重算_新摘要比旧摘要短时估算正好下降`() = runBlocking {
        // 第一轮压出**长**摘要，第二轮压出**短**摘要。
        val llm = ScriptedLlm(listOf(LONG_SUMMARY, SHORT_SUMMARY))
        val policy = ContextPolicy(maxContextChars = 2_000, keepRecentChars = 500, summaryTargetChars = 1_000)
        val mem = memory(llm, policy)
        val sim = PrefixSim(prompt = "提示词", digest = "长期记忆", alert = "告警", summaryTargetChars = 1_000)
        val history = (1..20).map { UserMessage("消息$it " + "x".repeat(100)) }

        val plan1 = mem.prepare(
            history = history,
            prefixChars = sim.planned(null),
            prefixCharsFor = { summary -> sim.actual(summary) },
        )
        // 第二轮：上一轮的长摘要已经在前缀里（预留位按 max(旧摘要, 目标长度) 算）
        val plan2 = mem.prepare(
            history = history,
            prefixChars = sim.planned(LONG_SUMMARY),
            prefixCharsFor = { summary -> sim.actual(summary) },
        )

        assertTrue(plan1.compacted, "第一轮该压")
        assertTrue(plan2.compacted, "第二轮该压")

        // ★ 压缩后的估算必须等于「最终形态」：压缩后的前缀 + 保留下来的历史
        assertEquals(
            sim.actual(SHORT_SUMMARY) + plan2.toolsChars + contentChars(plan2.history),
            plan2.estimatedChars,
            "压缩后的估算必须按最终形态算（新摘要替掉旧摘要位）",
        )
        // ★ 新摘要比旧摘要短 243 字符 ⇒ 估算必须正好下降 243。
        //   按压缩前的口径算（prefixChars 里还留着旧摘要的预留位）就不会降 ⇒ 这条会红。
        assertEquals(
            243,
            plan1.estimatedChars - plan2.estimatedChars,
            "新摘要比旧摘要短 243 字符 ⇒ 估算必须正好下降 243",
        )
    }

    @Test
    fun `没压缩时按预留位算_保证值语义不变`() = runBlocking {
        val policy = ContextPolicy(maxContextChars = 100_000, summaryTargetChars = 1_000)
        val mem = memory(FakeLlm(), policy)
        val sim = PrefixSim(prompt = "提示词", digest = "长期记忆", alert = "告警", summaryTargetChars = 1_000)
        val history = listOf(UserMessage("你好"), UserMessage("继续"))

        val plan = mem.prepare(
            history = history,
            prefixChars = sim.planned(null),
            prefixCharsFor = { summary -> sim.actual(summary) },
        )

        assertFalse(plan.compacted, "没超容量就不该压缩")
        // 没压缩 ⇒ 用**保证值**（摘要按预留位算），不是实际长度
        assertEquals(sim.planned(null), plan.prefixChars, "没压缩时前缀按预留位算")
        assertEquals(
            sim.planned(null) + plan.toolsChars + contentChars(plan.history),
            plan.estimatedChars,
            "没压缩时估算 = 保证值前缀 + 工具段 + 正文",
        )
        // 而实际长度（这一轮还没有摘要段）比保证值小 —— 预留位是给「摘要可能出现」留的
        assertTrue(
            plan.prefixChars > sim.actual(null),
            "预留位比实际长度大（${plan.prefixChars} > ${sim.actual(null)}）",
        )
    }

    // ---- ③ 压完复查预算 ------------------------------------------------------

    @Test
    fun `压完仍然超预算要看得见`() = runBlocking {
        // 预算小到「压完也装不下」（保留的原文本身就超了）。
        val policy = ContextPolicy(maxContextChars = 300, keepRecentChars = 500, summaryTargetChars = 200)
        val mem = memory(FakeLlm(), policy)
        val history = (1..20).map { UserMessage("消息$it " + "x".repeat(100)) }

        val plan = mem.prepare(history, prefixChars = 100)

        // 基线一：确实压了
        assertTrue(plan.compacted, "该压还是要压")
        // 基线二：估算 = 前缀 + 工具段 + 保留的历史（最终形态）
        assertEquals(
            100 + plan.toolsChars + contentChars(plan.history),
            plan.estimatedChars,
            "估算口径 = 前缀 + 工具定义段 + 正文",
        )
        // 基线三：压完的估算确实还超预算
        assertTrue(
            plan.estimatedChars > policy.maxContextChars,
            "基线：压完的估算确实还超预算（${plan.estimatedChars} > ${policy.maxContextChars}）",
        )
        // ★ 只报不压：压完还超 ⇒ 必须看得出来
        assertTrue(plan.overBudget, "压完还超预算 ⇒ overBudget 必须为真")
    }

    @Test
    fun `压得下就报告没超预算`() = runBlocking {
        val policy = ContextPolicy(maxContextChars = 1_500, keepRecentChars = 500, summaryTargetChars = 200)
        val mem = memory(FakeLlm(), policy)
        val history = (1..20).map { UserMessage("消息$it " + "x".repeat(100)) }

        val plan = mem.prepare(history, prefixChars = 100)

        // 基线一：压缩前**确实超了**（否则压缩根本不会触发，这条用例就没意义）
        assertTrue(
            100 + contentChars(history) > policy.maxContextChars,
            "基线：压缩前的估算确实超预算，否则压缩不会触发",
        )
        // 基线二：确实压了
        assertTrue(plan.compacted, "超容量就该压缩")
        // 基线三：估算就是压完之后那个数（最终形态）
        assertEquals(
            100 + plan.toolsChars + contentChars(plan.history),
            plan.estimatedChars,
            "估算必须是压完之后那个数",
        )
        // ★ 压得下 ⇒ 不该报超预算
        //   （用压缩前的估算判就会误报成超 ⇒ 这条会红）
        assertFalse(plan.overBudget, "压得下就报告没超预算")
    }

    // ---- ④ 透给宿主 ----------------------------------------------------------

    @Test
    fun `宿主能从公开返回值上看到容量口径的三个数字`() = runBlocking {
        val tools = listOf(bashSchema())
        val expectedTools = buildJsonArray { tools.forEach { add(it) } }.toString().length
        val policy = ContextPolicy(maxContextChars = 600, keepRecentChars = 500, summaryTargetChars = 200)
        val mem = memory(FakeLlm(), policy)
        val sim = PrefixSim(prompt = "提示词", digest = "长期记忆", alert = "告警", summaryTargetChars = 200)
        val history = (1..20).map { UserMessage("消息$it " + "x".repeat(100)) }

        // 宿主拿到的就是这个**公开**返回值（ContextPlan 是公开类型，字段都是公开的）。
        val plan: ContextPlan = mem.prepare(
            history = history,
            prefixChars = sim.planned(null),
            tools = tools,
            prefixCharsFor = { summary -> sim.actual(summary) },
        )

        // ① 工具段开销 —— 宿主看得到，而且与线上同构
        assertEquals(expectedTools, plan.toolsChars, "宿主要看得见工具段开销")
        // ② 按最终形态重算后的估算 —— 宿主看得到
        assertEquals(
            sim.actual(plan.summary) + expectedTools + contentChars(plan.history),
            plan.estimatedChars,
            "宿主要看得见按最终形态算出来的估算",
        )
        // ③ 是否仍超预算 —— 宿主看得到（这里预算很小，压完也超）
        assertTrue(
            plan.estimatedChars > policy.maxContextChars,
            "基线：压完的估算确实还超预算（${plan.estimatedChars} > ${policy.maxContextChars}）",
        )
        assertTrue(plan.overBudget, "宿主要看得见「压完仍然超预算」")
    }

    // ---- 替身 ---------------------------------------------------------------

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-cap-${System.nanoTime()}").also { it.mkdirs() }

    private fun memory(llm: LlmClient, policy: ContextPolicy) = ContextMemory(
        llm = llm,
        model = "test-model",
        store = MemoryStore(tempDir()),
        policy = policy,
        clock = { 1_000_000L },
    )

    /** 工具 schema（形状与线格式一致：type/function/name/description/parameters）。 */
    private fun bashSchema(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "bash")
            put("description", "在设备上执行一条 shell 命令。返回 stdout/stderr 与退出码。")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "要执行的命令行")
                    })
                })
                put("required", buildJsonArray { })
            })
        })
    }

    private fun echoSchema(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "echo")
            put("description", "回显一段文字。")
            put("parameters", buildJsonObject { put("type", "object") })
        })
    }

    /**
     * 正文字符数（与 SDK 内部同一套口径：只看正文，不看 JSON 封装）。
     */
    private fun contentChars(messages: List<ChatMessage>): Int = messages.sumOf { m ->
        when (m) {
            is UserMessage -> m.content.length
            is AssistantMessage ->
                (m.content?.length ?: 0) +
                    m.toolCalls.sumOf { it.name.length + it.argsOrEmpty().toString().length }
            is ToolMessage -> m.content.length
            is SystemMessage -> m.content.length
            else -> 0
        }
    }

    /**
     * 模拟门面的前缀拼法：提示词 → 长期记忆 → 会话摘要（标题 + 正文）→ 工作区告警。
     *
     * 段与段之间按**空行**拼（多一段多 2 个字符），与门面的算法一致。
     */
    private class PrefixSim(
        private val prompt: String,
        private val digest: String,
        private val alert: String,
        private val summaryTargetChars: Int,
    ) {
        /** 前缀里为会话摘要预留的位（标题 + 摘要正文）。 */
        fun reserve(priorSummary: String?): Int {
            val body = priorSummary?.let { maxOf(it.length, summaryTargetChars) } ?: summaryTargetChars
            return SUMMARY_HEADER.length + body
        }

        /** 协调后的「保证值」：摘要按**预留位**算。 */
        fun planned(priorSummary: String?): Int = joinLengths(
            prompt.length,
            digest.length,
            reserve(priorSummary),
            alert.length,
        )

        /** **实际**会发出去的前缀字符数（新摘要替掉旧摘要位）。 */
        fun actual(summary: String?): Int = joinLengths(
            prompt.length,
            digest.length,
            summary?.let { SUMMARY_HEADER.length + it.length } ?: 0,
            alert.length,
        )

        /** 多段 system 拼起来的总字符数：每多一段就多 2 个字符（后端用空行拼）。 */
        private fun joinLengths(vararg lengths: Int): Int {
            val kept = lengths.filter { it > 0 }
            if (kept.isEmpty()) return 0
            return kept.sum() + (kept.size - 1) * 2
        }
    }

    private companion object {
        /** 会话摘要进前缀时的标题（与门面同字面）。 */
        const val SUMMARY_HEADER = "## 之前的对话摘要（压缩后的历史）\n"

        /** 27 个字符。 */
        const val SUMMARY_BODY = "事实：用户在做安卓端 agent SDK 的开源工作。"

        /** 长摘要：270 个字符。 */
        val LONG_SUMMARY: String = SUMMARY_BODY.repeat(10)

        /** 短摘要：27 个字符（比长摘要短 243 个字符）。 */
        val SHORT_SUMMARY: String = SUMMARY_BODY
    }
}
