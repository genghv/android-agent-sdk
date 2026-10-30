package ai.agentkernel.tools.exec

/**
 * 设备命令执行接口 —— 内核与「设备能力」之间的唯一契约。
 *
 * 内核只认这一个方法：给命令，拿结果。至于命令是在本机 shell 跑（AndroidCommandExecutor）、
 * 还是经 Shizuku 提权跑（ShizukuBridge 包装）、还是测试用 mock，内核一概不关心。
 */
interface CommandExecutor {
    suspend fun execute(command: String, timeoutMs: Long = 60_000L): ExecResult
}

/** 命令执行结果。render() 产出回填给模型的文本。 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    /**
     * 回填给模型的文本。
     *
     * ## 为什么要截断（这是命中率的第二条腿）
     *
     * 前缀缓存的命中率是个**比值**：`命中 ≈ 1 − 每轮新增 / 上下文总量`。
     * 「每轮新增」里，模型自己的回答压不动，但**工具输出可以**——
     * 而且工具输出会**永久留在历史里**，它越大，后面每一轮的分母越大。
     *
     * 实测（真机多轮）：带工具的回合每步新增约 400 token，
     * 而普通问答每轮只新增约 30 token —— 工具输出就是那条腿。
     *
     * 截断规则：**保头保尾**。头是命令回显，尾常常是真正的错误信息
     * （很多命令的错误只在最后一行）。中间丢掉的部分用一行明确标记，
     * **不能让模型以为输出是完整的** —— 那是谎报。
     *
     * ★ **总长一定 ≤ maxChars**（标记行算进预算，不额外加）。
     * 标记里的「省略 N 字符」是**真正没露给模型的原文字符数**。
     *
     * 为什么标记必须算进预算：早先的写法是「head/tail 各占 maxChars 的份额、标记另外加」，
     * 于是输出长度 = maxChars + 标记长度 —— **超出了自己声明的上限**。
     * 后果不是「多几十个字符」那么轻：下游后端会再截一次，
     * **我们的标记被切掉、换成后端自己的标记**，
     * 而且后端报的「丢了多少字符」是按**我们截完的文本**算的
     * （真实缺口是几千字符，它会写成几十）——
     * 那个数字是给模型看的，模型据此判断自己缺了多少数据，**会被误导**。
     */
    fun render(maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS): String {
        val full = buildString {
            append("exitCode: ").append(exitCode)
            if (timedOut) append(" (timed out)")
            if (stdout.isNotEmpty()) append("\n--- stdout ---\n").append(stdout.trimEnd())
            if (stderr.isNotEmpty()) append("\n--- stderr ---\n").append(stderr.trimEnd())
        }.trim()
        if (full.length <= maxChars || maxChars <= 0) return full
        // The marker line counts against the budget: head + marker + tail == maxChars.
        // (The old code kept head/tail at maxChars each and added the marker on top,
        //  so the result was maxChars + marker length -- over its own declared cap.
        //  A downstream gateway truncates again, our marker gets cut away, and the
        //  "dropped N chars" number it reports is computed against our already
        //  truncated text -- the model is told it lost dozens of chars when it
        //  actually lost thousands.)
        //
        // "omitted N chars" must be the number of ORIGINAL chars the model never sees:
        //     omitted = full.length - budget
        //     budget  = maxChars - (fixed + digits(omitted) + digits(full.length))
        // => omitted = A + digits(omitted), with A = full.length - maxChars + fixed + digits(total)
        // That is a fixed point: iterate until the digit count stops moving.
        val a = full.length - maxChars + MARKER_FIXED_CHARS + digitsOf(full.length)
        var omitted = a + digitsOf(a)
        var digits = digitsOf(omitted)
        var guard = 0
        while (guard++ < 5) {
            val next = a + digits
            if (digitsOf(next) == digits) { omitted = next; break }
            digits = digitsOf(next)
            omitted = next
        }
        val marker = "\n… [输出已截断：省略 $omitted 字符(共 ${full.length} 字符)] …\n"
        val budget = maxChars - marker.length
        if (budget <= 0) {
            // Cap so small the marker itself does not fit: give the model the raw head
            // instead of an empty string (an empty tool result reads as "nothing happened").
            return full.substring(0, maxChars.coerceAtMost(full.length))
        }
        val keepHead = (budget * 2) / 3
        val keepTail = budget - keepHead
        return full.substring(0, keepHead) + marker + full.substring(full.length - keepTail)
    }

    companion object {
        /**
         * 回填给模型的输出上限（字符）。
         *
         * 1200 是权衡出来的：够装下绝大多数命令的完整输出，
         * 又把「一条命令吐 5000 字符」这种最坏情况的每轮新增压住。
         */
        const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1200

        /**
         * 截断标记里**除数字以外**的固定长度（形如「… [输出已截断：省略 N 字符(共 M 字符)] …」）。
         *
         * 里面两个数字的位置各留了**两个空格**（数字本身插在这两个空格之间），
         * 所以本值已经含那两个空位 ——
         * 实际标记长度 = 本值 + 位数(N) + 位数(M)。
         */
        private const val MARKER_FIXED_CHARS: Int =
            "\n… [输出已截断：省略  字符(共  字符)] …\n".length
    }

    /** 十进制位数（至少 1）。 */
    private fun digitsOf(n: Int): Int {
        var v = if (n < 0) -n else n
        var d = 1
        while (v >= 10) {
            v /= 10
            d++
        }
        return d
    }
}