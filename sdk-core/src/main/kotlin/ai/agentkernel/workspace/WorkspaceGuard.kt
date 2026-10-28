package ai.agentkernel.workspace

import ai.agentkernel.prefix.PrefixLayout

/**
 * 工作区守卫 —— **超限时把告警注入给 agent，让它自己清理维护**。
 *
 * 这里是「垃圾自己做处理」闭环的关键一环：
 * - 宿主/agent 定的配额 → [WorkspacePolicy]（不写死）；
 * - 实际占用 → [WorkspaceInspector] 扫出来；
 * - 接近或超过配额 → [alert] 生成一段**可执行**的告警文本；
 * - 宿主把这段文本作为 system 消息插进下一轮 → agent 看到 → 自己去跑
 *   `agent-cleanup` / 调 `agent-quota`。
 *
 * 也就是说：**清理动作是 agent 做的，我们只负责告诉它「该清了」并给出怎么清的命令。**
 */
class WorkspaceGuard(
    val layout: PrefixLayout,
    /** 宿主注入的默认策略（agent 没改过就用它）。 */
    private val hostPolicy: WorkspacePolicy,
    private val policyStore: WorkspacePolicyStore = WorkspacePolicyStore(layout),
    /** 设备当前剩余空间（字节）；只有「按剩余空间百分比」的配额用得上。 */
    private val freeSpaceProvider: () -> Long = { 0L },
) {
    private val inspector = WorkspaceInspector(layout)
    private val cleaner = WorkspaceCleaner(layout)

    /** 生效策略 + 来源（`host` / `agent`）。 */
    fun effectivePolicy(): Pair<WorkspacePolicy, String> {
        val agentPolicy = policyStore.load()
        return if (agentPolicy != null) agentPolicy to "agent" else hostPolicy to "host"
    }

    /** 体检报告。 */
    fun report(): WorkspaceReport {
        val (policy, source) = effectivePolicy()
        return inspector.inspect(policy, freeSpaceProvider(), source)
    }

    /** 可清理的条目（干跑）。 */
    fun plan(): CleanupPlan {
        val (policy, _) = effectivePolicy()
        return inspector.plan(policy)
    }

    /** 执行清理（垃圾进回收站）。 */
    fun clean(): CleanupResult {
        val (policy, _) = effectivePolicy()
        return cleaner.apply(policy)
    }

    /** 清掉回收站里超龄的条目。 */
    fun purgeTrash(): CleanupResult {
        val (policy, _) = effectivePolicy()
        return cleaner.purgeTrash(policy)
    }

    /** 改配额（agent 自己动手）。存下来，下次启动仍生效。 */
    fun savePolicy(policy: WorkspacePolicy) = policyStore.save(policy)

    /** 改回宿主注入的默认值（删掉 agent 的覆盖）。 */
    fun resetPolicy() = policyStore.clear()

    /** 宿主注入的默认策略（只读）。 */
    fun hostPolicy(): WorkspacePolicy = hostPolicy

    /**
     * 给 agent 的告警文本；**没超限就返回 null**（不打扰它）。
     *
     * 文本要写成「可直接照做」的形式 —— 给出确切命令，而不是只说「空间不足」。
     *
     * ## ⚠️ 这段文本必须**逐字稳定**
     *
     * 上游是**前缀缓存**：只有逐字相同的前缀才命中。所以告警里**不能出现每轮都变的数字**
     * （占用字节数、配额百分比、可清条目数……）。以前这里是 `占用 12.3MB（37%）` 这种写法，
     * 数字每轮变 → 前缀每轮断 → **缓存命中率从第 3 轮起归零**。
     *
     * 现在这段是**常量**：只说「该清」和「怎么清」，具体数字让 agent 自己
     * `agent-workspace` / `agent-quota show` 去查 —— 顺带还更符合 agent 的分工
     * （数据是它自己拉的，不是别人塞给它的）。
     *
     * 另外它必须**短**：网关会把客户端 system 段截到 2000 字符，
     * 系统提示词本身已占大半，超了这段告警会被**悄悄砍掉**（模型根本看不到）。
     */
    fun alert(): String? {
        val report = report()
        if (!report.shouldWarn) return null
        // 常量文本：条件成立期间，一个字都不许变（含数字）。
        return "[工作区告警] 占用接近或已超配额，自己清理：" +
            "agent-cleanup --dry-run → agent-cleanup --apply → agent-cleanup --purge-trash；" +
            "没得清就调配额（agent-quota set）。"
    }
}
