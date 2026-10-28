package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import ai.agentkernel.memory.AgentMemoryCapabilities
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.serialization.json.put

/**
 * 设备能力的 Android 实现 —— **只做 app 自身权限就够的那批**。
 *
 * 边界（用户定的）：**高级权限不给手机碰**。
 * - 这里这批（手电筒 / 震动 / 通知 / 剪贴板 / 电池）都是普通权限，不需要 su / Shizuku；
 * - 装应用 / 点屏 / 改系统设置那类**不实现**，`ShizukuBridge` 保持 `Noop`；
 * - 相机 / 定位 / 短信那类要运行时授权或太敏感，**这一批先不做**。
 *
 * ## 命令名逐字对齐 Termux:API
 *
 * 能力名 = Termux:API 的命令名去掉 `termux-` 前缀，**一字不差**：
 * `torch` / `vibrate` / `notification` / `clipboard-get` / `clipboard-set` / `battery-status`。
 *
 * 为什么要对齐：模型是按 Termux:API 的习惯写命令的。若把剪贴板合并成一个
 * `clipboard` 能力，模型写 `termux-clipboard-set` 时会落空 —— 它按 shell 去找这个
 * 二进制，找不到，得到「command not found」，而不是精心准备的 JSON。
 * 名字对齐 = 模型不用学新方言。
 *
 * ## 输出格式的**唯一**分歧
 *
 * Termux:API 的 `termux-clipboard-get` 直接打印纯文本，不是 JSON。
 * 这里**统一输出 JSON**（`{"ok":true,"text":"..."}`），因为：
 * - 工具契约是「命令 → JSON → 回填给模型」，纯文本会被模型当成普通输出混过去；
 * - JSON 能区分「剪贴板是空的」和「取剪贴板失败」这两种完全不同的情况。
 * 名字对齐、格式不刻意模仿 —— 名字是**接口**，格式是**SDK 的契约**。
 *
 * 失败一律 [CapabilityFailure] 抛异常，由注册表统一转 `{"ok":false,...}` + 非零退出码。
 */
class TorchCapability(private val context: Context) : DeviceCapability {

    override val name: String = "torch"
    override val usage: String = "termux-torch on|off"

    override fun run(args: List<String>): String {
        val turnOn = when (args.firstOrNull()) {
            "on" -> true
            "off" -> false
            else -> throw CapabilityFailure("用法：$usage")
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw CapabilityFailure("拿不到 CameraManager")
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw CapabilityFailure("这台设备没有带闪光的摄像头")
        return try {
            manager.setTorchMode(cameraId, turnOn)
            okJson { put("torch", if (turnOn) "on" else "off") }
        } catch (e: SecurityException) {
            throw CapabilityFailure("打开手电筒被系统拒绝（缺 CAMERA 权限）：${e.message}")
        }
    }
}

/** 震动：`termux-vibrate [-d 毫秒]`。需要 VIBRATE 权限。 */
class VibrateCapability(private val context: Context) : DeviceCapability {

    override val name: String = "vibrate"
    override val usage: String = "termux-vibrate [-d milliseconds]"

    override fun run(args: List<String>): String {
        var durationMs = 1000L
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-d" -> {
                    durationMs = args.getOrNull(i + 1)?.toLongOrNull()
                        ?: throw CapabilityFailure("用法：$usage")
                    i += 2
                }
                else -> throw CapabilityFailure("用法：$usage")
            }
        }
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: throw CapabilityFailure("拿不到 Vibrator")
        if (!vibrator.hasVibrator()) throw CapabilityFailure("这台设备没有振动马达")
        // Vibrator.vibrate(VibrationEffect) 是 **API 26** 才有的，
        // 本模块 minSdk 24 —— 不分流的话，Android 7.0/7.1 上直接 NoSuchMethodError。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        return okJson { put("duration_ms", durationMs) }
    }
}

/** 通知：`termux-notification -t 标题 -c 内容`。API 33+ 需要 POST_NOTIFICATIONS。 */
class NotificationCapability(private val context: Context) : DeviceCapability {

    override val name: String = "notification"
    override val usage: String = "termux-notification -t title -c content"

    override fun run(args: List<String>): String {
        var title = ""
        var content = ""
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-t" -> {
                    title = args.getOrNull(i + 1) ?: throw usageError()
                    i += 2
                }
                "-c" -> {
                    content = args.getOrNull(i + 1) ?: throw usageError()
                    i += 2
                }
                else -> throw usageError()
            }
        }
        if (title.isEmpty() && content.isEmpty()) throw usageError()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: throw CapabilityFailure("拿不到 NotificationManager")

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent", NotificationManager.IMPORTANCE_DEFAULT)
            )
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        val id = System.currentTimeMillis().toInt()
        manager.notify(id, builder.build())
        return okJson { put("id", id) }
    }

    private companion object {
        const val CHANNEL_ID = "agent-device-capability"
    }

    /**
     * 用法错误。
     *
     * 特意写清「含空格要加引号」—— 模型最容易在这里踩：
     * `termux-notification -t 标题 -c 你好 世界` 里，「你好 世界」会被拆成两个词，
     * 多出来的「世界」就成了非法参数。只说「用法」的话，模型往往改不对。
     */
    private fun usageError(): CapabilityFailure = CapabilityFailure(
        "用法：$usage（标题/内容里含空格时要用引号包起来，例如 " +
            """termux-notification -t "我的标题" -c "正文内容"）"""
    )
}

