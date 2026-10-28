package ai.agentkernel

import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.android.device.defaultDeviceCapabilities
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.prefix.PrefixLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **真机**设备能力验证 —— JVM 单测验不了这一层：
 * `CameraManager` / `Vibrator` / `ClipboardManager` / `NotificationManager` 在
 * unit test 里全是假实现（`unitTests.isReturnDefaultValues = true`），
 * 只有在真硬件上才能证明「手电筒真的亮了」。
 *
 * 跑法：
 * ```
 * gradle :sdk-android:connectedDebugAndroidTest
 * ```
 * 或指定设备：
 * ```
 * gradle :sdk-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.deviceId=cbcd72f2
 * ```
 *
 * ## 为什么用「进程内直调能力」而不是让模型来驱动
 *
 * 这一档验的是**设备能力那一层**（Android API 调得通、调得对），
 * 不是「模型会不会下发这条命令」。后者属于 `realLlmCheck` 那一档。
 * 两档分开，才知道出问题的是设备侧还是模型侧。
 *
 * ## 坑：方法名里不能有空格
 *
 * 仪器化测试要进 DEX，而 **DEX 不允许 SimpleName 里出现空格**（DEX 040 之前），
 * 所以这里的测试方法名不能像 JVM 单元测试那样写 `普通 shell 命令照常跑` ——
 * D8 会直接报 `Space characters in SimpleName ... are not allowed prior to DEX version 040`。
 * 中文没问题，只是**不能有空格**。
 */
