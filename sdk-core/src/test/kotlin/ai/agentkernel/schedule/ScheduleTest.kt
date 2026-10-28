package ai.agentkernel.schedule

import ai.agentkernel.prefix.PrefixLayout
import java.io.File
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 定时任务（cron 解析 + 任务存储 + 到期判定）的测试 —— 纯 JVM。
 */
class ScheduleTest {

    private fun tempLayout(): PrefixLayout {
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-sched-${System.nanoTime()}")
        return PrefixLayout(base.absolutePath)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }

    // ---- cron 解析 ----

    @Test
    fun `全星号每分钟都命中`() {
        val e = CronExpression.parse("* * * * *")
        assertTrue(e.matches(at(2026, 10, 27, 13, 45)))
    }

    @Test
    fun `指定分钟和小时`() {
        val e = CronExpression.parse("30 8 * * *")
        assertTrue(e.matches(at(2026, 10, 27, 8, 30)))
        assertFalse(e.matches(at(2026, 10, 27, 8, 31)))
        assertFalse(e.matches(at(2026, 10, 27, 9, 30)))
    }

    @Test
    fun `步长`() {
        val e = CronExpression.parse("*/15 * * * *")
        assertTrue(e.matches(at(2026, 10, 27, 13, 0)))
        assertTrue(e.matches(at(2026, 10, 27, 13, 45)))
        assertFalse(e.matches(at(2026, 10, 27, 13, 20)))
    }

    @Test
    fun `区间和列表`() {
        val e = CronExpression.parse("0 9-17 * * *")
        assertTrue(e.matches(at(2026, 10, 27, 9, 0)))
        assertTrue(e.matches(at(2026, 10, 27, 17, 0)))
        assertFalse(e.matches(at(2026, 10, 27, 18, 0)))

        val l = CronExpression.parse("0 0,12 * * *")
        assertTrue(l.matches(at(2026, 10, 27, 12, 0)))
        assertFalse(l.matches(at(2026, 10, 27, 13, 0)))
    }

    @Test
    fun `日和周同时指定时是或不是与`() {
        // 2026-10-27 是周二（DAY_OF_WEEK=3 → cron 里 2）
        val e = CronExpression.parse("0 0 27 * 5")   // 27 号 或 周五
        assertTrue(e.matches(at(2026, 10, 27, 0, 0)), "27 号要命中")
        assertFalse(e.matches(at(2026, 10, 29, 0, 0)), "29 号不是 27 号也不是周五")
    }

    @Test
    fun `写错的表达式要能看懂错在哪`() {
        val e = assertFailsWith<IllegalArgumentException> { CronExpression.parse("* * * *") }
        assertTrue(e.message!!.contains("5 段"), "要说清要几段：${e.message}")

        val e2 = assertFailsWith<IllegalArgumentException> { CronExpression.parse("99 * * * *") }
        assertTrue(e2.message!!.contains("超范围"), "要说清超范围：${e2.message}")

        val e3 = assertFailsWith<IllegalArgumentException> { CronExpression.parse("a * * * *") }
        assertTrue(e3.message!!.contains("不是数字"), "要说清不是数字：${e3.message}")
    }

    @Test
    fun `解析不了返回 null 而不是抛`() {
        assertEquals(null, CronExpression.parseOrNull("乱写"))
        assertTrue(CronExpression.parseOrNull("* * * * *") != null)
    }

    // ---- 任务存储 ----

    @Test
    fun `任务能加能列能删`() {
        val store = ScheduleStore(tempLayout())
        assertEquals(0, store.load().size)

        val t = store.add("*/5 * * * *", "agent-workspace")
        assertEquals(1, store.load().size)
        assertEquals("agent-workspace", store.load()[0].action)

        assertTrue(store.remove(t.id))
        assertEquals(0, store.load().size)
        assertFalse(store.remove("不存在"))
    }

    @Test
    fun `到期判定能认出该跑的任务`() {
        val store = ScheduleStore(tempLayout())
        val t = store.add("30 8 * * *", "干活")

        val due = store.dueTasks(now = at(2026, 10, 27, 8, 30).timeInMillis)
        assertEquals(1, due.size)
        assertEquals(t.id, due[0].id)

        assertEquals(0, store.dueTasks(now = at(2026, 10, 27, 9, 0).timeInMillis).size)
    }

    @Test
    fun `同一分钟不会重复跑`() {
        val store = ScheduleStore(tempLayout())
        store.add("* * * * *", "干活")

        val now = at(2026, 10, 27, 13, 45).timeInMillis
        assertEquals(1, store.dueTasks(now = now).size)

        // 标记已执行后，60 秒内不再重复
        store.markRun(store.load()[0].id, now)
        assertEquals(0, store.dueTasks(now = now + 10_000).size)
    }

    @Test
    fun `存储文件坏了也不能让 agent 挂掉`() {
        val layout = tempLayout()
        File(layout.varDir, ScheduleStore.FILE).apply {
            parentFile?.mkdirs()
            writeText("{ 不是 json")
        }
        assertEquals(0, ScheduleStore(layout).load().size)
    }

    @Test
    fun `cron 写错的任务不会被触发`() {
        val store = ScheduleStore(tempLayout())
        // 直接写一条坏 cron（绕开 add 的校验，模拟历史数据/手改文件）
        store.save(listOf(ScheduledTask(id = "x", cron = "乱写", action = "干活")))
        assertEquals(0, store.dueTasks(now = at(2026, 10, 27, 13, 45).timeInMillis).size)
    }
}
