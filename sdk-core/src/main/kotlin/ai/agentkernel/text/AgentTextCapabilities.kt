package ai.agentkernel.text

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import kotlinx.serialization.json.put
import java.io.File

/**
 * `agent-text` —— 让 agent **按行**改沙箱里的文本文件 —— **SDK 自制的命令**（前缀 `agent-`）。
 *
 * ## 为什么不让 agent 每次重写整个文件
 *
 * 改一个几百行的文件，让模型把全文重新输出一遍有三个老问题：token 贵、
 * 重贴时容易把**没想改的行**也改了、出错后说不清「到底改了哪儿」。
 * 这里让 agent 只说「哪一行换成什么」，改完回一段 **diff** 给它自己核对。
 *
 * ## 为什么只做**一条**命令
 *
 * 拆成 `agent-text-read` / `agent-text-patch` 两条，提示词里就多两行 ——
 * 而网关对客户端 system 段有 2000 字符上限。所以合成一条带子命令的 `agent-text`，
 * 用法只占一行。
 *
 * ## 命令
 *
 * ```
 * agent-text read <相对路径>
 * agent-text patch <相对路径> <补丁规格>
 * ```
 *
 * 补丁规格用的是 [TextPatcher] 那一套（`replace-lines` / `insert-after` /
 * `delete-line` / `replace-block`），与 `agent-memory patch` **完全一致**。
 */
class AgentTextCapabilities(
    /**
     * **沙箱根** —— agent 的私有根（宿主给的 `<filesDir>/agent`）。
     *
     * 记忆（`var/memory/`）和归档（`var/archive/`）都在它下面，
     * 所以这个根就是 agent 能碰到的**全部**范围：命令只认它下面的相对路径。
     */
    private val root: File,
    /**
     * 单个文件的字符上限：改完超了**拒绝并报错、不写盘**（别让 agent 一次把文件撑爆）。
     */
    private val maxFileChars: Int = 20_000,
) {
    val capabilities: List<DeviceCapability> = listOf(TextCapability(root, maxFileChars))
}

