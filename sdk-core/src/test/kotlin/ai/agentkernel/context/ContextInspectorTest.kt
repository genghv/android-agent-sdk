package ai.agentkernel.context

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上下文体检（**前缀稳定性检测**）的契约测试。
 *
 * ## 这组测试锁的是什么
 *
 * 前缀缓存只认**逐字相同**的前缀。所以「上下文检测」要回答的核心问题不是
 * 「上下文有多长」，而是 **「哪一条消息被改动了」**。
 *
 * 命中低有两种完全不同的原因，处置也相反：
 * - 前缀**没被动过**却没命中 → 缓存还没建起来（第 1-2 轮正常，等着就行）；
 * - 前缀**被改过** → **布局有 bug**（比如把系统提示词拼进了历史、
 *   或者告警里塞了每轮变的数字），缓存从那儿起全废。
 *
 * 所以 [ContextReport.prefixPreserved] 与 [ContextReport.firstChangedIndex]
 * 是这套检测的核心输出。
 */
class ContextInspectorTest {

    private fun sys(text: String) = SystemMessage(text)
    private fun user(text: String) = UserMessage(text)
    private fun assistant(text: String) = AssistantMessage(content = text)

    @Test
    fun `第一次请求没有对照，标记为首次`() {
        val inspector = ContextInspector()
        val report = inspector.observe(listOf(sys("S"), user("U1")))

        assertTrue(report.firstRequest)
        assertEquals(1, report.requestIndex)
        assertEquals(0, report.commonPrefixMessages)
        assertEquals(2, report.addedMessages)
        assertNull(report.firstChangedIndex, "没有对照就没有「变了的第一条」")
    }

    @Test
    fun `只增不改时前缀保持完好`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S"), user("U1")))

        val report = inspector.observe(listOf(sys("S"), user("U1"), assistant("A1"), user("U2")))

        assertTrue(report.prefixPreserved, "老消息一个字没动 → 前缀是好的")
        assertEquals(2, report.commonPrefixMessages, "前两条逐字相同")
        assertEquals(2, report.addedMessages)
        assertEquals(0, report.changedMessages)
        assertNull(report.firstChangedIndex)
    }

    @Test
    fun `中间某条被改了 → 指出是哪一条`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S"), user("U1"), assistant("A1")))

        // 第二条被改了（模拟「告警里塞了每轮变的数字」）
        val report = inspector.observe(listOf(sys("S"), user("U1-改了"), assistant("A1")))

        assertFalse(report.prefixPreserved, "前缀被动了就是被动了")
        assertEquals(1, report.firstChangedIndex, "断点应指向第 2 条（下标 1）")
        assertEquals(1, report.changedMessages)
        assertEquals(0, report.addedMessages)
    }

    @Test
    fun `系统提示词被改 → 断点在下标 0（这就是「前缀全废」）`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S-旧"), user("U1")))

        val report = inspector.observe(listOf(sys("S-新"), user("U1")))

        assertEquals(0, report.firstChangedIndex)
        assertFalse(report.prefixPreserved)
    }

    @Test
    fun `历史被裁短也算「变了」（裁头 = 前缀重置 = 一次全 miss）`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S"), user("U1"), assistant("A1")))

        val report = inspector.observe(listOf(sys("S"), user("U1")))

        assertFalse(report.prefixPreserved)
        assertEquals(1, report.changedMessages, "少了一条 = 变了 1 条")
        assertEquals(0, report.addedMessages)
    }

    @Test
    fun `公共前缀按字符算，能看出「这次多出来多少」`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S"), user("U1")))

        val report = inspector.observe(listOf(sys("S"), user("U1"), user("U2-新增的一段")))

        assertTrue(report.newChars > 0, "新增内容必然未命中")
        assertTrue(report.prefixRatio > 0.0)
        assertTrue(report.prefixRatio < 1.0, "新内容在场，前缀不可能占满")
    }

    @Test
    fun `超出条数预算要报出来（但 SDK 不自己丢消息）`() {
        val inspector = ContextInspector(maxMessages = 2)
        inspector.observe(listOf(sys("S"), user("U1")))

        val report = inspector.observe(listOf(sys("S"), user("U1"), assistant("A1"), user("U2")))

        assertTrue(report.overBudget, "4 条 > 预算 2 条")
        assertEquals(4, report.messageCount, "但一条都没被丢")
    }

    @Test
    fun `reset 之后重新从第一次算起`() {
        val inspector = ContextInspector()
        inspector.observe(listOf(sys("S")))
        inspector.reset()
        val report = inspector.observe(listOf(sys("S")))

        assertTrue(report.firstRequest)
        assertEquals(1, report.requestIndex)
    }
}
