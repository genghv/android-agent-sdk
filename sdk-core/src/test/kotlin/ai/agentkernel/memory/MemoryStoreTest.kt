package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 长期记忆的本地持久化 —— 纯 JVM。
 */
class MemoryStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-mem-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `追加之后能列出来`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "用户在配网关"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        val all = store.list()
        assertEquals(2, all.size)
        // 新的在前
        assertEquals("改提示词", all[0].title)
        assertEquals("配网关", all[1].title)
    }

    @Test
    fun `正文落盘且能原样读回`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "正文第一行\n正文第二行"))

        assertEquals("正文第一行\n正文第二行", store.readBody("20261028-1000"))
        assertTrue(File(dir, "entries/20261028-1000.md").isFile)
    }

    @Test
    fun `摘要块带的是正文不是只有标题`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "事实：用户的网关在 8080 端口"))

        val digest = store.digest(1_500)
        assertNotNull(digest)
        // 标题和正文都得在 —— 模型得知道结论是什么
        assertTrue(digest.contains("配网关"), "摘要块要带标题：$digest")
        assertTrue(digest.contains("8080"), "摘要块要带正文：$digest")
    }

    @Test
    fun `没有记忆时不返回任何内容`() {
        val store = MemoryStore(tempDir())
        assertNull(store.digest(1_500))
    }

    @Test
    fun `摘要块超预算就截断`() {
        val store = MemoryStore(tempDir())
        repeat(20) { i ->
            store.append(MemoryEntry("20261028-100$i", 1_000L + i, "标题$i", "正文$i".repeat(50)))
        }
        val digest = store.digest(200)!!
        assertTrue(digest.length <= 260, "摘要块要受预算约束，实际 ${digest.length} 字")
    }

    @Test
    fun `索引丢了能从正文目录重建`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "正文"))

        // 索引损坏（模拟：直接删掉）
        File(dir, "index.json").delete()

        // 重建后又能列出来
        assertEquals(1, store.rebuildIndex())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `同秒内追加两条不会互相覆盖`() {
        val store = MemoryStore(tempDir())
        val a = store.append(MemoryEntry("20261028-1000", 1_000L, "A", "正文A"))
        val b = store.append(MemoryEntry("20261028-1000", 1_000L, "B", "正文B"))
        assertTrue(a.id != b.id, "id 必须唯一，实际都是 ${a.id}")
        assertEquals(2, store.list().size)
    }
}

/** 假模型客户端：只回一句固定的摘要，并记录收到的请求。 */
internal class FakeLlm(private val reply: String = "摘要：用户在配网关") : LlmClient {
    val requests = mutableListOf<ChatRequest>()

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        requests += request
        return AssistantMessage(content = reply)
    }
}
