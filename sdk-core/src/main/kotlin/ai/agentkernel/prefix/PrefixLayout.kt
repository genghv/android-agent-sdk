package ai.agentkernel.prefix

/**
 * 拟 Linux 的目录布局 —— **agent 的整个世界收在一棵子树里**。
 *
 * ## 为什么要隔离
 *
 * Termux 的布局是 `files/home` + `files/usr`（两个平级目录，都在 App 的 filesDir 底下）。
 * 照抄的话，**agent 的 HOME 和宿主 App 自己的文件是平级的** ——
 * agent 随手一个 `rm -rf` 就可能误伤宿主自己的数据。
 *
 * 所以这里把整套东西往里收一层：
 * ```
 * <filesDir>/agent/          ← agent 私有根（隔离区）
 *   ├── usr/                ← PREFIX：拟 Linux 根（bin / lib / share）
 *   │   └── tmp/            ← TMPDIR
 *   ├── home/               ← HOME：agent 的工作目录（shell 的 cwd）
 *   ├── var/                ← agent 的状态（配额策略、清理记录）
 *   ├── trash/              ← 回收站：agent 删掉的东西先进这里，不是真删
 *   └── usr-staging/        ← bootstrap 解压中转，装好后原子改名
 * ```
 * 宿主 App 自己的 `files/` 内容跟这棵树**完全分开**。
 *
 * ## 这是「工作区隔离」，不是「权限隔离」
 *
 * agent 和宿主 App 是**同一个 UID**，所以从内核角度它仍然能读到宿主的其它文件。
 * 这里的隔离是**约定式**的：HOME / cwd / TMPDIR / PATH 全在 agent 根里，
 * agent 的默认行为不会越界。真正需要强隔离时（比如跑不受信任的代码），
 * 得靠独立进程 / 独立 uid，那是另一件事。
 *
 * @param filesDir 宿主 App 的 filesDir（**只能由宿主的 Context 给出，不能写死包名**）
 * @param agentRootName agent 私有根的目录名；传空串则退回 Termux 那种平级布局
 */
class PrefixLayout(
    filesDir: String,
    agentRootName: String = DEFAULT_AGENT_ROOT,
) {
    /** agent 私有根。 */
    val agentRoot: String = if (agentRootName.isBlank()) filesDir else "$filesDir/$agentRootName"

    /** 拟 Linux 根（对应 Termux 的 `$PREFIX`）。 */
    val prefix: String = "$agentRoot/usr"

    /** agent 的 HOME —— shell 的工作目录。 */
    val home: String = "$agentRoot/home"

    /** 临时目录（对应 Termux 的 `$TMPDIR`）。 */
    val tmp: String = "$prefix/tmp"

    /** 可执行文件目录（PATH 的第一段）。 */
    val bin: String = "$prefix/bin"

    /** 共享库目录。 */
    val lib: String = "$prefix/lib"

    /** agent 的状态目录：配额策略、清理记录都放这儿。 */
    val varDir: String = "$agentRoot/var"

    /** 回收站：agent 删掉的东西先进这里（可恢复），不是直接抹掉。 */
    val trash: String = "$agentRoot/trash"

    /** bootstrap 解压中转；装好后原子 rename 成 [prefix]。 */
    val staging: String = "$agentRoot/usr-staging"

    /** 环境变量落盘位置。 */
    val envFile: String = "$agentRoot/termux.env"

    companion object {
        /** 默认的 agent 私有根目录名。 */
        const val DEFAULT_AGENT_ROOT = "agent"
    }
}