/** `agent-text` —— 读文件 / 按行改文件。 */
private class TextCapability(
    root: File,
    private val maxFileChars: Int,
) : DeviceCapability {
    override val name: String = "text"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-text <read <相对路径>|patch <相对路径> <补丁规格>>"

    /**
     * 短用法 —— 只说「能读能改」，不列路径与补丁规格的写法。
     *
     * 理由同 `agent-memory`：完整形态留给完整模式，核心模式只留「有这条命令」。
     */
    override val shortUsage: String = "agent-text <read|patch>"

    /**
     * root 的**真实路径**（解掉了符号链接/联接点）—— 沙箱校验的基准。
     *
     * ★ 为什么要「真实路径」而不是 `File.canonicalFile`：
     *   Windows 的**目录联接点（junction）**不会被 `File.getCanonicalPath()` 解析
     *   （2026-10-29 实测：canonical 仍是 `root\link\x.md`），
     *   但 `Path.toRealPath()` 会（实测：解析成沙箱外的真实目录）⇒
     *   只用 canonicalFile 的话，Windows 上能用 junction 逃出沙箱。
     */
    private val base: File = RealPath.of(root)
        ?: runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: throw CapabilityFailure("用法：$usage")
        return when (action) {
            "read" -> read(args.getOrNull(1))
            "patch" -> patch(args.getOrNull(1), args.drop(2))
            else -> throw CapabilityFailure("不认识的子命令「$action」。用法：$usage")
        }
    }

    // ---- read ----

    /** `agent-text read <相对路径>` —— 读出文件内容。 */
    private fun read(raw: String?): String {
        val target = resolve(raw)
        if (!target.file.exists()) {
            throw CapabilityFailure("文件不存在：${target.path}")
        }
        if (!target.file.isFile) {
            throw CapabilityFailure("不是一个文件：${target.path}")
        }
        val content = try {
            target.file.readText()
        } catch (e: Exception) {
            throw CapabilityFailure("读不了文件 ${target.path}：${e.message}")
        }
        if (content.length > maxFileChars) {
            throw CapabilityFailure(
                "文件太大：${target.path} 有 ${content.length} 字符（上限 $maxFileChars）。"
            )
        }
        return okJson {
            put("path", target.path)
            put("chars", content.length)
            put("content", content)
        }
    }

    // ---- patch ----

    /**
     * `agent-text patch <相对路径> <补丁规格>` —— 按行改文件。
     *
     * ## 参数怎么收
     *
     * 规格可能被拆成**好几个参数**（`replace-lines 3 4 新内容` 就是 4 个词），
     * 也可能整体带引号（`"replace-block|旧|新"`）——
     * 所以这里把路径之后的参数**用空格拼回一个规格串**，再交给 [TextPatcher.parse]。
     * 拼的时候 `|` 原样保留（`replace-block` 靠它分锚点和新内容）。
     *
     * ## 文件不存在怎么办
     *
     * 当**空文件**新建 —— 但空文件上只有 `replace-lines 1 1 <内容>` 这一种写法能落地
     * （别的操作在空文件上没有落脚点），见 [createFromReplaceLines]。
     */
    private fun patch(raw: String?, rest: List<String>): String {
        val target = resolve(raw)
        val spec = rest.joinToString(" ").trim()
        if (spec.isEmpty()) throw CapabilityFailure("用法：$usage")
        val textPatch = try {
            TextPatcher.parse(spec)
        } catch (e: TextPatchException) {
            throw CapabilityFailure("补丁规格不对：${e.message}。用法：$usage")
        }
        if (target.file.exists() && !target.file.isFile) {
            throw CapabilityFailure("不是一个文件：${target.path}")
        }
        val original = if (target.file.isFile) target.file.readText() else ""
        val patched = try {
            applyPatch(original, textPatch)
        } catch (e: TextPatchException) {
            throw CapabilityFailure("补丁没打上：${e.message}")
        }
        if (patched.length > maxFileChars) {
            throw CapabilityFailure(
                "改完是 ${patched.length} 字符，超过上限 $maxFileChars —— 没有写盘。"
            )
        }
        try {
            target.file.parentFile?.mkdirs()
            target.file.writeText(patched)
        } catch (e: Exception) {
            throw CapabilityFailure("写不了文件 ${target.path}：${e.message}")
        }
        return okJson {
            put("path", target.path)
            put("chars", patched.length)
            put("diff", TextPatcher.describe(original, patched))
        }
    }

    // ---- 路径沙箱 ----

    /**
     * 把 agent 给的相对路径解析成沙箱里的真实文件。
     *
     * 三道关：
     * 1. **绝对路径直接拒**（`/x`、`C:\x`）—— agent 的路径一定是相对沙箱根的；
     * 2. **`..` 直接拒** —— 连「绕一圈还在沙箱里」的那种也拒（`a/../b` 不给过），
     *    少一种「以为能过其实被拒」的意外；
     * 3. **canonical 之后再判是不是还在 root 下面** —— 这一道防的是**符号链接逃逸**
     *    （`root/link → /etc`，路径看着在沙箱里，真实位置在外面）。
     *
     * 相对路径里的 `\` 和 `/` 都当分隔符（agent 两种都爱写）。
     */
    private fun resolve(raw: String?): Target {
        val rel = raw?.trim().orEmpty()
        if (rel.isEmpty()) throw CapabilityFailure("用法：$usage")
        if (isAbsoluteLike(rel)) {
            throw CapabilityFailure("只能写沙箱根下面的相对路径，不能写绝对路径：$rel")
        }
        val normalized = rel.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) {
            throw CapabilityFailure("路径里不能有 ..（只能访问沙箱根下面的文件）：$rel")
        }
        val candidate = File(base, normalized)
        // ★ 判沙箱要用**解掉了链接/联接点**的真实路径 ——
        //   canonicalFile 在 Windows 上不解析 junction（实测），拿它判等于没判。
        //   拿不到真实路径时退回 canonicalFile（Android 上它本来就够用：会解析符号链接）。
        val real = RealPath.of(candidate)
            ?: runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
        if (!isInside(real)) {
            throw CapabilityFailure("路径越出了沙箱根（只能访问沙箱根下面的文件）：$rel")
        }
        // 给 agent 看的相对路径：算不出来就用它给的那个（只影响显示，不影响沙箱判定）。
        val shown = runCatching { real.relativeTo(base).invariantSeparatorsPath }
            .getOrElse { normalized }
        return Target(real, shown)
    }

    /** 绝对路径的几种写法：`/x`、`\x`、`C:\x`、`C:x`（Windows 的盘符在别的平台上也不该给过）。 */
    private fun isAbsoluteLike(rel: String): Boolean =
        rel.startsWith("/") || rel.startsWith("\\") || WINDOWS_DRIVE.matches(rel) || File(rel).isAbsolute

    /** canonical 之后是不是还在沙箱根底下（`/root` 与 `/root2` 不能混为一谈）。 */
    private fun isInside(file: File): Boolean {
        val basePath = base.path
        val filePath = file.path
        if (filePath == basePath) return true
        val prefix = if (basePath.endsWith(File.separator)) basePath else basePath + File.separator
        return filePath.startsWith(prefix)
    }

    // ---- 补丁怎么打 ----

    /** 打补丁 —— 空文件上的 `replace-lines 1 1` 特殊处理（见 [createFromReplaceLines]）。 */
    private fun applyPatch(original: String, patch: TextPatch): String {
        if (original.isEmpty()) {
            val created = createFromReplaceLines(patch)
            if (created != null) return created
        }
        return TextPatcher.apply(original, patch)
    }

    /**
     * 空文件上的 `replace-lines 1 1 <内容>` ⇒ **把这个文件建出来**。
     *
     * 为什么特殊处理：[TextPatcher] 对空文档是 0 行，`replace-lines 1 1` 在它那儿算越界。
     * 但「新建一个文件」只有这一种写法能落地 ——
     * `insert-after` 的行号在空文件上全部越界、`replace-block` 的锚点必然找不到。
     * 所以这里把「写第 1 行」理解成「写这个文件的唯一一行」。
     */
    private fun createFromReplaceLines(patch: TextPatch): String? {
        if (patch !is TextPatch.ReplaceLines) return null
        val end = if (patch.endLine < patch.startLine) patch.startLine else patch.endLine
        if (patch.startLine != 1 || end != 1) return null
        return renderContent(patch.text)
    }

    /** 补丁内容 ⇒ 正文：末尾那个换行是「这一行的结束」，不算多出来的一行。 */
    private fun renderContent(text: String): String {
        val parts = text.split("\n")
        val lines = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        return lines.joinToString("\n")
    }

    // ---- 内部结构 ----

    /** 解析出来的目标：真实文件 + 给 agent 看的相对路径。 */
    private class Target(val file: File, val path: String)

    /** `C:` / `c:` 开头 —— Windows 的盘符路径（哪怕当前平台不是 Windows 也不该给过）。 */
    private companion object {
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
    }
}

