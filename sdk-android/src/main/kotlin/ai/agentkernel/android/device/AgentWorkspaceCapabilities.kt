package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.schedule.ScheduleStore
import ai.agentkernel.workspace.Quota
import ai.agentkernel.workspace.WorkspaceGuard
import ai.agentkernel.workspace.WorkspacePolicy
import ai.agentkernel.workspace.formatBytes
import android.content.Context
import android.os.StatFs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工作区相关的能力 —— **SDK 自制的命令**（前缀 `agent-`）。
 *
 * 这一组是「垃圾自己做处理、无用的删除、有用的留住」那条需求的落点：
 * agent 收到告警后，靠这三个命令自己完成清理与配额调整。
 *
 * | 命令 | 干什么 |
 * |------|--------|
 * | `agent-workspace` | 看工作区占用、配额、各目录分布 |
 * | `agent-cleanup --dry-run` | 列出可清理的垃圾（**默认**，不删） |
 * | `agent-cleanup --apply` | 真的清理（先进回收站） |
 * | `agent-cleanup --purge-trash` | 清掉回收站里超龄的条目 |
 * | `agent-quota show` | 看当前生效配额 |
 * | `agent-quota set 200MB` | 改配额（**不写死**，agent 自己能调） |
 * | `agent-quota reset` | 改回宿主注入的默认值 |
 *
 * 注意这些命令**不在 Termux:API 里** —— 它们是这套环境特有的，
 * 所以提示词里要分开介绍（见 [ai.agentkernel.prompt.AgentSystemPrompt]）。
 */
class AgentWorkspaceCapabilities(
    context: Context,
    /** 宿主注入的默认策略（agent 没改过就用它）。 */
    hostPolicy: WorkspacePolicy = WorkspacePolicy(),
) {
    val layout: PrefixLayout = PrefixLayout(context.filesDir.path)

    val guard: WorkspaceGuard = WorkspaceGuard(
        layout = layout,
        hostPolicy = hostPolicy,
        freeSpaceProvider = {
            try {
                StatFs(context.filesDir.path).availableBytes
            } catch (_: Exception) {
                0L
            }
        },
    )

    val capabilities: List<DeviceCapability> = listOf(
        WorkspaceStatusCapability(guard),
        CleanupCapability(guard),
        QuotaCapability(guard),
        AgentScheduleCapability(ScheduleStore(layout)),
    )
}

/** `agent-workspace` —— 工作区体检。 */
private class WorkspaceStatusCapability(private val guard: WorkspaceGuard) : DeviceCapability {
    override val name: String = "workspace"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-workspace"

    override fun run(args: List<String>): String {
        if (args.isNotEmpty()) throw CapabilityFailure("用法：$usage（不带参数）")
        val report = guard.report()
        return okJson {
            put("root", report.root)
            put("usage_bytes", report.usageBytes)
            put("usage", formatBytes(report.usageBytes))
            put("quota_bytes", report.quotaBytes ?: -1L)
            put("quota", report.quotaBytes?.let { formatBytes(it) } ?: "不限")
            put("ratio", report.ratio ?: 0.0)
            put("over_quota", report.overQuota)
            put("should_warn", report.shouldWarn)
            put("trashable_bytes", report.trashableBytes)
            put("trashable_count", report.trashableCount)
            put("policy_source", report.policySource)
            put("categories", buildJsonArray {
                report.categories.forEach { c ->
                    add(
                        buildJsonObject {
                            put("name", c.name)
                            put("bytes", c.bytes)
                            put("files", c.files)
                        }
                    )
                }
            })
        }
    }
}

/** `agent-cleanup` —— 清理垃圾。 */
private class CleanupCapability(private val guard: WorkspaceGuard) : DeviceCapability {
    override val name: String = "cleanup"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-cleanup [--dry-run|--apply|--purge-trash]"

