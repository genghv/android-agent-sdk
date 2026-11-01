package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapabilityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `agent-skill` 的 JVM 单测 —— **不碰设备**。
 *
 * 两层：
 * - 能力层（list/show/search 的解析与 JSON）注入 [FakeSkillStore]，只验契约；
 * - 文件层（[FileSkillStore]）用临时目录验**真文件**：UTF-8 边界截断、符号链接逃逸、
 *   目录里有什么就列什么（索引只是装饰）。
 */
class SkillCapabilityTest {

    // ------------------------------------------------------------ 假存储

    /** 内存存储：想让它有什么就有什么，顺带记下能力传下来的 maxBytes。 */
    private class FakeSkillStore(
        private val skills: Map<String, String> = emptyMap(),
        var index: String? = null,
    ) : SkillStore {
        var lastMaxBytes: Int? = null
        /** 让某个 id 读的时候抛（模拟无权限）。 */
        var readFailure: String? = null

        override fun ids(): List<String> = skills.keys.sorted()

        override fun indexJson(): String? = index

        override fun chars(id: String): Int = textOf(id).length

        override fun read(id: String, maxBytes: Int): SkillText {
            lastMaxBytes = maxBytes
            val text = textOf(id)
            return SkillText(text, text.length, truncated = false)
        }

        override fun readAll(id: String): String = textOf(id)

        private fun textOf(id: String): String {
            readFailure?.let { throw CapabilityFailure("读不了技能「$id」的文件：$it") }
            return skills[id] ?: throw CapabilityFailure("没有这个技能：$id")
        }
    }

    private fun capability(store: SkillStore, maxBytes: Int = AgentSkillCapabilities.DEFAULT_MAX_BYTES) =
        AgentSkillCapabilities(store, maxBytes)

    private fun okJson(store: SkillStore, vararg args: String): JsonObject =
        Json.parseToJsonElement(capability(store).capabilities.single().run(args.toList())).jsonObject

    // ------------------------------------------------------------ list

