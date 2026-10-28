package ai.agentkernel.memory

import ai.agentkernel.archive.ArchiveSegmentMeta
import ai.agentkernel.archive.ArchiveStore
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        llm: LlmClient,
        policy: ContextPolicy,
        now: () -> Long,
        archive: ArchiveStore? = null,
    ) = ContextMemory(
        llm = llm,
        model = "test-model",
        store = MemoryStore(tempDir()),
        policy = policy,
        clock = now,
        archive = archive,
    )

    /** 基准时刻（2026-10-29 前后）；跨天测试在它上面加 [twoDays]。 */
    private val baseNow: Long = 1_793_203_200_000L

    /** 48 小时 —— 跨它一定是另一个自然日（时区/夏令时都算得过来）。 */
    private val twoDays: Long = 2L * 24L * 60L * 60L * 1000L

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
        // ⚠️ 压缩请求的 tools **必须与主请求逐字一致**（否则那次调用不吃缓存）——
        //    这条断言在「压缩请求是逐字重放的」那条里。
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
    fun `第二次压缩靠重放前缀带上一次的摘要`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        // 主请求的稳定前缀里带着上一轮的摘要（它本来就是一个 system 段）。
        val priorSummary = SystemMessage("## 之前的对话摘要（压缩后的历史）\n用户在配网关")
        mem.prepare(bigHistory(20), prefixChars = 0, replayPrefix = listOf(priorSummary))
        mem.prepare(bigHistory(20), prefixChars = 0, replayPrefix = listOf(priorSummary))

        assertEquals(2, llm.requests.size)
        val second = llm.requests[1]
        // 上一轮的摘要**逐字**出现在重放的前缀里 —— 否则第一次压掉的内容就永久丢了
        assertEquals(listOf<ChatMessage>(priorSummary), second.messages.take(1))
        assertTrue(
            second.messages.none { it is UserMessage && it.content.contains("用户在配网关") },
            "上一轮摘要不该再被塞进 user 消息（会变成同一段内容出现两遍）：" +
                (second.messages.last() as UserMessage).content,
        )
    }

    // ---- 压缩请求的形状（dsh 式逐字重放）----

    @Test
    fun `压缩请求是逐字重放的`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        val replayPrefix = listOf<ChatMessage>(
            SystemMessage("你是设备上的助手"),
            SystemMessage("## 你之前和用户聊过什么（长期记忆）\n- 配网关"),
        )
        val tools = listOf(
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "bash") })
            }
        )
        val history = bigHistory(20)

        mem.prepare(history, prefixChars = 0, replayPrefix = replayPrefix, tools = tools)

        val request = llm.requests.single()
        // ① 前缀 → 被遮蔽区（原样）→ 压缩指令（最后一条）
        val middle = request.messages.drop(replayPrefix.size).dropLast(1)
        assertTrue(middle.isNotEmpty(), "被遮蔽区不该是空的")
        assertEquals(replayPrefix, request.messages.take(replayPrefix.size))
        assertEquals(history.take(middle.size), middle, "被遮蔽区必须逐字重放")
        assertTrue(request.messages.last() is UserMessage, "最后一条必须是压缩指令（user 消息）")

        // ② 被遮蔽区的内容**逐字**出现，不是 render 出来的「[用户] …」文本
        val contents = request.messages.filterIsInstance<UserMessage>().map { it.content }
        assertTrue(
            contents.contains("消息1 " + "x".repeat(60)),
            "被遮蔽区的消息必须逐字出现：$contents",
        )
        assertTrue(
            contents.none { it.startsWith("[用户]") },
            "不能把消息渲染成文本 —— 那样前缀对不上、缓存不命中：$contents",
        )

        // ③ tools 与主请求**完全一致**（不一致 = 压缩这次调用不吃缓存）
        assertEquals(tools, request.tools)

        // ④ 网关据此识别压缩请求
        assertEquals(JsonPrimitive(true), request.extra["compact"])
    }

    @Test
    fun `压缩指令在同一 targetChars 下逐字相同`() = runBlocking {
        val llm = FakeLlm()
        val summarizer = ConversationSummarizer(llm, "test-model")

        summarizer.summarize(listOf(UserMessage("要压的内容")), targetChars = 1_200)
        summarizer.summarize(listOf(UserMessage("要压的内容")), targetChars = 1_200)
        summarizer.summarize(listOf(UserMessage("要压的内容")), targetChars = 800)

        val first = (llm.requests[0].messages.last() as UserMessage).content
        val second = (llm.requests[1].messages.last() as UserMessage).content
        val other = (llm.requests[2].messages.last() as UserMessage).content
        // 指令会进请求 —— 差一个字就多一次缓存失效
        assertEquals(first, second, "同一 targetChars 下指令必须逐字相同")
        assertTrue(first != other, "不同 targetChars 的指令应该不同（长度约束得生效）")
        assertTrue(first.contains("1200"), "指令里要带上长度约束：$first")
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

    @Test
    fun `切分点不能落在工具结果上`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        // keepRecentChars 调得刚好让切分点落在那条 ToolMessage 上。
        val mem = memory(llm, ContextPolicy(maxContextChars = 200, keepRecentChars = 150), { now })

        val history: List<ChatMessage> = listOf(
            UserMessage("A" + "x".repeat(100)),
            AssistantMessage(
                content = null,
                toolCalls = listOf(ToolCall(id = "c1", name = "bash", arguments = "{}")),
            ),
            ToolMessage("c1", "B" + "y".repeat(100)),
            UserMessage("C" + "z".repeat(100)),
        )

        val plan = mem.prepare(history, prefixChars = 0)

        assertTrue(plan.compacted, "该压还是要压")
        // ① 主请求的保留段不能以工具结果开头 —— 它的 assistant(tool_calls) 已经被压进摘要了
        assertTrue(
            plan.history.first() !is ToolMessage,
            "保留段不能以工具结果开头（它的 tool_calls 被压没了，上游会 400）：${plan.history.first()}",
        )
        // ② 压缩请求重放的前缀不能以「没被回答的 tool_calls」结尾
        val replayed = llm.requests.single().messages.dropLast(1)
        assertTrue(
            replayed.none { it is AssistantMessage && it.toolCalls.isNotEmpty() },
            "被遮蔽区里不能有没被回答的工具调用：$replayed",
        )
        // ③ 工具调用那一组要**整组**留在保留段里
        assertTrue(
            plan.history.any { it is AssistantMessage && it.toolCalls.isNotEmpty() },
            "assistant(tool_calls) 应该和它的工具结果留在同一侧：${plan.history}",
        )
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

    // ---- 每日摘要槽（闲置归档写「当天一条」，后来的替换前面的）----

    @Test
    fun `同一天两次闲置归档只剩一条`() = runBlocking {
        val llm = ScriptedLlm(listOf("第一段话题的结论", "第二段话题的结论"))
        var now = baseNow
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        // 第一段话题
        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 120_000
        mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")),
            prefixChars = 0,
        )
        assertEquals(1, mem.memoryStore().list().size, "第一次闲置归档要落一条")

        // 同一天又闲置了一次
        now += 120_000
        val plan = mem.prepare(
            listOf(UserMessage("新问题"), AssistantMessage("第二个回答"), UserMessage("再一个问题")),
            prefixChars = 0,
        )

        assertNotNull(plan.archived, "第二次闲置也要归档")
        assertEquals(
            1,
            mem.memoryStore().list().size,
            "同一天只能有一条每日摘要（后来的替换前面的，不是追加）",
        )
    }

    @Test
    fun `同一天再归档会替换标题和正文`() = runBlocking {
        val llm = ScriptedLlm(listOf("第一段话题的结论\n细节一", "第二段话题的结论\n细节二"))
        var now = baseNow
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 120_000
        mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")),
            prefixChars = 0,
        )
        now += 120_000
        mem.prepare(
            listOf(UserMessage("新问题"), AssistantMessage("第二个回答"), UserMessage("再一个问题")),
            prefixChars = 0,
        )

        val list = mem.memoryStore().list()
        assertEquals(1, list.size, "同一天只留一条")
        val entry = list.single()
        // ★ 替换的是**整条** —— 标题和正文都得是新的
        assertTrue(entry.title.contains("第二段话题"), "标题要换成新的：${entry.title}")
        assertTrue(!entry.title.contains("第一段话题"), "旧标题不该留着：${entry.title}")
        assertTrue(entry.summary.contains("第二段话题"), "正文要换成新的：${entry.summary}")
        assertTrue(!entry.summary.contains("第一段话题"), "旧正文不该留着：${entry.summary}")
    }

    @Test
    fun `跨天归档是两个不同的槽位`() = runBlocking {
        val llm = ScriptedLlm(listOf("第一天的话题", "第二天的话题"))
        var now = baseNow
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 120_000
        mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")),
            prefixChars = 0,
        )

        // 跨天（48 小时 ⇒ 一定是另一个自然日）
        now += twoDays
        mem.prepare(
            listOf(UserMessage("新问题"), AssistantMessage("第二个回答"), UserMessage("再一个问题")),
            prefixChars = 0,
        )

        val list = mem.memoryStore().list()
        assertEquals(2, list.size, "跨天就该是两个槽位（老那天的还在磁盘上）")
        assertTrue(
            list.any { it.summary.contains("第一天的话题") },
            "第一天那条要还在：${list.map { it.summary }}",
        )
        assertTrue(
            list.any { it.summary.contains("第二天的话题") },
            "第二天要另写一条：${list.map { it.summary }}",
        )
    }

    @Test
    fun `手动归档也写当天的每日摘要槽`() = runBlocking {
        val llm = ScriptedLlm(listOf("手动归档的结论\n第一次", "手动归档的结论\n第二次"))
        var now = baseNow
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now })

        val first = mem.archiveNow(listOf(UserMessage("问题"), AssistantMessage("回答"), UserMessage("新一轮")))
        assertNotNull(first)
        assertEquals(1, mem.memoryStore().list().size, "手动归档也要落一条")

        // 同一天再手动归档一次 ⇒ 还是一条（替换）
        val second = mem.archiveNow(listOf(UserMessage("问题2"), AssistantMessage("回答2"), UserMessage("新一轮2")))
        assertNotNull(second)
        val list = mem.memoryStore().list()
        assertEquals(1, list.size, "手动归档也是每日槽：同一天只留一条")
        assertTrue(list.single().summary.contains("第二次"), "正文要被替换成新的：${list.single().summary}")
        assertTrue(!list.single().summary.contains("第一次"), "旧正文不该留着：${list.single().summary}")
    }

    @Test
    fun `压缩出来的摘要不进长期记忆`() = runBlocking {
        val llm = ScriptedLlm(listOf("压缩出来的会话摘要"))
        var now = baseNow
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        val plan = mem.prepare(bigHistory(20), prefixChars = 0)

        assertTrue(plan.compacted, "超容量就该压缩")
        assertNotNull(plan.summary, "压缩完要有会话摘要")
        assertNull(plan.archived, "压缩不是归档")
        // ★ 压缩摘要进的是**稳定前缀**，不是长期记忆
        assertEquals(0, mem.memoryStore().list().size, "压缩不写长期记忆")
    }

    @Test
    fun `压缩不会在另一天多开一个每日摘要槽`() = runBlocking {
        val llm = ScriptedLlm(listOf("第一天手动归档的结论", "压缩出来的会话摘要"))
        var now = baseNow
        val mem = memory(
            llm,
            ContextPolicy(maxContextChars = 500, keepRecentChars = 100, idleArchiveMillis = 60_000),
            { now },
        )

        // 第一天：手动归档 ⇒ 占掉当天的每日槽
        mem.archiveNow(listOf(UserMessage("问题"), AssistantMessage("回答"), UserMessage("新一轮")))
        assertEquals(1, mem.memoryStore().list().size)

        // 跨天 + 压缩：压缩只进前缀，不该再写一条每日摘要
        now += twoDays
        val plan = mem.prepare(bigHistory(20), prefixChars = 0)

        assertTrue(plan.compacted, "该压还是要压")
        assertEquals(2, llm.requests.size, "一次归档摘要 + 一次压缩")
        assertEquals(
            1,
            mem.memoryStore().list().size,
            "压缩不写长期记忆（也不该在另一天多开一个每日槽）",
        )
    }

    // ---- 分段归档（原文留存）----

    @Test
    fun `压缩掉的原文会落一段归档`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val archive = ArchiveStore(tempDir())
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now }, archive)

        val history = bigHistory(20)
        val plan = mem.prepare(history, prefixChars = 0)

        // ★ 摘要**不删原文** —— 被压掉的那一段要能在归档里原样找回来。
        val segment = plan.archivedSegment
        assertNotNull(segment, "压缩完要回报新落的归档段")
        assertEquals(ArchiveSegmentMeta.KIND_COMPACT, segment.kind)

        val stored = archive.read(segment.id)
        assertNotNull(stored, "归档段要能读回来")
        assertTrue(stored.messages.isNotEmpty(), "归档段里要有原文")
        assertTrue(
            stored.messages.any { it is UserMessage && it.content.startsWith("消息1 ") },
            "归档里要能翻到被压掉的第一条原文",
        )
        // 压缩后主请求里的历史变短了，但归档里那段是**全的**
        assertTrue(stored.messages.size < history.size, "归档的是被压掉的那一段，不是整段历史")
    }

    @Test
    fun `闲置归档的原文也会落一段归档`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val archive = ArchiveStore(tempDir())
        val mem = memory(llm, ContextPolicy(idleArchiveMillis = 60_000), { now }, archive)

        mem.prepare(listOf(UserMessage("第一个问题"), AssistantMessage("回答")), prefixChars = 0)
        now += 120_000
        val plan = mem.prepare(
            listOf(UserMessage("第一个问题"), AssistantMessage("回答"), UserMessage("新问题")),
            prefixChars = 0,
        )

        val segment = plan.archivedSegment
        assertNotNull(segment, "闲置归档也要落一段")
        assertEquals(ArchiveSegmentMeta.KIND_IDLE, segment.kind)

        val stored = archive.read(segment.id)!!
        assertEquals(2, stored.messages.size, "归档的是「上一段」对话，不含本轮新问题")
        assertEquals("第一个问题", (stored.messages[0] as UserMessage).content)
    }

    @Test
    fun `不配归档仓库也不影响压缩`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now })

        val plan = mem.prepare(bigHistory(20), prefixChars = 0)

        assertTrue(plan.compacted)
        assertNull(plan.archivedSegment, "没配归档仓库就不该有归档段")
        assertNull(mem.archiveStore())
    }

    @Test
    fun `多次压缩会落多段且段间有链`() = runBlocking {
        val llm = FakeLlm()
        var now = 1_000_000L
        val archive = ArchiveStore(tempDir())
        val mem = memory(llm, ContextPolicy(maxContextChars = 500, keepRecentChars = 100), { now }, archive)

        mem.prepare(bigHistory(20), prefixChars = 0)
        now += 1_000
        mem.prepare(bigHistory(20), prefixChars = 0)

        assertEquals(2, archive.size(), "压两次就该有两段归档")
        val list = archive.list()
        assertEquals(2, list.size)
        // 链：更早的那段的 nextId 指向更晚的
        assertEquals(list[0].id, list[1].nextId)
        assertEquals(list[1].id, list[0].prevId)
    }
}

/**
 * 按剧本一句一句回摘要的假模型客户端。
 *
 * [FakeLlm] 每次都回同一句 —— 测「同一天后来的替换前面的」得能分清前后两段摘要，
 * 所以这里按调用顺序回不同的内容。
 */
internal class ScriptedLlm(private val replies: List<String>) : LlmClient {
    val requests = mutableListOf<ChatRequest>()
    private var index = 0

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        requests += request
        val reply = if (index < replies.size) replies[index] else replies.lastOrNull().orEmpty()
        index++
        return AssistantMessage(content = reply)
    }
}
