package ai.agentkernel.device

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一条设备能力命令 —— 对应「拟 Linux」里可用的那批命令。
 *
 * ## 为什么能力不是「往 PATH 里塞一个二进制」
 *
 * 塞二进制要付三笔账：
 * 1. **noexec**：宿主 targetSdk ≥ 29 时，私有目录里的可执行文件直接 EACCES 起不来
 *    （只有 `nativeLibraryDir` 豁免，那就要按 ABI 拆包）；
 * 2. **shebang 改写**：bootstrap 里的脚本写死了 `/data/data/com.termux/...`；
 * 3. **包名路径**：`bin/am` 之类还写死了 `com.termux`。
 *
 * 走能力路由，这三笔账**一笔都不用付** —— 命令由宿主在进程内执行，
 * 不经过「文件能不能被执行」这一关。
 *
 * ## 契约
 *
 * - 命令形态沿用 Termux:API 规范：**命令名 + 参数 + 输出 JSON 到 stdout**；
 * - 命令名与 Termux:API **逐字对齐**（去掉 `termux-` 前缀），模型按习惯写就认；
 * - 执行失败**抛 [CapabilityFailure]**，由 [DeviceCapabilityRegistry] 统一转
 *   `{"ok":false,...}` + 非零退出码。
 */
interface DeviceCapability {
    /**
     * 命令名（不带前缀），例如 `torch`。
     *
     * 必须与 Termux:API 的命令名逐字一致，否则模型按习惯写的命令会落空。
     */
    val name: String

    /** 用法说明（含完整命令名），例如 `termux-torch on|off`。参数不对时回给模型，让它自己纠正。 */
    val usage: String

    /**
     * **短用法** —— 只说「这条命令能干什么」，不列完整参数形态。
     *
     * ## 为什么要有两个
     *
     * 有些能力（`agent-memory` 那种带七八个子命令的）完整用法一行就上百字符，
     * 而**提示词的「核心模式」存在的意义就是省预算** —— 装进网关那 2000 字符里。
     * 参数形态写详细了，核心模式直接装不下；删掉这条能力更糟（模型压根不知道它存在）。
     *
     * ⇒ 核心模式列**短用法**（知道有这条命令、知道大概干什么），
     * 完整参数形态放技能文档里按需查；完整模式才列 [usage]。
     *
     * 默认就是 [usage] —— 简单的能力不需要短版本。
     */
    val shortUsage: String get() = usage

    /**
     * 这条能力属于哪一组。
     *
     * - [CapabilityOrigin.TERMUX] —— 与 Termux:API 逐字对齐的标准命令；
     * - [CapabilityOrigin.INTERNAL] —— **SDK 自制**的命令。
     *
     * 分组不是为了好看：提示词里要**分别告诉模型这两类命令的来历**，
     * 否则模型会把 `agent-cleanup` 当成 Termux 命令去别处找。
     */
    val origin: CapabilityOrigin get() = CapabilityOrigin.TERMUX

    /** 命令前缀。默认按 [origin] 取：Termux 对齐 → `termux-`，内部自制 → `agent-`。 */
    val prefix: String
        get() = when (origin) {
            CapabilityOrigin.TERMUX -> DeviceCommandParser.TERMUX_PREFIX
            CapabilityOrigin.INTERNAL -> DeviceCommandParser.AGENT_PREFIX
        }

    /**
     * 执行一条能力命令。
     *
     * @param args 命令后面的参数（已按 shell 规则拆词、引号已去掉）
     * @return 给模型看的文本，约定是 JSON
     * @throws CapabilityFailure 执行失败（用法不对、权限不够、设备不支持……）
     */
    fun run(args: List<String>): String
}

/**
 * 能力命令的来源分组。
 */
enum class CapabilityOrigin {
    /** 与 Termux:API 逐字对齐 —— 模型按 Termux 的习惯写就认。 */
    TERMUX,

