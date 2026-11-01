package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * `agent-skill` —— 让 agent **按需读本地技能文档** —— SDK 自制的命令（前缀 `agent-`）。
 *
 * ## 为什么要有这条命令
 *
 * 技能（怎么写接口、怎么用某个业务系统…）**由 App 下载维护**，SDK 里一行业务内容都不放。
 * 但技能全文不能塞进提示词：那会立刻吃光两千字符的核心预算，而绝大多数轮次根本用不到它。
 * ⇒ 提示词里只留一行 `agent-skill <list|show|search>`（能力清单自动生成），
 * 模型真需要时再 `list` → `show`，**按需取、按需截断**。
 *
 * ## 目录约定（在 agent 工作区内，宿主可直接用文件系统维护）
 *
 * ```
 * <filesDir>/agent/usr/share/skills/
 *   ├── index.json          索引（可选）：给技能起个人话名字
 *   └── <id>/SKILL.md       一个技能一个目录
 * ```
 *
 * **文件系统是权威，索引只是装饰**（这条很重要，见 [FileSkillStore.indexJson]）：
 * - 目录里有、索引里没有 ⇒ **照样列**（名字退回 id）—— 索引漏更新不该让技能消失；
 * - 索引里有、目录里没有 ⇒ **不列** —— 不能让模型看到一个点开就 404 的技能。
 *
 * ## 路径安全（与 `agent-text` 同级要求）
 *
 * 三道关，缺一不可：
 * 1. **id 字符白名单** `[A-Za-z0-9._-]` ⇒ 结构上就不可能有 `/`、`\`、`:`；
 * 2. **拒绝 `.` / `..`**（白名单允许点号，但这两个必须单独拦）；
 * 3. **canonical 之后必须仍在技能根目录下** ⇒ 防**符号链接逃逸**
 *    （`skills/evil → /data/data/…/files`，路径看着合法，真实位置在外面）。
 *
 * ## 失败一律说清、绝不编内容
 *
 * 不存在 / 无权限 / 索引坏了 ⇒ 抛 [CapabilityFailure]（注册表统一转 `{"ok":false,...}` + 退出码 1）。
 * 拿不到就是拿不到：**不会**返回空正文冒充「这个技能是空的」。
 *
 * ## 可测
 *
 * 文件访问抽成 [SkillStore]：JVM 单测注入内存实现就能验 list/show/search/不存在/超限/穿越；
 * 想换成「技能从 assets 或网络取」的宿主，实现 [SkillStore] 注入即可，能力本体不用改。
 *
 * @param store 技能存储（生产用 [FileSkillStore]）
 * @param maxBytes `show` 的默认字节上限（`--max-bytes` 可覆盖）
 */
class AgentSkillCapabilities(
    private val store: SkillStore,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    val capabilities: List<DeviceCapability> = listOf(SkillCapability(store, maxBytes))

    companion object {
        /** `show` 默认最多返回 32 KiB（约 1 万汉字）—— 再多就该分次读，别一次塞爆上下文。 */
        const val DEFAULT_MAX_BYTES = 32 * 1024

        /** `--max-bytes` 上限：单个技能文件再大也不该一次全给模型。 */
        const val MAX_BYTES_LIMIT = 256 * 1024

        /** `--max-bytes` 下限：至少容得下一个汉字（4 字节）+ 余量，否则截出来是空的，没意义。 */
        const val MIN_BYTES = 64

        /** 生产入口：`<filesDir>/agent/usr/share/skills`。 */
        fun forDirectory(skillsDir: File, maxBytes: Int = DEFAULT_MAX_BYTES): AgentSkillCapabilities =
            AgentSkillCapabilities(FileSkillStore(skillsDir), maxBytes)
    }
}

/** `agent-skill` —— 只读技能文档，不写、不执行、不联网。 */
private class SkillCapability(
    private val store: SkillStore,
    private val defaultMaxBytes: Int,
) : DeviceCapability {

    override val name: String = "skill"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL

    /**
     * 完整用法（**完整模式**的提示词用）。
     *
     * 结尾那句「技能在 usr/share/skills/」是给模型的**位置线索**：
     * 否则它可能压根不知道技能是本地文件，或者用 `cat` 去硬读（那会绕过截断、直接吃爆上下文）。
     */
    override val usage: String =
        "agent-skill <list|show <id> [--max-bytes N]|search <关键词>>（技能在 usr/share/skills/，用本命令读）"

    /** 短用法（**核心模式**用，预算只剩几十字符，所以只留命令名与子命令）。 */
    override val shortUsage: String = "agent-skill <list|show|search>"

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: throw CapabilityFailure("用法：$usage")
        return when (action) {
            "list" -> list()
            "show" -> show(args.drop(1))
            "search" -> search(args.drop(1))
            else -> throw CapabilityFailure("不认识的子命令「$action」。用法：$usage")
        }
    }

    // ---------------------------------------------------------------- list

    /**
     * `agent-skill list` —— 有哪些技能。
     *
     * 名字优先取索引，索引里没有就用 id 顶上；字符数**永远按真实文件算**（不采信索引里的数字，
     * 那可能是下载时写的旧值）。
     */
    private fun list(): String {
        val ids = store.ids()
        // 索引坏了就整条失败（任务口径：坏 index 要说出来），但**没有索引**不算坏。
        val names = indexNames()
        val entries = ids.map { id -> Triple(id, names[id] ?: id, store.chars(id)) }
        return okJson {
            put("skills", buildJsonArray {
                entries.forEach { (id, name, chars) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("chars", chars)
                    })
                }
            })
            if (ids.isEmpty()) {
                put(
                    "note",
                    "还没有安装技能。App 把技能放到 usr/share/skills/<id>/SKILL.md 后就会出现，" +
                        "可选的 usr/share/skills/index.json 用来给技能起名字。",
                )
            }
        }
    }

    /**
     * 读索引里的 `id → name`。
     *
     * 容忍两种形状：`{"skills":[…]}` 与顶层数组 `[…]`；每项只要 `id`（`name` 可选）。
     * 其它形状一律当「坏索引」—— 静默忽略会让 App 下载到半截的技能包看起来正常。
     */
    private fun indexNames(): Map<String, String> {
        val text = store.indexJson() ?: return emptyMap()
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw CapabilityFailure(
                "技能索引 index.json 不是合法 JSON：${e.message}。" +
                    "技能包可能没下完整 —— 重新同步；或删掉 index.json，会退回按目录扫描。",
            )
        }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["skills"] as? JsonArray
                ?: throw CapabilityFailure("技能索引 index.json 格式不对：对象里缺 skills 数组。")
            else -> throw CapabilityFailure("技能索引 index.json 格式不对：顶层要是对象或数组。")
        }
        val out = LinkedHashMap<String, String>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            // 索引里的非法 id 直接跳过（文件系统才是权威，不因为一条脏数据全盘失败）。
            if (!SkillIds.isValid(id)) return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            out[id] = name.ifEmpty { id }
        }
        return out
    }

    // ---------------------------------------------------------------- show

    /** `agent-skill show <id> [--max-bytes N]` —— 读全文（超限按 UTF-8 边界截断并标 `truncated`）。 */
    private fun show(rest: List<String>): String {
        val rawId = rest.firstOrNull() ?: throw CapabilityFailure("用法：$usage")
        var maxBytes = defaultMaxBytes
        var i = 1
        while (i < rest.size) {
            when (val arg = rest[i]) {
                "--max-bytes" -> {
                    val raw = rest.getOrNull(i + 1)
                    val value = raw?.toIntOrNull()
                        ?: throw CapabilityFailure(
                            "--max-bytes 要一个整数（收到 ${raw ?: "缺参数"}）。用法：$usage",
                        )
                    if (value < AgentSkillCapabilities.MIN_BYTES) {
                        throw CapabilityFailure("--max-bytes 至少 ${AgentSkillCapabilities.MIN_BYTES}（收到 $value）。")
                    }
                    if (value > AgentSkillCapabilities.MAX_BYTES_LIMIT) {
                        throw CapabilityFailure(
                            "--max-bytes 最多 ${AgentSkillCapabilities.MAX_BYTES_LIMIT}（收到 $value）：" +
                                "一次给模型几百 KB 会直接吃爆上下文。要分段看就分几次读。",
                        )
                    }
                    maxBytes = value
                    i += 2
                }
                else -> throw CapabilityFailure("看不懂的参数「$arg」。用法：$usage")
            }
        }

        val id = SkillIds.require(rawId)
        val text = store.read(id, maxBytes)
        return okJson {
            put("id", id)
            put("chars", text.content.length)
            put("total_chars", text.totalChars)
            put("truncated", text.truncated)
            put("content", text.content)
            if (text.truncated) {
                put(
                    "note",
                    "内容被截断：只给了前 ${text.content.length} 字（全文 ${text.totalChars} 字）。" +
                        "加大 --max-bytes 可以多拿一些，但注意上下文预算。",
                )
            }
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * `agent-skill search <关键词>` —— 在**全部技能**里找关键词，返回命中行。
     *
     * 大小写不敏感；`hits` 最多 [MAX_HITS] 条（按 id、行号排序），真实的命中总数放 `total`
     * —— 让模型知道「还有更多」而不是以为只有这几条。
     */
    private fun search(rest: List<String>): String {
        val keyword = rest.joinToString(" ").trim()
        if (keyword.isEmpty()) throw CapabilityFailure("用法：$usage（search 后面要跟关键词）")
        val needle = keyword.lowercase()

        val hits = mutableListOf<Triple<String, Int, String>>()
        var total = 0
        for (id in store.ids()) {
            // search 要看全文才能不漏（show 的截断只对「给模型看」有意义）。
            val text = store.readAll(id)
            text.lineSequence().forEachIndexed { index, line ->
                if (!line.lowercase().contains(needle)) return@forEachIndexed
                total++
                if (hits.size < MAX_HITS) {
                    hits += Triple(id, index + 1, snippet(line, needle))
                }
            }
        }
        return okJson {
            put("keyword", keyword)
            put("hits", buildJsonArray {
                hits.forEach { (id, line, text) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("line", line)
                        put("snippet", text)
                    })
                }
            })
            put("total", total)
            put("truncated", total > hits.size)
            if (total == 0) put("note", "没有命中。可以先 `agent-skill list` 看看有哪些技能。")
        }
    }

    /** 命中行的片段：先 trim，太长就围绕命中位置截一段（带省略号）。 */
    private fun snippet(line: String, needle: String): String {
        val text = line.trim()
        if (text.length <= SNIPPET_MAX) return text
        val at = text.lowercase().indexOf(needle).coerceAtLeast(0)
        val start = (at - SNIPPET_MAX / 3).coerceIn(0, text.length - SNIPPET_MAX)
        val end = (start + SNIPPET_MAX).coerceAtMost(text.length)
        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        return head + text.substring(start, end) + tail
    }

    private companion object {
        /** 一次 search 最多回多少条命中（再多就变成把技能库倒进上下文了）。 */
        const val MAX_HITS = 20

        /** 单条命中片段的字符上限。 */
        const val SNIPPET_MAX = 160
    }
}

// ---------------------------------------------------------------- 存储接缝

/** 一次 `show` 的结果。 */
data class SkillText(
    /** 这次实际返回的正文（可能被截断）。 */
    val content: String,
    /** 文件全文的字符数。 */
    val totalChars: Int,
    /** 是否发生了截断。 */
    val truncated: Boolean,
)

/**
 * 技能存储 —— 能力本体与文件系统之间的唯一接缝。
 *
 * 抽出来是为了两件事：
 * 1. **JVM 可测**：断言「超限截断 / 路径穿越 / 不存在」不用真文件；
 * 2. **宿主可换**：技能改从 assets、数据库或网络取，实现本接口注入即可。
 *
 * 约定：
 * - 实现**必须**自己做路径安全（[SkillIds] 的白名单 + 真实路径包含判定），
 *   不能指望调用方保证 id 干净 —— 这层是安全边界；
 * - 拿不到就抛 [CapabilityFailure]（带一句人话），**不许**返回空串冒充「内容为空」。
 */
interface SkillStore {
    /** 有哪些技能（已按 id 排序；目录不存在就是空表，不是错误）。 */
    fun ids(): List<String>

    /** `index.json` 原文；不存在返回 null。坏 JSON 由调用方判，读失败抛 [CapabilityFailure]。 */
    fun indexJson(): String?

    /** 技能全文的字符数（`list` 用）。不存在抛 [CapabilityFailure]。 */
    fun chars(id: String): Int

    /** 读技能正文，最多 [maxBytes] 字节（UTF-8 边界截断）。不存在抛 [CapabilityFailure]。 */
    fun read(id: String, maxBytes: Int): SkillText

    /** 读**全文**（`search` 用）。不存在抛 [CapabilityFailure]。 */
    fun readAll(id: String): String
}

/**
 * 技能 id 的白名单 —— **路径安全的第一道关**。
 *
 * 只允许 `A-Za-z0-9._-`：
 * - 不含 `/` `\` `:` ⇒ 结构上就拼不出子路径、绝对路径、盘符路径；
 * - `.` 与 `..` 单独拦（它们本身符合白名单，但是目录跳转）；
 * - 长度 ≤ 64（技能 id 是目录名，没必要更长，也防病态输入）。
 *
 * 宿主若自建 [SkillStore]，**必须复用本对象**校验 id，否则符号链接/路径穿越的锅会落到你身上。
 */
object SkillIds {

    /** 合法 id 的正则（逐字符白名单，不是黑名单）。 */
    private val PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** 只判形状，不抛异常。 */
    fun isValid(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && PATTERN.matches(id)

    /** 校验并返回 trim 后的 id；不合法抛 [CapabilityFailure]。 */
    fun require(raw: String?): String {
        val id = raw?.trim().orEmpty()
        if (id.isEmpty()) throw CapabilityFailure("技能 id 不能为空。")
        if (isValid(id)) return id
        throw CapabilityFailure(
            "技能 id 不合法：「$raw」。只允许字母、数字、点、下划线、连字符" +
                "（不能有 / \\ 空格，也不能是 . 或 ..）；绝对路径与路径穿越一律拒绝。",
        )
    }
}

/**
 * 生产实现：`<filesDir>/agent/usr/share/skills` 下的真实文件。
 *
 * ## 符号链接逃逸怎么挡
 *
 * id 白名单只能挡「拼出来的路径」，挡不住**预先放在磁盘上的软链接**：
 * `skills/evil → /data/data/<pkg>/files`，`evil` 这个 id 完全合法。
 * ⇒ 解析出的文件要过 `canonicalFile`（Android = Linux，它会解掉软链接），
 * 再判「真实路径是否还在技能根下面」。根目录自己也 canonical 一次，两边口径一致。
 *
 * （sdk-core 的 `agent-text` 还要额外处理 Windows 的**目录联接点**——那是给 JVM 测试用的；
 * 本模块只跑在 Android 上，`canonicalFile` 足够。）
 *
 * @param skillsDir 技能根目录（`<filesDir>/agent/usr/share/skills`）
 * @param canonicalize 解真实路径的方式；默认 `canonicalFile`。**一般不用传**，
 *   它是测试接缝：没有建软链接权限的平台（Windows 默认要管理员/开发者模式）也能验
 *   「越界哨兵」是否真的生效。
 */
class FileSkillStore(
    skillsDir: File,
    private val canonicalize: (File) -> File = { file ->
        runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
    },
) : SkillStore {

    /** 技能根（真实路径，懒解析：别在 App 启动时做 IO）。 */
    private val base: File by lazy { canonicalize(skillsDir) }

    override fun ids(): List<String> {
        val children = base.listFiles() ?: return emptyList()
        return children.asSequence()
            .filter { it.isDirectory }
            .map { it.name }
            .filter { SkillIds.isValid(it) }
            // 软链接目录解析后跑到根外面的，不列（否则列出来点开就报错）。
            .filter { id ->
                runCatching {
                    val file = File(File(base, id), SKILL_FILE)
                    file.isFile && isInside(realPath(file), base)
                }.getOrDefault(false)
            }
            .sorted()
            .toList()
    }

    override fun indexJson(): String? {
        val file = File(base, INDEX_FILE)
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw CapabilityFailure("读不了技能索引 ${file.path}：${e.message}")
        }
    }

    override fun chars(id: String): Int = readAll(id).length

    override fun read(id: String, maxBytes: Int): SkillText = truncate(readAll(id), maxBytes)

    override fun readAll(id: String): String {
        val file = skillFile(id)
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw CapabilityFailure("读不了技能「$id」的文件 ${file.path}：${e.message}")
        }
    }

    // ---------------------------------------------------------------- 内部

    /** id → 真实文件；不合法/越界/不存在都在这里拦掉。 */
    private fun skillFile(rawId: String): File {
        val id = SkillIds.require(rawId)
        val base = base
        val file = File(File(base, id), SKILL_FILE)
        val real = realPath(file)
        if (!isInside(real, base)) {
            throw CapabilityFailure(
                "技能「$id」的真实路径越出了技能目录（疑似符号链接逃逸）：${real.path}",
            )
        }
        if (!real.isFile) {
            throw CapabilityFailure("没有这个技能：$id（用 `agent-skill list` 看有哪些）")
        }
        return real
    }

    private fun realPath(file: File): File = canonicalize(file)

    /** 真实路径是否在根下面（`/root` 与 `/root2` 不能混为一谈）。 */
    private fun isInside(file: File, root: File): Boolean {
        val rootPath = root.path
        if (file.path == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return file.path.startsWith(prefix)
    }

    /**
     * 按 **UTF-8 字节**截断，但**绝不切断一个字符**。
     *
     * 直接 `bytes.copyOf(n)` 再 `String()` 会把一个汉字劈成两半 ⇒ 解码出 `�`，
     * 模型看到的是乱码，还会以为技能文件坏了。所以逐码点累加字节数，超了就停在字符边界上。
     */
    private fun truncate(text: String, maxBytes: Int): SkillText {
        val total = text.length
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (encoded.size <= maxBytes) return SkillText(text, total, false)

        var used = 0
        var end = 0
        var i = 0
        while (i < total) {
            val codePoint = text.codePointAt(i)
            val size = utf8Size(codePoint)
            if (used + size > maxBytes) break
            used += size
            i += Character.charCount(codePoint)
            end = i
        }
        return SkillText(text.substring(0, end), total, true)
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private companion object {
        /** 一个技能一个目录，目录里固定这个文件名。 */
        const val SKILL_FILE = "SKILL.md"

        /** 可选的索引文件，与技能目录平级。 */
        const val INDEX_FILE = "index.json"
    }
}
