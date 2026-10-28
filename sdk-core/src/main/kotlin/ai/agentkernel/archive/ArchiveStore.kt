package ai.agentkernel.archive

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatMessage
import ai.agentkernel.protocol.ChatMessageCodec
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.ToolMessage
import ai.agentkernel.protocol.UserMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * 会话**分段归档**的本地持久化 —— 一堆普通文件。
 *
 * ## 落在哪儿
 *
 * `<agentRoot>/var/archive/`：
 * ```
 * <agentRoot>/var/archive/
 *   ├── index.json              ← 索引（每段一行元信息，新→旧）
 *   └── segments/
 *       └── 20261029-0012.json  ← 一段一份：元信息 + 原始消息
 * ```
 *
 * ## 段与段之间有链
 *
 * 每段记 `prevId`（**更早**的一段）/ `nextId`（**更晚**的一段）——
 * 前端往上滚时顺着 `prevId` 走一段停一段，**不需要一次全量拉**。
 *
 * 链的方向要记清：**createdAt 大的段 = 内容更晚的段**
 * （第一次压缩归档的是最早的那批，第二次压缩归档的是更晚的那批）。
 * 索引按「新的在前」排 ⇒ `list()` 的第一条就是**内容最新**的那段。
 *
 * ## ★ 链是**推导**出来的，不是两处各写一遍
 *
 * 段文件里**不存** `prevId`/`nextId` —— 只存这一段自己的东西
 * （id / 时间 / 类型 / 标题 / 摘要 / 条数 / 字数）。
 * 链永远由「段的时间顺序」现算（见 [linkChain]）。
 *
 * 为什么不存：**存两处就迟早会对不上**。
 * 早期版本把 `nextId` 只写进索引、没回写段文件 —— 索引一坏（解析失败就从
 * `segments/` 重建），链就全丢了，而正文还在。
 * 推导式版本没有这个问题：索引坏不坏都不影响链。
 *
 * ⇒ 索引其实只是**缓存**（列��快一点）。正文才是本体。
 *
 * ## 排序
 *
 * 列表按「新的在前」。时间完全相同（同一毫秒追加多段）时按 id 兜底，
 * 所以 id 里那段序号是**补零**的（`-002`），字典序 == 追加顺序。
 */
class ArchiveStore(private val root: File) {

    private val indexFile = File(root, INDEX_FILE)
    private val segmentsDir = File(root, SEGMENTS_DIR)

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    // ---- 写 ----

    /**
     * 追加一段（一次压缩 / 一次闲置归档 = 一段）。
     *
     * @param kind [ArchiveSegmentMeta.KIND_COMPACT] / [ArchiveSegmentMeta.KIND_IDLE]。
     * @param title 一句话标题。
     * @param summary 这一段的摘要（没有就 null）。
     * @param messages 这一段的**原始消息**（原文）。
     * @return 新段的元信息（链已按当前状态填好）。
     *
     * ⚠️ 返回的是**快照**：之后再追加别的段，这份快照里的 `nextId` 就过期了
     * （早段的 `nextId` 会指向新段）。要最新状态就去 [meta] / [list] 读。
     */
    fun append(
        kind: String,
        title: String,
        summary: String?,
        messages: List<ChatMessage>,
    ): ArchiveSegmentMeta {
        val now = System.currentTimeMillis()
        val existing = readIndex()
        val id = newId(now, takenIds(existing))
        val meta = ArchiveSegmentMeta(
            id = id,
            createdAt = now,
            kind = kind,
            title = title,
            summary = summary,
            prevId = null,   // 下面由 linkChain 填
            nextId = null,
            messageCount = messages.size,
            chars = charsOf(messages),
        )
        writeSegment(meta, messages)
        // 整份索引重写：链是推导出来的，重写一次就把链刷新了。
        writeIndex(linkChain(listOf(meta) + existing))
        return meta
    }

    // ---- 读 ----

    /**
     * 列出归档段，**新的在前**。
     *
     * @param limit 最多返回几段。
     * @param before 游标：只返回**比它更早**的段（前端往上滚时传上一段的 id）。
     *   null = 从最新的一段开始。
     */
    fun list(limit: Int = 20, before: String? = null): List<ArchiveSegmentMeta> {
        if (limit <= 0) return emptyList()
        val all = readIndex()
        val start = if (before == null) {
            0
        } else {
            val i = all.indexOfFirst { it.id == before }
            if (i < 0) 0 else i + 1
        }
        return all.drop(start).take(limit)
    }

    /** 读一段的完整内容（原文 + 元信息）；没有就 null。 */
    fun read(id: String): ArchiveSegment? {
        val file = File(segmentsDir, "$id.json")
        if (!file.isFile) return null
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        // ★ 元信息从**索引**取（链是推导的），不从段文件取 ——
        //   段文件里存的是「这一段自己」的东西，不含链。
        val meta = meta(id) ?: return null
        val messages = root["messages"]?.jsonArray?.let { ChatMessageCodec.fromJson(it) } ?: emptyList()
        return ArchiveSegment(meta, messages)
    }

    /** 读一段的元信息（不读正文 —— 列表页用）。 */
    fun meta(id: String): ArchiveSegmentMeta? = readIndex().firstOrNull { it.id == id }

    /** 一共有多少段。 */
    fun size(): Int = readIndex().size

    /** 归档目录（给宿主/agent 看路径用）。 */
    fun rootDir(): File = root