/**
 * 解出「解掉了符号链接/目录联接点」的**真实路径**。
 *
 * ## 为什么用**反射**而不是直接写 `java.nio.file.Path`
 *
 * `java.nio.file` 是 **API 26** 才有的（Android 7.x 上根本没这个包），
 * 而本模块的 minSdk 是 24 ⇒ 直接静态引用会在**类加载期**炸
 * （`NoClassDefFoundError` —— 不可捕获、不是普通异常，整个功能起不来），
 * 而且 core library desugaring 救不了它（名单里只有 `java.time` / `java.util.stream` /
 * `java.util.Optional` / `java.util.function`）。
 *
 * ⇒ 这里**反射**调用，静态符号一个都不引用：老系统上反射不到就返回 null，
 * 调用方退回 `File.getCanonicalPath()` —— 那边是 Android/Linux，
 * canonicalFile 本来就会解析符号链接，守卫照样成立。
 *
 * ## 为什么需要它
 *
 * Windows 的**目录联接点（junction）**不会被 `File.getCanonicalPath()` 解析
 * （2026-10-29 实测：canonical 仍是 `root\link\x.md`），
 * 而 `Path.toRealPath()` 会（实测：解析成沙箱外的真实目录）
 * ⇒ 只用 canonicalFile 判沙箱的话，Windows 上能拿一个 junction 逃出沙箱。
 */
