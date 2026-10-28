package ai.agentkernel.device

import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备能力路由的测试 —— 这部分是**纯 JVM** 的，所以能单测。
 *
 * 它要证明的是：模型输出的 `termux-*` 命令能被正确认出、正确拆参、
 * 正确改写成 shell 能跑的形式 —— 而这些在设备上才跑得起来，
 * 靠真机验太慢也太贵。
 */
class DeviceCapabilityTest {

    /** 假能力：不碰任何 Android API，只回显参数。 */
    private class EchoCapability(override val name: String, private val fail: Boolean = false) : DeviceCapability {
        override val usage: String = "termux-$name <args>"
        var lastArgs: List<String> = emptyList()
        override fun run(args: List<String>): String {
            lastArgs = args
            if (fail) throw CapabilityFailure("模拟失败")
            return okJson { put("args", args.joinToString(" ")) }
        }
    }

    private fun registry(vararg caps: DeviceCapability) = DeviceCapabilityRegistry(caps.toList())

    @Test
    fun `整条能力命令能被认出`() {
        val reg = registry(EchoCapability("torch"))
        val cmd = reg.parseWhole("termux-torch on")
        assertNotNull(cmd, "应认出 termux-torch")
        assertEquals("torch", cmd.name)
        assertEquals(listOf("on"), cmd.args)
    }

    @Test
    fun `引号里的空格算一个参数`() {
        val reg = registry(EchoCapability("clipboard-set"))
        val cmd = reg.parseWhole("""termux-clipboard-set "hello world"""")
        assertNotNull(cmd)
        assertEquals(listOf("hello world"), cmd.args)
    }

    @Test
    fun `单引号也能用`() {
        val reg = registry(EchoCapability("clipboard-set"))
        val cmd = reg.parseWhole("termux-clipboard-set 'a b c'")
        assertNotNull(cmd)
        assertEquals(listOf("a b c"), cmd.args)
    }

    @Test
    fun `复合命令里能找出所有能力调用`() {
        val reg = registry(EchoCapability("torch"), EchoCapability("battery-status"))
        val found = reg.findAll("termux-torch on && termux-battery-status")
        assertEquals(2, found.size)
        assertEquals("torch", found[0].name)
        assertEquals("battery-status", found[1].name)
        assertEquals(listOf("on"), found[0].args)
    }

    @Test
    fun `能力参数到操作符为止`() {
        val reg = registry(EchoCapability("notification"))
        val found = reg.findAll("termux-notification -t 标题 -c 内容 && echo done")
        assertEquals(1, found.size)
        assertEquals(listOf("-t", "标题", "-c", "内容"), found[0].args)
    }

    @Test
    fun `复合命令不是整条能力命令`() {
        val reg = registry(EchoCapability("torch"))
        assertNull(reg.parseWhole("termux-torch on && echo done"), "带其它部分就不算整条能力命令")
    }

    @Test
    fun `没注册的能力名不当能力处理`() {
        val reg = registry(EchoCapability("torch"))
        assertNull(reg.parseWhole("termux-torch-x on"), "没注册的名字不该被认成能力")
        assertTrue(reg.findAll("termux-unknown on").isEmpty())
    }

    @Test
    fun `改写把能力调用换成占位符`() {
        val reg = registry(EchoCapability("torch"))
        val plan = reg.plan("termux-torch on && echo done")
        assertNotNull(plan, "应给出改写计划")
        assertEquals(1, plan.calls.size)
        assertTrue(
            plan.rewritten.startsWith("@@AGENT_CAP_0_0@@"),
            "能力调用应被换成占位符，实际：${plan.rewritten}",
        )
        assertTrue(plan.rewritten.endsWith("&& echo done"), "命令链其余部分要保留")
    }

    @Test
    fun `改写保留多个能力调用的顺序`() {
        val reg = registry(EchoCapability("torch"), EchoCapability("battery-status"))
        val plan = reg.plan("termux-torch on && termux-battery-status")
        assertNotNull(plan)
        assertEquals(2, plan.calls.size)
        assertEquals("torch", plan.calls[0].command.name)
        assertEquals("battery-status", plan.calls[1].command.name)
        assertTrue(
            plan.rewritten.indexOf("AGENT_CAP_0_0") < plan.rewritten.indexOf("AGENT_CAP_0_1"),
            "顺序不能乱",
        )
    }

    @Test
    fun `落地后成功的能力用 printf 打印结果`() {
        val reg = registry(EchoCapability("torch"))
        val plan = reg.plan("termux-torch on && echo done")
        assertNotNull(plan)
        val cmd = reg.parseWhole("termux-torch on")
        assertNotNull(cmd)
        val materialized = reg.materialize(plan, listOf(reg.execute(cmd)))
        assertEquals(
            """printf '%s' "${'$'}AGENT_CAP_0_0" && echo done""",
            materialized.command,
            "成功的能力不该改变整条命令的退出码",
        )
        assertEquals("""{"ok":true,"args":"on"}""", materialized.env["AGENT_CAP_0_0"])
    }

    @Test
    fun `落地后失败的能力要打印原因并让整条命令失败`() {
        val reg = registry(EchoCapability("torch", fail = true))
        val plan = reg.plan("termux-torch on && echo done")
        assertNotNull(plan)
        val cmd = reg.parseWhole("termux-torch on")
        assertNotNull(cmd)
        val materialized = reg.materialize(plan, listOf(reg.execute(cmd)))
        assertTrue(
            materialized.command.contains("; false"),
            "失败的能力要让整段退出码为 1（&& 链要短路）：${materialized.command}",
        )
        assertTrue(
            materialized.env["AGENT_CAP_0_0"]!!.contains("\"ok\":false"),
            "失败原因也要传给模型：${materialized.env["AGENT_CAP_0_0"]}",
        )
    }

    @Test
    fun `能力抛的失败被注册表兜住并给非零退出码`() {
        val reg = registry(EchoCapability("torch", fail = true))
        val cmd = reg.parseWhole("termux-torch on")
        assertNotNull(cmd)
        val result = reg.execute(cmd)
        assertEquals(false, result.ok)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("\"ok\":false"), "失败也要是 JSON：${result.output}")
    }