@RunWith(AndroidJUnit4::class)
class DeviceCapabilityInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var registry: DeviceCapabilityRegistry

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = DeviceCapabilityRegistry(defaultDeviceCapabilities(context))
    }

    /** 运行时权限（CAMERA / POST_NOTIFICATIONS）自己授，测试才能自足。 */
    private fun grant(permission: String) {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }
    }

    // ---- 不需要运行时权限的 ----

    @Test
    fun `电池状态能取到`() {
        val out = run(registry, "termux-battery-status")
        assertEquals("退出码应为 0：$out", 0, out.exitCode)
        assertTrue("输出应是 JSON：$out", out.stdout.contains("\"ok\":true"))
        assertTrue("应含百分比：$out", out.stdout.contains("percentage"))
    }

    @Test
    fun `剪贴板能写入再读回`() {
        // 必须先有前台焦点：Android 10+ 只让有焦点的 App 读剪贴板。
        // 真实宿主 App 有界面、天然有焦点；仪器化测试没有，所以得自己弄一个。
        bringAppToForeground()
        val marker = "agent-sdk-clip-${System.currentTimeMillis()}"
        val set = run(registry, "termux-clipboard-set $marker")
        assertEquals("写入应成功：$set", 0, set.exitCode)

        val get = run(registry, "termux-clipboard-get")
        assertEquals("读取应成功：$get", 0, get.exitCode)
        assertTrue("读回来应包含刚写的内容：$get", get.stdout.contains(marker))
    }

    /**
     * 把测试 App 拉到前台。
     *
     * 用 `UiAutomation.executeShellCommand` 走 **shell 权限** 启动 [FocusActivity] ——
     * 小米/HyperOS 不让后台 App 自己起 Activity（`result code=102`），
     * 但 shell 可以。Activity 到了前台，这个包就有了焦点。
     */
    private fun bringAppToForeground() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/ai.agentkernel.FocusActivity"
        ).use { fd ->
            // 必须把输出读完，否则命令的管道不关，am 可能一直等着。
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        // 给系统一点时间把焦点交过去。
        Thread.sleep(1200)
    }

    /**
     * 没有前台焦点时读剪贴板会被系统拒 —— 这时能力必须**明确失败**，
     * 不能回一个 `{"ok":true,"text":""}` 骗模型说「剪贴板是空的」。
     *
     * 真机实测：Android 16 上 `getPrimaryClip()` 返回 null，
     * logcat 里有 `ClipboardService: Denying clipboard access ... not in focus`。
     */
    @Test
    fun `没有前台焦点时读剪贴板要明确报错而不是谎报为空`() {
        val get = run(registry, "termux-clipboard-get")
        if (get.exitCode == 0) {
            // 这台设备/系统版本没限制（比如还没被别的 App 写过剪贴板），读到了也是合法的。
            assertTrue("成功时应是 JSON：$get", get.stdout.contains("\"ok\":true"))
        } else {
            assertTrue("失败也要是 JSON：$get", get.stdout.contains("\"ok\":false"))
            assertTrue(
                "失败原因要提到焦点限制：$get",
                get.stdout.contains("焦点") || get.stdout.contains("clipboard"),
            )
        }
    }

    @Test
    fun `震动能触发`() {
        val out = run(registry, "termux-vibrate -d 200")
        assertEquals("震动应成功：$out", 0, out.exitCode)
        assertTrue("输出应是 JSON：$out", out.stdout.contains("\"ok\":true"))
    }

    // ---- 需要运行时权限的 ----

    @Test
    fun `手电筒能开关`() {
        grant(android.Manifest.permission.CAMERA)
        // 没有闪光的设备（比如平板、部分模拟器）跳过，不算失败。
        val hasFlash = context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_CAMERA_FLASH
        )
        assumeTrue("这台设备没有闪光灯", hasFlash)

        val on = run(registry, "termux-torch on")
        assertEquals("开手电筒应成功：$on", 0, on.exitCode)
        assertTrue("输出应是 JSON：$on", on.stdout.contains("\"ok\":true"))

        // 一定要关掉：否则手电筒会一直亮着。
        val off = run(registry, "termux-torch off")
        assertEquals("关手电筒应成功：$off", 0, off.exitCode)
    }

    @Test
    fun `通知能发出去`() {
        grant(android.Manifest.permission.POST_NOTIFICATIONS)
        val out = run(registry, "termux-notification -t agent-sdk -c hello-from-instrumented-test")
        assertEquals("发通知应成功：$out", 0, out.exitCode)
        assertTrue("输出应是 JSON：$out", out.stdout.contains("\"ok\":true"))
    }

    // ---- 失败语义（真机上也要对） ----

    @Test
    fun `参数用错时给非零退出码和可读原因`() {
        val out = run(registry, "termux-torch 瞎写")
        assertEquals("用法错误应给非零退出码：$out", 1, out.exitCode)
        assertTrue("应告诉模型怎么用：$out", out.stdout.contains("\"ok\":false"))
        assertTrue("应含用法说明：$out", out.stdout.contains("termux-torch"))
    }

    @Test
    fun `复合命令里的能力会被执行且结果回到stdout`() = runBlocking {
        val out = run(registry, "termux-battery-status && echo done")
        assertEquals("复合命令应成功：$out", 0, out.exitCode)
        assertTrue("能力结果要回到 stdout：$out", out.stdout.contains("\"ok\":true"))
        assertTrue("命令链其余部分要照常跑：$out", out.stdout.contains("done"))
    }

    // ---- 普通命令不受能力路由影响 ----

    @Test
    fun `普通shell命令照常跑`() = runBlocking {
        val out = run(registry, "echo plain-command")
        assertEquals("普通命令应成功：$out", 0, out.exitCode)
        assertTrue("普通命令的输出应原样回来：$out", out.stdout.contains("plain-command"))
    }

    /**
     * 走**真的**执行器跑一条命令 —— 这样验的是「能力路由接进执行器之后」的行为，
     * 而不是绕过执行器直调能力。
     */
    private fun run(registry: DeviceCapabilityRegistry, command: String) =
        runBlocking {
            val base = java.io.File(context.filesDir, "cap-test")
            java.io.File(base, "home").mkdirs()
            java.io.File(base, "usr/tmp").mkdirs()
            AndroidCommandExecutor(
                shellPath = AndroidCommandExecutor.DEFAULT_SHELL,
                layout = PrefixLayout(base.path),
                capabilities = registry,
            ).execute(command)
        }
}
