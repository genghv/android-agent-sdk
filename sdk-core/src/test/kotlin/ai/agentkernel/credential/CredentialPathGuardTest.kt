package ai.agentkernel.credential

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **门禁：凭据目录的示例路径不许落在 agent 私有根里。**
 *
 * ## 为什么要有这道门禁
 *
 * 文档与 KDoc 里的示例路径属于**没人跑过的代码** —— 编译跑不到、测试也测不到，
 * 只有**人读的时候**才会发现写错了。而这类示例恰恰是**别人接入时的第一份参考**：
 * 照抄就把凭据（哪怕是密文）放进了 agent 可达范围 ⇒ **加密白做**。
 *
 * 这道门禁扫的就是它：凡示例里出现「凭据目录 = agent 私有根里的某个路径」，一律判违规。
 *
 * ## 判定规则（刻意收窄，避免假阳性）
 *
 * - `.kt` 文件：**每一行**都算；
 *   「凭据语境」= 本行出现凭据字样 **或** 文件路径里带 `credential` / `凭据`。
 * - `.md` 文件：**只查围栏代码块**（正文里引用错误示例做说明**不算违规**）。
 * - 违规 = 该行出现 **agent 私有根的路径字面量**（`<filesDir>/agent/`、`"agent/…` 等）。
 *
 * ⚠️ 这条门禁**自己也要防假阳性**：一个只会喊「有 agent 路径就是错」的检查，
 * 会把「会话归档仓库在 agent 根里」这种**本来就该在里面的**路径也报出来 ⇒ 门禁很快会被绕开。
 * ⇒ 规则里那条「凭据语境」是必须的。
 */
class CredentialPathGuardTest {

    // ================= 规则 =================

