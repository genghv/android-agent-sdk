package ai.agentkernel.workspace

import ai.agentkernel.prefix.PrefixLayout
import java.io.File

/**
 * 清理器：把「垃圾」挪走。
 *
 * **默认进回收站**（`<agentRoot>/trash/`），不是直接抹掉 ——
 * agent 自己删错了还能找回来。回收站里超龄的条目再由 [purgeTrash] 彻底清掉。
 */
class WorkspaceCleaner(private val layout: PrefixLayout) {

    private val inspector = WorkspaceInspector(layout)

    /** 执行清理。 */
    fun apply(policy: WorkspacePolicy): CleanupResult {
        val plan = inspector.plan(policy)
        val removed = ArrayList<TrashCandidate>()
        val failed = ArrayList<String>()

        val trashDir = File(layout.trash)
        if (policy.useTrash) trashDir.mkdirs()
        val stamp = System.currentTimeMillis()

        for (candidate in plan.candidates) {
            val src = File(candidate.path)
            if (!src.exists()) continue
            val ok = if (policy.useTrash) {
                val dst = File(trashDir, "$stamp-${src.name}")
                move(src, dst)
            } else {
                src.delete()
            }
            if (ok) removed += candidate else failed += candidate.path
        }
        return CleanupResult(
            removed = removed,
            freedBytes = removed.sumOf { it.bytes },
            toTrash = policy.useTrash,
            failed = failed,
        )
    }

    /**
     * 清空回收站里超龄的条目。
     *
     * 回收站本身也是占空间的地方 —— 不清的话，agent「清理」只是把垃圾搬了个家。
     */
    fun purgeTrash(policy: WorkspacePolicy): CleanupResult {
        val trashDir = File(layout.trash)
        if (!trashDir.isDirectory) return CleanupResult(emptyList(), 0, false)
        val cutoff = System.currentTimeMillis() - policy.trashMaxAgeDays * 24L * 3600L * 1000L
        val removed = ArrayList<TrashCandidate>()
        val failed = ArrayList<String>()

        trashDir.listFiles()?.forEach { f ->
            if (f.lastModified() >= cutoff) return@forEach
            val bytes = if (f.isDirectory) dirSize(f) else f.length()
            val ok = f.deleteRecursively()
            if (ok) removed += TrashCandidate(f.absolutePath, bytes, "回收站里超过 ${policy.trashMaxAgeDays} 天")
            else failed += f.absolutePath
        }
        return CleanupResult(removed, removed.sumOf { it.bytes }, false, failed)
    }

    private fun move(src: File, dst: File): Boolean {
        // 同一文件系统优先用 rename（原子、便宜）；跨设备再退回复制+删除。
        if (src.renameTo(dst)) return true
        return try {
            src.copyTo(dst, overwrite = true)
            src.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
