package ai.agentkernel.android

import ai.agentkernel.android.platform.AndroidPrefixPlatform
import ai.agentkernel.prefix.BootstrapInstaller
import ai.agentkernel.prefix.PrefixLayout
import android.content.Context
import java.io.File

/**
 * 首次启动时把内置的 bootstrap 二进制解压进 `$PREFIX`（对应 Termux 的 `setupBootstrapIfNeeded`）。
 *
 * zip 建议放宿主的 `assets/bootstrap-<abi>.zip`。**注意**：这个 zip 是**二进制载荷**
 * （bash / coreutils / apt 等编译产物），不是可移植代码——它就是「拟 Linux」的本体。
 * 这里只负责「搬进去」，不含任何 Linux 逻辑。
 */
class AndroidBootstrap(
    private val context: Context,
    private val assetName: String = "bootstrap-aarch64.zip",
) {
    fun installIfNeeded(layout: PrefixLayout) {
        val prefixDir = File(layout.prefix)
        if (prefixDir.exists() && prefixDir.list()?.isNotEmpty() == true) return

        val zipBytes = context.assets.open(assetName).use { it.readBytes() }
        BootstrapInstaller(AndroidPrefixPlatform()).install(zipBytes, layout)
    }
}