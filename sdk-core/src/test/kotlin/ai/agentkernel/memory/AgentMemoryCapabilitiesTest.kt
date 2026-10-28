package ai.agentkernel.memory

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `agent-memory` —— 长期记忆的增删改查 —— 纯 JVM。
 *
 * 这几条守的是**契约**：
 * - 输出**一律是 JSON**（模型要按字段取值，不能靠肉眼看）；
 * - 失败**抛 [CapabilityFailure]**（注册表会转成 `{"ok":false,...}` + 退出码 1）；
 * - 只有**一条**命令（提示词里每多一行就多占字符，网关对 system 段有 2000 字符上限）。
 */
class AgentMemoryCapabilitiesTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-memcap-${System.nanoTime()}").also { it.mkdirs() }

    private fun store(dir: File = tempDir()): MemoryStore = MemoryStore(dir)

    private fun capability(store: MemoryStore): DeviceCapability =
        AgentMemoryCapabilities(store).capabilities.single()

    /** 跑一条命令并把输出解析成 JSON（顺带断言它是合法 JSON）。 */
    private fun run(cap: DeviceCapability, vararg args: String): JsonObject {
        val raw = cap.run(args.toList())
        return Json.parseToJsonElement(raw).jsonObject
    }

    // ---- 契约本身 ----

    @Test
    fun `只做一条命令且是 SDK 自制的`() {
        val caps = AgentMemoryCapabilities(store()).capabilities
        assertEquals(1, caps.size, "只做一条命令 —— 提示词里每多一行就多占字符")
        val cap = caps.single()
        assertEquals("memory", cap.name)
        assertEquals(CapabilityOrigin.INTERNAL, cap.origin)
        assertEquals("agent-", cap.prefix, "内部自制的命令前缀是 agent-")
        assertTrue(cap.usage.startsWith("agent-memory "), "用法要带完整命令名：${cap.usage}")
    }

    // ---- 六条路径 ----

    @Test
    fun `list 列出全部记忆`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "用户在配网关"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        val out = run(capability(store), "list")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, out["count"]!!.jsonPrimitive.int)
        val entries = out["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        val first = entries[0].jsonObject
        assertEquals("20261028-1100", first["id"]!!.jsonPrimitive.content)
        assertEquals("改提示词", first["title"]!!.jsonPrimitive.content)
        assertEquals(2_000L, first["createdAt"]!!.jsonPrimitive.longOrNull)
        assertEquals(6, first["chars"]!!.jsonPrimitive.int)
    }

    @Test
    fun `get 读出正文`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "用户在配网关"))

        val out = run(capability(store), "get", "20261028-1000")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("20261028-1000", out["id"]!!.jsonPrimitive.content)
        assertEquals("配网关", out["title"]!!.jsonPrimitive.content)
        assertEquals("用户在配网关", out["summary"]!!.jsonPrimitive.content)
        assertEquals(1_000L, out["createdAt"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `add 追加一条并返回 id`() {
        val store = store()
        val out = run(capability(store), "add", "事实：用户把网关跑在 8080 端口")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)

        val id = out["id"]!!.jsonPrimitive.content
        assertTrue(id.isNotEmpty())
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals(id, all[0].id)
        assertEquals("事实：用户把网关跑在 8080 端口", all[0].summary)
        assertEquals("事实：用户把网关跑在 8080 端口", all[0].title, "标题取正文第一行")
    }

    @Test
    fun `edit 改正文`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "旧标题", "旧正文"))

        val out = run(capability(store), "edit", "20261028-1000", "新正文")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("20261028-1000", out["id"]!!.jsonPrimitive.content)

        val entry = store.list().single()
        assertEquals("新正文", entry.summary)
        assertEquals("新正文", entry.title, "标题跟着正文第一行走")
    }

    @Test
    fun `delete 删掉一条`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "用户在配网关"))

        val out = run(capability(store), "delete", "20261028-1000")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertTrue(out["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(0, store.list().size)
    }

    @Test
    fun `search 按关键词命中`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配网关", "用户在配 Gateway"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        val out = run(capability(store), "search", "gateway")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, out["count"]!!.jsonPrimitive.int)
        val hit = out["entries"]!!.jsonArray.single().jsonObject
        assertEquals("20261028-1000", hit["id"]!!.jsonPrimitive.content)
        assertEquals("配网关", hit["title"]!!.jsonPrimitive.content)
    }

    // ---- 失败路径 ----

    @Test
    fun `不认识的子命令要报错并带上用法`() {
        val cap = capability(store())
        val e = assertFailsWith<CapabilityFailure> { cap.run(listOf("frobnicate")) }
        assertTrue(e.message!!.contains("agent-memory"), "用法错误要说清怎么用：${e.message}")
    }

    @Test
    fun `缺参数要报错`() {
        val cap = capability(store())
        assertFailsWith<CapabilityFailure> { cap.run(emptyList()) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("get")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("add")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("delete")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("search")) }
    }

    @Test
    fun `id 不存在要报错`() {
        val cap = capability(store())
        assertFailsWith<CapabilityFailure> { cap.run(listOf("get", "没有这条")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("edit", "没有这条", "正文")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("delete", "没有这条")) }
    }
}
