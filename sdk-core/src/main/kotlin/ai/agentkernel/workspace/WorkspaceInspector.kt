package ai.agentkernel.workspace

import ai.agentkernel.prefix.PrefixLayout
import java.io.File

/** 文件的归类。 */
enum class FileClass {
    /** 有用 —— 保留。 */
    KEEP,

    /** 垃圾 —— 可以清。 */
    TRASH,
}

/**
 * 工作区体检：扫一遍 agent 私有根，算出占用、把「有用的」和「垃圾」分开。
 *
 * ## 分类原则：保守
 *
 * 只有**明确命中** [WorkspacePolicy.trashPatterns] 的才算垃圾，其余一律保留。
 * 理由：agent 自己产生什么它自己最清楚，分类器只该处理「明显是垃圾」的那部分；
 * 误删一个 agent 正在用的脚本，代价远大于多留一个文件。
 *
 * ## 三块受保护区域
 * - `usr/`（拟 Linux 根）—— 运行时本身，**不清**；
 * - `var/`（状态）—— 配额策略、清理记录，**不清**；
 * - 任何含 `.agent-keep` 标记文件的目录 —— 整棵子树保留。
 */
class WorkspaceInspector(private val layout: PrefixLayout) {

    /** 扫一遍，出报告。 */
    fun inspect(
        policy: WorkspacePolicy,
        freeSpaceBytes: Long = 0L,
        policySource: String = "host",
    ): WorkspaceReport {
        val usage = scan(policy)
        val quota = policy.quota.resolve(freeSpaceBytes)

        val categoryMap = LinkedHashMap<String, Pair<Long, Int>>()
        var trashBytes = 0L
        var trashableBytes = 0L
        var trashableCount = 0

        for (entry in usage.files) {
            val top = entry.relativePath.substringBefore('/')
            val acc = categoryMap.getOrPut(top) { 0L to 0 }
            categoryMap[top] = (acc.first + entry.bytes) to (acc.second + 1)

            if (entry.clazz == FileClass.TRASH) {
                trashableBytes += entry.bytes
                trashableCount++
            }
            if (entry.inTrash) trashBytes += entry.bytes
        }

        val total = usage.totalBytes
        val ratio = quota?.let { if (it > 0) total.toDouble() / it else null }

        return WorkspaceReport(
            root = layout.agentRoot,
            usageBytes = total,
            quotaBytes = quota,
            ratio = ratio,
            overQuota = quota != null && total > quota,
            warnRatio = policy.warnRatio,
            categories = categoryMap.map { (name, v) -> CategoryUsage(name, v.first, v.second) },
            trashBytes = trashBytes,
            trashableBytes = trashableBytes,
            trashableCount = trashableCount,
            policySource = policySource,
        )
    }

    /** 列出可清理的条目（干跑）。 */
    fun plan(policy: WorkspacePolicy): CleanupPlan {
        val candidates = scan(policy).files
            .filter { it.clazz == FileClass.TRASH }
            .map { TrashCandidate(it.absolutePath, it.bytes, it.reason) }
            .sortedByDescending { it.bytes }
        return CleanupPlan(candidates, candidates.sumOf { it.bytes })
    }

    // ---- 内部 ----

    private data class ScanEntry(
        val relativePath: String,
        val absolutePath: String,
        val bytes: Long,
        val clazz: FileClass,
        val reason: String,
        val inTrash: Boolean,
    )

    private data class ScanResult(val totalBytes: Long, val files: List<ScanEntry>)

    private fun scan(policy: WorkspacePolicy): ScanResult {
        val out = ArrayList<ScanEntry>()
        var total = 0L
        val root = File(layout.agentRoot)
        if (!root.isDirectory) return ScanResult(0, out)

        val trashRoot = File(layout.trash).absolutePath
        val protectedRoots = setOf(File(layout.prefix).absolutePath, File(layout.varDir).absolutePath)

        fun walk(dir: File, relative: String, keepInherited: Boolean) {
            val children = dir.listFiles() ?: return
            // 目录里放了标记文件 → 整棵子树保留
            val marked = keepInherited || children.any { it.isFile && it.name == policy.keepMarkerName }
            for (child in children) {
                val rel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                val abs = child.absolutePath
                if (child.isDirectory) {
                    walk(child, rel, marked)
                    continue
                }
                if (!child.isFile) continue // 符号链接等，一律不碰

                val bytes = child.length()
                total += bytes
                val inTrash = abs.startsWith(trashRoot + File.separator)
                val protectedRoot = protectedRoots.any { abs.startsWith(it + File.separator) }

                val (clazz, reason) = when {
                    inTrash -> FileClass.TRASH to "在回收站里"
                    protectedRoot -> FileClass.KEEP to "受保护的运行时/状态目录"
                    marked -> FileClass.KEEP to "所在目录有 ${policy.keepMarkerName} 标记"
                    policy.keepPatterns.matchesAny(child.name) -> FileClass.KEEP to "命中保留规则"
                    policy.trashPatterns.matchesAny(child.name) -> FileClass.TRASH to "命中垃圾规则（${child.name}）"
                    else -> FileClass.KEEP to "默认保留"
                }
                out += ScanEntry(rel, abs, bytes, clazz, reason, inTrash)
            }
        }
        walk(root, "", false)
        return ScanResult(total, out)
    }
}
