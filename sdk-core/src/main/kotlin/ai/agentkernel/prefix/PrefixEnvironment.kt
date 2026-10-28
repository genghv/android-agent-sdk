package ai.agentkernel.prefix

/**
 * 移植自 `TermuxShellEnvironment.getEnvironment`（termux-shared）。
 *
 * 只保留对「跑命令」有用的最小集：
 *   HOME / PREFIX / TMPDIR / PATH，以及老版本（Android 5/6）才需要设的 LD_LIBRARY_PATH。
 *
 * 关键结论（照抄 Termux）：**Android 7+ 二进制靠 DT_RUNPATH 定位 so，不应设 LD_LIBRARY_PATH**，
 * 所以 [androidLegacy] 默认 false 时故意不带 LD_LIBRARY_PATH。
 */
object PrefixEnvironment {

    fun build(layout: PrefixLayout, androidLegacy: Boolean = false): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["HOME"] = layout.home
        env["PREFIX"] = layout.prefix
        env["TMPDIR"] = layout.tmp

        // agent 私有根与回收站 —— 让 agent 脚本知道自己能往哪儿写、删掉的东西去哪儿了。
        env["AGENT_ROOT"] = layout.agentRoot
        env["AGENT_TRASH"] = layout.trash
        env["AGENT_VAR"] = layout.varDir

        // PATH 的分隔符必须跟平台走：
        // Android 是 `:`（Unix），Windows 是 `;`。
        // 写死 `:` 的话，在 Windows 上整条 PATH 会变成一个不存在的目录名 ——
        // shell 内建命令（echo）还活着，但任何外部程序（cat / ls）都会 command not found。
        // 在 Android 上 `File.pathSeparator` 就是 `:`，行为不变。
        val sep = java.io.File.pathSeparator

        if (androidLegacy) {
            // Android 5/6：busybox applets 目录 + 显式 LD_LIBRARY_PATH
            env["PATH"] = "${layout.bin}$sep${layout.bin}/applets"
            env["LD_LIBRARY_PATH"] = layout.lib
        } else {
            env["PATH"] = layout.bin
        }
        return env
    }
}