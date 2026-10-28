package ai.agentkernel.archive

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话**分段归档** —— 纯 JVM。
 *
 * 守的是三件事：
 * - **不丢东西**：被摘要掉的原文还在归档里，能原样读回来；
 * - **链是通的**：段与段之间 prevId/nextId 能把时间顺序串起来；
 * - **分页不重不漏**：前端往上滚，一段一段拉，不重复也不跳过。
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
}
