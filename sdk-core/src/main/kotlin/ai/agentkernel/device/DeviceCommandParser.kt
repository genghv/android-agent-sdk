package ai.agentkernel.device

/**
 * 一条能力调用在原命令里的位置。
 *
 * @param start/end 在**去掉首尾空白后的**命令串里的字符区间（左闭右开）
 */
data class DeviceCommand(
    /** 命令前缀（`termux-` / `agent-`）。 */
    val prefix: String,
    /** 去掉前缀后的能力名（如 `torch` / `http-get`）。 */
    val name: String,
    val args: List<String>,
    val start: Int,
    val end: Int,
) {
    /** 这一条调用是不是覆盖了整条命令（没有别的部分）。 */
    fun coversWholeLine(trimmed: String): Boolean = start == 0 && end == trimmed.length
}

/**
 * 从一行 shell 命令里认出能力调用。
 *
 * 只做**够用**的拆分，不做完整 shell 解析：
 * - 按空白拆词，引号（`"` / `'`）内的内容原样保留，引号本身去掉；
 * - `&&` `||` `|` `;` `&` 换行 `>` 视为操作符 —— 能力调用的参数到操作符为止。
 *
 * 认不出的 `termux-xxx` / `agent-xxx`（注册表里没有这个名字）**不当能力处理**，
 * 照常当普通命令走 shell —— 这样「拼错名字」会得到命令找不到，
 * 而不是被我们编一个假错误。
 *
 * ## 支持多前缀
 *
 * - `termux-`：与 **Termux:API 逐字对齐**的标准命令（模型按 Termux 的习惯写就认）；
 * - `agent-`：**我们内部拟 Linux 自制**的命令（工作区维护、HTTP 调用等）。
 *
 * 前缀 + 能力名是**一对**去注册表里查的，所以 `termux-agent-foo` 和 `agent-foo`
 * 这种撞名情况不会认错。
 *
 * ## 为什么 `>` 也要算操作符
 *
 * 模型很自然会写 `termux-battery-status > b.txt`（把能力结果存进文件）。
 * 若 `>` 不算操作符，重定向符和文件名会被当成**能力的参数**吞进去：
 * 能力照跑、结果回 stdout，但**文件根本没生成**，而退出码还是 0、`ok` 还是 true。
 * 那就是「谎报成功」——比直接报错更坏。
 *
 * 加上之后，`termux-battery-status > b.txt` 不再是「整条能力命令」，
 * 会走复合命令那条路 → 改写成 `printf '%s' "$AGENT_CAP_0_0" > b.txt`
 * → 由 shell 真的把结果写进文件。
 *
 * 引号里的 `>` 不受影响（引号内的内容原样保留）。
 */
internal object DeviceCommandParser {

    /** 与 Termux:API 对齐的命令前缀。 */
    const val TERMUX_PREFIX = "termux-"

    /** 我们内部拟 Linux 自制命令的前缀。 */
    const val AGENT_PREFIX = "agent-"

    /**
     * 找出命令里所有能力调用。
     *
     * @param line 待解析的整行命令
     * @param known 注册表里已知的 `(前缀, 能力名)` 对
     */
    fun findAll(line: String, known: Set<Pair<String, String>>): List<DeviceCommand> {
        val tokens = tokenize(line)
        val result = mutableListOf<DeviceCommand>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (!token.operator) {
                // 前缀要**最长优先**：否则 `termux-agent-foo` 可能被当成 `agent-foo`。
                // 但也不能只看前缀就认 —— 得 `(前缀, 能力名)` 这一对在注册表里存在才算。
                val candidates = known.asSequence()
                    .filter { (prefix, _) -> token.text.startsWith(prefix) }
                    .sortedByDescending { (prefix, _) -> prefix.length }
                var matched: Pair<String, String>? = null
                for ((prefix, _) in candidates) {
                    val raw = token.text.removePrefix(prefix)
                    val capabilityName = raw.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
                    if (capabilityName.isNotEmpty() && known.contains(prefix to capabilityName)) {
                        matched = prefix to capabilityName
                        break
                    }
                }
                if (matched != null) {
                    val (prefix, capabilityName) = matched
                    var j = i + 1
                    val args = mutableListOf<String>()
                    while (j < tokens.size && !tokens[j].operator) {
                        args += tokens[j].text
                        j++
                    }
                    val end = if (args.isEmpty()) token.end else tokens[j - 1].end
                    result += DeviceCommand(prefix, capabilityName, args, token.start, end)
                    i = j
                    continue
                }
            }
            i++
        }
        return result
    }

    /** 拆词。 */
    private fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '&' && i + 1 < line.length && line[i + 1] == '&' -> {
                    tokens += Token("&&", i, i + 2, operator = true); i += 2
                }
                c == '|' && i + 1 < line.length && line[i + 1] == '|' -> {
                    tokens += Token("||", i, i + 2, operator = true); i += 2
                }
                c == '>' && i + 1 < line.length && line[i + 1] == '>' -> {
                    tokens += Token(">>", i, i + 2, operator = true); i += 2
                }
                c == '|' || c == ';' || c == '&' || c == '\n' || c == '>' -> {
                    tokens += Token(c.toString(), i, i + 1, operator = true); i++
                }
                else -> {
                    val start = i
                    val sb = StringBuilder()
                    // ⚠️ 这个循环**必须至少消费一个字符**。
                    // 它的退出条件里有 `isOperatorStart`，而上面的 `when` 是按同一套字符分派的 ——
                    // 两边一旦对不齐（比如加了新操作符却忘了加分支），这里就会**一个字符都不消费**，
                    // 于是外层 `while` 原地打转、token 列表无限增长 → `OutOfMemoryError`。
                    // 兜底：正常路径不会走到这个 `if`，走到就说明分派和退出条件不一致，
                    // 那就把这个字符当成普通字符吃掉，保证循环一定前进。
                    while (i < line.length && !line[i].isWhitespace() && !isOperatorStart(line[i])) {
                        if (line[i] == '"' || line[i] == '\'') {
                            val quote = line[i]
                            i++
                            while (i < line.length && line[i] != quote) {
                                sb.append(line[i]); i++
                            }
                            if (i < line.length) i++ // 跳过收尾引号
                        } else {
                            sb.append(line[i]); i++
                        }
                    }
                    // 兜底：这个循环一次字符都没消费 → 说明上面 `when` 的分派
                    // 和这里的退出条件对不齐（加了新操作符却忘了加分支就会这样）。
                    // 那就硬吃一个字符，保证外层 `while` 一定前进 ——
                    // 否则 token 列表会无限增长直到 `OutOfMemoryError`。
                    if (i == start && i < line.length) {
                        sb.append(line[i]); i++
                    }
                    tokens += Token(sb.toString(), start, i, operator = false)
                }
            }
        }
        return tokens
    }

    private fun isOperatorStart(c: Char): Boolean =
        c == '|' || c == ';' || c == '&' || c == '\n' || c == '>'

    private data class Token(val text: String, val start: Int, val end: Int, val operator: Boolean)
}