    @Test
    fun `list 列出技能并用索引里的名字`() {
        val store = FakeSkillStore(
            skills = mapOf("agent-text" to "# 文本\n按行改文件", "biz-api" to "# API\n怎么调"),
            index = """{"skills":[{"id":"agent-text","name":"文本按行改"},{"id":"biz-api","name":"业务接口"}]}""",
        )
        val obj = okJson(store, "list")
        val skills = obj["skills"]!!.jsonArray

        assertEquals(2, skills.size)
        assertEquals(
            setOf("id", "name", "chars"),
            skills[0].jsonObject.keys,
            "每一项就这三个字段：$skills",
        )
        assertEquals("agent-text", skills[0].jsonObject["id"]!!.jsonPrimitive.content, "要按 id 排序")
        assertEquals("文本按行改", skills[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("# 文本\n按行改文件".length, skills[0].jsonObject["chars"]!!.jsonPrimitive.int)
        assertEquals("biz-api", skills[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("业务接口", skills[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有索引就拿 id 当名字`() {
        val store = FakeSkillStore(skills = mapOf("biz-api" to "x"))
        val skills = okJson(store, "list")["skills"]!!.jsonArray
        assertEquals("biz-api", skills[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `索引里多出来的技能不列`() {
        // 目录里只有 a；索引里还写着已经删掉的 b —— 不能列出来让模型点开就 404。
        val store = FakeSkillStore(
            skills = mapOf("a" to "x"),
            index = """{"skills":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}""",
        )
        val skills = okJson(store, "list")["skills"]!!.jsonArray
        assertEquals(listOf("a"), skills.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `坏索引要明确失败而不是装作没有技能`() {
        val store = FakeSkillStore(skills = mapOf("a" to "x"), index = "{这不是 JSON")
        val failure = assertFailsWith<CapabilityFailure> { capability(store).capabilities.single().run(listOf("list")) }
        assertTrue(failure.message!!.contains("index.json"), "要点名是索引坏了：${failure.message}")
        assertTrue(failure.message!!.contains("JSON"), failure.message!!)
    }

    @Test
    fun `索引形状不对也要失败`() {
        val store = FakeSkillStore(skills = mapOf("a" to "x"), index = """{"foo":1}""")
        val failure = assertFailsWith<CapabilityFailure> { capability(store).capabilities.single().run(listOf("list")) }
        assertTrue(failure.message!!.contains("skills"), failure.message!!)
    }

    @Test
    fun `一个技能都没有时返回空表并给提示`() {
        val obj = okJson(FakeSkillStore(), "list")
        assertEquals(0, (obj["skills"] as JsonArray).size)
        assertTrue(obj["note"]!!.jsonPrimitive.content.contains("还没有安装技能"), "$obj")
    }

    // ------------------------------------------------------------ show

    @Test
    fun `show 原样返回正文`() {
        val text = "# 标题\n\n第一段\n第二段\n"
        val store = FakeSkillStore(skills = mapOf("doc" to text))
        val obj = okJson(store, "show", "doc")

        assertEquals("doc", obj["id"]!!.jsonPrimitive.content)
        assertEquals(text, obj["content"]!!.jsonPrimitive.content, "正文要一字不差")
        assertEquals(text.length, obj["chars"]!!.jsonPrimitive.int)
        assertEquals(text.length, obj["total_chars"]!!.jsonPrimitive.int)
        assertFalse(obj["truncated"]!!.jsonPrimitive.boolean)
        assertFalse(obj.containsKey("note"), "没截断就不该有 note")
    }

    @Test
    fun `show 的 max-bytes 会传给存储 默认值也对`() {
        val store = FakeSkillStore(skills = mapOf("doc" to "x"))
        capability(store, maxBytes = 1234).capabilities.single().run(listOf("show", "doc"))
        assertEquals(1234, store.lastMaxBytes, "没传 --max-bytes 时用构造时的默认值")

        capability(store).capabilities.single().run(listOf("show", "doc", "--max-bytes", "4096"))
        assertEquals(4096, store.lastMaxBytes)
    }

    @Test
    fun `show 不存在的技能要报没有这个技能`() {
        val store = FakeSkillStore(skills = mapOf("a" to "x"))
        val failure = assertFailsWith<CapabilityFailure> { capability(store).capabilities.single().run(listOf("show", "nope")) }
        assertTrue(failure.message!!.contains("没有这个技能"), failure.message!!)
    }

    @Test
    fun `max-bytes 写得不像话要拒绝`() {
        val store = FakeSkillStore(skills = mapOf("a" to "x"))
        val cap = capability(store).capabilities.single()
        listOf(
            listOf("show", "a", "--max-bytes", "0"),
            listOf("show", "a", "--max-bytes", "10"),
            listOf("show", "a", "--max-bytes", "999999"),
            listOf("show", "a", "--max-bytes", "abc"),
            listOf("show", "a", "--max-bytes"),
            listOf("show", "a", "--nope"),
        ).forEach { args ->
            val failure = assertFailsWith<CapabilityFailure>("$args 应被拒") { cap.run(args) }
            assertTrue(
                failure.message!!.contains("--max-bytes") || failure.message!!.contains("看不懂"),
                "$args 的报错要说清哪儿不对：${failure.message}",
            )
        }
        assertTrue(store.lastMaxBytes == null, "参数没校验完就不该去读文件")
    }

    // ------------------------------------------------------------ search

    @Test
    fun `search 命中行号与片段`() {
        val text = "第一行\n第二行有 API 密钥\n第三行"
        val store = FakeSkillStore(skills = mapOf("biz" to text))
        val obj = okJson(store, "search", "API")
        val hits = obj["hits"]!!.jsonArray

        assertEquals(1, hits.size)
        assertEquals("biz", hits[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, hits[0].jsonObject["line"]!!.jsonPrimitive.int, "行号从 1 开始")
        assertTrue(hits[0].jsonObject["snippet"]!!.jsonPrimitive.content.contains("API 密钥"), "$obj")
        assertEquals(1, obj["total"]!!.jsonPrimitive.int)
        assertFalse(obj["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `search 大小写不敏感且跨技能`() {
        val store = FakeSkillStore(
            skills = mapOf(
                "a-doc" to "这里写了 Api 的用法",
                "b-doc" to "无关心",
                "c-doc" to "api 也可以小写",
            ),
        )
        val obj = okJson(store, "search", "aPi")
        val ids = obj["hits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("a-doc", "c-doc"), ids, "按 id 排序、大小写不敏感")
    }

    @Test
    fun `search 没命中返回空数组而不是报错`() {
        val obj = okJson(FakeSkillStore(skills = mapOf("a" to "无关内容")), "search", "找不到的词")
        assertEquals(0, (obj["hits"] as JsonArray).size)
        assertEquals(0, obj["total"]!!.jsonPrimitive.int)
        assertTrue(obj["note"]!!.jsonPrimitive.content.contains("没有命中"), "$obj")
    }

    @Test
    fun `search 命中太多会截断但 total 说实话`() {
        val many = (1..30).joinToString("\n") { "第 $it 行都有 关键词" }
        val obj = okJson(FakeSkillStore(skills = mapOf("a" to many)), "search", "关键词")
        assertEquals(20, (obj["hits"] as JsonArray).size, "一次最多回 20 条")
        assertEquals(30, obj["total"]!!.jsonPrimitive.int)
        assertTrue(obj["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `search 不给关键词要报用法`() {
        val failure = assertFailsWith<CapabilityFailure> {
            capability(FakeSkillStore()).capabilities.single().run(listOf("search"))
        }
        assertTrue(failure.message!!.contains("用法"), failure.message!!)
    }

    // ------------------------------------------------------------ 路径安全

    @Test
    fun `非法 id 一律拒绝`() {
        val root = newTempDir()
        try {
            writeSkill(root, "ok", "内容")
            val cap = AgentSkillCapabilities.forDirectory(root).capabilities.single()
            listOf("../etc/passwd", "..", ".", "a/b", "/etc/passwd", "C:\\x", "a b", "a\u0000b").forEach { bad ->
                val failure = assertFailsWith<CapabilityFailure>("「$bad」应被拒") {
                    cap.run(listOf("show", bad))
                }
                assertTrue(
                    failure.message!!.contains("不合法") || failure.message!!.contains("不能为空"),
                    "「$bad」的报错要说不合法：${failure.message}",
                )
            }
            // 合法 id 仍然能读 —— 拒绝不是把整条命令搞坏。
            assertTrue(cap.run(listOf("show", "ok")).contains("内容"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `真实路径跑到根外面就拒绝`() {
        val root = newTempDir()
        val outside = newTempDir()
        try {
            writeSkill(root, "evil", "根里的内容")
            writeSkill(outside, "evil", "根外的内容")
            writeSkill(root, "ok", "正常技能")
            // 模拟「skills/evil 是个指向根外的软链接」：把这个目录下文件的真实路径映射到外部那份。
            // 这样不依赖平台建软链接的权限，哨兵逻辑本身也能被验到。
            val store = FileSkillStore(root) { file ->
                if (file.parentFile?.name == "evil") File(outside, "evil/${file.name}") else file.absoluteFile
            }
            val failure = assertFailsWith<CapabilityFailure> { store.read("evil", 1024) }
            assertTrue(
                failure.message!!.contains("越出") || failure.message!!.contains("逃逸"),
                "要明确说越界：${failure.message}",
            )
            assertFalse(store.ids().contains("evil"), "越界的技能不该出现在 list 里")
            assertTrue(store.ids().contains("ok"), "别的合法技能不受影响：${store.ids()}")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `符号链接逃逸被拒且不列进清单`() {
        val root = newTempDir()
        val outside = newTempDir()
        try {
            writeSkill(outside, "secret", "沙箱外的内容")
            val skills = File(root, "skills").apply { mkdirs() }
            // 真建一条软链接。Windows 默认要管理员/开发者模式 ⇒ 建不了就标 skipped（不是"通过"）。
            val linked = runCatching {
                java.nio.file.Files.createSymbolicLink(
                    File(skills, "evil").toPath(),
                    File(outside, "secret").toPath(),
                )
            }.isSuccess
            org.junit.Assume.assumeTrue("平台不允许建符号链接，跳过（Linux/CI 上会真跑）", linked)

            val store = FileSkillStore(skills)
            val failure = assertFailsWith<CapabilityFailure> { store.read("evil", 1024) }
            assertTrue(failure.message!!.contains("越出") || failure.message!!.contains("逃逸"), failure.message!!)
            assertFalse(FileSkillStore(skills).ids().contains("evil"), "逃逸的链接不该出现在 list 里")
        } finally {
            File(root, "skills").deleteRecursively()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ 真文件：截断

    @Test
    fun `超限按 UTF-8 边界截断并标记`() {
        val root = newTempDir()
        try {
            val text = "第一行：中文技能说明\n" + "很长的内容".repeat(40)
            writeSkill(root, "cn", text)
            val cap = AgentSkillCapabilities.forDirectory(root, maxBytes = 40).capabilities.single()
            val obj = Json.parseToJsonElement(cap.run(listOf("show", "cn"))).jsonObject
            val content = obj["content"]!!.jsonPrimitive.content

            assertTrue(obj["truncated"]!!.jsonPrimitive.boolean, "超限必须标 truncated")
            assertTrue(content.isNotEmpty())
            assertTrue(text.startsWith(content), "截断只能截尾巴，不能改内容")
            assertTrue(content.toByteArray(Charsets.UTF_8).size <= 40, "返回的字节数不能超过上限")
            assertFalse(content.contains('\uFFFD'), "不能在汉字中间劈一刀（会变成乱码）")
            assertEquals(text.length, obj["total_chars"]!!.jsonPrimitive.int, "total_chars 是全文长度")
            assertEquals(content.length, obj["chars"]!!.jsonPrimitive.int)
            assertTrue(obj["note"]!!.jsonPrimitive.content.contains("截断"), "$obj")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没超限就不截断`() {
        val root = newTempDir()
        try {
            writeSkill(root, "short", "短短一行")
            val obj = Json.parseToJsonElement(
                AgentSkillCapabilities.forDirectory(root).capabilities.single()
                    .run(listOf("show", "short")),
            ).jsonObject
            assertFalse(obj["truncated"]!!.jsonPrimitive.boolean)
            assertEquals("短短一行", obj["content"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ 真文件：目录扫描

    @Test
    fun `目录扫描只认有 SKILL 的合法目录`() {
        val root = newTempDir()
        try {
            writeSkill(root, "good-two", "b")
            writeSkill(root, "good-one", "a")
            File(root, "no-skill-file").mkdirs()          // 没有 SKILL.md
            File(root, "bad name").apply { mkdirs() }      // 名字不合法
            File(root, "bad name/SKILL.md").writeText("x")

            val ids = FileSkillStore(root).ids()
            assertEquals(listOf("good-one", "good-two"), ids, "按 id 排序、跳过不合法的")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `技能根不存在时 list 是空表而不是报错`() {
        val missing = File(newTempDir(), "not-created")
        val store = FileSkillStore(missing)
        assertEquals(emptyList(), store.ids())
        val obj = Json.parseToJsonElement(
            AgentSkillCapabilities(store).capabilities.single().run(listOf("list")),
        ).jsonObject
        assertEquals(0, (obj["skills"] as JsonArray).size)
    }

    // ------------------------------------------------------------ 契约 / 提示词

    @Test
    fun `命令名 前缀 与用法都对齐 agent- 那批`() {
        val cap = AgentSkillCapabilities(FakeSkillStore()).capabilities.single()
        assertEquals("skill", cap.name)
        assertEquals(CapabilityOrigin.INTERNAL, cap.origin)
        assertEquals("agent-", cap.prefix)
        assertTrue(cap.usage.startsWith("agent-skill"), cap.usage)
        assertTrue(cap.usage.contains("usr/share/skills"), "完整用法里要给出技能位置：${cap.usage}")
        assertTrue(cap.shortUsage.startsWith("agent-skill"), cap.shortUsage)
    }

    @Test
    fun `能力清单自动进提示词`() {
        // 提示词就是按 describeByOrigin 的结果渲染的（AgentSystemPrompt:138）——
        // 这里断言「模型真能看见这条命令」，而不是只断言我们写了 usage 字段。
        val registry = DeviceCapabilityRegistry(AgentSkillCapabilities(FakeSkillStore()).capabilities)
        val core = registry.describeByOrigin(short = true)[CapabilityOrigin.INTERNAL].orEmpty()
        val full = registry.describeByOrigin(short = false)[CapabilityOrigin.INTERNAL].orEmpty()
        assertTrue(core.any { it.contains("agent-skill") }, "核心模式要看得见：$core")
        assertTrue(full.any { it.contains("usr/share/skills") }, "完整模式要看得见位置：$full")
    }

    @Test
    fun `经过注册表执行 失败给非零退出码`() {
        val registry = DeviceCapabilityRegistry(
            AgentSkillCapabilities(FakeSkillStore(skills = mapOf("a" to "x"))).capabilities,
        )
        val good = registry.execute(registry.parseWhole("agent-skill list")!!)
        assertTrue(good.ok, "应成功：${good.output}")
        assertEquals(0, good.exitCode)

        val bad = registry.execute(registry.parseWhole("agent-skill show nope")!!)
        assertFalse(bad.ok)
        assertEquals(1, bad.exitCode)
        assertTrue(bad.output.contains("\"ok\":false"), bad.output)
        assertTrue(bad.output.contains("没有这个技能"), "失败原因要能照着改：${bad.output}")
    }

    @Test
    fun `不认识的子命令给用法`() {
        val failure = assertFailsWith<CapabilityFailure> {
            AgentSkillCapabilities(FakeSkillStore()).capabilities.single().run(listOf("delete", "a"))
        }
        assertTrue(failure.message!!.contains("用法"), failure.message!!)
    }

    // ------------------------------------------------------------ 小工具

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agent-skill-test-${System.nanoTime()}")
            .apply { mkdirs() }
            .also { tempDirs += it }

    private fun writeSkill(root: File, id: String, text: String) {
        File(root, "$id/SKILL.md").apply { parentFile?.mkdirs(); writeText(text) }
    }
}
