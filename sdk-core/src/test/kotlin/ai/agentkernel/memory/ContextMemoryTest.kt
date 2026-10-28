package ai.agentkernel.memory

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上下文压缩 + 闲置归档 —— 纯 JVM。
 *
 * 这几条守的是**两个最容易做错的地方**：
 * - 压缩不能把内容丢了（第二次压缩要把上一次的摘要一起压）；
 * - 归档之后要**开新会话**（旧内容不再每轮重发）。
 */
class ContextMemoryTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-ctxmem-${System.nanoTime()}").also { it.mkdirs() }

    private fun memory(
        llm: FakeLlm,
        policy: ContextPolicy,
        now: () -> Long,
    ) = ContextMemory(
        llm = llm,
        model = "test-model",
        store = MemoryStore(tempDir()),
        policy = policy,
        clock = now,
    )

    private fun bigHistory(n: Int, size: Int = 60): List<UserMessage> =
        (1..n).map { UserMessage("消息$it " + "x".repeat(size)) }

    // ---- 压缩 ----

    @Test
    fun `没超容量就不压缩也不调 API`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 10_000, keepRecentChars = 1_000), { now })

        val history = listOf(UserMessage("你好"), AssistantMessage("在"), UserMessage("天气不错"))
        val plan = mem.prepare(history, prefixChars = 100)

        assertTrue(!plan.compacted, "没超容量就不该压缩")
        assertEquals(history, plan.history)
        assertEquals(0, llm.requests.size, "不该调 API")
        assertNull(plan.summary)
    }

    @Test
    fun `超容量就压一次`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        val history = bigHistory(20)
        val plan = mem.prepare(history, prefixChars = 0)

        assertTrue(plan.compacted, "超容量就该压缩")
        assertEquals(1, llm.requests.size, "只压一次")
        assertNotNull(plan.summary, "压缩完要有摘要")
        assertTrue(plan.history.size < history.size, "历史应该变短")
        // 摘要请求不能带工具 —— 那是给模型调工具用的
        assertTrue(llm.requests[0].tools.isEmpty(), "摘要请求不该带工具")
    }

    @Test
    fun `压缩后的历史能装进预算`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        val plan = mem.prepare(bigHistory(20), prefixChars = 0)
        val keptChars = plan.history.sumOf { (it as UserMessage).content.length }
        assertTrue(keptChars <= 200, "保留的原文应该远小于原历史，实际 $keptChars")
    }

    @Test
    fun `第二次压缩要把上一次的摘要一起压`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        mem.prepare(bigHistory(20), prefixChars = 0)
        mem.prepare(bigHistory(20), prefixChars = 0)

        assertEquals(2, llm.requests.size)
        val secondBody = (llm.requests[1].messages[1] as UserMessage).content
        assertTrue(
            secondBody.contains("【已有的摘要】"),
            "第二次压缩必须带上一次的摘要，否则第一次压掉的内容就永久丢了：$secondBody",
        )
    }

    @Test
    fun `关掉压缩就完全不压`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy.OFF, { now })

        val history = bigHistory(50)
        val plan = mem.prepare(history, prefixChars = 0)

        assertTrue(!plan.compacted)
        assertEquals(history, plan.history)
        assertEquals(0, llm.requests.size)
    }

    // ---- 闲置归档 ----

    @Test
    fun `闲置超时就把这一段归档成记忆并开新会话`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        // 第一轮：聊了点别的
        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)

        // 闲置两分钟后再来一句
        now += 120_000
        val plan = mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")),
            prefixChars = 0,
        )

        assertNotNull(plan.archived, "闲置到点就该归档")
        assertEquals(1, mem.memoryStore().list().size, "归档后磁盘上要有一条记忆")
        // 归档 = 开新会话：旧的不再每轮重发
        assertEquals(listOf<UserMessage>(UserMessage("新问题")), plan.history)
    }

    @Test
    fun `没闲置就不归档`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 1_000   // 才 1 秒
        val plan = mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("继续")),
            prefixChars = 0,
        )

        assertNull(plan.archived, "没到闲置阈值就不该归档")
        assertEquals(0, mem.memoryStore().list().size)
    }

    @Test
    fun `归档后的记忆会进摘要块`() = runBlocking {
        val llm = FakeLlm(reply = "事实：用户在配网关")
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 120_000
        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")), prefixChars = 0)

        val digest = mem.digest()
        assertNotNull(digest, "有记忆就该有摘要块")
        assertTrue(digest.contains("配网关"), "摘要块要带上记忆内容：$digest")
    }

    @Test
    fun `归档之后本会话的压缩摘要要清掉`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(
            llm,
            ContextPolicy(maxContextChars = 500, keepRecentChars = 100, idleArchiveMillis = 60_000),
            { now },
        )

        // 先压一次，产生会话摘要
        mem.prepare(bigHistory(20), prefixChars = 0)
        assertNotNull(mem.sessionSummary())

        // 闲置到点 → 归档 → 新会话 → 会话摘要应该清掉
        now += 120_000
        mem.prepare(bigHistory(20), prefixChars = 0)
        assertNull(mem.sessionSummary(), "归档后是新会话，不该还挂着上一次的压缩摘要")
    }

    @Test
    fun `手动归档也能用`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        val entry = mem.archiveNow(listOf(UserMessage("问题"), AssistantMessage("回答"), UserMessage("新一轮")))
        assertNotNull(entry)
        assertEquals(1, mem.memoryStore().list().size)
    }
}
