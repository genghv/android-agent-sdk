package com.agentkernel.demo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 能力清单 —— **演示台的所有内容都从这里生成**，不写死两份。
 *
 * 命令名与 Termux:API **逐字对齐**（去掉 `termux-` 前缀），
 * 所以模型按 Termux 的习惯写出来的命令，这里照样认得。
 */
data class CapabilitySpec(
    /** 能力名（= 注册表里的 key）。 */
    val key: String,
    /** 给人看的名字。 */
    val title: String,
    /** 一句话说明这个能力干什么。 */
    val subtitle: String,
    /** 实际下发的完整命令。 */
    val command: String,
    val icon: ImageVector,
    /** 需要运行时权限的给出来（普通权限给 null）。 */
    val runtimePermission: String? = null,
    /** 需要前台焦点的能力（Android 10+ 读剪贴板的限制）。 */
    val needsForeground: Boolean = false,
)

object CapabilityCatalog {

    val all: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            key = "torch",
            title = "手电筒",
            subtitle = "打开/关闭闪光灯",
            command = "termux-torch on",
            icon = Icons.Filled.FlashOn,
            runtimePermission = android.Manifest.permission.CAMERA,
        ),
        CapabilitySpec(
            key = "vibrate",
            title = "震动",
            subtitle = "让手机震一下",
            command = "termux-vibrate -d 300",
            icon = Icons.Filled.Vibration,
        ),
        CapabilitySpec(
            key = "notification",
            title = "通知",
            subtitle = "发一条系统通知",
            // 内容含空格 → **必须加引号**。这条命令就是活的「用法示例」：
            // 不加引号的话，「的测试通知」会被拆成两个词，能力会如实报错。
            command = """termux-notification -t "能力台" -c "这是一条来自 android-agent-sdk 的测试通知"""",
            icon = Icons.Filled.Notifications,
            runtimePermission = android.Manifest.permission.POST_NOTIFICATIONS,
        ),
        CapabilitySpec(
            key = "clipboard-set",
            title = "写剪贴板",
            subtitle = "往剪贴板写一段文字",
            command = "termux-clipboard-set hello-from-agent-sdk",
            icon = Icons.Filled.ContentPaste,
        ),
        CapabilitySpec(
            key = "clipboard-get",
            title = "读剪贴板",
            subtitle = "读回剪贴板内容",
            command = "termux-clipboard-get",
            icon = Icons.Filled.ContentCopy,
            needsForeground = true,
        ),
        CapabilitySpec(
            key = "battery-status",
            title = "电池状态",
            subtitle = "电量 / 是否充电 / 温度",
            command = "termux-battery-status",
            icon = Icons.Filled.BatteryFull,
        ),
    )

    /**
     * 我们**内部拟 Linux 自制**的命令（`agent-` 前缀）。
     *
     * 这批不在 Termux:API 里 —— 是这套环境特有的（工作区维护、内部接口调用）。
     * Demo 用的就是 SDK 注册表里的同一份，不是另写一套假的。
     */
    val internalCommands: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            "workspace",
            "工作区体检",
            "看占用、配额、各目录分布",
            "agent-workspace",
            Icons.Filled.BatteryFull,
        ),
        CapabilitySpec(
            "cleanup-dry",
            "干跑清理",
            "列出可清的垃圾（不删）",
            "agent-cleanup --dry-run",
            Icons.Filled.ContentCopy,
        ),
        CapabilitySpec(
            "cleanup-apply",
            "执行清理",
            "垃圾进回收站，不是真删",
            "agent-cleanup --apply",
            Icons.Filled.ContentPaste,
        ),
        CapabilitySpec(
            "quota-show",
            "看配额",
            "当前生效的配额是多少",
            "agent-quota show",
            Icons.Filled.BatteryFull,
        ),
        CapabilitySpec(
            "http-get",
            "HTTP 取数据",
            "调内部接口取数据（token 由宿主注入，命令里不出现）",
            "agent-http-get http://www.baidu.com",
            Icons.Filled.ContentCopy,
        ),
        CapabilitySpec(
            "schedule-list",
            "定时任务列表",
            "看有哪些定时任务",
            "agent-schedule list",
            Icons.Filled.BatteryFull,
        ),
        CapabilitySpec(
            "schedule-add",
            "加一条定时任务",
            "cron 表达式 + 到点做什么",
            "agent-schedule add \"*/5 * * * *\" \"agent-workspace\"",
            Icons.Filled.BatteryFull,
        ),
    )

    /**
     * 环境探针 —— 回答「这台设备上，App 私有目录到底能不能执行二进制」。
     *
     * 这条决定了「能不能预装 Python / Termux 运行时」：
     * Android 10 起，targetSdk ≥ 29 的 App 在私有目录执行 ELF 会被系统拒绝（noexec）；
     * 能不能绕过去，取决于宿主 App 的 targetSdk 与设备实现。
     *
     * ⚠️ **必须由 App 进程自己执行**才算数：
     * 用 `adb shell run-as` 测是**不准的** —— run-as 跑在 `runas_app` SELinux 域，
     * 比 App 真实的 `untrusted_app` 域宽松，实测会得到「能执行」的假阳性。
     */
    val envProbes: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            "exec-probe",
            "私有目录能跑二进制吗",
            "复制 toybox 到私有目录再执行（决定能不能预装 Python）",
            // 成功 → 退出码 0；被系统拒 → 126（sh 报 Permission denied）；
            // 复制失败 → 非 0。输出会带上系统的原话。
            //
            // ⚠️ 探针**用完即删**（rm -f ./tb ./t）—— 它自己往工作区丢了 600KB，
            // 不清理的话每次自检都留垃圾，跟本项目「垃圾自己处理」的主题相悖。
            // 用 `rc=$?` 保住真实退出码（否则最后一条 rm 的退出码会把结论盖掉）。
            "cp /system/bin/toybox ./tb && ln -sf tb ./t && ./t; rc=\$?; rm -f ./tb ./t; exit \$rc",
            Icons.Filled.BatteryFull,
        ),
    )

    /**
     * 普通 shell 命令 —— 证明「不只是那几个能力命令，shell 本身也是通的」。
     *
     * 这些命令走的是 Android 自带的 toybox（`/system/bin`），
     * 不依赖 bootstrap 里的二进制，所以**在没装 bootstrap 的情况下也能跑**。
     */
    val shellSamples: List<CapabilitySpec> = listOf(
        CapabilitySpec("id", "看身份", "当前进程是谁", "id", Icons.Filled.BatteryFull),
        CapabilitySpec("pwd", "看目录", "当前工作目录（= HOME）", "pwd", Icons.Filled.BatteryFull),
        CapabilitySpec(
            "ls",
            "列文件",
            "列出 App 私有目录",
            "ls -la .",
            Icons.Filled.BatteryFull,
        ),
        CapabilitySpec(
            "version",
            "看内核",
            "内核版本",
            // 注意：Android 10+ 限制 App 读 /proc（`cat /proc/version` 会 Permission denied），
            // 所以用 toybox 的 uname。
            "uname -a",
            Icons.Filled.BatteryFull,
        ),
        CapabilitySpec(
            "prop",
            "看机型",
            "Android 版本",
            "getprop ro.build.version.release",
            Icons.Filled.BatteryFull,
        ),
    )

    /**
     * 文件基础操作：写 / 读 / 删 / 跑脚本。
     *
     * 这些**不需要新能力** —— 走普通 shell 就行（toybox 自带 `echo`/`cat`/`rm`/`ls`/`sh`）。
     * 关键前提：执行器的工作目录是 **HOME**（`files/home`），所以相对路径落在 App 私有目录里。
     *
     * ⚠️ **能跑脚本 ≠ 能跑二进制**：
     * - 跑 **shell 脚本**（`sh run.sh`）→ 可以。显式给解释器就绕开了 shebang 写死路径的问题。
     * - 跑 **ELF 二进制** → **不行**：`targetSdk ≥ 29` 时私有目录里的可执行文件直接 EACCES
     *   （唯一豁免是 `nativeLibraryDir`，那要按 ABI 拆包）。
     */
    val fileOps: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            "write",
            "写文件",
            "写一行内容到 HOME/demo.txt",
            """echo "hello from android-agent-sdk" > demo.txt""",
            Icons.Filled.ContentPaste,
        ),
        CapabilitySpec(
            "read",
            "读文件",
            "把刚写的文件读回来",
            "cat demo.txt",
            Icons.Filled.ContentCopy,
        ),
        CapabilitySpec(
            "write-multi",
            "写多行",
            "用 heredoc 写一个多行文件",
            // 多行命令：模型是可以输出带换行的命令的，sh -c 会原样执行。
            "cat > notes.txt << 'EOF'\n第一行 hello\n第二行 world\nEOF",
            Icons.Filled.ContentPaste,
        ),
        CapabilitySpec(
            "read-multi",
            "读多行",
            "读回那个多行文件",
            "cat notes.txt",
            Icons.Filled.ContentCopy,
        ),
        CapabilitySpec(
            "script",
            "跑脚本",
            "写一个 .sh 再用 sh 执行它",
            "echo 'echo script-ran-ok' > run.sh && sh run.sh",
            Icons.Filled.BatteryFull,
        ),
        // 能力命令的**输出重定向** —— 单独一组，因为它踩过一个很隐蔽的坑：
        // 分词器早先不认 `>`，于是 `> battery.txt` 被当成能力的参数吞掉了，
        // 能力照跑、回 ok:true，但**文件根本没生成**（静默失败 = 谎报成功）。
        CapabilitySpec(
            "cap-redirect",
            "能力输出存文件",
            "能力的输出重定向进文件",
            "termux-battery-status > battery.txt",
            Icons.Filled.ContentPaste,
        ),
        CapabilitySpec(
            "cap-redirect-read",
            "读回那个文件",
            "确认重定向真的写进去了",
            "cat battery.txt",
            Icons.Filled.ContentCopy,
        ),
        CapabilitySpec(
            "delete",
            "删文件",
            "把上面造的临时文件都删掉",
            "rm -f demo.txt notes.txt run.sh battery.txt && ls -la .",
            Icons.Filled.BatteryFull,
        ),
    )
}

