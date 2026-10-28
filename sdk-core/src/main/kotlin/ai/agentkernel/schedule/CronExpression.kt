package ai.agentkernel.schedule

import java.util.Calendar

/**
 * 极简 cron 表达式（5 段：分 时 日 月 周）。
 *
 * 支持：`*`、数字、`a-b` 区间、步长（每 n 个一次，写作 `*` 加斜杠加 n）、`,` 列表。
 * 不支持：月份名/星期名（`jan`/`mon`）、`L`/`W`/`#`。
 *
 * ## 为什么自己写而不用现成的
 *
 * Android 上没有内置 cron 解析；引第三方库要给每个宿主 App 增一个依赖。
 * 而调度这块**宿主多半要自己接 WorkManager/AlarmManager**，那部分本来就要写，
 * 表达式解析顺手就写了，依赖为零。
 */
class CronExpression(val fields: List<Set<Int>>) {

    init {
        require(fields.size == 5) { "cron 要 5 段（分 时 日 月 周），收到 ${fields.size} 段" }
    }

    /**
     * 这个表达式在给定时刻是否命中。
     *
     * @param calendar 时刻（用系统本地时区）
     */
    fun matches(calendar: Calendar): Boolean {
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // Calendar: 周日=1

        // cron 的语义：**日和周都指定时是「或」**，不是「与」。
        // 只指定一个就按那个算；两个都没指定就都算命中。
        val dayHit = when {
            fields[2].isEmpty() && fields[4].isEmpty() -> true
            fields[2].isEmpty() -> fields[4].contains(dayOfWeek)
            fields[4].isEmpty() -> fields[2].contains(dayOfMonth)
            else -> fields[2].contains(dayOfMonth) || fields[4].contains(dayOfWeek)
        }

        return fields[0].contains(minute) &&
            fields[1].contains(hour) &&
            dayHit &&
            fields[3].contains(month)
    }

    companion object {
        /** 解析一行 cron；解析不了抛 [IllegalArgumentException]（带上原因）。 */
        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(parts.size == 5) {
                "cron 要 5 段「分 时 日 月 周」，收到 ${parts.size} 段：「$expression」"
            }
            val ranges = listOf(0..59, 0..23, 1..31, 1..12, 0..6)
            val fields = parts.mapIndexed { i, part -> parseField(part, ranges[i], i) }
            return CronExpression(fields)
        }

        /** 解析不了就返回 null（不抛）—— 适合「读一行配置」的场景。 */
        fun parseOrNull(expression: String): CronExpression? =
            try { parse(expression) } catch (_: IllegalArgumentException) { null }

        private fun parseField(part: String, range: IntRange, index: Int): Set<Int> {
            val out = sortedSetOf<Int>()
            for (piece in part.split(',')) {
                val p = piece.trim()
                if (p.isEmpty()) throw IllegalArgumentException("第 ${index + 1} 段有空项：「$part」")
                when {
                    p == "*" -> out += range
                    p.startsWith("*/") -> {
                        val step = p.removePrefix("*/").toIntOrNull()
                            ?: throw IllegalArgumentException("步长不是数字：「$p」")
                        require(step > 0) { "步长要大于 0：「$p」" }
                        var v = range.first
                        while (v <= range.last) {
                            out += v
                            v += step
                        }
                    }
                    else -> {
                        val dash = p.indexOf('-')
                        if (dash > 0) {
                            val from = p.substring(0, dash).toIntOrNull()
                                ?: throw IllegalArgumentException("区间起点不是数字：「$p」")
                            val to = p.substring(dash + 1).toIntOrNull()
                                ?: throw IllegalArgumentException("区间终点不是数字：「$p」")
                            require(from in range && to in range) { "区间超范围：「$p」（允许 ${range.first}..${range.last}）" }
                            require(from <= to) { "区间起点大于终点：「$p」" }
                            out += from..to
                        } else {
                            val v = p.toIntOrNull()
                                ?: throw IllegalArgumentException("不是数字：「$p」")
                            require(v in range) { "超范围：「$p」（允许 ${range.first}..${range.last}）" }
                            out += v
                        }
                    }
                }
            }
            return out
        }
    }
}
