package ai.agentkernel.android.platform

import ai.agentkernel.prefix.PrefixPlatform
import android.system.Os
import java.io.File

/**
 * Android 侧的平台实现：权限 / 软链直接走 libc（`android.system.Os`），与 Termux 同款。
 * 这是 sdk-android 里**唯一**需要 Android 框架 API 的胶水之一，其余 Kotlin 代码保持纯 JVM。
 */
class AndroidPrefixPlatform : PrefixPlatform {

    override fun ensureDirectory(path: String) {
        File(path).mkdirs()
    }

    override fun chmod(path: String, mode: Int) {
        Os.chmod(path, mode)
    }

    override fun symlink(linkTarget: String, linkPath: String) {
        Os.symlink(linkTarget, linkPath)
    }

    override fun rename(from: String, to: String) {
        if (!File(from).renameTo(File(to))) error("rename failed: $from -> $to")
    }

    override fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    override fun exists(path: String): Boolean = File(path).exists()
}