/**
 * 读剪贴板：`termux-clipboard-get`。
 *
 * 与 Termux:API 的**唯一**分歧：那边打印纯文本，这里打印 JSON（见文件头说明）。
 *
 * ## Android 10+ 的焦点限制（真机实测）
 *
 * 系统规定**只有当前有焦点的 App（以及默认输入法）能读剪贴板**。
 * 真机实测（Android 16 / 小米）：
 * ```
 * E/ClipboardService: Denying clipboard access to <pkg>, application is not in focus
 * ```
 * 此时 `getPrimaryClip()` 返回 **null** —— 与「剪贴板确实是空的」**无法区分**。
 *
 * 所以这里**不把 null 当成空剪贴板**（那样等于骗模型「剪贴板是空的」），
 * 而是明确报错并把可能的原因告诉它。
 * 写入不受此限（实测后台进程写进去，系统剪贴板确实收到了）。
 */
class ClipboardGetCapability(private val context: Context) : DeviceCapability {

    override val name: String = "clipboard-get"
    override val usage: String = "termux-clipboard-get"

    override fun run(args: List<String>): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw CapabilityFailure("拿不到 ClipboardManager")
        val clip = manager.primaryClip
            ?: throw CapabilityFailure(
                "读不到剪贴板：可能是剪贴板为空，也可能是 App 当前没有前台焦点" +
                    "（Android 10+ 的系统限制；logcat 里会有 " +
                    "「ClipboardService: Denying clipboard access」）。"
            )
        if (clip.itemCount == 0) return okJson { put("text", "") }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return okJson { put("text", text) }
    }
}

/**
 * 写剪贴板：`termux-clipboard-set <文本>`。
 *
 * 文本按 Termux:API 的口径取**余下全部参数**（用空格拼回），
 * 所以 `termux-clipboard-set hello world` 写进去的是 `hello world`。
 */
class ClipboardSetCapability(private val context: Context) : DeviceCapability {

    override val name: String = "clipboard-set"
    override val usage: String = "termux-clipboard-set <text>"

    override fun run(args: List<String>): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw CapabilityFailure("拿不到 ClipboardManager")
        val text = args.joinToString(" ")
        if (text.isEmpty()) {
            throw CapabilityFailure(
                "用法：$usage（内容含空格时要用引号包起来，例如 " +
                    """termux-clipboard-set "hello world"）"""
            )
        }
        manager.setPrimaryClip(ClipData.newPlainText("agent", text))
        return okJson { put("set", true); put("length", text.length) }
    }
}

/** 电池：`termux-battery-status`。无权限。 */
class BatteryCapability(private val context: Context) : DeviceCapability {

    override val name: String = "battery-status"
    override val usage: String = "termux-battery-status"

    override fun run(args: List<String>): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw CapabilityFailure("拿不到电池状态")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return okJson {
            put("level", level)
            put("scale", scale)
            put("percentage", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            put("status", status)
            put(
                "charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
            put("temperature_c", temperature / 10.0)
        }
    }
}

/**
 * 组装「app 自身权限就够」的那批基础能力。
 *
 * 想加能力就往这个列表里加；要实现一个能力，实现 [DeviceCapability] 即可。
 *
 * 名字必须与 Termux:API 的命令名一致（去掉 `termux-` 前缀），否则模型按习惯写的
 * 命令会落空 —— 详见文件头说明。
 *
 * @param workspace 工作区能力（`agent-*`）。不传就不注册 —— 宿主不需要工作区维护时别加。
 * @param http HTTP 调用能力（`agent-http-*`）。不传就不注册。
 * @param memory 长期记忆能力（`agent-memory`）。不传就不注册。
 *   ⚠️ **记忆开着才注册**：宿主没启用记忆（没传 `memoryPolicy`）的话，
 *   agent 写进去的记忆没人读（不会进提示词），纯属白写。
 */
fun defaultDeviceCapabilities(
    context: Context,
    workspace: AgentWorkspaceCapabilities? = null,
    http: AgentHttpCapabilities? = null,
    memory: AgentMemoryCapabilities? = null,
): List<DeviceCapability> = buildList {
    // —— 与 Termux:API 逐字对齐的那批 ——
    add(TorchCapability(context))
    add(VibrateCapability(context))
    add(NotificationCapability(context))
    add(ClipboardGetCapability(context))
    add(ClipboardSetCapability(context))
    add(BatteryCapability(context))
    // —— SDK 自制的那批（`agent-` 前缀）——
    workspace?.let { addAll(it.capabilities) }
    http?.let { addAll(it.capabilities) }
    memory?.let { addAll(it.capabilities) }
}
