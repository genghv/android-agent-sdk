package ai.agentkernel.prefix

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 移植自 `TermuxInstaller.setupBootstrapIfNeeded`（termux-app）。
 *
 * 干的活（稳定不变的核心 5 步）：
 *   1) 清掉可能残留的 staging / prefix；
 *   2) 把 bootstrap zip 解压到 staging（保持目录结构）；
 *   3) 读到 `SYMLINKS.txt` 时，按行记录软链——格式 `目标←相对路径`，分隔符是 `←`（U+2190）；
 *   4) 对 `bin/`、`libexec`、`lib/apt/apt-helper`、`lib/apt/methods` 下的文件 chmod 0700；
 *   5) 原子 rename：staging → prefix，避免半装状态。
 *
 * 与 Termux 的差异：Termux 从 native `.so`（`getZip()`）取 zip；这里直接从字节数组取，
 * 于是宿主可以把 zip 放 `assets/` 或任意来源，无需 JNI。
 */
class BootstrapInstaller(private val platform: PrefixPlatform) {

    fun install(zipBytes: ByteArray, layout: PrefixLayout) {
        // 1) 清残留
        platform.deleteRecursively(layout.staging)
        platform.deleteRecursively(layout.prefix)
        platform.ensureDirectory(layout.staging)
        platform.ensureDirectory(layout.prefix)

        // 2) 解压 + 3) 收集软链 + 4) 记权限
        val symlinks = mutableListOf<Pair<String, String>>()
        val buffer = ByteArray(8192)

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == SYMLINKs_FILE) {
                    val reader = BufferedReader(InputStreamReader(zin, Charsets.UTF_8))
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val parts = line.split(SYMLINK_SEP)
                            require(parts.size == 2) { "Malformed symlink line: $line" }
                            val linkPath = "${layout.staging}/${parts[1]}"
                            platform.ensureDirectory(parentOf(linkPath))
                            symlinks += parts[0] to linkPath
                        }
                        line = reader.readLine()
                    }
                } else {
                    val target = "${layout.staging}/$name"
                    if (entry.isDirectory) {
                        platform.ensureDirectory(target)
                    } else {
                        platform.ensureDirectory(parentOf(target))
                        FileOutputStream(target).use { out ->
                            var read = zin.read(buffer)
                            while (read != -1) {
                                out.write(buffer, 0, read)
                                read = zin.read(buffer)
                            }
                        }
                        if (needsExecutableBit(name)) {
                            platform.chmod(target, 0b111000000) // 0700
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }

        require(symlinks.isNotEmpty()) { "No $SYMLINKs_FILE encountered" }
        symlinks.forEach { (linkTarget, linkPath) -> platform.symlink(linkTarget, linkPath) }

        // 5) 原子入位
        platform.rename(layout.staging, layout.prefix)
    }

    private fun needsExecutableBit(name: String): Boolean =
        name.startsWith("bin/") ||
            name.startsWith("libexec") ||
            name.startsWith("lib/apt/apt-helper") ||
            name.startsWith("lib/apt/methods")

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    companion object {
        const val SYMLINKs_FILE = "SYMLINKS.txt"
        const val SYMLINK_SEP = "←"
    }
}