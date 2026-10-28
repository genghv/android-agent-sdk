package ai.agentkernel

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 一个空 Activity，只为让测试 App **有前台焦点**。
 *
 * ## 为什么需要它
 *
 * Android 10+ 的系统限制：**只有当前有焦点的 App（以及默认输入法）能读剪贴板**。
 * 仪器化测试 APK 没有界面，永远拿不到焦点，所以读剪贴板会被系统拒：
 * ```
 * E/ClipboardService: Denying clipboard access to <pkg>, application is not in focus
 * ```
 *
 * 真实的宿主 App 是有界面的，运行时天然有焦点 —— 所以测试得**还原这个条件**，
 * 否则测的是一个宿主根本不会遇到的场景。
 *
 * ## 为什么 `exported=true`
 *
 * 小米/HyperOS 不让**后台 App 自己启动 Activity**：
 * ```
 * MIUILOG- Permission Denied Activity : cmp=<pkg>/ai.agentkernel.FocusActivity
 * ActivityTaskManager: START ... result code=102
 * ```
 * 所以 `ActivityScenario.launch` 在这台机上会被拒，测试会一直等到超时。
 * 绕法是让测试进程**用 shell 权限**（`UiAutomation.executeShellCommand`）去拉起它。
 * 为了让 shell 拉得起，这个 Activity 必须 exported。
 *
 * 它只存在于**测试 APK** 里（`src/androidTest`），不进正式产物，也不进宿主 App。
 */
class FocusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "agent-sdk focus holder" })
    }
}