    /** SDK 自制 —— 不在 Termux:API 里，是本 SDK 环境特有的。 */
    INTERNAL,
}

/**
 * 能力执行失败。
 *
 * 约定：**失败就抛这个异常**，由 [DeviceCapabilityRegistry] 统一转成
 * `{"ok":false,"error":...}` 并给非零退出码。
 *
 * 为什么不靠返回值里的 `ok:false` 判断：那样注册表就得回头解析能力返回的 JSON，
 * 一旦能力自己改了格式，退出码就会和内容对不上（内容说失败、退出码说成功）。
 */
class CapabilityFailure(message: String) : Exception(message)

/** 一条能力调用的执行结果（供执行器组装 ExecResult 用）。 */
data class CapabilityResult(
    val ok: Boolean,
    val output: String,
    val exitCode: Int = if (ok) 0 else 1,
)

/** 能力命令表：负责「把一行 shell 命令认出能力调用」并执行。 */
class DeviceCapabilityRegistry(capabilities: List<DeviceCapability>) {

    private val ordered: List<DeviceCapability> = capabilities.toList()
    private val byName: Map<String, DeviceCapability> = ordered.associateBy { it.name }

    /** 注册表里已知的 `(前缀, 能力名)` 对 —— 解析时按这一对去查，避免撞名认错。 */
    private val known: Set<Pair<String, String>> =
        ordered.map { it.prefix to it.name }.toSet()

    val names: Set<String> get() = byName.keys

    fun find(name: String): DeviceCapability? = byName[name]

    /**
     * 给模型看的能力清单 —— 每行一条完整命令。
     *
     * 这份清单要进 `bash` 工具的描述里，否则模型不知道 `termux-torch` 存在，
     * 根本不会去写它（能力实现了等于没实现）。
     *
     * 分组返回：Termux 对齐的在前，内部自制的在后 ——
     * 提示词里要分别介绍这两类。
     */
    fun describeLines(): List<String> = ordered.map { it.usage }

    /** 按来源分组的清单，供提示词分段落讲。 */
    fun describeByOrigin(short: Boolean = false): Map<CapabilityOrigin, List<String>> =
        ordered.groupBy { it.origin }.mapValues { (_, v) -> v.map { if (short) it.shortUsage else it.usage } }

