package ai.agentkernel.text

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `agent-text` —— 按行改沙箱里的文本文件 —— 纯 JVM。
 *
 * 这几条守的是**契约**：
 * - 输出**一律是 JSON**（模型要按字段取值，不能靠肉眼看）；
 * - 失败**抛 [CapabilityFailure]**（注册表会转成 `{"ok":false,...}` + 退出码 1）；
 * - **路径沙箱**：只能碰沙箱根下面的相对路径（`..` / 绝对路径 / 符号链接逃逸都拒）。
 */
class AgentTextCapabilitiesTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-textcap-${System.nanoTime()}").also { it.mkdirs() }

    private fun capability(root: File, maxFileChars: Int = 20_000): DeviceCapability =
        AgentTextCapabilities(root, maxFileChars).capabilities.single()

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
        val caps = AgentTextCapabilities(tempDir()).capabilities
        assertEquals(1, caps.size, "只做一条命令 —— 提示词里每多一行就多占字符")
        val cap = caps.single()
        assertEquals("text", cap.name)
        assertEquals(CapabilityOrigin.INTERNAL, cap.origin)
        assertEquals("agent-", cap.prefix, "内部自制的命令前缀是 agent-")
        assertTrue(cap.usage.startsWith("agent-text "), "用法要带完整命令名：${cap.usage}")
        assertTrue(cap.usage.contains("read <相对路径>"), "用法行要带 read 子命令：${cap.usage}")
        assertTrue(cap.usage.contains("patch <相对路径> <补丁规格>"), "用法行要带 patch 子命令：${cap.usage}")
    }

    // ---- read ----

    @Test
    fun `read 读出文件内容`() {
        val root = tempDir()
        File(root, "notes.md").writeText("第一行\n第二行")
        val cap = capability(root)

        val out = run(cap, "read", "notes.md")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("notes.md", out["path"]!!.jsonPrimitive.content)
        assertEquals("第一行\n第二行", out["content"]!!.jsonPrimitive.content)
        assertEquals("第一行\n第二行".length, out["chars"]!!.jsonPrimitive.int)
    }

    @Test
    fun `read 子目录里的文件`() {
        val root = tempDir()
        File(root, "var/memory").mkdirs()
        File(root, "var/memory/a.md").writeText("内容")
        val cap = capability(root)

        val out = run(cap, "read", "var/memory/a.md")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("内容", out["content"]!!.jsonPrimitive.content)
        assertEquals("var/memory/a.md", out["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read 文件不存在要报错`() {
        val cap = capability(tempDir())

        val e = assertFailsWith<CapabilityFailure> { cap.run(listOf("read", "没有这个.md")) }
        assertTrue(e.message!!.contains("没有这个.md"), "要说清是哪个文件：${e.message}")

        // 注册表会把它转成 {"ok":false,...} + 非零退出码。
        val r = runViaRegistry(cap, "read", "没有这个.md")
        assertFalse(r.ok)
        assertTrue(r.exitCode != 0, "失败要非零退出码")
        val err = Json.parseToJsonElement(r.output).jsonObject
        assertFalse(err["ok"]!!.jsonPrimitive.boolean)
    }

    // ---- patch：改一行 ----

    @Test
    fun `patch 改一行并回 diff`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行\n第三行") }
        val cap = capability(root)

        val out = run(cap, "patch", "notes.md", "replace-lines", "2", "2", "新的第二行")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("notes.md", out["path"]!!.jsonPrimitive.content)
        assertEquals("第一行\n新的第二行\n第三行", file.readText(), "只改了第 2 行")
        assertEquals(file.readText().length, out["chars"]!!.jsonPrimitive.int, "chars = 改完正文的字符数")

        val diff = out["diff"]!!.jsonPrimitive.content
        assertTrue(diff.isNotBlank(), "diff 要有内容")
        assertTrue(diff.contains("2"), "diff 要带改动的行号：$diff")
    }

    @Test
    fun `patch 规格被拆成多个参数也能拼起来解析`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行") }
        val cap = capability(root)

        // 整条命令按 shell 规则拆词后，规格散成 4 个参数 —— 要拼回一个规格串再解析。
        val args = splitArgs(cap, "agent-text patch notes.md replace-lines 2 2 新的第二行")
        assertEquals(listOf("patch", "notes.md", "replace-lines", "2", "2", "新的第二行"), args)

        val out = run(cap, *args.toTypedArray())
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("第一行\n新的第二行", file.readText())
    }

    @Test
    fun `patch 的 replace-block 保留竖线`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("标题\n旧的一行") }
        val cap = capability(root)

        // `|` 在 shell 里是管道操作符 ⇒ 整段规格必须加引号；引号里的 `|` 原样保留。
        val args = splitArgs(cap, "agent-text patch notes.md \"replace-block|旧的一行|新的一行\"")
        assertEquals(listOf("patch", "notes.md", "replace-block|旧的一行|新的一行"), args)

        val out = run(cap, *args.toTypedArray())
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("标题\n新的一行", file.readText())
    }

    @Test
    fun `patch 的参数里的换行转义能生效`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行") }
        val cap = capability(root)

        // 规格里的 `\n` 表示真换行（shell 引号里传不了真换行）。
        run(cap, "patch", "notes.md", "replace-lines", "2", "2", "新的第二行\\n第三行")
        assertEquals("第一行\n新的第二行\n第三行", file.readText())
    }

    // ---- patch：路径沙箱 ----

    @Test
    fun `patch 路径逃逸要拒绝且不能写文件`() {
        val parent = tempDir()
        val root = File(parent, "root").apply { mkdirs() }
        val cap = capability(root)

        // 相对路径里的 ..
        val r1 = runViaRegistry(cap, "patch", "../逃逸.md", "replace-lines", "1", "1", "内容")
        assertFalse(r1.ok, ".. 必须拒绝")
        assertTrue(r1.exitCode != 0, "失败要非零退出码")
        assertFalse(File(parent, "逃逸.md").exists(), "逃逸的文件不能被写出来")

        // 绝对路径
        val r2 = runViaRegistry(cap, "patch", "/abs/path.md", "replace-lines", "1", "1", "内容")
        assertFalse(r2.ok, "绝对路径必须拒绝")
        val err2 = Json.parseToJsonElement(r2.output).jsonObject
        assertFalse(err2["ok"]!!.jsonPrimitive.boolean)
        assertTrue(err2["error"]!!.jsonPrimitive.content.contains("相对路径"), "要说清要写相对路径")

        // Windows 盘符路径（在别的平台上也不该给过）
        val r3 = runViaRegistry(cap, "patch", "C:\\Windows\\x.md", "replace-lines", "1", "1", "内容")
        assertFalse(r3.ok, "盘符路径必须拒绝")

        // 一个都没写出来
        assertEquals(0, root.listFiles()?.size ?: 0, "沙箱里不该多出文件")
    }

    @Test
    fun `read 路径逃逸要拒绝`() {
        val root = tempDir()
        val cap = capability(root)
        assertFailsWith<CapabilityFailure> { cap.run(listOf("read", "../逃逸.md")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("read", "/abs/path.md")) }
    }

    @Test
    fun `符号链接指向沙箱外要拒绝`() {
        val root = tempDir()
        val outside = tempDir()
        val link = File(root, "link")
        // 建不出符号链接（Windows 没开开发者模式 / 权限不够）就跳过这一条。
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return

        val cap = capability(root)
        val r = runViaRegistry(cap, "patch", "link/逃逸.md", "replace-lines", "1", "1", "内容")
        assertFalse(r.ok, "符号链接逃逸必须拒绝")
        assertFalse(File(outside, "逃逸.md").exists(), "沙箱外的文件不能被写出来")
    }

    @Test
    fun `相对路径里的反斜杠也当分隔符`() {
        val root = tempDir()
        File(root, "var/memory").mkdirs()
        File(root, "var/memory/a.md").writeText("内容")
        val cap = capability(root)

        val out = run(cap, "read", "var\\memory\\a.md")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("内容", out["content"]!!.jsonPrimitive.content)
    }

    // ---- patch：上限与新建 ----

    @Test
    fun `patch 超过上限要拒绝且原文件不变`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行") }
        val cap = capability(root, maxFileChars = 5)

        val r = runViaRegistry(cap, "patch", "notes.md", "replace-lines", "2", "2", "很长很长很长很长的新内容")
        assertFalse(r.ok, "超过上限必须拒绝")
        assertTrue(r.exitCode != 0, "失败要非零退出码")
        val err = Json.parseToJsonElement(r.output).jsonObject
        assertFalse(err["ok"]!!.jsonPrimitive.boolean)
        val message = err["error"]!!.jsonPrimitive.content
        assertTrue(message.contains("上限"), "要说清是超上限：$message")
        assertEquals("第一行\n第二行", file.readText(), "没写盘就不能改")
    }

    @Test
    fun `patch 文件不存在时新建`() {
        val root = tempDir()
        val cap = capability(root)

        val out = run(cap, "patch", "notes.md", "replace-lines", "1", "1", "第一行")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals("notes.md", out["path"]!!.jsonPrimitive.content)
        assertEquals("第一行".length, out["chars"]!!.jsonPrimitive.int)

        val file = File(root, "notes.md")
        assertTrue(file.exists(), "文件要被建出来")
        assertEquals("第一行", file.readText())
    }

    @Test
    fun `patch 文件不存在时行号越界要报错`() {
        val root = tempDir()
        val cap = capability(root)

        // 空文件上只有 replace-lines 1 1 能落地，别的行号都没有落脚点。
        val r1 = runViaRegistry(cap, "patch", "notes.md", "replace-lines", "2", "2", "第二行")
        assertFalse(r1.ok, "越界必须拒绝")

        val r2 = runViaRegistry(cap, "patch", "notes.md", "insert-after", "1", "内容")
        assertFalse(r2.ok, "insert-after 越界必须拒绝")

        val r3 = runViaRegistry(cap, "patch", "notes.md", "replace-block|不存在的锚点|新行")
        assertFalse(r3.ok, "锚点找不到必须拒绝")

        assertFalse(File(root, "notes.md").exists(), "没成就不能建出文件")
    }

    // ---- patch：规格不对 ----

    @Test
    fun `patch 规格格式不对要报错`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行") }
        val cap = capability(root)

        // replace-block 缺 `|` —— 锚点边界猜不出来，必须报错（不能猜着改）。
        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "notes.md", "replace-block", "第一行", "新行"))
        }
        assertTrue(e.message!!.contains("agent-text"), "要带用法让模型自我纠正：${e.message}")

        val r = runViaRegistry(cap, "patch", "notes.md", "replace-block", "第一行", "新行")
        assertFalse(r.ok)
        val err = Json.parseToJsonElement(r.output).jsonObject
        assertFalse(err["ok"]!!.jsonPrimitive.boolean)
        assertTrue(err["error"]!!.jsonPrimitive.content.contains("agent-text"))
        assertEquals("第一行\n第二行", file.readText(), "没打上就不能改")
    }

    @Test
    fun `patch 锚点找不到要报错`() {
        val root = tempDir()
        val file = File(root, "notes.md").apply { writeText("第一行\n第二行") }
        val cap = capability(root)

        val e = assertFailsWith<CapabilityFailure> {
            cap.run(listOf("patch", "notes.md", "replace-block|不存在的锚点|新行"))
        }
        assertTrue(e.message!!.contains("锚点"), "要说清是锚点没找到：${e.message}")
        assertEquals("第一行\n第二行", file.readText(), "没打上就不能改")
    }

    @Test
    fun `缺参数和不认识的子命令要报错`() {
        val cap = capability(tempDir())
        assertFailsWith<CapabilityFailure> { cap.run(emptyList()) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("read")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("patch")) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("patch", "notes.md")) }

        val e = assertFailsWith<CapabilityFailure> { cap.run(listOf("frobnicate")) }
        assertTrue(e.message!!.contains("agent-text"), "用法错误要说清怎么用：${e.message}")
    }
}
