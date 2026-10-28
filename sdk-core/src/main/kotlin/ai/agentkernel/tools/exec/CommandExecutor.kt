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
     */
    fun render(maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS): String {
        val full = buildString {
            append("exitCode: ").append(exitCode)
            if (timedOut) append(" (timed out)")
            if (stdout.isNotEmpty()) append("\n--- stdout ---\n").append(stdout.trimEnd())
            if (stderr.isNotEmpty()) append("\n--- stderr ---\n").append(stderr.trimEnd())
        }.trim()
        if (full.length <= maxChars || maxChars <= 0) return full
        val keepHead = (maxChars * 2) / 3
        val keepTail = maxChars - keepHead
        val omitted = full.length - keepHead - keepTail
        val marker = "\n… [输出已截断：省略 $omitted 字符(共 ${full.length} 字符)] …\n"
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
    }
}