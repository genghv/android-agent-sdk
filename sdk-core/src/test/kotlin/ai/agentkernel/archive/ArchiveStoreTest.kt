package ai.agentkernel.archive

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话**分段归档** —— 纯 JVM。
 *
 * 守的是四件事：
 * - **不丢东西**：被摘要掉的原文还在归档里，能原样读回来；
 * - **链是通的**：段与段之间 prevId/nextId 能把时间顺序串起来；
 * - **分页不重不漏**：前端往上滚，一段一段拉，不重复也不跳过；
 * - **★ 容量有上限**：超了删最旧的段，最新的一段永远留着，删完链照样是通的。
 */
class ArchiveStoreTest {

    private fun store(): ArchiveStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "agentkernel-archive-${System.nanoTime()}")
        dir.mkdirs()
        return ArchiveStore(dir)
    }

    private fun msgs(vararg texts: String): List<ai.agentkernel.protocol.ChatMessage> =
        texts.map { UserMessage(it) }

    @Test
    fun `追加一段就能列出来`() {
        val s = store()
        val meta = s.append(ArchiveSegmentMeta.KIND_COMPACT, "第一段", "摘要一", msgs("a", "b"))

        assertEquals(1, s.size())
        val list = s.list()
        assertEquals(1, list.size)
        assertEquals(meta.id, list[0].id)
        assertEquals("第一段", list[0].title)
        assertEquals(2, list[0].messageCount)
    }

    @Test
    fun `段与段之间有链`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("x"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("y"))
        val c = s.append(ArchiveSegmentMeta.KIND_IDLE, "C", null, msgs("z"))

        // ★ 必须**读回存储里那份**来断言。
        //   append() 返回的是**快照** —— 之后再追加别的段，
        //   早段快照里的 nextId 就永远是 null（快照不会自己变）。
        val metaA = s.meta(a.id)!!
        val metaB = s.meta(b.id)!!
        val metaC = s.meta(c.id)!!

        // 新段的 prevId 指向**更早**的一段
        assertNull(metaA.prevId, "最早的一段没有更早的")
        assertEquals(metaA.id, metaB.prevId)
        assertEquals(metaB.id, metaC.prevId)
        // 更早的那段的 nextId 指向更晚的
        assertEquals(metaB.id, metaA.nextId)
        assertEquals(metaC.id, metaB.nextId)
        assertNull(metaC.nextId, "最新的一段没有更晚的")
    }

    @Test
    fun `索引坏了链还在`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("x"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("y"))

        // 把索引搞坏 —— 链是从正文目录重建的，不是从索引读的
        File(s.rootDir(), "index.json").writeText("{ 这不是 JSON")

        val metaA = s.meta(a.id)!!
        val metaB = s.meta(b.id)!!
        assertEquals(metaB.id, metaA.nextId, "索引坏了也要连上")
        assertEquals(metaA.id, metaB.prevId)
    }

    @Test
    fun `列表按新的在前`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("x"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("y"))

        val list = s.list()
        assertEquals(listOf(b.id, a.id), list.map { it.id }, "新的要排在前面")
    }

    @Test
    fun `分页不重不漏`() {
        val s = store()
        val ids = (1..5).map { s.append(ArchiveSegmentMeta.KIND_COMPACT, "第${it}段", null, msgs("m$it")).id }

        // 首屏：最新的那段
        val first = s.list(limit = 1)
        assertEquals(listOf(ids[4]), first.map { it.id })

        // 往上滚：游标传当前最早的那段 → 只给更早的
        val older = s.list(limit = 2, before = ids[3])
        assertEquals(listOf(ids[2], ids[1]), older.map { it.id })

        // 一直往上滚到头
        val oldest = s.list(limit = 2, before = ids[0])
        assertEquals(emptyList(), oldest.map { it.id })
    }

    @Test
    fun `原文能原样读回来`() {
        val s = store()
        val original = listOf(
            UserMessage("用户问了什么"),
            AssistantMessage(
                content = null,
                toolCalls = listOf(ToolCall(id = "c1", name = "bash", arguments = "{\"command\":\"ls -la\"}")),
                finishReason = "tool_calls",
            ),
            ToolMessage("c1", "total 14"),
            AssistantMessage(content = "答完了", finishReason = "stop"),
        )
        val meta = s.append(ArchiveSegmentMeta.KIND_COMPACT, "一段", "摘要", original)

        val seg = s.read(meta.id)
        assertNotNull(seg)
        assertEquals(original.size, seg.messages.size)

        val assistant = seg.messages[1] as AssistantMessage
        assertNull(assistant.content, "本来就没正文的回合，null 要保住")
        assertEquals(1, assistant.toolCalls.size)
        assertEquals("bash", assistant.toolCalls[0].name)
        assertEquals("{\"command\":\"ls -la\"}", assistant.toolCalls[0].arguments)
        assertEquals("tool_calls", assistant.finishReason)

        val tool = seg.messages[2] as ToolMessage
        assertEquals("c1", tool.toolCallId)
        assertEquals("total 14", tool.content)

        assertEquals("答完了", (seg.messages[3] as AssistantMessage).content)
    }

    @Test
    fun `索引坏了能从正文目录重建`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("x"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("y"))

        // 把索引搞坏
        File(s.rootDir(), "index.json").writeText("{ 这不是 JSON")

        val list = s.list()
        assertEquals(listOf(b.id, a.id), list.map { it.id }, "索引坏了要从正文目录重建")
        // 正文也要还在
        assertNotNull(s.read(a.id))
    }

    @Test
    fun `同秒内追加两段不会互相覆盖`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("x"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("y"))

        assertTrue(a.id != b.id, "同秒也要给出不同的 id")
        assertEquals(2, s.size())
        assertEquals("x", (s.read(a.id)!!.messages[0] as UserMessage).content)
        assertEquals("y", (s.read(b.id)!!.messages[0] as UserMessage).content)
    }

    @Test
    fun `摘要和原文都在`() {
        val s = store()
        val meta = s.append(ArchiveSegmentMeta.KIND_COMPACT, "标题", "这是摘要", msgs("原文一行", "原文二行"))

        assertEquals("这是摘要", meta.summary)
        assertEquals("标题", meta.title)
        // 字数 = 两行原文的实际长度（4 + 4）
        assertEquals(8, meta.chars)
        assertEquals(2, s.read(meta.id)!!.messages.size)
    }

    // ---- 容量上限 ----

    @Test
    fun `段数超上限就删最旧的`() {
        val s = store()
        s.applyLimits(maxSegments = 2, maxChars = 0)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("c"))

        // ★ 淘汰在 append 之后**立刻**生效：第三段一落地就超了 2 段 ⇒ 立刻删最旧的 A
        assertEquals(2, s.size())
        assertEquals(listOf(c.id, b.id), s.list().map { it.id }, "留最新的两段")
        assertNull(s.read(a.id), "最旧的段要被删掉")
        assertNotNull(s.read(b.id))
        assertNotNull(s.read(c.id))
    }

    @Test
    fun `总字符超上限就删最旧的`() {
        val s = store()
        s.applyLimits(maxSegments = 0, maxChars = 25)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("0123456789"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("0123456789"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("0123456789"))

        // 三段共 30 字 > 25 ⇒ 删最旧的一段（10 字）⇒ 剩 20 字，不超了
        assertEquals(2, s.size())
        assertEquals(20, s.totalChars(), "删到不超上限为止")
        assertNull(s.read(a.id), "最旧的段要被删掉")
        assertNotNull(s.read(b.id))
        assertNotNull(s.read(c.id))
    }

    @Test
    fun `最新的一段永远不会被删`() {
        val s = store()
        s.applyLimits(maxSegments = 1, maxChars = 0)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("c"))

        // 段数上限是 1 ⇒ 只剩最新的一段
        assertEquals(1, s.size())
        assertNotNull(s.read(c.id), "最新的一段要留着 —— 那是当前正在用的历史")
        assertNull(s.read(a.id))
        assertNull(s.read(b.id))
    }

    @Test
    fun `最新的那段自己超了上限也不删`() {
        val s = store()
        s.applyLimits(maxSegments = 0, maxChars = 5)

        // 这一段自己就 10 字 > 5 —— 但它是最新的一段，不能删
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("0123456789"))
        assertEquals(1, s.size())
        assertNotNull(s.read(a.id))

        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("0123456789"))
        // 两段都超了上限 ⇒ 只能删最旧的那一段，最新的照留
        assertEquals(1, s.size())
        assertNotNull(s.read(b.id))
        assertNull(s.read(a.id))
    }

    @Test
    fun `淘汰后链重新串好`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("c"))
        val d = s.append(ArchiveSegmentMeta.KIND_COMPACT, "D", null, msgs("d"))
        val e = s.append(ArchiveSegmentMeta.KIND_COMPACT, "E", null, msgs("e"))

        // 淘汰前：D 的 prevId 指着更早的 C
        assertEquals(c.id, s.meta(d.id)!!.prevId)

        s.applyLimits(maxSegments = 3, maxChars = 0)
        val f = s.append(ArchiveSegmentMeta.KIND_COMPACT, "F", null, msgs("f"))

        // 6 段超了 3 段 ⇒ 删最旧的 A、B、C ⇒ 剩下 F、E、D
        val list = s.list()
        assertEquals(listOf(f.id, e.id, d.id), list.map { it.id })

        // ★ 链重新串好：D 现在是最旧的 ⇒ 没有更早的了；
        //   E、F 直接接在它后面（中间的段被删了，链不许指着已删的段）。
        assertNull(s.meta(d.id)!!.prevId, "最旧的那段没有更早的了")
        assertEquals(d.id, s.meta(e.id)!!.prevId)
        assertEquals(e.id, s.meta(f.id)!!.prevId)
        // 反向：nextId 指向更晚的一段
        assertEquals(e.id, s.meta(d.id)!!.nextId)
        assertEquals(f.id, s.meta(e.id)!!.nextId)
        assertNull(s.meta(f.id)!!.nextId)

        // ★ 没有一条链指向已删的段（不留悬空的链）
        val alive = list.map { it.id }.toSet()
        for (m in list) {
            m.prevId?.let { assertTrue(it in alive, "prevId 指向了已删的段：$it") }
            m.nextId?.let { assertTrue(it in alive, "nextId 指向了已删的段：$it") }
        }
    }

    @Test
    fun `淘汰后读已删的段返回 null`() {
        val s = store()
        s.applyLimits(maxSegments = 1, maxChars = 0)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))

        assertNull(s.read(a.id), "被淘汰的段读不出来")
        assertNull(s.meta(a.id), "被淘汰的段连元信息也没有了")
        assertNotNull(s.read(b.id), "留下的段照常读得到")
        assertNotNull(s.meta(b.id))
    }

    @Test
    fun `淘汰后索引和正文目录一致`() {
        val s = store()
        s.applyLimits(maxSegments = 2, maxChars = 0)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("c"))

        val ids = s.list().map { it.id }
        assertEquals(listOf(c.id, b.id), ids)

        // 索引里不留已删的段（直接看索引原文，不靠 API）
        //   ★ 比的是 `"id":"xxx"` 整个值 —— 只比裸 id 会误判：
        //   同秒追加的 id 是 `seg-T` / `seg-T-002`，前者是后者的**前缀**。
        val indexText = File(s.rootDir(), "index.json").readText()
        assertFalse(indexText.contains("\"id\":\"${a.id}\""), "索引里不该留着已删的段")
        assertTrue(indexText.contains("\"id\":\"${c.id}\""), "留下的段要在索引里")

        // 正文目录里不留孤儿文件（孤儿会被 rebuild 捡回来「复活」）
        val files = File(s.rootDir(), "segments")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json") }
        assertEquals(ids.toSet(), files.toSet(), "索引和正文目录要一致：既没有幽灵条目，也没有孤儿文件")
    }

    @Test
    fun `索引坏了也不会把淘汰掉的段复活`() {
        val s = store()
        s.applyLimits(maxSegments = 1, maxChars = 0)

        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        assertNull(s.read(a.id))

        // 索引坏了 → 从正文目录重建 —— 已删的段不该复活（正文文件是真删了）
        File(s.rootDir(), "index.json").writeText("{ 这不是 JSON")

        assertEquals(listOf(b.id), s.list().map { it.id }, "删掉的段不能被重建捡回来")
        assertEquals(1, s.size())
    }

    @Test
    fun `上限传 0 就是不限`() {
        val s = store()
        s.applyLimits(maxSegments = 0, maxChars = 0)

        val ids = (1..5).map { s.append(ArchiveSegmentMeta.KIND_COMPACT, "第${it}段", null, msgs("m$it")).id }

        assertEquals(5, s.size(), "上限是 0 ⇒ 不限")
        assertEquals(10, s.totalChars(), "5 段，每段 2 个字")
        assertEquals(ids.reversed(), s.list().map { it.id })

        // 再设一个真的上限 ⇒ 开始淘汰
        s.applyLimits(maxSegments = 1, maxChars = 1)
        s.append(ArchiveSegmentMeta.KIND_COMPACT, "第6段", null, msgs("m6"))
        assertEquals(1, s.size(), "有上限就要淘汰")

        // ★ 再设回 0 ⇒ 上限被清掉，不再淘汰
        s.applyLimits(maxSegments = 0, maxChars = 0)
        s.append(ArchiveSegmentMeta.KIND_COMPACT, "第7段", null, msgs("m7"))
        assertEquals(2, s.size(), "上限设成 0 之后就不再淘汰了")
    }

    @Test
    fun `上限是硬线：设了就立刻收拾已有的段`() {
        val s = store()
        val a = s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("a"))
        val b = s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("b"))
        val c = s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("c"))
        assertEquals(3, s.size())

        // ★ 设上限的**这一刻**就要生效 —— 不等下一次 append。
        //   （与记忆库那套 applyLimits 同一语义：两个同名方法不能一个生效一个不生效。）
        s.applyLimits(maxSegments = 1, maxChars = 0)

        assertEquals(1, s.size(), "上限是硬线：设了就立刻删最旧的")
        assertNull(s.read(a.id), "最旧的 A 已被淘汰")
        assertNull(s.read(b.id), "中间的 B 也没了")
        assertNotNull(s.read(c.id), "最新的一段留着")
    }

    @Test
    fun `总字符数是所有段正文之和`() {
        val s = store()
        s.append(ArchiveSegmentMeta.KIND_COMPACT, "A", null, msgs("abc", "de"))   // 3 + 2 = 5
        s.append(ArchiveSegmentMeta.KIND_COMPACT, "B", null, msgs("123456"))     // 6
        assertEquals(11, s.totalChars())

        // ★ 淘汰后总字符数立刻反映出来（口径不变：还是各段正文之和）
        s.applyLimits(maxSegments = 0, maxChars = 5)
        s.append(ArchiveSegmentMeta.KIND_COMPACT, "C", null, msgs("xy"))          // 2
        assertEquals(2, s.totalChars(), "超了上限 ⇒ 删最旧的，删到不超为止")
        assertEquals(1, s.size())
    }
}
