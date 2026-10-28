package ai.agentkernel.workspace

/**
 * 工作区配额 —— **不写死**。
 *
 * 「限制不写死」有三层意思，这里都覆盖了：
 * 1. **由宿主注入**：不是常量，是数据（[WorkspacePolicy.quota]）；
 * 2. **agent 自己能改**：改完落在 `<agentRoot>/var/workspace-policy.json`，
 *    下次启动生效（见 [WorkspacePolicyStore]）；
 * 3. **能按设备实况算**：[Quota.PercentOfFreeSpace] 在运行时按当时的剩余空间折算，
 *    所以同一条策略在不同设备、不同时间得到的是不同阈值。
 */
sealed interface Quota {

    /** 不限。 */
    data object Unlimited : Quota

    /** 绝对字节数。 */
    data class Bytes(val bytes: Long) : Quota

    /**
     * 按「设备剩余空间」的百分比。
     *
     * 用它就**完全不写死阈值**：阈值跟着设备的真实剩余空间走。
     * [percent] 是百分比（5.0 = 5%）。
     */
    data class PercentOfFreeSpace(val percent: Double) : Quota

    /**
     * 算出实际字节上限。
     *
     * @param freeSpaceBytes 设备当前剩余空间（字节）；只有 [PercentOfFreeSpace] 用得上
     * @return null = 不限
     */
    fun resolve(freeSpaceBytes: Long): Long? = when (this) {
        is Unlimited -> null
        is Bytes -> bytes
        is PercentOfFreeSpace ->
            if (freeSpaceBytes <= 0) null
            else ((freeSpaceBytes * percent) / 100.0).toLong().coerceAtLeast(0)
    }
}

/**
 * 工作区策略 —— 什么算「有用要留」、什么算「垃圾可删」、超了怎么办。
 *
 * **默认是保守的**：只有**明确命中** [trashPatterns] 的才算垃圾，其余一律保留。
 * 宁可漏删，不可误删 —— agent 自己产生的东西它自己最清楚，
 * 我们的分类器只负责处理「明显是垃圾」的那部分。
 */
data class WorkspacePolicy(
    /** 配额。默认：设备剩余空间的 5%（**跟着实况走，不写死**）。 */
    val quota: Quota = Quota.PercentOfFreeSpace(5.0),

    /** 用到配额的多少比例就提醒（默认 85%）：留出余量让 agent 自己收，而不是等爆了才说。 */
    val warnRatio: Double = 0.85,

    /** 命中就**保留**的文件名模式（源码、数据、配置）。 */
    val keepPatterns: List<String> = DEFAULT_KEEP_PATTERNS,

    /** 命中就**算垃圾**的文件名模式（临时件、日志、缓存）。 */
    val trashPatterns: List<String> = DEFAULT_TRASH_PATTERNS,

    /** 命中就整个目录保留的标记文件名。 */
    val keepMarkerName: String = ".agent-keep",

    /** 回收站里超过这么多天的条目可以被彻底清掉。 */
    val trashMaxAgeDays: Int = 3,

    /** 删东西时先进回收站（true）还是直接抹掉（false）。 */
    val useTrash: Boolean = true,
) {
    companion object {
        /** 默认保留：源码 / 数据 / 配置 —— 这些是「有用的」。 */
        val DEFAULT_KEEP_PATTERNS = listOf(
            ".agent-keep",
            "*.py", "*.pyi", "*.sh", "*.bash", "*.js", "*.ts", "*.json",
            "*.yaml", "*.yml", "*.toml", "*.ini", "*.cfg", "*.conf",
            "*.csv", "*.tsv", "*.md", "*.txt", "*.sql", "*.xml", "*.html", "*.css",
        )

        /** 默认垃圾：临时件 / 日志 / 缓存 —— 这些是「无用的」。 */
        val DEFAULT_TRASH_PATTERNS = listOf(
            "*.tmp", "*.temp", "*.part", "*.partial", "*.swp", "*.swo", "*.bak", "*.old",
            "*.log", "*.pid", "*.pyc", "*.pyo", "*.class", "*.o",
            "nohup.out", "core", "core.*", ".DS_Store", "__pycache__",
        )
    }
}

/**
 * 极简通配符匹配：`*` 匹配任意长，`?` 匹配一个字符。
 *
 * 不引 glob 库是因为规则就这么两条，自己写更可控（也更好测）。
 */
fun globMatch(pattern: String, name: String): Boolean {
    var p = 0
    var n = 0
    var starP = -1
    var starN = 0
    while (n < name.length) {
        when {
            p < pattern.length && (pattern[p] == '?' || pattern[p] == name[n]) -> {
                p++; n++
            }
            p < pattern.length && pattern[p] == '*' -> {
                starP = p; starN = n; p++
            }
            starP >= 0 -> {
                p = starP + 1; starN++; n = starN
            }
            else -> return false
        }
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}

/** 命中任意一个模式就算命中（忽略大小写）。 */
fun List<String>.matchesAny(name: String): Boolean =
    any { globMatch(it.lowercase(), name.lowercase()) }
