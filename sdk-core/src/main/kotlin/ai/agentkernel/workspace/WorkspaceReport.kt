package ai.agentkernel.workspace

/** 一类文件的占用。 */
data class CategoryUsage(
    /** 顶层名字（如 `usr` / `home` / `var` / `trash`）。 */
    val name: String,
    val bytes: Long,
    val files: Int,
)

/** 一个待清理条目。 */
data class TrashCandidate(
    val path: String,
    val bytes: Long,
    /** **为什么**认为它是垃圾 —— 每条都要能解释，不能黑箱删。 */
    val reason: String,
)

/** 工作区体检报告。 */
data class WorkspaceReport(
    /** agent 私有根。 */
    val root: String,
    /** 总占用（字节）。 */
    val usageBytes: Long,
    /** 配额（字节）；null = 不限。 */
    val quotaBytes: Long?,
    /** 占用占配额的比例；不限时为 null。 */
    val ratio: Double?,
    /** 是否已经超配额。 */
    val overQuota: Boolean,
    /** 用到多少比例就该提醒了。 */
    val warnRatio: Double,
    /** 分目录占用。 */
    val categories: List<CategoryUsage>,
    /** 回收站占用。 */
    val trashBytes: Long,
    /** 可清理的垃圾合计（字节）。 */
    val trashableBytes: Long,
    /** 可清理的条目数。 */
    val trashableCount: Int,
    /** 配额来自哪儿：`host` = 宿主注入的默认值，`agent` = agent 自己改过。 */
    val policySource: String,
) {
    /** 该不该提醒 agent 了。 */
    val shouldWarn: Boolean
        get() = quotaBytes != null && quotaBytes > 0 &&
            (overQuota || (ratio ?: 0.0) >= warnRatio)
}

/** 清理计划（干跑的结果）。 */
data class CleanupPlan(
    val candidates: List<TrashCandidate>,
    val totalBytes: Long,
) {
    val count: Int get() = candidates.size
}

/** 清理结果。 */
data class CleanupResult(
    val removed: List<TrashCandidate>,
    val freedBytes: Long,
    /** 进回收站还是直接删了。 */
    val toTrash: Boolean,
    /** 失败项（删不掉的文件）。 */
    val failed: List<String> = emptyList(),
) {
    val count: Int get() = removed.size
}

/** 人类可读的字节数。 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
