package ai.agentkernel.prompt

import java.io.File

/**
 * 运行时探测「这台设备上真的有哪些 shell 命令」。
 *
 * ## 为什么不能写死
 *
 * 提示词里列的命令如果这台设备上**没有**，模型就会一次次去试 —— 每次失败，
 * 还会在工作区里留下垃圾（这是本项目最讨厌的失败模式）。
 * 而「有哪些命令」是**机型相关**的：同一份 SDK 跑在 Android 8 和 Android 16 上，
 * toybox 的子命令集合不一样（`awk`/`xxd`/`stat`/`timeout` 都是后来才进 toybox 的）。
 *
 * 所以做法是：**白名单 ∩ 设备实存**。
 *
 * - **白名单**（[WHITELIST]）—— 只收「安全 + 对写脚本有用」的命令。
 *   刻意**不收** `pm` / `am` / `dumpsys` / `su` 这类：它们在 `/system/bin` 里确实存在，
 *   但要么要特权、要么会引出「装应用 / 点屏」这种**故意不做**的事。
 *   模型看见 `pm install` 就会去试，然后失败 —— 所以别列。
 * - **设备实存** —— 真的去 stat（`/system/bin`、`/system/xbin`），
 *   只有**这台机器上确实存在**的命令才会进提示词。
 *
 * 两个条件取交集，结果就是「**列出来的必然能用，没列出来的一律别猜**」。
 *
 * ## ⚠️ 探测必须逐个 stat，不能列目录
 *
 * Android 上 `/system/bin` 是 `drwxr-x--x`：普通 App **没有读权限、只有执行/搜索权限**。
 * 所以 `File.list()` 返回 null（列目录要 `r`），而 `File(dir, name).exists()` 是 true。
 * 详见 [available] 的注释。
 *
 * ## 为什么不探 `PREFIX/bin`
 *
 * 拟 Linux 根（bootstrap 里的 bash/coreutils 等）装在**宿主 App 的私有目录**，
 * 而 Android 对私有目录是 `noexec` 的 —— 那个目录里的 ELF **执行不了**（实测 exit=126）。
 * 把它们列进提示词就是骗模型。
 * 真要让这套运行时可用，只能把宿主 `targetSdk` 降到 28，或把二进制放进 `nativeLibraryDir`。
 */
object ShellCommandInventory {

    /** 一组命令（按用途分组，方便在提示词里分行列出）。 */
    data class Group(val title: String, val commands: List<String>)

    /** 探测结果。 */
    data class Result(val groups: List<Group>) {
        /** 命令总数。 */
        val commandCount: Int get() = groups.sumOf { it.commands.size }

        /** 全部命令名（扁平）。 */
        fun allCommands(): List<String> = groups.flatMap { it.commands }

        /** 某个命令在不在清单里。 */
        fun contains(command: String): Boolean = command in allCommands()
    }

    /**
     * 白名单 —— 只列安全且对写脚本有用的命令。
     *
     * 顺序即提示词里的展示顺序（按用途分组）。
     */
    val WHITELIST: List<Group> = listOf(
        Group(
            "文件与目录",
            listOf(
                "ls", "pwd", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "touch", "chmod",
                "find", "xargs", "du", "df", "stat", "readlink", "realpath",
                "basename", "dirname", "mktemp",
            ),
        ),
        Group(
            "文本处理",
            listOf(
                "cat", "echo", "printf", "head", "tail", "wc", "cut", "tr", "sort", "uniq",
                "grep", "egrep", "fgrep", "sed", "awk", "diff", "patch",
                "nl", "paste", "split", "comm", "tee", "cmp", "file",
            ),
        ),
        Group(
            "压缩与编码",
            listOf(
                "tar", "gzip", "gunzip", "unzip", "zip", "cpio",
                "base64", "xxd", "od", "dd",
                "cksum", "sha1sum", "md5sum", "sha256sum", "sha512sum",
            ),
        ),
        Group(
            "网络",
            listOf("curl", "nc", "ping", "wget"),
        ),
        Group(
            "系统信息与流程控制",
            listOf(
                "sh", "env", "printenv", "id", "whoami", "uname", "hostname", "getprop",
                "date", "sleep", "seq", "expr", "true", "false", "test",
                "timeout", "which", "clear", "more", "dos2unix", "unix2dos",
            ),
        ),
    )

    /**
     * 核心命令集 —— **每台 Android 都有的**那批高频命令。
     *
     * 用途：提示词瘦身。完整清单可能有 80 多条（600+ 字符），
     * 而很多网关对**客户端 system 段有字数上限**（实测有网关硬截断到 2000 字符），
     * 清单一长，提示词尾部就会被砍掉 —— 而被砍掉的往往正是「能力清单」和「工作区规则」。
     *
     * 所以默认只把这批**高频**的写进提示词，完整清单放技能文档里按需查。
     * 注意：它同样是**白名单 ∩ 实存** —— 设备上没有的照样不列。
     */
    val CORE_COMMANDS: List<String> = listOf(
        // 文件与目录
        "ls", "pwd", "cp", "mv", "rm", "mkdir", "chmod", "find", "du", "df", "touch",
        // 文本处理
        "cat", "echo", "grep", "sed", "awk", "cut", "sort", "head", "tail", "wc", "tee",
        // 压缩与编码
        "tar", "gzip", "base64",
        // 网络
        "curl",
        // 流程控制
        "sh", "timeout",
    )

    /**
     * 只留核心命令（提示词瘦身用）。
     *
     * 分组结构保留（读起来清楚），只是每组里只留 [CORE_COMMANDS] 里的。
     */
    fun core(result: Result): Result = Result(
        result.groups
            .map { group -> group.copy(commands = group.commands.filter { it in CORE_COMMANDS }) }
            .filter { it.commands.isNotEmpty() }
    )

    /**
     * 探测 [roots] 下真实存在的白名单命令。
     *
     * ## ⚠️ 必须靠**逐个 stat**，不能靠「列目录」
     *
     * Android 上 `/system/bin` 的权限是 `drwxr-x--x` ——
     * 对普通 App（既不是 root 也不在 shell 组）**只有 `x`（执行/搜索）权限，没有 `r`（读）权限**。
     * 所以：
     *
     * - `File("/system/bin").list()` → **返回 null**（列目录要 `r`）——
     *   真机实测过，这条路在 Android 上**根本走不通**；
     * - `File("/system/bin", "ls").exists()` → **true**
     *   （只要目录的 search 权限 + 文件的 stat 权限，两样普通 App 都有）。
     *
     * 也就是说：**目录列不出来，但里面的文件可以逐个 stat。**
     * 所以探测是「白名单里每个命令去 `exists()` 一下」，而不是「列目录再过滤」。
     *
     * （`/system/xbin` 是 `drwxr-x---`，App 连 search 都没有 —— 逐个 stat 自然查不到，
     * 不用为此特判。）
     *
     * @param roots 要探的目录（例如 `/system/bin`、`/system/xbin`）
     */
    fun available(roots: List<File>): Result {
        val groups = WHITELIST
            .map { group ->
                Group(
                    group.title,
                    group.commands.filter { command ->
                        roots.any { root ->
                            try {
                                File(root, command).exists()
                            } catch (t: Throwable) {
                                false
                            }
                        }
                    },
                )
            }
            .filter { it.commands.isNotEmpty() }
        return Result(groups)
    }

    /** 渲染成提示词里的一段文字（每组一行）。 */
    fun render(result: Result): String =
        result.groups.joinToString("\n") { group ->
            "${group.title}：${group.commands.joinToString(" ")}"
        }
}
