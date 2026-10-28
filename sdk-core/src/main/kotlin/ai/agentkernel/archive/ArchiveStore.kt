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
 * ⇒ 索引其实只是**缓存**（列表快一点）。正文才是本体。
 *
 * ## 排序
 *
 * 列表按「新的在前」。时间完全相同（同一毫秒追加多段）时按 id 兜底，
 * 所以 id 里那段序号是**补零**的（`-002`），字典序 == 追加顺序。
 *
 * ## ★ 容量上限（机械规则，不问 AI）
 *
 * 归档段是「被摘要掉的对话原文」—— 每压一次长一段，不设上限的话 App 会一直变大。
 * [applyLimits] 记下两个上限（段数 / 总字符），之后每次 [append] 后**自动淘汰**：
 *
 * - 超段数 ⇒ 删**最旧**的段；超总字符 ⇒ 同样删**最旧**的段，删到不超为止；
 * - ★ **最新的一段永不删**（哪怕只剩它 —— 那是当前正在用的历史）；
 * - 删段 = 删 `segments/<id>.json` + 从索引移除（不留幽灵条目，也不留孤儿文件）；
 * - 删完**链重新推导** —— 链本来就是现算的（[linkChain]），剩下的段自动串好。
 */
class ArchiveStore(private val root: File) {

    private val indexFile = File(root, INDEX_FILE)
    private val segmentsDir = File(root, SEGMENTS_DIR)

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** 段数上限；<= 0 = 不限（默认不限 —— 没设上限就不该偷偷删用户的历史）。 */
    private var maxSegments: Int = 0

    /** 所有段正文的总字符上限；<= 0 = 不限。 */
    private var maxChars: Int = 0

    /**
     * 这个实例**分配过**的 id —— 已经被淘汰掉的段也算。
     *
     * ★ 为什么不能只看「现在还在的段」：段被淘汰之后 id 就空出来了，
     *   同一秒里再追加一段会**复用**这个 id ⇒ 前端拿着旧 id 读回来的是
     *   另一段的内容（而且同毫秒追加的先后顺序也会排错）。
     *   ⇒ id 一旦用过就不再复用。
     */
    private val usedIds = LinkedHashSet<String>()

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
     *
     * ★ 设了容量上限（[applyLimits]）的话，追加完**立刻**淘汰超限的最旧段 ——
     * 刚追加的这一段永远不会被删（它是当前正在用的历史）。
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
        usedIds.add(id)   // ★ 用过的 id 就不再复用（哪怕后来被淘汰掉了）
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
        // ★ 容量上限：追加完**立刻**淘汰超限的最旧段（机械规则，不问 AI）。
        //   删掉的段：正文文件真删 + 索引里不留条目 + 链重新串好。
        val kept = enforceLimits(linkChain(listOf(meta) + existing), protectId = meta.id)
        // 整份索引重写：链是推导出来的，重写一次就把链刷新了。
        writeIndex(kept)
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

    /**
     * 设容量上限：**段数** / **总字符**。<= 0 = 不限。
     *
     * ★ **上限是硬线，设了立刻生效** —— 调用它就按新上限收拾一遍已有的段
     * （超了就删最旧的），之后每次 [append] 再检查一次。
     *
     * 为什么「设了就删」而不是「等下次追加才删」：
     * 宿主把上限调小，意思就是「我不要这么多历史了」——
     * 要是等到下次追加才生效，中间这一段时间 App 仍然是超的。
     * （另一个理由：它得和记忆库那套 [ai.agentkernel.memory.MemoryStore.applyLimits]
     * **语义一致** —— 两个同名方法一个立即生效一个不生效，是个坑。）
     *
     * @param maxSegments 最多留几段；<= 0 = 不限。
     * @param maxChars 所有段正文的总字符上限；<= 0 = 不限。
     */
    fun applyLimits(maxSegments: Int, maxChars: Int) {
        this.maxSegments = maxSegments
        this.maxChars = maxChars
        val existing = readIndex()
        if (existing.isEmpty()) return
        // 整份索引重写：删掉的段不留条目，链也重新串好。
        writeIndex(enforceLimits(linkChain(existing), protectId = null))
    }

    /**
     * 所有归档段的**总字符数**（正文之和）。
     *
     * 口径 = [charsOf]：每段的字数在 [append] 时就按同一套算法算好记在元信息里（`chars`），
     * 这里只是求和 —— 不另写一套口径。
     */
    fun totalChars(): Int = readIndex().sumOf { it.chars }

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

    /**
     * 容量上限：超了就删**最旧**的段（机械规则，不问 AI）。
     *
     * - 段数超 [maxSegments] ⇒ 删最旧的；
     * - 总字符超 [maxChars] ⇒ 同样删最旧的，删到不超为止；
     * - ★ **最新的一段永不删** —— 那是当前正在用的历史。
     *
     * 一次只删一段，删完重新统计 —— 段数和字符是**同一个循环**里的两个条件，
     * 谁超就删谁（删的总是最旧的那一段，所以最后留下的集合是一样的）。
     *
     * @param linked 按「新的在前」排好、带链的段。
     * @param protectId 刚追加的那一段（保护它：它就是当前正在用的历史）。
     * @return 淘汰后剩下的段（**重新串过链**）。
     */
    private fun enforceLimits(linked: List<ArchiveSegmentMeta>, protectId: String?): List<ArchiveSegmentMeta> {
        if (linked.isEmpty()) return linked
        if (maxSegments <= 0 && maxChars <= 0) return linked
        var kept = linked
        while (kept.size > 1) {
            val overCount = maxSegments > 0 && kept.size > maxSegments
            val overChars = maxChars > 0 && kept.sumOf { it.chars } > maxChars
            if (!overCount && !overChars) break
            // 最旧的一段 = 列表的**最后一个**（列表是「新的在前」）。
            // ★ 刚追加的那段、以及按时间算最新的那段，都不删 ——
            //   哪怕它自己就超了字符上限，也留着（那是正在用的历史）。
            val newestId = kept.firstOrNull()?.id
            val victim = kept.lastOrNull { it.id != protectId && it.id != newestId } ?: break
            deleteSegment(victim.id)
            kept = kept.filterNot { it.id == victim.id }
        }
        // ★ 链重新推导：链本来就是现算的（[linkChain]）——
        //   删掉一段之后，剩下的段自动串好（被删段的邻居直接连上）。
        return linkChain(kept)
    }

    /**
     * 删掉一段的**正文文件**（索引里的条目由调用方重写时一并去掉）。
     *
     * 文件是真删 —— 只从索引里拿掉的话，索引一坏 [rebuildIndex] 会把它**捡回来复活**。
     */
    private fun deleteSegment(id: String) {
        val file = File(segmentsDir, "$id.json")
        // 索引里的 id 要是坏了，也不能让删除操作跑到 segments/ 外面去。
        val inside = runCatching { file.canonicalFile.parentFile == segmentsDir.canonicalFile }.getOrDefault(false)
        if (!inside) return
        if (file.isFile) file.delete()
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
     * 已经用掉的 id —— **索引、正文目录、这个实例分配过的都算**。
     *
     * 只看索引会漏：索引坏了的时候新段可能和正文里的重名 ⇒ 覆盖掉历史。
     * 只看「现在还在的」也会漏：被淘汰掉的段 id 不能再用（会「复活」成另一段）。
     */
    private fun takenIds(indexed: List<ArchiveSegmentMeta>): Set<String> {
        val ids = LinkedHashSet<String>()
        ids.addAll(usedIds)
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
