package ai.agentkernel.memory

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.CapabilityResult
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.device.DeviceCommand
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `agent-memory` —— 长期记忆的增删改查 —— 纯 JVM。
 *
 * 这几条守的是**契约**：
 * - 输出**一律是 JSON**（模型要按字段取值，不能靠肉眼看）；
 * - 失败**抛 [CapabilityFailure]**（注册表会转成 `{"ok":false,...}` + 退出码 1）；
 * - 只有**一条**命令（提示词里每多一行就多占字符，后端对 system 段有 2000 字符上限）。
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

    /** 经注册表跑一条命令 —— 失败会被转成 `{"ok":false,...}` + 非零退出码。 */
    private fun runViaRegistry(cap: DeviceCapability, vararg args: String): CapabilityResult {
        val registry = DeviceCapabilityRegistry(listOf(cap))
        return registry.execute(DeviceCommand("agent-", cap.name, args.toList(), 0, 0))
    }

    /**
     * 按 shell 规则把一整条命令拆成能力参数（引号里的 `|` 原样保留）。
     *
     * 走的是**真实的解析器** —— 「规格被拆成好几个参数」这件事只有经过它才算数。
     */
    private fun splitArgs(cap: DeviceCapability, line: String): List<String> {
        val registry = DeviceCapabilityRegistry(listOf(cap))
        val cmd = registry.parseWhole(line) ?: error("这不是一条完整的能力命令：$line")
        return cmd.args
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
        assertTrue(cap.usage.contains("patch <id> <补丁规格>"), "用法行要带 patch 子命令：${cap.usage}")
    }

    // ---- 六条路径 ----

    @Test
    fun `list 列出全部记忆`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配后端"))
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
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配后端"))

        val out = run(capability(store), "get", "20261028-1000")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("20261028-1000", out["id"]!!.jsonPrimitive.content)
        assertEquals("配后端", out["title"]!!.jsonPrimitive.content)
        assertEquals("用户在配后端", out["summary"]!!.jsonPrimitive.content)
        assertEquals(1_000L, out["createdAt"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `add 追加一条并返回 id`() {
        val store = store()
        val out = run(capability(store), "add", "事实：用户把后端跑在 8080 端口")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)

        val id = out["id"]!!.jsonPrimitive.content
        assertTrue(id.isNotEmpty())
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals(id, all[0].id)
        assertEquals("事实：用户把后端跑在 8080 端口", all[0].summary)
        assertEquals("事实：用户把后端跑在 8080 端口", all[0].title, "标题取正文第一行")
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
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配后端"))

        val out = run(capability(store), "delete", "20261028-1000")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertTrue(out["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(0, store.list().size)
    }

    @Test
    fun `search 按关键词命中`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "配后端", "用户在配 Gateway"))
        store.append(MemoryEntry("20261028-1100", 2_000L, "改提示词", "提示词要瘦身"))

        val out = run(capability(store), "search", "gateway")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, out["count"]!!.jsonPrimitive.int)
        val hit = out["entries"]!!.jsonArray.single().jsonObject
        assertEquals("20261028-1000", hit["id"]!!.jsonPrimitive.content)
        assertEquals("配后端", hit["title"]!!.jsonPrimitive.content)
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
        assertFailsWith<CapabilityFailure> { cap.run(listOf("patch")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("patch", "20261028-1000")) }
    }

    @Test
    fun `id 不存在要报错`() {
        val cap = capability(store())
        assertFailsWith<CapabilityFailure> { cap.run(listOf("get", "没有这条")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("edit", "没有这条", "正文")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("delete", "没有这条")) }
    }

    // ---- patch：行级补丁（只贴改动部分）----

    @Test
    fun `patch 改一行并回 diff`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "第一行\n第二行\n第三行"))

        val out = run(capability(store), "patch", "20261028-1000", "replace-lines", "2", "2", "新的第二行")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("20261028-1000", out["id"]!!.jsonPrimitive.content)

        val body = store.readBody("20261028-1000")!!
        assertEquals("第一行\n新的第二行\n第三行", body, "只改了第 2 行")
        assertEquals(body.length, out["chars"]!!.jsonPrimitive.int, "chars = 改完正文的字符数")

        val diff = out["diff"]!!.jsonPrimitive.content
        assertTrue(diff.isNotBlank(), "diff 要有内容")
        assertTrue(diff.contains("2"), "diff 要带改动的行号：$diff")
    }

    @Test
    fun `patch 规格被拆成多个参数也能拼起来解析`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "第一行\n第二行\n第三行"))
        val cap = capability(store)

        // 整条命令按 shell 规则拆词后，规格散成 6 个参数 —— 要拼回一个规格串再解析。
        val args = splitArgs(cap, "agent-memory patch 20261028-1000 replace-lines 2 2 新的第二行")
        assertEquals(listOf("patch", "20261028-1000", "replace-lines", "2", "2", "新的第二行"), args)

        val out = run(cap, *args.toTypedArray())
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("第一行\n新的第二行\n第三行", store.readBody("20261028-1000")!!)
    }

    @Test
    fun `patch 的 replace-block 保留竖线`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "第一行\n第二行"))
        val cap = capability(store)

        // `|` 在 shell 里是管道操作符 ⇒ 整段规格必须加引号；引号里的 `|` 原样保留。
        val args = splitArgs(cap, "agent-memory patch 20261028-1000 \"replace-block|第一行|新的第一行\"")
        assertEquals(listOf("patch", "20261028-1000", "replace-block|第一行|新的第一行"), args)

        val out = run(cap, *args.toTypedArray())
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("新的第一行\n第二行", store.readBody("20261028-1000")!!)
    }

    @Test
    fun `patch 目标不存在要报错`() {
        val cap = capability(store())

        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "没有这条", "delete-line", "1"))
        }
        assertTrue(e.message!!.contains("没有这条记忆"), "要说清是「没这条」：${e.message}")

        // 注册表会把它转成 {"ok":false,...} + 非零退出码。
        val r = runViaRegistry(cap, "patch", "没有这条", "delete-line", "1")
        assertFalse(r.ok)
        assertTrue(r.exitCode != 0, "失败要非零退出码")
        val err = Json.parseToJsonElement(r.output).jsonObject
        assertFalse(err["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `patch 规格格式不对要带用法`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "第一行\n第二行"))
        val cap = capability(store)

        // replace-block 缺 `|` —— 锚点边界猜不出来，必须报错（不能猜着改）。
        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "20261028-1000", "replace-block", "第一行", "新行"))
        }
        assertTrue(e.message!!.contains("agent-memory"), "要带用法让模型自我纠正：${e.message}")

        val r = runViaRegistry(cap, "patch", "20261028-1000", "replace-block", "第一行", "新行")
        assertFalse(r.ok)
        val err = Json.parseToJsonElement(r.output).jsonObject
        assertFalse(err["ok"]!!.jsonPrimitive.boolean)
        assertTrue(err["error"]!!.jsonPrimitive.content.contains("agent-memory"))
        assertEquals("第一行\n第二行", store.readBody("20261028-1000")!!, "没打上就不能改")
    }

    @Test
    fun `patch 锚点找不到要报错`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "第一行\n第二行"))
        val cap = capability(store)

        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "20261028-1000", "replace-block|不存在的锚点|新行"))
        }
        assertTrue(e.message!!.contains("锚点"), "要说清是锚点没找到：${e.message}")
        assertEquals("第一行\n第二行", store.readBody("20261028-1000")!!, "没打上就不能改")
    }

    @Test
    fun `patch 锚点命中多处要带行号`() {
        val store = store()
        store.append(MemoryEntry("20261028-1000", 1_000L, "标题", "重复行\n中间行\n重复行"))
        val cap = capability(store)

        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "20261028-1000", "replace-block|重复行|新行"))
        }
        assertTrue(e.message!!.contains("行号"), "命中多处要说清是哪几行：${e.message}")
        assertTrue(e.message!!.contains("1") && e.message!!.contains("3"), "要带出命中行号：${e.message}")
        assertEquals("重复行\n中间行\n重复行", store.readBody("20261028-1000")!!, "没打上就不能改")
    }
}
