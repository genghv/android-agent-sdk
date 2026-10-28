package ai.agentkernel.android.shizuku

import ai.agentkernel.tools.exec.ExecResult

/**
 * 特权命令通道 —— **预留接口**。内核不知道、也不关心特权从哪来。
 *
 * 参考业界常见的「特权三通道降级」策略：
 *   1. su 通道：设备有 root 时，直接 `su -c <cmd>`；
 *   2. Shizuku 通道：无 root 时，经 Shizuku 的 App 进程执行（需用户授权）；
 *   3. rish 通道：Shizuku 的 rish 兜底（adb shell 级权限）。
 *
 * 接入方式：实现本接口，把结果包成 ExecResult，再交给 ShellTool 即可让模型用上特权能力。
 */
interface ShizukuBridge {
    /** 当前是否可用（已授权 + 通道就绪）。 */
    val available: Boolean

    suspend fun executePrivileged(command: String, timeoutMs: Long = 60_000L): ExecResult
}

/** 默认空实现：未接入特权时一切特权调用都返回明确的失败，模型能看到原因。 */
class NoopShizukuBridge : ShizukuBridge {
    override val available: Boolean get() = false
    override suspend fun executePrivileged(command: String, timeoutMs: Long): ExecResult =
        ExecResult(exitCode = 127, stdout = "", stderr = "shizuku bridge 未接入（当前无特权通道）")
}