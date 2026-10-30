package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.text.TextPatch
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

    /** 摘要块里的日期标签（跟 [MemoryStore] 用同一套格式化，时区也一致）。 */
    private fun dateLabel(epochMillis: Long): String =
        java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))

    @Test
    fun `追加之后能列出来`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配后端"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        val all = store.list()
        assertEquals(2, all.size)
        // 新的在前
        assertEquals("改提示词", all[0].title)
        assertEquals("配后端", all[1].title)
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
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "事实：用户的后端在 8080 端口"))

        val digest = store.digest(1_500)
        assertNotNull(digest)
        // 标题和正文都得在 —— 模型得知道结论是什么
        assertTrue(digest.contains("配后端"), "摘要块要带标题：$digest")
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
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "正文"))

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

    // ---- 改 / 删 / 搜（agent 自己动手的那部分）----

    @Test
    fun `改正文后能读回新内容且标题跟着更新`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "旧标题", "旧正文第一行\n旧正文第二行"))

        val edited = store.edit("20261028-1000", "新正文第一行\n新正文第二行")
        assertNotNull(edited)
        assertEquals("新正文第一行\n新正文第二行", store.readBody("20261028-1000"))
        assertEquals("新正文第一行", edited.title, "标题要跟着新正文的第一行走")
        assertEquals("新正文第一行", store.list().first().title, "索引里的标题也要更新")
        assertEquals(1_000L, store.list().first().createdAt, "改内容不该改创建时间")
    }

    @Test
    fun `改不存在的 id 返回 null`() {
        val store = MemoryStore(tempDir())
        assertNull(store.edit("不存在的id", "随便写点什么"))
        assertEquals(0, store.list().size)
    }

    @Test
    fun `删掉之后列表里没有它且读不到正文`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配后端"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        assertTrue(store.delete("20261028-1000"))
        assertNull(store.readBody("20261028-1000"))
        assertEquals(listOf("改提示词"), store.list().map { it.title })
    }

    @Test
    fun `删不存在的 id 返回 false`() {
        val store = MemoryStore(tempDir())
        assertTrue(!store.delete("不存在的id"))
    }

    @Test
    fun `按关键词搜标题和正文且不区分大小写`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配 Gateway，端口 8080"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        // 标题命中
        assertEquals(listOf("20261028-1100"), store.search("改提示").map { it.id })
        // 正文命中，且不区分大小写
        assertEquals(listOf("20261028-1000"), store.search("GATEWAY").map { it.id })
        assertEquals(listOf("20261028-1000"), store.search("8080").map { it.id })
        // 搜不到就是空
        assertEquals(0, store.search("根本没有这个词").size)
    }

    @Test
    fun `搜出来的结果按时间倒序`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "后端在 8080"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "换后端", "后端搬到 9090"))

        val hits = store.search("后端")
        assertEquals(2, hits.size)
        assertEquals("换后端", hits.first().title, "新的在前")
    }

    // ---- 每日摘要槽 ----

    @Test
    fun `dailyKey 是八位日期键`() {
        val store = MemoryStore(tempDir())
        // 正午 —— 任何时区都落在同一个自然日
        val noon = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 29, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        assertEquals("20261029", store.dailyKey(noon.timeInMillis))
        assertTrue(store.dailyKey(noon.timeInMillis).matches(Regex("\\d{8}")))
    }

    @Test
    fun `同一天的每日摘要是替换不是追加`() {
        val store = MemoryStore(tempDir())
        val first = store.upsertDaily("20261029", "第一版标题", "第一版摘要")
        val second = store.upsertDaily("20261029", "第二版标题", "第二版摘要")

        assertEquals(first.id, second.id, "同一天必须是同一个槽位")
        assertEquals(1, store.list().size, "同一天只留一条，不是追加")
        assertEquals("第二版摘要", store.readBody(first.id), "正文要被替换成新的")
        assertEquals("第二版标题", store.list().single().title, "标题也要换成新的")
        assertEquals(MemoryKind.DAILY, store.list().single().kind)
    }

    @Test
    fun `跨天是两个不同的每日槽`() {
        val store = MemoryStore(tempDir())
        val a = store.upsertDaily("20261028", "第一天", "第一天的摘要")
        val b = store.upsertDaily("20261029", "第二天", "第二天的摘要")

        assertTrue(a.id != b.id, "跨天必须是两个槽位")
        assertEquals(2, store.list().size)
        assertEquals("第一天的摘要", store.readBody(a.id))
        assertEquals("第二天的摘要", store.readBody(b.id))
    }

    @Test
    fun `每日槽的 id 是可预测的`() {
        val store = MemoryStore(tempDir())
        assertEquals("daily-20261029", store.upsertDaily("20261029", "标题", "摘要").id)
        // 同一个键 ⇒ 同一个槽位（替换，不是多一条）
        assertEquals("daily-20261029", store.upsertDaily("20261029", "标题2", "摘要2").id)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `追加 DAILY 条目走覆盖语义`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("daily-20261029", 1_000L, "标题一", "摘要一", kind = MemoryKind.DAILY))
        store.append(MemoryEntry("daily-20261029", 2_000L, "标题二", "摘要二", kind = MemoryKind.DAILY))

        assertEquals(1, store.list().size, "DAILY 走 upsert 语义，同 id 覆盖")
        assertEquals("摘要二", store.readBody("daily-20261029"))
    }

    @Test
    fun `每日槽不会被容量淘汰删掉`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        // 每日槽比两条日常条目都老 —— 淘汰「最旧的」时必须跳过它
        store.append(MemoryEntry("daily-20261028", 100L, "前一天", "旧摘要", kind = MemoryKind.DAILY))
        store.append(MemoryEntry("n1", 200L, "旧条目", "旧正文"))
        store.append(MemoryEntry("n2", 300L, "新条目", "新正文"))

        store.applyLimits(maxEntryChars = 0, maxTotalChars = 0, maxEntries = 2)

        assertEquals(
            setOf("daily-20261028", "n2"),
            store.list().map { it.id }.toSet(),
            "只该删最旧的日常条目，每日槽必须留下",
        )
        assertTrue(File(dir, "entries/daily-20261028.md").isFile, "每日槽的正文必须还在")
        assertTrue(!File(dir, "entries/n1.md").exists(), "被淘汰的条目正文要一起删掉（不能留幽灵文件）")
        assertTrue(File(dir, "entries/n2.md").isFile, "没被淘汰的条目正文还在")
    }

    @Test
    fun `改每日槽不会把它降级成日常条目`() {
        val store = MemoryStore(tempDir())
        store.upsertDaily("20261029", "标题", "摘要")
        store.edit("daily-20261029", "新摘要")

        assertEquals(MemoryKind.DAILY, store.list().single().kind, "改内容不该改种类")
        assertEquals("新摘要", store.readBody("daily-20261029"))
    }

    // ---- 容量上限 ----

    @Test
    fun `单条正文超限被截断`() {
        val store = MemoryStore(tempDir())
        store.applyLimits(maxEntryChars = 20, maxTotalChars = 0, maxEntries = 0)

        val entry = store.append(MemoryEntry("n1", 100L, "标题", "很长的正文".repeat(20)))

        assertTrue(entry.chars <= 20, "截断后仍要满足上限，实际 ${entry.chars}")
        assertTrue(
            store.readBody("n1")!!.endsWith(MemoryStore.TRUNCATION_MARKER),
            "要标出这是截断过的",
        )
    }

    @Test
    fun `改和打补丁之后也会走单条截断`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("n1", 100L, "标题", "短正文"))
        store.applyLimits(maxEntryChars = 20, maxTotalChars = 0, maxEntries = 0)

        val edited = store.edit("n1", "长正文".repeat(20))
        assertNotNull(edited)
        assertTrue(edited.chars <= 20, "改完也要截断，实际 ${edited.chars}")

        val patched = store.patch("n1", TextPatch.ReplaceLines(1, 1, "更长的一行".repeat(20)))
        assertNotNull(patched)
        assertTrue(patched.first.chars <= 20, "打完补丁也要截断，实际 ${patched.first.chars}")
    }

    @Test
    fun `总字符超限删最旧的条目`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.append(MemoryEntry("n1", 100L, "旧", "旧".repeat(100)))
        store.append(MemoryEntry("n2", 200L, "新", "新".repeat(100)))

        store.applyLimits(maxEntryChars = 0, maxTotalChars = 150, maxEntries = 0)

        assertEquals(listOf("n2"), store.list().map { it.id }, "超总量要删最旧的")
        assertTrue(!File(dir, "entries/n1.md").exists(), "正文文件也要删，不能留幽灵条目")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `条数超限删最旧的条目`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.append(MemoryEntry("n1", 100L, "一", "正文"))
        store.append(MemoryEntry("n2", 200L, "二", "正文"))
        store.append(MemoryEntry("n3", 300L, "三", "正文"))

        store.applyLimits(maxEntryChars = 0, maxTotalChars = 0, maxEntries = 2)

        assertEquals(listOf("n3", "n2"), store.list().map { it.id }, "超条数要删最旧的")
        assertTrue(!File(dir, "entries/n1.md").exists(), "正文文件也要删")
    }

    // ---- 行级补丁 ----

    @Test
    fun `行级补丁只改目标行`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("n1", 100L, "标题", "第一行\n第二行\n第三行"))

        val result = store.patch("n1", TextPatch.ReplaceLines(2, 2, "改过的行"))
        assertNotNull(result, "补丁该成功")
        val (entry, diff) = result

        val body = store.readBody("n1")!!
        assertTrue(body.contains("改过的行"), "目标行要换成新的：$body")
        assertTrue(!body.contains("第二行"), "被替换掉的那行不该还在：$body")
        assertTrue(body.contains("第一行") && body.contains("第三行"), "别的行不能动：$body")
        assertTrue(diff.isNotBlank(), "要给出 diff 描述")
        assertEquals("第一行", entry.title, "标题按新正文的第一行走（首行没动，标题也不变）")
    }

    @Test
    fun `按锚点打补丁也能改`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("n1", 100L, "标题", "第一行\n第二行\n第三行"))

        val result = store.patch("n1", TextPatch.ReplaceBlock("第二行", "锚点换掉的行"))
        assertNotNull(result, "按锚点也该成功")
        assertTrue(store.readBody("n1")!!.contains("锚点换掉的行"), "锚点那一块要换成新的")
    }

    @Test
    fun `锚点找不到时补丁返回 null`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("n1", 100L, "标题", "第一行\n第二行"))

        assertNull(store.patch("n1", TextPatch.ReplaceBlock("根本不存在的锚点", "x")))
        // 失败不该改动正文
        assertEquals("第一行\n第二行", store.readBody("n1"))
    }

    @Test
    fun `行号越界时补丁返回 null`() {
        val store = MemoryStore(tempDir())
        store.append(MemoryEntry("n1", 100L, "标题", "第一行\n第二行"))

        assertNull(store.patch("n1", TextPatch.ReplaceLines(99, 99, "x")))
        assertEquals("第一行\n第二行", store.readBody("n1"))
    }

    @Test
    fun `条目不存在时补丁返回 null`() {
        val store = MemoryStore(tempDir())
        assertNull(store.patch("没有这条", TextPatch.DeleteLine(1)))
    }

    // ---- 摘要块的两块结构 ----

    @Test
    fun `摘要块分两块且形状固定`() {
        val store = MemoryStore(tempDir())
        store.upsertDaily("20261029", "今天的标题", "今天聊了后端")
        store.append(MemoryEntry("20261029-1000", 1_000L, "配后端", "用户的后端在 8080"))

        val digest = store.digest(2_000)!!
        assertEquals(
            """
            ## 你之前和用户聊过什么（长期记忆）

            ### 今天的摘要
            今天聊了后端

            ### 你自己记下的事
            - [${dateLabel(1_000L)}] 配后端
              用户的后端在 8080
            """.trimIndent(),
            digest,
        )
    }

    @Test
    fun `预算不够时先砍日常条目`() {
        val store = MemoryStore(tempDir())
        store.upsertDaily("20261029", "今天的标题", "今天聊了后端")
        store.append(MemoryEntry("n1", 1_000L, "旧条目", "旧正文".repeat(50)))

        val digest = store.digest(120)!!

        assertTrue(digest.contains("### 今天的摘要"), "摘要那一块要保住：$digest")
        assertTrue(digest.contains("今天聊了后端"), "摘要正文要保住：$digest")
        assertTrue(!digest.contains("旧正文"), "日常条目的正文要先让位：$digest")
        assertTrue(!digest.contains(MemoryStore.TRUNCATION_MARKER), "不该动摘要正文：$digest")
        assertTrue(digest.length <= 120, "整块不能超预算，实际 ${digest.length}")
    }

    @Test
    fun `预算太小时日常条目整块消失但摘要还在`() {
        val store = MemoryStore(tempDir())
        store.upsertDaily("20261029", "今天的标题", "今天聊了后端")
        store.append(MemoryEntry("n1", 1_000L, "旧条目", "旧正文"))

        val digest = store.digest(60)!!

        assertTrue(digest.contains("今天聊了后端"), "摘要正文要保住：$digest")
        assertTrue(!digest.contains("旧条目"), "放不下就连标题都不给：$digest")
        assertTrue(!digest.contains("### 你自己记下的事"), "空的日常段不该留标题：$digest")
        assertTrue(digest.length <= 60, "整块不能超预算，实际 ${digest.length}")
    }

    @Test
    fun `摘要块超预算时砍摘要正文`() {
        val store = MemoryStore(tempDir())
        store.upsertDaily("20261029", "今天的标题", "很长的摘要".repeat(50))

        val digest = store.digest(100)!!

        assertTrue(digest.contains("### 今天的摘要"), "摘要那一块要保住：$digest")
        assertTrue(digest.length <= 100, "整块不能超预算，实际 ${digest.length}")
        assertTrue(digest.contains(MemoryStore.TRUNCATION_MARKER), "砍过的要标出来：$digest")
    }

    // ---- 索引格式 ----

    @Test
    fun `新索引写出了 kind 字段`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.upsertDaily("20261029", "标题", "摘要")
        store.append(MemoryEntry("n1", 100L, "日常", "正文"))

        val index = File(dir, "index.json").readText()
        assertTrue(index.contains("\"kind\":\"DAILY\""), "索引要记下种类：$index")
        assertTrue(index.contains("\"kind\":\"NOTE\""), "索引要记下种类：$index")
    }

    @Test
    fun `老索引没有 kind 字段也能读`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.append(MemoryEntry("20261028-1000", 1_000L, "旧条目", "旧正文"))

        // 手工写成老格式（只有 id / createdAt / title）
        File(dir, "index.json").writeText("""[{"id":"20261028-1000","createdAt":1000,"title":"旧条目"}]""")

        val all = store.list()
        assertEquals(1, all.size, "老索引不能读不出来")
        assertEquals(MemoryKind.NOTE, all[0].kind, "老索引里的条目按 NOTE 处理")
        assertEquals("旧正文", all[0].summary)
    }

    @Test
    fun `索引丢了重建后每日槽仍是 DAILY`() {
        val dir = tempDir()
        val store = MemoryStore(dir)
        store.upsertDaily("20261029", "标题", "摘要")

        File(dir, "index.json").delete()
        assertEquals(1, store.rebuildIndex())

        assertEquals(MemoryKind.DAILY, store.list().single().kind, "重建不能把每日槽降级成日常条目")
    }
}

/** 假模型客户端：只回一句固定的摘要，并记录收到的请求。 */
internal class FakeLlm(private val reply: String = "摘要：用户在配后端") : LlmClient {
    val requests = mutableListOf<ChatRequest>()

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        requests += request
        return AssistantMessage(content = reply)
    }
}
