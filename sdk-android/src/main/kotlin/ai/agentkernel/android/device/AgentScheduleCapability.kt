package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import ai.agentkernel.schedule.CronExpression
import ai.agentkernel.schedule.ScheduleStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `agent-schedule` —— **定时任务**（SDK 自制命令）。
 *
 * 子命令：
 * - `list` —— 看有哪些任务；
 * - `add` —— 加一条，参数是「cron 表达式 + 到点做什么」；
 * - `remove <id>` —— 删一条；
 * - `due` —— 哪些到点了（该跑哪几条）。
 *
 * cron 是 5 段「分 时 日 月 周」，支持 `*`、数字、`a-b` 区间、步长、`,` 列表。
 * 具体写法见 `usage`（那里的示例带引号，参数里有空格必须加引号）。
 *
 * ## 边界：SDK 不负责「到点唤醒」
 *
 * Android 上没有纯 App 内的可靠定时：要么宿主用 `WorkManager`（周期任务），
 * 要么用 `AlarmManager`（精确闹钟，Android 12+ 还要用户授权 `SCHEDULE_EXACT_ALARM`）。
 *
 * 所以分工是：
 * - **本命令**负责定义任务、解析 cron、判定到期；
 * - **宿主**决定用什么调度器，到点调一次 `agent-schedule due` 并执行返回的任务。
 *
 * 这样做的好处是 SDK 不必给每个宿主塞一个调度库依赖，宿主也能换自己的方案。
 */
class AgentScheduleCapability(private val store: ScheduleStore) : DeviceCapability {
    override val name: String = "schedule"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-schedule list|add \"<cron>\" \"<动作>\"|remove <id>|due"

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: "list"
        return when (action) {
            "list" -> {
                val tasks = store.load()
                // 先在外面拼好再塞进去 —— 嵌进 okJson 里会出现两个隐式接收者打架。
                val tasksJson = buildJsonArray {
                    tasks.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", t.id)
                                put("cron", t.cron)
                                put("action", t.action)
                                put("enabled", t.enabled)
                                put("last_run_at", t.lastRunAt)
                            }
                        )
                    }
                }
                okJson {
                    put("count", tasks.size)
                    put("tasks", tasksJson)
                    put("note", "到点唤醒由宿主负责（WorkManager / AlarmManager）；到点时调 agent-schedule due")
                }
            }

            "add" -> {
                val cron = args.getOrNull(1) ?: throw CapabilityFailure("用法：$usage")
                val what = args.getOrNull(2) ?: throw CapabilityFailure("用法：$usage")
                // 解析不了就直接报错并说明表达式怎么写 —— 别存一条永远不触发的任务。
                CronExpression.parse(cron)
                val task = store.add(cron, what)
                okJson {
                    put("action", "add")
                    put("id", task.id)
                    put("cron", task.cron)
                    put("what", task.action)
                    put("note", "已保存。真正到点执行由宿主的调度器触发 agent-schedule due。")
                }
            }

            "remove" -> {
                val id = args.getOrNull(1) ?: throw CapabilityFailure("用法：$usage")
                val removed = store.remove(id)
                okJson {
                    put("action", "remove")
                    put("id", id)
                    put("removed", removed)
                }
            }

            "due" -> {
                val due = store.dueTasks()
                val dueJson = buildJsonArray {
                    due.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", t.id)
                                put("cron", t.cron)
                                put("action", t.action)
                            }
                        )
                    }
                }
                okJson {
                    put("action", "due")
                    put("count", due.size)
                    put("tasks", dueJson)
                }
            }

            else -> throw CapabilityFailure("用法：$usage")
        }
    }
}
