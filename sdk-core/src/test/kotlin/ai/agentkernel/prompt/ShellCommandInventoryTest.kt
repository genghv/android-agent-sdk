package ai.agentkernel.prompt

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 命令清单探测的测试。
 *
 * 规则就一条：**列进提示词的必须真的能跑**。
 * 所以这里盯三件事 ——
 * 设备上没有的不列、白名单外的不列、目录读不到时不炸。
 */
class ShellCommandInventoryTest {

    private fun fakeBin(vararg names: String): File {
        val dir = Files.createTempDirectory("fakebin").toFile()
        for (name in names) {
            File(dir, name).writeText("#!/system/bin/sh\n")
        }
        return dir
    }

    @Test
    fun `设备上有的才列出来`() {
        val bin = fakeBin("ls", "cat", "awk")
        val result = ShellCommandInventory.available(listOf(bin))
        assertTrue(result.contains("ls"))
        assertTrue(result.contains("cat"))
        assertTrue(result.contains("awk"))
        assertFalse(result.contains("rm"), "目录里没有的 rm 不能列")
        assertFalse(result.contains("curl"))
    }

    @Test
    fun `白名单之外的一律不列`() {
        // pm / am / dumpsys / su 在真机上确实存在，但它们是特权工具 ——
        // 模型看见 pm install 就会去试，然后失败。所以即便存在也不列。
        val bin = fakeBin("pm", "am", "dumpsys", "su", "mount", "ls")
        val result = ShellCommandInventory.available(listOf(bin))
        assertTrue(result.contains("ls"))
        for (forbidden in listOf("pm", "am", "dumpsys", "su", "mount")) {
            assertFalse(result.contains(forbidden), "$forbidden 不该出现在清单里")
        }
    }

    @Test
    fun `目录不存在也不炸`() {
        val result = ShellCommandInventory.available(
            listOf(File("/definitely/not/here"), File("/also/not/here"))
        )
        assertEquals(0, result.commandCount, "探测不到就当没有，不能抛异常")
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `多个目录合并`() {
        val a = fakeBin("ls", "cat")
        val b = fakeBin("rm", "grep")
        val result = ShellCommandInventory.available(listOf(a, b))
        assertTrue(result.contains("ls"))
        assertTrue(result.contains("rm"))
        assertTrue(result.contains("grep"))
        assertFalse(result.contains("awk"))
    }

    @Test
    fun `按用途分组，空组不出现`() {
        val bin = fakeBin("ls", "cat")
        val result = ShellCommandInventory.available(listOf(bin))
        assertEquals(2, result.groups.size, "只有两个组有内容")
        assertTrue(result.groups.none { it.commands.isEmpty() }, "空组不该渲染出来")
        val text = ShellCommandInventory.render(result)
        assertTrue(text.contains("文件与目录"))
        assertTrue(text.contains("文本处理"))
    }

    @Test
    fun `白名单覆盖脚本常用命令`() {
        val all = ShellCommandInventory.WHITELIST.flatMap { it.commands }.toSet()
        for (must in listOf("sh", "cat", "echo", "ls", "rm", "grep", "sed", "awk", "curl", "tar", "gzip", "base64")) {
            assertTrue(must in all, "白名单里必须有 $must")
        }
    }

    /**
     * 回归：**目录列不出来，也得能认到里面的命令**。
     *
     * Android 上 `/system/bin` 是 `drwxr-x--x` —— 普通 App 没有读权限，
     * `File.list()` 返回 null（真机踩过：清单探测出 0 条命令）。
     * 但有 search 权限，所以逐个 `exists()` 是能成的。
     *
     * 这个用例把「列不出来」模拟出来，防止有人把探测改回 list()。
     */
    @Test
    fun `目录列不出来也要能靠 stat 认到命令`() {
        val dir = fakeBin("ls", "cat")
        val unlistable = UnlistableDir(dir)
        assertEquals(null, unlistable.list(), "前提：这个目录确实列不出来")
        assertEquals(null, unlistable.listFiles(), "前提：listFiles 也列不出来")

        val result = ShellCommandInventory.available(listOf(unlistable))
        assertTrue(result.contains("ls"), "列不出来也得能 stat 到：\n$result")
        assertTrue(result.contains("cat"))
    }

    /** 模拟 Android 的 `drwxr-x--x`：list()/listFiles() 失败，但 stat 照常。 */
    private class UnlistableDir(dir: File) : File(dir.path) {
        override fun list(): Array<String>? = null
        override fun listFiles(): Array<File>? = null
    }
}
