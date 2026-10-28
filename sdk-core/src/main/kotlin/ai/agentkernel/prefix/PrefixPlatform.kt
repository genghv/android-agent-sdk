package ai.agentkernel.prefix

/**
 * 把平台差异（权限 / 软链 / 重命名）抽出来：
 * - Android 实现走 `android.system.Os`（见 sdk-android 模块，Termux 同款方式）；
 * - 纯 JVM / 单测用 java.nio 实现。
 * 这样 [BootstrapInstaller] 的**逻辑**可以脱机编译并测试。
 */
interface PrefixPlatform {
    fun ensureDirectory(path: String)
    fun chmod(path: String, mode: Int)
    fun symlink(linkTarget: String, linkPath: String)
    fun rename(from: String, to: String)
    fun deleteRecursively(path: String)
    fun exists(path: String): Boolean
}