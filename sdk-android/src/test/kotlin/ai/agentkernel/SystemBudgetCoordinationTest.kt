package ai.agentkernel

import ai.agentkernel.android.AndroidAgentSdk
import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.memory.ContextMemory
import ai.agentkernel.memory.ContextPolicy
import ai.agentkernel.memory.MemoryEntry
import ai.agentkernel.memory.MemoryStore
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **客户端 system 段的字符预算协调**。
 *
 * 后端把客户端 system 段**硬截到固定长度**，超了**悄悄砍尾巴** ——
 * 而尾巴上排的正是「长期记忆 digest / 会话摘要 / 工作区告警」。
 * 所以 SDK 不能只体检，必须**在发出去之前**就协调好：
 *
 * - 砍得动的只有**长期记忆 digest**（`agent-memory search` 能取回来）；
 * - 提示词 / 会话摘要 / 工作区告警**一律不砍**；
 * - 砍完还超 ⇒ **报出来**（`SystemBudget.overflow`），不静默。
 *
 * ★ 另一条更硬的：**协调必须发生在 `prepare()` 之前**。
 * 压缩请求要**逐字重放**稳定前缀，digest 截断若发生在重放之后，
 * 压缩那次调用就**吃不到上游缓存** —— 本文件里有一条用例专门盯这个。
 */
class SystemBudgetCoordinationTest {

    // ---- ① 空间够：记忆完整进前缀 ------------------------------------------

    @Test
    fun `空间够时长期记忆完整进前缀`() {
        val mem = memoryWith(policy = ContextPolicy(memoryDigestChars = 1500), "一条记忆的正文")
        val sdk = sdk(prompt = "P".repeat(100), limit = 6000, memory = mem)

        val b = sdk.systemBudget()
        assertTrue(b.memoryChars > 0, "记忆应该进来了：${b.memoryChars}")
        assertEquals(
            mem.digest()!!.length,
            b.memoryChars,
            "记忆不该被截断（上限 6000，记忆自己才 1500）",
        )
        assertTrue(!b.over, "不该超限：${b.totalChars}/${b.limitChars}")
        assertTrue(!b.overflow, "不该溢出")
    }

    // ---- ② 空间不够：先砍记忆，摘要位保住 -----------------------------------

    @Test
    fun `空间不够时先砍长期记忆digest且不砍摘要`() {
        // 记忆要**够长**才有得砍：完整摘要（1500 上限）约 1400 字符，
        // 而这一轮能给记忆的只有一百多 —— 砍不砍得动，差别才看得出来。
        val mem = memoryWith(
            policy = ContextPolicy(memoryDigestChars = 1500, summaryTargetChars = 100),
            "记忆正文\n第二行\n".repeat(60),
        )
        val sdk = sdk(prompt = "P".repeat(100), limit = 400, memory = mem)

        val b = sdk.systemBudget()
        val full = mem.digest()!!.length
        assertTrue(b.memoryChars < full, "记忆应该被砍短：${b.memoryChars} vs $full")
        assertTrue(b.memoryChars > 0, "还有空间就不该把记忆砍光：${b.memoryChars}")
        assertTrue(b.reservedSummaryChars > 0, "摘要位必须留着（摘要可能这一轮才出现）")
        assertTrue(b.plannedChars <= 400, "协调后不该超限：${b.plannedChars}")
    }

    // ---- ③ 提示词本身就太大：溢出必须看得见 --------------------------------

    @Test
    fun `提示词本身就超限时overflow可见且记忆一分不给`() {
        val mem = memoryWith(policy = ContextPolicy(), "记忆正文")
        val sdk = sdk(prompt = "P".repeat(3000), limit = 1000, memory = mem)

        val b = sdk.systemBudget()
        assertEquals(0, b.memoryChars, "记忆应该一分不给（不可压的部分已经超了）")
        assertTrue(b.overflow, "砍光记忆也装不下 ⇒ overflow 必须为真")
        assertTrue(b.over, "实际也超了")
    }

    // ---- ④ ★ 协调必须发生在 prepare() 之前（缓存命中的前提）-----------------

