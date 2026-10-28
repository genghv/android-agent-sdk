package ai.agentkernel.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 一条**长期记忆**（一次会话的归档摘要）。
 *
 * @param id 稳定标识（同时是文件名的一部分）。
 * @param createdAt 归档时刻（epoch 毫秒）。
 * @param title 一句话标题（给列表看）。
 * @param summary 摘要正文（进提示词的就是它）。
 */
data class MemoryEntry(
    val id: String,
    val createdAt: Long,
    val title: String,
    val summary: String,
) {
    /** 正文字符数。 */
    val chars: Int get() = summary.length
}

/**
 * 长期记忆的**本地持久化** —— 一堆普通文件。
 *
 * ## 落在哪儿
 *
 * `<agentRoot>/var/memory/`：
 * ```
 * <agentRoot>/var/memory/
 *   ├── index.json          ← 索引（每条一行元数据）
 *   └── entries/
 *       └── 20261028-1432.md  ← 一条一份，纯 markdown
 * ```
 *
 * ## 为什么是「一堆文件」而不是一个数据库
 *
 * 1. **agent 自己就能读** —— 它已经有终端能力，`cat var/memory/entries/xxx.md` 就完了；
 *    需要搜索时用 `grep` 也行。增删改查另有一条**命令**（`agent-memory`，见 [AgentMemoryCapabilities]）——
 *    磁盘布局是实现细节，不该让模型把 `index.json` 的字段名写死在命令里。
 * 2. **可读可 diff 可备份** —— 出问题时人能直接看内容，不用工具反序列化。
 * 3. **坏了不致命** —— 索引解析失败就当没有记忆，只丢索引不丢正文。
 *
 * ## 索引坏了怎么办
 *
 * 索引只是「哪些条目存在」的快捷方式。索引解析失败时，
 * [rebuildIndex] 可以从 `entries/` 目录把索引重建出来 —— 正文才是本体，索引是缓存。
 */
class MemoryStore(private val root: File) {

    private val entriesDir: File get() = File(root, ENTRIES_DIR)
    private val indexFile: File get() = File(root, INDEX_FILE)

    /** 列出全部记忆（新的在前）。 */
    fun list(): List<MemoryEntry> {
        val meta = readIndex()
        if (meta.isNotEmpty()) {
            return meta.mapNotNull { m ->
                val body = readBody(m.id) ?: return@mapNotNull null
                MemoryEntry(id = m.id, createdAt = m.createdAt, title = m.title, summary = body)
            }.sortedByDescending { it.createdAt }
        }
        // 索引没了就扫目录重建 —— 正文才是本体。
        return scanEntries()
    }

    /** 追加一条记忆（先写正文，再更新索引）。 */
    fun append(entry: MemoryEntry): MemoryEntry {
        entriesDir.mkdirs()
        // 同一秒内追加两条时 id 会撞上 —— 加个后缀，别把上一条覆盖掉。
        var id = entry.id
        var n = 1
        while (File(entriesDir, "$id.md").exists()) {
            id = "${entry.id}-$n"
            n++
        }
        val stored = entry.copy(id = id)
        File(entriesDir, "$id.md").writeText(stored.summary)
        val meta = readIndex().filterNot { it.id == id } + Meta(id, stored.createdAt, stored.title)
        writeIndex(meta)
        return stored
    }

