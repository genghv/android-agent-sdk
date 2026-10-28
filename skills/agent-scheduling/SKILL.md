---
name: agent-scheduling
description: 在安卓设备上安排定时任务的约定——用 agent-schedule 定义 cron 任务、宿主的调度器负责到点唤醒、任务存哪、失败怎么排查。当需要「每天几点自动做某事」「每隔几分钟查一次」这类周期性任务时使用。
---

# 定时任务（agent-schedule）

## 一、分工：SDK 定任务，宿主负责唤醒

Android 上**没有纯 App 内的可靠定时**。要定时唤醒，只能靠系统调度器：

| 方案 | 特点 |
|---|---|
| `WorkManager` | 周期任务，**不需要精确到分钟**，系统会找时机跑；不需要额外权限 |
| `AlarmManager` | 精确闹钟；Android 12+ 要用户授权 `SCHEDULE_EXACT_ALARM`； doze 模式下要额外处理 |

所以这套机制的分工是：

- **本命令（agent-schedule）**：定义任务、解析 cron、判定「哪些到点了」；
- **宿主 App**：决定用哪个调度器，到点调一次 `agent-schedule due`，然后执行返回的任务。

这样 SDK 不必给每个宿主塞一个调度库依赖，宿主也能换成自己的方案。

## 二、命令

```
agent-schedule list                              看有哪些任务
agent-schedule add "<cron>" "<到点做什么>"        加一条
agent-schedule remove <id>                       删一条
agent-schedule due                               哪些到点了
```

### cron 表达式

5 段：**分 时 日 月 周**

```
*/5 * * * *     每 5 分钟
0 */2 * * *     每 2 小时（整点）
30 8 * * *      每天 08:30
0 9-17 * * *    09:00 到 17:00 每小时
0 0 1 * 5       每月 1 号 或 每个周五
```

支持：`*`、数字、`a-b` 区间、步长（星号加斜杠加 n）、`,` 列表。
不支持月份名/星期名，也不支持 `L`/`W`/`#`。

**日和周同时指定时是「或」**（跟标准 cron 一致），不是「与」。

表达式写错会**当场报错**并说清错在哪（哪一段、超了什么范围）——
所以别怕试，写错立刻就知道，不会存下一条永远不触发的任务。

### 任务存哪

`<AGENT_ROOT>/var/schedule.json`。它属于你的工作区，
所以：**清理工作区时它是「有用」的，不会被当垃圾删掉**。

## 三、宿主怎么接（伪代码）

```kotlin
// 宿主侧：注册一个周期任务（WorkManager）
class AgentPeriodicWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val caps = defaultDeviceCapabilities(applicationContext, workspace = AgentWorkspaceCapabilities(applicationContext))
        val reg = DeviceCapabilityRegistry(caps)
        val cmd = reg.parseWhole("agent-schedule due")!!
        val due = reg.execute(cmd)
        // 解析 due 里的 tasks，逐条执行（每条执行完记得 markRun）
        return Result.success()
    }
}
```

要点：
1. 到点后先 `agent-schedule due` 拿清单，**别自己猜哪些到点了**；
2. 执行完要标记已跑（同一分钟内不重复触发）——
   `ScheduleStore.markRun(id)`；
3. `due` 返回空数组很正常（大多数时候没到点）。

## 四、几条规矩

1. **定时任务不是后台常驻** —— 到点唤醒一次、跑完就结束。别设计成「一直盯着」。
2. **别用定时任务做高频轮询** —— 每分钟一次已经是 WorkManager 的合理上限，
   再密系统也不会更准。
3. **任务里要做的事尽量短** —— 长了会被系统杀掉；长任务要拆。
4. **失败要能重入** —— 到点执行失败时，下次到点会再执行一遍；
   所以任务本身要幂等（写文件用「先写临时文件再改名」这种写法）。
5. **别把定时任务当闹钟用** —— 精确到分钟的场景，用户自己设系统闹钟更靠谱。