    @Test
    fun `能力成功返回 JSON`() {
        val cap = EchoCapability("torch")
        val reg = registry(cap)
        val cmd = reg.parseWhole("termux-torch on")
        assertNotNull(cmd)
        val result = reg.execute(cmd)
        assertTrue(result.ok)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"ok\":true"), "成功也要是 JSON：${result.output}")
        assertEquals(listOf("on"), cap.lastArgs, "参数要原样传下去")
    }

    // ---- 输出重定向：能力命令的结果也要能存进文件 ----
    //
    // 背景：模型很自然会写 `termux-battery-status > b.txt`（把结果存下来）。
    // 如果分词器不认 `>`，重定向符和文件名会被当成**能力的参数**吞进去，
    // 于是能力照跑、结果回 stdout，但**文件根本没生成**，而退出码还是 0、ok 还是 true ——
    // 这正是本项目定义的「谎报成功」，最坏的一类。

    @Test
    fun `能力命令后的重定向符不算能力参数`() {
        val reg = registry(EchoCapability("battery-status"))
        val found = reg.findAll("termux-battery-status > b.txt")
        assertEquals(1, found.size)
        assertEquals(emptyList(), found[0].args, "重定向符和文件名不是能力参数")
    }

    @Test
    fun `能力命令带参数时重定向也不算参数`() {
        val reg = registry(EchoCapability("torch"))
        val found = reg.findAll("termux-torch on > t.txt")
        assertEquals(1, found.size)
        assertEquals(listOf("on"), found[0].args, "重定向符和文件名不是能力参数")
    }

    @Test
    fun `能力输出重定向会留在最终命令里`() {
        val reg = registry(EchoCapability("battery-status"))
        val plan = reg.plan("termux-battery-status > b.txt")
        assertNotNull(plan)
        assertEquals(
            "@@AGENT_CAP_0_0@@ > b.txt",
            plan.rewritten,
            "重定向必须留在命令里，否则文件不会被生成",
        )
    }

    @Test
    fun `带重定向的能力命令不算整条能力命令`() {
        val reg = registry(EchoCapability("battery-status"))
        assertNull(
            reg.parseWhole("termux-battery-status > b.txt"),
            "整条还有 shell 部分，就该走复合命令那条路（这样重定向才会被执行）",
        )
    }

    @Test
    fun `引号里的重定向符不当操作符`() {
        val reg = registry(EchoCapability("clipboard-set"))
        val found = reg.findAll("""termux-clipboard-set "a > b"""")
        assertEquals(1, found.size)
        assertEquals(listOf("a > b"), found[0].args, "引号里的 > 是内容，不是重定向")
    }

    // ---- SDK 自制的命令（`agent-` 前缀） ----
    //
    // 这批命令**不在 Termux:API 里**，是这套环境特有的（工作区维护、HTTP 调用…）。
    // 所以必须与 Termux 对齐的那批区分开：模型要把它们当成「内部命令」，
    // 而不是以为去别处也能找到。

    private class InternalCapability(override val name: String) : DeviceCapability {
        override val usage: String = "agent-$name <args>"
        override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
        override fun run(args: List<String>): String = okJson { put("args", args.joinToString(" ")) }
    }

    @Test
    fun `内部自制命令用 agent- 前缀且能被认出来`() {
        val reg = registry(InternalCapability("cleanup"))
        val cmd = reg.parseWhole("agent-cleanup --dry-run")
        assertNotNull(cmd, "agent- 前缀的能力要认得出")
        assertEquals("cleanup", cmd.name)
        assertEquals("agent-", cmd.prefix)
        assertEquals(listOf("--dry-run"), cmd.args)
    }

    @Test
    fun `前缀不对就不认`() {
        val reg = registry(EchoCapability("torch"), InternalCapability("cleanup"))
        assertEquals("torch", reg.parseWhole("termux-torch on")?.name)
        assertEquals("cleanup", reg.parseWhole("agent-cleanup")?.name)
        assertNull(reg.parseWhole("agent-torch on"), "前缀不对就不该认成 torch")
        assertNull(reg.parseWhole("termux-cleanup"), "前缀不对就不该认成 cleanup")
    }

    @Test
    fun `能力清单按来源分组`() {
        val reg = registry(EchoCapability("torch"), InternalCapability("cleanup"))
        val byOrigin = reg.describeByOrigin()
        assertEquals(listOf("termux-torch <args>"), byOrigin[CapabilityOrigin.TERMUX])
        assertEquals(listOf("agent-cleanup <args>"), byOrigin[CapabilityOrigin.INTERNAL])
    }

    @Test
    fun `复合命令里两种前缀的能力都能认出`() {
        val reg = registry(EchoCapability("torch"), InternalCapability("cleanup"))
        val found = reg.findAll("termux-torch on && agent-cleanup --apply")
        assertEquals(2, found.size)
        assertEquals("torch", found[0].name)
        assertEquals("cleanup", found[1].name)
    }

    // ---- 名字必须与 Termux:API 逐字对齐 ----
    // 下面这几条是回归护栏：模型是按 Termux:API 的习惯写命令的，
    // 把剪贴板合并成一个 `clipboard` 能力的话，模型写
    // `termux-clipboard-set` 会直接落空（找不到二进制 → command not found）。

    @Test
    fun `剪贴板读写是两个独立能力`() {
        val reg = registry(EchoCapability("clipboard-get"), EchoCapability("clipboard-set"))
        val get = reg.parseWhole("termux-clipboard-get")
        val set = reg.parseWhole("termux-clipboard-set hello")
        assertNotNull(get, "termux-clipboard-get 要认得出")
        assertNotNull(set, "termux-clipboard-set 要认得出")
        assertEquals("clipboard-get", get.name)
        assertEquals("clipboard-set", set.name)
        assertEquals(emptyList(), get.args, "clipboard-get 不该带参数")
        assertEquals(listOf("hello"), set.args)
    }

    @Test
    fun `电池能力名是 battery-status`() {
        val reg = registry(EchoCapability("battery-status"))
        val cmd = reg.parseWhole("termux-battery-status")
        assertNotNull(cmd, "termux-battery-status 要认得出")
        assertEquals("battery-status", cmd.name)
    }

    @Test
    fun `能力清单逐条对齐 Termux 命令名`() {        val reg = registry(
            EchoCapability("torch"),
            EchoCapability("vibrate"),
            EchoCapability("notification"),
            EchoCapability("clipboard-get"),
            EchoCapability("clipboard-set"),
            EchoCapability("battery-status"),
        )
        assertEquals(
            listOf(
                "termux-torch <args>",
                "termux-vibrate <args>",
                "termux-notification <args>",
                "termux-clipboard-get <args>",
                "termux-clipboard-set <args>",
                "termux-battery-status <args>",
            ),
            reg.describeLines(),
            "清单要给模型看，名字要和 Termux:API 逐字一致",
        )
    }
}