    /** 读某条记忆的正文。 */
    fun readBody(id: String): String? {
        val file = File(entriesDir, "$id.md")
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /**
     * 改一条记忆的正文（写文件 + 更新索引）。
     *
     * 记忆原来只增不改（归档是追加的），现在 agent 也能改：
     * 归档时压出来的那条摘要可能有偏差，让 agent 自己纠正比让人回来手改文件靠谱。
     *
     * @param newTitle 新标题；null = 按新正文的第一行更新（与 [add] 同一套规则）。
     * @return 改完的那条；id 不存在时返回 null（调用方要能区分「没这条」和「改失败」）。
     */
    fun edit(id: String, newSummary: String, newTitle: String? = null): MemoryEntry? {
        val file = File(entriesDir, "$id.md")
        if (!file.isFile) return null
        // 索引可能是空的（丢了/坏了）—— 那就从正文目录把索引捞回来，
        // 否则写回去的索引只剩这一条，别的条目就「看不见」了。
        val meta = readIndex().ifEmpty { scanEntries().map { Meta(it.id, it.createdAt, it.title) } }
        val old = meta.firstOrNull { it.id == id }
        // 索引丢了也能改 —— id 前缀就是时间戳，拿它当创建时间兜底。
        val createdAt = old?.createdAt
            ?: id.substringBefore('-').toLongOrNull()?.times(1000L)
            ?: file.lastModified()
        val title = newTitle ?: titleOf(newSummary)
        val stored = MemoryEntry(id = id, createdAt = createdAt, title = title, summary = newSummary)
        file.writeText(newSummary)
        writeIndex(meta.filterNot { it.id == id } + Meta(id, createdAt, title))
        return stored
    }

    /**
     * 删一条记忆（删正文文件 + 从索引移除）。
     *
     * @return 真的删掉了返回 true；id 不存在返回 false。
     */
    fun delete(id: String): Boolean {
        val file = File(entriesDir, "$id.md")
        val existed = file.isFile
        val deleted = existed && file.delete()
        // 索引里可能还留着这条（正文被外部删了）—— 顺手清掉，别让它变成幽灵条目。
        val meta = readIndex()
        if (meta.any { it.id == id }) writeIndex(meta.filterNot { it.id == id })
        return deleted
    }

    /**
     * 按关键词搜记忆（标题或正文命中，**不区分大小写**）。
     *
     * agent 想找「之前聊过网关那次」时用这个 —— 比自己 grep 目录稳
     * （正文在磁盘上的布局是实现细节，不该让模型知道）。
     *
     * @return 命中的条目，按 [MemoryEntry.createdAt] **倒序**（新的在前）。
     */
    fun search(keyword: String): List<MemoryEntry> {
        val needle = keyword.trim()
        if (needle.isEmpty()) return emptyList()
        return list()
            .filter { e ->
                e.title.contains(needle, ignoreCase = true) ||
                    e.summary.contains(needle, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 从正文推出标题：取第一行非空行，前 60 字。
     *
     * 「第一行」就是摘要的结论句 —— 列表里一眼能认出来的那个短句。
     *
     * internal：能力命令（[AgentMemoryCapabilities]）加条目时要用同一套规则，
     * 两边各写一份的话，改了一边另一边就对不上了。
     */
    internal fun titleOf(body: String): String =
        body.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(60)
            .orEmpty()

    /**
     * 生成进提示词的**记忆块**。
     *
     * 只带**最近**的若干条（按 [ContextPolicy.memoryDigestChars] 的预算截断），
     * 完整的都在磁盘上 —— agent 想翻旧记忆自己 `cat` 就行。
     *
     * 带的是**正文**，不是只有标题：模型得知道当时聊出了什么结论，
     * 才知道现在该不该沿用那个结论。
     *
     * 返回 null = 一条记忆都没有（不注入任何内容）。
     */
    fun digest(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val entries = list()
        if (entries.isEmpty()) return null

        val header = "## 你之前和用户聊过什么（长期记忆）\n"
        val sb = StringBuilder(header)
        for (e in entries) {
            val block = buildString {
                append("- [").append(dateOf(e.createdAt)).append("] ")
                append(e.title.ifBlank { e.summary.take(30) }).append('\n')
                e.summary.lines().filter { it.isNotBlank() }.forEach {
                    append("  ").append(it.trim()).append('\n')
                }
            }
            if (sb.length + block.length > maxChars) {
                // 放不下整块就只放标题那一行，好过什么都不给。
                val head = block.lineSequence().first()
                if (sb.length + head.length + 1 <= maxChars) {
                    sb.append(head).append('\n')
                }
                break
            }
            sb.append(block)
        }
        if (sb.length == header.length) return null
        return sb.toString().trimEnd()
    }

    /** 清空全部记忆。 */
    fun clear() {
        entriesDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }

    /** 从 `entries/` 目录重建索引（索引坏了或丢了时用）。 */
    fun rebuildIndex(): Int {
        val found = scanEntries()
        writeIndex(found.map { Meta(it.id, it.createdAt, it.title) })
        return found.size
    }

    // ---- 内部 ----

    private data class Meta(val id: String, val createdAt: Long, val title: String)

    private fun readIndex(): List<Meta> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            Json.parseToJsonElement(indexFile.readText()).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Meta(
                    id = id,
                    createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun writeIndex(meta: List<Meta>) {
        root.mkdirs()
        indexFile.writeText(
            buildJsonArray {
                meta.forEach { m ->
                    add(
                        buildJsonObject {
                            put("id", m.id)
                            put("createdAt", m.createdAt)
                            put("title", m.title)
                        }
                    )
                }
            }.toString()
        )
    }

    private fun scanEntries(): List<MemoryEntry> {
        if (!entriesDir.isDirectory) return emptyList()
        return entriesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.mapNotNull { f ->
            val id = f.name.removeSuffix(".md")
            val body = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val createdAt = id.substringBefore('-').toLongOrNull()?.times(1000L) ?: f.lastModified()
            val title = body.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.take(80).orEmpty()
            MemoryEntry(id = id, createdAt = createdAt, title = title, summary = body)
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    private fun dateOf(epochMillis: Long): String {
        val d = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        return d.format(java.util.Date(epochMillis))
    }

    companion object {
        const val INDEX_FILE = "index.json"
        const val ENTRIES_DIR = "entries"

        /** 按时间生成一个条目 id（同一毫秒内不重复）。 */
        fun newId(now: Long): String {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            return d.format(java.util.Date(now))
        }
    }
}