    /**
     * 整条命令**就是**一个能力调用吗？
     *
     * 是的话由宿主在进程内执行（不经过 shell）—— 这样失败时能给出真实的退出码，
     * 而且不用起子进程。
     */
    fun parseWhole(line: String): DeviceCommand? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val found = findAll(trimmed)
        if (found.size != 1) return null
        val only = found.single()
        return only.takeIf { it.coversWholeLine(trimmed) }
    }

    /** 找出命令里所有能力调用。 */
    fun findAll(line: String): List<DeviceCommand> = DeviceCommandParser.findAll(line, known)

    /**
     * 复合命令里的能力调用 → 改写计划。
     *
     * 改写方式：把能力调用换成 `printf '%s' "$AGENT_CAP_0"`，
     * 能力**先**执行、结果放进环境变量，shell 再把它打印出来。
     * 这样 `termux-torch on && echo done` 这类组合也能用，
     * 而且完全绕开 noexec（最终执行的是 shell 内建命令）。
     *
     * ## 为什么不用「结果落盘 + shell 读文件」
     *
     * 落盘方案要把 `cat '<file>'` 塞进命令里，于是这条路径**依赖宿主 shell 还能找到
     * `cat`**。而 SDK 恰恰会把 PATH 整个换成「拟 Linux 的 bin + /system/bin」——
     * 在 Android 上碰巧有 `/system/bin/cat` 兜着，换个宿主就没有了
     * （本机 Git Bash 实测就是 `cat: command not found`，退出码 127）。
     *
     * 环境变量方案不依赖任何外部程序：
     * - `printf` 是 POSIX shell 内建命令（bash / dash / mksh / ash 都有）；
     * - 不用建临时文件，也就没有路径转义和残留清理的问题；
     * - 结果**原样**传过去（`printf '%s'` 不做 word splitting、不解释反斜杠）。
     *
     * 代价：能力调用会**先于**命令链的其余部分执行（因为结果得先进环境变量）。
     */
    fun plan(line: String, index: Int = 0): RewritePlan? {
        val found = findAll(line)
        if (found.isEmpty()) return null
        val calls = found.mapIndexed { i, occ ->
            CapabilityCall(occ, "AGENT_CAP_${index}_$i")
        }
        var rewritten = line
        // 从后往前替换，避免前面的替换把后面的下标顶掉。
        found.asReversed().forEachIndexed { revIdx, occ ->
            val call = calls[found.size - 1 - revIdx]
            rewritten = rewritten.replaceRange(occ.start, occ.end, marker(call.envName))
        }
        return RewritePlan(rewritten, calls)
    }

    /**
     * 把改写计划落地：能力跑完之后，得到「最终命令 + 要注入的环境变量」。
     *
     * 成功的能力 → `printf '%s' "$AGENT_CAP_0"`（打印结果，退出码 0）；
     * 失败的能力 → `printf '%s' "$AGENT_CAP_0"; false`
     * （**照样把失败原因打印出来**，但整段的退出码是 1，
     * 于是 `&&` 链会短路、`||` 链会走兜底分支 —— 与真实命令的语义一致）。
     */
    fun materialize(plan: RewritePlan, results: List<CapabilityResult>): Materialized {
        require(results.size == plan.calls.size) { "结果数与调用数对不上" }
        val env = mutableMapOf<String, String>()
        var command = plan.rewritten
        plan.calls.forEachIndexed { i, call ->
            val result = results[i]
            env[call.envName] = result.output
            val print = "printf '%s' \"\$${call.envName}\""
            val replacement = if (result.ok) print else "$print; false"
            command = command.replace(marker(call.envName), replacement)
        }
        return Materialized(command, env)
    }

    /**
     * 执行一条能力命令，返回给模型的文本（JSON）。
     *
     * 失败由这里兜住（能力抛的 [CapabilityFailure]、以及任何别的异常），
     * 一律转成 `{"ok":false,...}` + 退出码 1，**不往外抛** ——
     * 工具执行失败要变成模型看得见的输出，而不是把内核打断。
     */
    fun execute(command: DeviceCommand): CapabilityResult {
        val capability = byName[command.name]
            ?: return CapabilityResult(false, errorJson("未知能力命令 ${command.prefix}${command.name}"))
        return try {
            CapabilityResult(true, capability.run(command.args))
        } catch (e: CapabilityFailure) {
            CapabilityResult(false, errorJson("${command.prefix}${command.name}: ${e.message}"))
        } catch (e: Exception) {
            CapabilityResult(
                false,
                errorJson("${command.prefix}${command.name} 执行失败：${e.message ?: e::class.java.simpleName}"),
            )
        }
    }
}

/** 一次能力调用：命令 + 结果要放进哪个环境变量。 */
data class CapabilityCall(val command: DeviceCommand, val envName: String)

/** 改写计划：命令里留着占位符，等能力跑完再落地成真正的命令。 */
data class RewritePlan(val rewritten: String, val calls: List<CapabilityCall>)

/** 落地结果：最终命令 + 要注入子进程的环境变量。 */
data class Materialized(val command: String, val env: Map<String, String>)

/** 占位符：能力调用的位置先摆一个标记，跑完再换成真正的 printf。 */
fun marker(envName: String): String = "@@$envName@@"

/** 能力调用的 JSON 输出（统一的失败格式）。 */
fun errorJson(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

/** 成功输出的便捷构造。 */
fun okJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        put("ok", true)
        build()
    }.toString()

/** 供测试/调试用：把 JsonObject 转成紧凑字符串。 */
fun JsonObject.compact(): String = toString()