    override fun run(args: List<String>): String {
        val mode = args.firstOrNull() ?: "--dry-run"
        if (args.size > 1) throw CapabilityFailure("用法：$usage")
        return when (mode) {
            "--dry-run", "dry-run" -> {
                val plan = guard.plan()
                okJson {
                    put("mode", "dry-run")
                    put("count", plan.count)
                    put("total_bytes", plan.totalBytes)
                    put("total", formatBytes(plan.totalBytes))
                    put("candidates", buildJsonArray {
                        plan.candidates.take(50).forEach { c ->
                            add(
                                buildJsonObject {
                                    put("path", c.path)
                                    put("bytes", c.bytes)
                                    put("reason", c.reason)
                                }
                            )
                        }
                    })
                    put("hint", "确认无误后执行 agent-cleanup --apply")
                }
            }
            "--apply", "apply" -> {
                val result = guard.clean()
                okJson {
                    put("mode", "apply")
                    put("count", result.count)
                    put("freed_bytes", result.freedBytes)
                    put("freed", formatBytes(result.freedBytes))
                    put("to_trash", result.toTrash)
                    put("trash_dir", guard.layout.trash)
                    put("failed", buildJsonArray { result.failed.forEach { add(JsonPrimitive(it)) } })
                }
            }
            "--purge-trash", "purge-trash" -> {
                val result = guard.purgeTrash()
                okJson {
                    put("mode", "purge-trash")
                    put("count", result.count)
                    put("freed_bytes", result.freedBytes)
                    put("freed", formatBytes(result.freedBytes))
                    put("failed", buildJsonArray { result.failed.forEach { add(JsonPrimitive(it)) } })
                }
            }
            else -> throw CapabilityFailure("用法：$usage")
        }
    }
}

/** `agent-quota` —— 查 / 改配额。 */
private class QuotaCapability(private val guard: WorkspaceGuard) : DeviceCapability {
    override val name: String = "quota"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-quota show|set <大小，如 200MB|1GB|不限>|reset"

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: "show"
        return when (action) {
            "show" -> {
                val (policy, source) = guard.effectivePolicy()
                val report = guard.report()
                okJson {
                    put("action", "show")
                    put("quota", policy.quota.describe())
                    put("quota_bytes", report.quotaBytes ?: -1L)
                    put("policy_source", source)
                    put("warn_ratio", policy.warnRatio)
                    put("usage_bytes", report.usageBytes)
                    put("usage", formatBytes(report.usageBytes))
                    put("over_quota", report.overQuota)
                    put("trashable_bytes", report.trashableBytes)
                }
            }
            "set" -> {
                val raw = args.getOrNull(1) ?: throw CapabilityFailure("用法：$usage")
                val current = guard.effectivePolicy().first
                val updated = current.copy(quota = parseQuota(raw))
                guard.savePolicy(updated)
                okJson {
                    put("action", "set")
                    put("quota", updated.quota.describe())
                    put("policy_source", "agent")
                    put("note", "已保存，下次启动仍生效")
                }
            }
            "reset" -> {
                guard.resetPolicy()
                val (policy, source) = guard.effectivePolicy()
                okJson {
                    put("action", "reset")
                    put("quota", policy.quota.describe())
                    put("policy_source", source)
                }
            }
            else -> throw CapabilityFailure("用法：$usage")
        }
    }

    /** `200MB` / `1GB` / `500KB` / `1048576` / `不限`。 */
    private fun parseQuota(raw: String): Quota {
        val text = raw.trim()
        if (text == "不限" || text.equals("unlimited", ignoreCase = true)) return Quota.Unlimited
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMgG]?)([bB]?)$").find(text)
            ?: throw CapabilityFailure("看不懂的大小：「$raw」。写成 200MB / 1GB / 1048576 或「不限」")
        val value = m.groupValues[1].toDouble()
        val unit = m.groupValues[2].lowercase()
        val bytes = when (unit) {
            "k" -> value * 1024
            "m" -> value * 1024 * 1024
            "g" -> value * 1024 * 1024 * 1024
            else -> value
        }.toLong()
        if (bytes <= 0) throw CapabilityFailure("大小要大于 0")
        return Quota.Bytes(bytes)
    }
}

/** 配额的人话描述。 */
internal fun Quota.describe(): String = when (this) {
    is Quota.Unlimited -> "不限"
    is Quota.Bytes -> formatBytes(bytes)
    is Quota.PercentOfFreeSpace -> "设备剩余空间的 $percent%"
}