private object RealPath {

    private val toPathMethod: java.lang.reflect.Method? = runCatching {
        File::class.java.getMethod("toPath")
    }.getOrNull()

    /**
     * `Path.toRealPath(LinkOption...)` —— **变参**方法。
     *
     * ★ 坑：`getMethod("toRealPath")`（按零参数找）会抛 `NoSuchMethodException` ——
     *   变参在反射里就是「一个数组参数」，不是「零个参数」。
     *   ⇒ 必须按 `LinkOption[]` 这个参数类型去找。
     *   （2026-10-29 实踩：查不到 ⇒ 静默退回 canonicalFile ⇒ 守卫看着在、其实没生效。）
     */
    /**
     * `java.nio.file.LinkOption` 的 Class（**元素类**，不是数组类）。
     *
     * ⚠️ 两个坑都踩过：
     * 1. `Class.forName("java.nio.file.LinkOption[]")` 找不到（数组类的 binary name 是
     *    `[Ljava/nio/file/LinkOption;`，`forName` 不吃这种写法）⇒ 改用「造一个空数组」反推。
     * 2. 造空数组要用**元素类**（`LinkOption`）——
     *    拿数组类去 `Array.newInstance` 会造出 `LinkOption[][]`，
     *    反射直接抛 `IllegalArgumentException: argument type mismatch`，
     *    而它被 `runCatching` 吞掉 ⇒ 静默退回 canonicalFile ⇒ 守卫形同虚设。
     */
    private val linkOptionClass: Class<*>? = runCatching {
        Class.forName("java.nio.file.LinkOption")
    }.getOrNull()

    /** 数组类（`LinkOption[]`）—— 按参数类型找方法时要用。 */
    private val linkOptionArrayClass: Class<*>? = linkOptionClass?.let {
        java.lang.reflect.Array.newInstance(it, 0).javaClass
    }

    private val toRealPathMethod: java.lang.reflect.Method? = runCatching {
        val opts = linkOptionArrayClass ?: error("java.nio.file 不可用")
        Class.forName("java.nio.file.Path").getMethod("toRealPath", opts)
    }.getOrNull()

    private val toFileMethod: java.lang.reflect.Method? = runCatching {
        Class.forName("java.nio.file.Path").getMethod("toFile")
    }.getOrNull()

    /**
     * 真实路径；**拿不到就返回 null**（调用方退回 canonicalFile）。
     *
     * 目标不存在（新建文件）⇒ 解析它**最近的、存在的祖先**，再把剩下的段拼回去
     * —— 不存在的那几段里不会有链接可藏。
     */
    fun of(file: File): File? {
        val toPath = toPathMethod ?: return null
        val toRealPath = toRealPathMethod ?: return null
        val toFile = toFileMethod ?: return null
        val optsClass = linkOptionClass ?: return null
        // 变参要传一个**真的空数组**（类型必须是 LinkOption[]，Object[] 会被反射拒掉）。
        val noOptions = java.lang.reflect.Array.newInstance(optsClass, 0)
        var cur = file
        val tail = ArrayDeque<String>()
        while (true) {
            val pathObj = runCatching { toPath.invoke(cur) }.getOrNull() ?: return null
            val realPath = runCatching { toRealPath.invoke(pathObj, noOptions) }.getOrNull()
            if (realPath != null) {
                val realFile = runCatching { toFile.invoke(realPath) as File }.getOrNull() ?: return null
                var out = realFile
                for (name in tail) out = File(out, name)
                return out
            }
            val parent = cur.parentFile ?: return null
            tail.addFirst(cur.name)
            cur = parent
        }
    }
}
