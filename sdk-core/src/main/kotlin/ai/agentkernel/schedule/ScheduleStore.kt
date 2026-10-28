package ai.agentkernel.schedule

import ai.agentkernel.prefix.PrefixLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Calendar

/** 一条定时任务。 */
data class ScheduledTask(
    val id: String,
    /** cron 表达式（5 段）。 */
    val cron: String,
    /** 到点要执行什么（命令或提示词）。 */
    val action: String,
    /** 最近一次是否已执行过（避免同一分钟重复跑）。 */
    val lastRunAt: Long = 0L,
    /** 是否启用。 */
    val enabled: Boolean = true,
)

/**
 * 定时任务的存储 —— 落在 agent 私有根的 `var/schedule.json`。
 *
 * ## 边界要说清楚
 *
 * **本 SDK 不负责「到点唤醒」**。Android 上没有纯 App 内的可靠定时：
 * 要么宿主用 [WorkManager][androidx.work.WorkManager]（周期任务），
 * 要么用 `AlarmManager`（精确闹钟，Android 12+ 要用户授权 `SCHEDULE_EXACT_ALARM`）。
 *
 * 所以这里的分工是：
 * - **SDK**：定义任务、解析 cron、判定「哪些到点了」（[dueTasks]）；
 * - **宿主**：决定用什么调度器，到点调一次 [dueTasks] 并执行。
 *
 * 这样 SDK 不用给每个宿主塞一个 WorkManager 依赖，宿主也能换自己的调度方案。
 */
class ScheduleStore(private val layout: PrefixLayout) {

    /** 读全部任务；文件不存在或坏了就当空列表（不能因为一个坏文件就让 agent 挂掉）。 */
    fun load(): List<ScheduledTask> {
        val file = file()
        if (!file.isFile) return emptyList()
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val array = root["tasks"] as? JsonArray ?: return emptyList()
            array.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val cron = o["cron"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ScheduledTask(
                    id = id,
                    cron = cron,
                    action = o["action"]?.jsonPrimitive?.content ?: "",
                    lastRunAt = o["lastRunAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    enabled = o["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 写回全部任务。 */
    fun save(tasks: List<ScheduledTask>) {
        val file = file()
        file.parentFile?.mkdirs()
        file.writeText(
            buildJsonObject {
                put("tasks", buildJsonArray {
                    tasks.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", t.id)
                                put("cron", t.cron)
                                put("action", t.action)
                                put("lastRunAt", t.lastRunAt)
                                put("enabled", t.enabled)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    /** 加一条；返回带 id 的新任务。 */
    fun add(cron: String, action: String): ScheduledTask {
        val tasks = load().toMutableList()
        val id = "task-${System.currentTimeMillis()}"
        val task = ScheduledTask(id = id, cron = cron, action = action)
        tasks += task
        save(tasks)
        return task
    }

    /** 按 id 删一条；返回是否真的删掉了。 */
    fun remove(id: String): Boolean {
        val tasks = load()
        val kept = tasks.filterNot { it.id == id }
        if (kept.size == tasks.size) return false
        save(kept)
        return true
    }

    /** 标记某条已执行（记录时间，避免同一分钟重复跑）。 */
    fun markRun(id: String, at: Long = System.currentTimeMillis()) {
        val tasks = load().map { if (it.id == id) it.copy(lastRunAt = at) else it }
        save(tasks)
    }

    /**
     * 哪些任务到点了。
     *
     * @param now 当前时刻（默认系统时间）
     * @param windowMinutes 同一分钟内的去重窗口；默认 60 秒 —— 防止宿主每 10 秒调一次时重复跑。
     */
    fun dueTasks(now: Long = System.currentTimeMillis(), windowMinutes: Long = 60_000L): List<ScheduledTask> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return load().filter { task ->
            if (!task.enabled) return@filter false
            val expr = CronExpression.parseOrNull(task.cron) ?: return@filter false
            if (!expr.matches(cal)) return@filter false
            now - task.lastRunAt >= windowMinutes
        }
    }

    private fun file(): File = File(layout.varDir, FILE)

    companion object {
        const val FILE = "schedule.json"
    }
}