    // ---- 内部 ----

    /**
     * 读索引；索引没了/坏了 → 从正文目录重建。
     *
     * 两条路都返回**带链**的结果（链是推导的）。
     */
    private fun readIndex(): List<ArchiveSegmentMeta> {
        val fromFile = readIndexFile()
        if (fromFile.isNotEmpty()) return fromFile
        return rebuildIndex()
    }

    private fun readIndexFile(): List<ArchiveSegmentMeta> {
        if (!indexFile.isFile) return emptyList()
        val root = runCatching { json.parseToJsonElement(indexFile.readText()).jsonObject }.getOrNull() ?: return emptyList()
        val arr = root["segments"]?.jsonArray ?: return emptyList()
        val metas = arr.mapNotNull { el ->
            runCatching { metaFromJson(el.jsonObject) }.getOrNull()
        }
        return linkChain(metas)
    }

    private fun rebuildIndex(): List<ArchiveSegmentMeta> {
        val files = segmentsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val metas = files.mapNotNull { f ->
            val root = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return@mapNotNull null
            runCatching { metaFromJson(root["meta"]?.jsonObject ?: return@mapNotNull null) }.getOrNull()
        }
        val linked = linkChain(metas)
        if (linked.isNotEmpty()) writeIndex(linked)
        return linked
    }

    /**
     * 排序 + 串链 —— **链的唯一来源**。
     *
     * 输入乱序没关系：先按「时间新的在前」排，时间一样再按 id 倒序
     * （id 里的序号是补零的 ⇒ 字典序 == 追加顺序）。
     * 然后：
     * - `nextId` = 列表里**前一条**（更晚的一段）；
     * - `prevId` = 列表里**后一条**（更早的一段）。
     */
    private fun linkChain(metas: List<ArchiveSegmentMeta>): List<ArchiveSegmentMeta> {
        if (metas.isEmpty()) return emptyList()
        val sorted = metas.sortedWith(
            compareByDescending<ArchiveSegmentMeta> { it.createdAt }.thenByDescending { it.id }
        )
        return sorted.mapIndexed { i, m ->
            m.copy(
                nextId = sorted.getOrNull(i - 1)?.id,
                prevId = sorted.getOrNull(i + 1)?.id,
            )
        }
    }

    private fun writeIndex(metas: List<ArchiveSegmentMeta>) {
        root.mkdirs()
        val body = buildJsonObject {
            put("segments", buildJsonArray { metas.forEach { add(metaToJson(it)) } })
        }
        indexFile.writeText(body.toString())
    }

    private fun writeSegment(meta: ArchiveSegmentMeta, messages: List<ChatMessage>) {
        segmentsDir.mkdirs()
        val body = buildJsonObject {
            put("meta", metaToJson(meta))
            put("messages", ChatMessageCodec.toJson(messages))
        }
        File(segmentsDir, "${meta.id}.json").writeText(body.toString())
    }

    private fun metaToJson(m: ArchiveSegmentMeta): JsonObject = buildJsonObject {
        put("id", m.id)
        put("createdAt", m.createdAt)
        put("kind", m.kind)
        put("title", m.title)
        m.summary?.let { put("summary", it) }
        m.prevId?.let { put("prevId", it) }
        m.nextId?.let { put("nextId", it) }
        put("messageCount", m.messageCount)
        put("chars", m.chars)
    }

    private fun metaFromJson(o: JsonObject): ArchiveSegmentMeta = ArchiveSegmentMeta(
        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
        createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: ArchiveSegmentMeta.KIND_COMPACT,
        title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
        summary = o["summary"]?.jsonPrimitive?.contentOrNull,
        prevId = o["prevId"]?.jsonPrimitive?.contentOrNull,
        nextId = o["nextId"]?.jsonPrimitive?.contentOrNull,
        messageCount = o["messageCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        chars = o["chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    )

    /**
     * 已经用掉的 id —— **索引和正文目录都算**。
     *
     * 只看索引会漏：索引坏了的时候新段可能和正文里的重名 ⇒ 覆盖掉历史。
     */
    private fun takenIds(indexed: List<ArchiveSegmentMeta>): Set<String> {
        val ids = LinkedHashSet<String>()
        indexed.forEach { ids.add(it.id) }
        segmentsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            ids.add(f.name.removeSuffix(".json"))
        }
        return ids
    }

    /**
     * 同秒追加多条不会互相覆盖。
     *
     * 后缀**补零**（`-002`）：同一秒里追加第 10 条时，`-010` 仍然排在 `-009` 后面 ——
     * 字典序 == 追加顺序，重建索引时的排序才不会错。
     */
    private fun newId(now: Long, taken: Set<String>): String {
        val base = "seg-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(now))
        if (base !in taken) return base
        var i = 2
        while ("$base-" + i.toString().padStart(3, '0') in taken) i++
        return "$base-" + i.toString().padStart(3, '0')
    }

    private fun charsOf(messages: List<ChatMessage>): Int = messages.sumOf { m ->
        when (m) {
            is UserMessage -> m.content.length
            is AssistantMessage -> (m.content?.length ?: 0) +
                m.toolCalls.sumOf { it.name.length + it.arguments.length }
            is ToolMessage -> m.content.length
            is SystemMessage -> m.content.length
            else -> 0
        }
    }

    companion object {
        private const val INDEX_FILE = "index.json"
        private const val SEGMENTS_DIR = "segments"
    }
}