    private companion object {
        /** agent 私有根的目录名（与 `PrefixLayout.DEFAULT_AGENT_ROOT` 一致）。 */
        const val AGENT_ROOT = "agent"

        /** 凭据语境：出现这些字样就算「在讲凭据」。 */
        val CREDENTIAL_MARKER = Regex("credential|凭据|api[ _]?key", RegexOption.IGNORE_CASE)

        /** agent 私有根的路径字面量。 */
        val AGENT_PATH = Regex(
            """(<filesDir>/agent/|["']agent/|filesDir,\s*["']agent/|files/agent/)""",
            RegexOption.IGNORE_CASE,
        )

        data class Hit(val file: String, val line: Int, val text: String)

        /** `.kt`：每行都算；凭据语境 = 本行有凭据字样，或**文件路径**里带凭据字样。 */
        fun scanKotlin(fileLabel: String, text: String): List<Hit> {
            val fileIsCredential = CREDENTIAL_MARKER.containsMatchIn(fileLabel)
            val hits = mutableListOf<Hit>()
            text.split("\n").forEachIndexed { i, raw ->
                val line = raw.trimEnd('\r')
                if (!AGENT_PATH.containsMatchIn(line)) return@forEachIndexed
                if (!fileIsCredential && !CREDENTIAL_MARKER.containsMatchIn(line)) return@forEachIndexed
                hits += Hit(fileLabel, i + 1, line.trim())
            }
            return hits
        }

        /** `.md`：**只查围栏代码块**；凭据语境 = 整个代码块里有凭据字样。 */
        fun scanMarkdown(fileLabel: String, text: String): List<Hit> {
            val hits = mutableListOf<Hit>()
            var inBlock = false
            var block = mutableListOf<String>()
            var blockStart = 0
            fun flush() {
                if (block.isEmpty()) return
                if (CREDENTIAL_MARKER.containsMatchIn(block.joinToString("\n"))) {
                    block.forEachIndexed { k, t ->
                        if (AGENT_PATH.containsMatchIn(t)) hits += Hit(fileLabel, blockStart + k, t.trim())
                    }
                }
                block = mutableListOf()
            }
            text.split("\n").forEachIndexed { i, raw ->
                val line = raw.trimEnd('\r')
                if (line.trimStart().startsWith("```")) {
                    if (inBlock) { flush(); inBlock = false } else { inBlock = true; blockStart = i + 2 }
                    return@forEachIndexed
                }
                if (inBlock) block.add(line)
            }
            flush()
            return hits
        }

        fun collectFiles(dir: File, out: MutableList<File> = mutableListOf()): MutableList<File> {
            if (!dir.exists()) return out
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) collectFiles(f, out) else if (f.isFile) out.add(f)
            }
            return out
        }

        /**
         * **门禁不扫自己**（就这一个文件名）。
         *
         * 这道门禁的源码里**必然**有反例素材：规则说明里写着路径字面量、正则本身、
         * 以及「归档仓库在 agent 根里」这种合法样本 —— 它们都落在凭据语境的文件里 ⇒ 自指。
         * ⇒ 只豁免这一个文件；别处写错照样被抓。
         */
        const val SELF_FILE = "CredentialPathGuardTest.kt"
    }

    // ================= 门禁本身（扫仓库）=================

    @Test
    fun `仓库里没有落在 agent 私有根内的凭据目录示例`() {
        val moduleDir = File("").absoluteFile
        val repoRoot = moduleDir.parentFile
        val isCheckout = File(repoRoot, "settings.gradle.kts").exists()

        val targets = mutableListOf<File>()
        // 本模块的源码与测试：永远存在（跑测试就是在仓库里）。
        targets += File(moduleDir, "src/main/kotlin")
        targets += File(moduleDir, "src/test/kotlin")
        if (isCheckout) {
            targets += File(repoRoot, "sdk-android/src/main/kotlin")
            targets += File(repoRoot, "sdk-android/src/test/kotlin")
        }

        // 本模块这两处**必须存在** —— 不存在说明路径算错了（门禁不能静默放过）。
        assertTrue(File(moduleDir, "src/main/kotlin").exists(), "sdk-core 源码目录不见了 —— 门禁路径算错了")
        assertTrue(File(moduleDir, "src/test/kotlin").exists(), "sdk-core 测试目录不见了 —— 门禁路径算错了")

        val hits = mutableListOf<Hit>()
        var scanned = 0

        for (dir in targets) {
            val files = collectFiles(dir)
            for (f in files) {
                if (!f.name.endsWith(".kt")) continue
                if (f.name == SELF_FILE) continue   // 门禁不扫自己（见 SELF_FILE 的说明）
                scanned++
                val label = relativeLabel(repoRoot, f)
                hits += scanKotlin(label, f.readText(Charsets.UTF_8))
            }
        }

        // 文档：只在仓库检出态扫（发布成 maven 产物时仓库根不在旁边）。
        if (isCheckout) {
            val md = repoRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedBy { it.name } ?: emptyList()
            assertTrue(md.isNotEmpty(), "仓库根一份 .md 都没有 —— 门禁路径算错了")
            for (f in md) {
                scanned++
                hits += scanMarkdown(relativeLabel(repoRoot, f), f.readText(Charsets.UTF_8))
            }
        }

        assertTrue(scanned > 0, "一个文件都没扫到 —— 门禁就是空转（比没有更坏）")
        if (hits.isNotEmpty()) {
            fail(
                "凭据目录示例落在 agent 私有根里（agent 的 shell 能读那个根 ⇒ 加密白做）：\n" +
                    hits.joinToString("\n") { "  ${it.file}:${it.line}  ${it.text}" } +
                    "\n⇒ 改成 agent 私有根**之外**的目录，例如 <filesDir>/credentials/。",
            )
        }
    }

    private fun relativeLabel(repoRoot: File, f: File): String =
        f.absolutePath.removePrefix(repoRoot.absolutePath).trimStart('/', '\\').replace('\\', '/')

    // ================= 规则本身（拿真实形状校准）=================

    /**
     * 故意**拆开写**的错误路径。
     *
     * 这道门禁要扫**它自己所在的这个文件**（文件路径带 `credential` ⇒ 全文都在凭据语境里），
     * 而下面的用例必须喂它一个**真实的错误路径** ⇒ 字面量原样写在源码里会被自己判违规（自指）。
     * ⇒ 运行期拼出来的字符串仍然是那个错路径（规则照常被考到），
     * 而**源码里**匹配不上路径字面量 ⇒ 门禁不自己绊自己。
     */
    private fun badPath(): String = "agent" + "/var/credentials"

    @Test
    fun `规则能逮住那个真实的错误示例`() {
        // 这就是 v1.1.8 修掉的那一行（原样照抄）。
        val bad = " * val store = CredentialStore(dir = File(filesDir, \"${badPath()}\"), cipher = CredentialCipherLoader.resolve())"
        val hits = scanKotlin("sdk-core/.../credential/CredentialCipher.kt", bad)
        assertTrue(hits.size == 1, "这一行必须被判违规：$hits")
    }

    @Test
    fun `正确的示例不违规`() {
        val good = " * val store = CredentialStore(dir = File(filesDir, \"credentials\"), cipher = CredentialCipherLoader.resolve())"
        assertTrue(scanKotlin("sdk-core/.../credential/CredentialCipher.kt", good).isEmpty(), "正确示例不该被判违规")
    }

    @Test
    fun `与凭据无关的 agent 路径不违规`() {
        // 会话归档仓库**本来就该**在 agent 私有根里 —— 这条最容易被假阳性伤到。
        val archive = " * 打开会话归档仓库（`<filesDir>/agent/var/archive/`）。"
        assertTrue(scanKotlin("sdk-android/.../AndroidAgentSdk.kt", archive).isEmpty(), "归档路径不该被判违规")
    }

    @Test
    fun `正文里的反例引用不算违规`() {
        // 文档里**引用**那个错误示例来批评它 —— 这是正文，不是让人照抄的用法。
        val md = """
            | ③ **KDoc 示例与类注释自相矛盾**：示例把凭据目录写成
            |  `File(filesDir, "${badPath()}")` —— 那在 **agent 私有根里面**。
        """.trimMargin()
        assertTrue(scanMarkdown("打包说明.md", md).isEmpty(), "正文里的反例引用不该被判违规")
    }

    @Test
    fun `文档代码块里的错误示例算违规`() {
        val md = """
            | ```kotlin
            | val store = CredentialStore(dir = File(filesDir, "${badPath()}"))
            | ```
        """.trimMargin()
        val hits = scanMarkdown("接入指南.md", md)
        assertTrue(hits.size == 1, "代码块里的错误示例必须被判违规：$hits")
    }
}