    @Test
    fun `压缩请求重放的digest与主请求逐字一致`() = runBlocking {
        val summaryLlm = RecordingLlm(reply = "压缩出来的摘要")
        val mainLlm = RecordingLlm(reply = "主回复")
        val policy = ContextPolicy(
            maxContextChars = 1500,
            keepRecentChars = 200,
            summaryTargetChars = 100,
            memoryDigestChars = 1500,
        )
        val mem = memoryWith(policy = policy, "记忆正文\n".repeat(60))
        val sdk = sdk(prompt = "P".repeat(200), limit = 400, memory = mem, llm = mainLlm, memoryLlm = summaryLlm)

        // 历史够长 ⇒ 触发压缩。
        val history = (1..40).map { UserMessage("消息$it " + "x".repeat(100)) }
        sdk.runTurn(history)

        assertEquals(1, summaryLlm.requests.size, "应该发生了一次压缩：${summaryLlm.requests.size}")
        assertEquals(1, mainLlm.requests.size, "主请求应该只有一次：${mainLlm.requests.size}")

        val compactDigest = digestOf(summaryLlm.requests[0])
        val mainDigest = digestOf(mainLlm.requests[0])
        assertEquals(
            compactDigest,
            mainDigest,
            "压缩请求重放的前缀必须与主请求逐字一致 —— 不一致则压缩那次调用吃不到上游缓存",
        )
        assertTrue(compactDigest.isNotEmpty(), "digest 不该是空的")
        assertTrue(
            compactDigest.length < mem.digest()!!.length,
            "这一轮记忆确实被截断了（${compactDigest.length} < ${mem.digest()!!.length}）",
        )
    }

    // ---- ⑤ 压缩之后摘要进前缀，体检数字要全 --------------------------------

    @Test
    fun `压缩之后体检里能看见摘要`() = runBlocking {
        val summaryLlm = RecordingLlm(reply = "压缩出来的摘要")
        val mem = memoryWith(
            policy = ContextPolicy(maxContextChars = 1500, keepRecentChars = 200, summaryTargetChars = 100),
            "记忆正文\n".repeat(60),
        )
        val sdk = sdk(prompt = "P".repeat(100), limit = 4000, memory = mem, llm = RecordingLlm(), memoryLlm = summaryLlm)

        sdk.runTurn((1..40).map { UserMessage("消息$it " + "x".repeat(100)) })

        val b = sdk.systemBudget()
        assertTrue(b.summaryChars > 0, "压缩出了摘要 ⇒ 体检里要看得见：${b.summaryChars}")
        assertTrue(
            b.reservedSummaryChars >= b.summaryChars,
            "预留位不能小于实际摘要（否则下一轮会挤掉别的）：${b.reservedSummaryChars} < ${b.summaryChars}",
        )
        assertTrue(b.memoryChars > 0, "记忆也在")
    }

    // ---- ⑥ 没有上限（直连官方）时什么都不动 ---------------------------------

    @Test
    fun `不限上限时记忆保持原样`() {
        val mem = memoryWith(policy = ContextPolicy(memoryDigestChars = 1500), "记忆正文")
        val sdk = sdk(prompt = "P".repeat(100), limit = null, memory = mem)

        val b = sdk.systemBudget()
        assertEquals(mem.digest()!!.length, b.memoryChars, "没有上限就不该动记忆")
        assertEquals(null, b.limitChars)
        assertTrue(!b.over && !b.overflow)
    }

    // ---- 替身 ---------------------------------------------------------------

    /** 记下每个请求 —— 预算协调要断言「两次请求里的 digest 逐字一致」。 */
    private class RecordingLlm(private val reply: String = "ok") : LlmClient {
        val requests = mutableListOf<ChatRequest>()
        override suspend fun complete(request: ChatRequest): AssistantMessage {
            requests += request
            return AssistantMessage(content = reply)
        }
    }

    private fun digestOf(request: ChatRequest): String =
        request.messages
            .filterIsInstance<SystemMessage>()
            .map { it.content }
            .firstOrNull { it.contains("长期记忆") }
            ?: ""

    private fun memoryWith(policy: ContextPolicy, vararg summaries: String): ContextMemory {
        val store = MemoryStore(File(tempDir(), "memory"))
        summaries.forEachIndexed { i, body ->
            store.append(
                MemoryEntry(
                    id = "2026010${i + 1}-000000",
                    createdAt = 1_700_000_000_000L + i,
                    title = "记忆标题${i + 1}",
                    summary = body,
                )
            )
        }
        return ContextMemory(
            llm = RecordingLlm(),
            model = "test-model",
            store = store,
            policy = policy,
        )
    }

    private fun sdk(
        prompt: String? = null,
        limit: Int? = 6000,
        memory: ContextMemory? = null,
        llm: LlmClient = RecordingLlm(),
        memoryLlm: LlmClient? = null,
    ): AndroidAgentSdk {
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-budget-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        val layout = PrefixLayout(base.path)
        // 压缩用的模型客户端要能换（要断言压缩请求里的 digest）。
        val mem = memory?.let { m ->
            if (memoryLlm == null) m
            else ContextMemory(
                llm = memoryLlm,
                model = "test-model",
                store = m.memoryStore(),
                policy = m.policy,
            )
        }
        return AndroidAgentSdk(
            llm = llm,
            model = "test-model",
            executor = AndroidCommandExecutor(shellPath = "sh", layout = layout),
            systemPrompt = prompt,
            systemBudgetLimitChars = limit,
            memory = mem,
        )
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-budget-${System.nanoTime()}").also { it.mkdirs() }
}