/**
 * 判定结果 —— 「什么情况算合格」写在这里，界面上也照这个显示。
 */
enum class Verdict(val label: String, val emoji: String) {
    /** 退出码 0 且返回 `{"ok":true,...}`。 */
    PASS("合格", "✅"),

    /** 能力**如实报了错**（`{"ok":false,...}` + 非零退出码），且原因是系统限制。 */
    WARN("受系统限制", "⚠️"),

    /** 返回了不该出现的错误 —— 这才是真问题。 */
    FAIL("不合格", "❌"),
}

/**
 * 判定一条执行结果。
 *
 * **合格的标准不是「没报错」，而是「行为符合契约」**：
 * - 成功 → 退出码 0 + `ok:true`；
 * - 失败 → 退出码非 0 + `ok:false` + **能说清原因**。
 *
 * 「如实报错」和「假装成功」是两回事：前者是合格，后者是不合格。
 * 比如剪贴板读不到（Android 10+ 没焦点），如实报错 = 合格；
 * 但如果它回 `{"ok":true,"text":""}` 骗人说剪贴板是空的，那就是不合格。
 */
fun judge(exitCode: Int, stdout: String): Verdict {
    // 普通 shell 命令的输出里没有 ok 字段（那是能力命令才有的），按退出码判。
    if (!stdout.contains("\"ok\":")) {
        return if (exitCode == 0) Verdict.PASS else Verdict.FAIL
    }
    if (exitCode == 0 && stdout.contains("\"ok\":true")) return Verdict.PASS
    val systemLimit = listOf("焦点", "被系统拒绝", "缺少", "权限").any { stdout.contains(it) }
    return if (systemLimit) Verdict.WARN else Verdict.FAIL
}
