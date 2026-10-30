package ai.agentkernel.memory

import ai.agentkernel.text.TextPatch
import ai.agentkernel.text.TextPatcher
import kotlinx.serialization.json.Json
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
 * 记忆条目的**种类**。
 *
 * 分两类不是为了分类好看，而是两类的**写入语义根本不同**：
 * - 日常条目是**追加**的（agent 自己记的，越记越多）；
 * - 每日摘要是**替换**的（同一天只有一条，新的摘要盖掉旧的）。
 *
 * 为什么不统一成追加：一天聊十次就有十条摘要，十条摘要全进提示词 ⇒
 * 每轮请求都在为重复内容付钱，而且模型看到的是十份「今天聊过什么」的残片，
 * 不如一份完整的当天摘要。
 */
enum class MemoryKind {
    /** agent 自己记的日常条目（追加式，摘要**不替换**它）。 */
    NOTE,

    /** 每日摘要槽（每天一条，新摘要**替换**当天那条）。 */
    DAILY,
}

/**
 * 一条**长期记忆**。
 *
 * @param id 稳定标识（同时是文件名的一部分）。
 * @param createdAt 归档时刻（epoch 毫秒）。
 * @param title 一句话标题（给列表看）。
 * @param summary 摘要正文（进提示词的就是它）。
 * @param kind 种类；默认 NOTE（老数据没有这个字段，按 NOTE 读）。
 */
data class MemoryEntry(
    val id: String,
    val createdAt: Long,
    val title: String,
    val summary: String,
    /** 种类；默认 NOTE（老数据没有这个字段，按 NOTE 读）。 */
    val kind: MemoryKind = MemoryKind.NOTE,
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
 *       ├── 20261028-1432.md  ← 一条一份，纯 markdown
 *       └── daily-20261029.md ← 每日摘要槽（每天一个）
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
 * ## 两类记忆
 *
 * | | 种类 | 写入语义 | id |
 * |---|---|---|---|
 * | 日常条目 | [MemoryKind.NOTE] | 追加 | 时间戳 |
 * | 每日摘要槽 | [MemoryKind.DAILY] | **同一天替换** | `daily-<yyyyMMdd>` |
 *
 * 每日摘要槽的 id 是**可预测**的（`daily-20261029`）—— 「替换」因此就是
 * 「覆盖同一个文件」，不用先查再删，也不会出现两条同一天的摘要。
 *
 * ## 容量上限
 *
 * 记忆是**要进提示词**的，而提示词每轮都要重发 ⇒ 不设上限的话，
 * 代价是**每轮都付**。所以单条 / 总量 / 条数三条硬上限都是**机械规则**
 * （超了截断或删最旧的，不问 AI）—— 见 [applyLimits]。
 *
 * ## 索引坏了怎么办
 *
 * 索引只是「哪些条目存在」的快捷方式。索引解析失败时，
 * [rebuildIndex] 可以从 `entries/` 目录把索引重建出来 —— 正文才是本体，索引是缓存。
 */
class MemoryStore(private val root: File) {

    private val entriesDir: File get() = File(root, ENTRIES_DIR)
    private val indexFile: File get() = File(root, INDEX_FILE)

    /** 单条正文上限（<= 0 = 不限）。 */
    private var maxEntryChars: Int = 0

    /** 全部条目正文的总字符上限（<= 0 = 不限）。 */
    private var maxTotalChars: Int = 0

    /** 条数上限（<= 0 = 不限）。 */
    private var maxEntries: Int = 0

    // ---- 读 ----

    /** 列出全部记忆（新的在前）。 */
    fun list(): List<MemoryEntry> {
        val meta = readIndex()
        if (meta.isNotEmpty()) {
            return meta.mapNotNull { m ->
                val body = readBody(m.id) ?: return@mapNotNull null
                MemoryEntry(
                    id = m.id,
                    createdAt = m.createdAt,
                    title = m.title,
                    summary = body,
                    kind = m.kind,
                )
            }.sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenByDescending { it.id })
        }
        // 索引没了就扫目录重建 —— 正文才是本体。
        return scanEntries()
    }

    /** 读某条记忆的正文。 */
    fun readBody(id: String): String? {
        val file = File(entriesDir, "$id.md")
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    // ---- 写 ----

    /**
     * 追加一条记忆（先写正文，再更新索引）。
     *
     * DAILY 走**替换**语义（同一个 id 覆盖同一个文件，不是多一条）；
     * NOTE 才是追加（同 id 撞上了就加后缀，别把上一条覆盖掉）。
     */
    fun append(entry: MemoryEntry): MemoryEntry {
        if (entry.kind == MemoryKind.DAILY) {
            // 每日摘要槽是「一个位置」，不是「一条内容」—— 同 id 覆盖。
            return writeSlot(entry.id, entry.createdAt, entry.title, entry.summary, MemoryKind.DAILY)
        }
        entriesDir.mkdirs()
        // 同一秒内追加两条时 id 会撞上 —— 加个后缀，别把上一条覆盖掉。
        var id = entry.id
        var n = 1
        while (File(entriesDir, "$id.md").exists()) {
            id = "${entry.id}-$n"
            n++
        }
        return writeSlot(id, entry.createdAt, entry.title, entry.summary, MemoryKind.NOTE)
    }

    /**
     * ★ 写当天的每日摘要槽：同一天已有 DAILY 条目 ⇒ **替换**它（不是追加）。
     *
     * 槽位 id 是**可预测**的（`daily-<dateKey>`）—— 「替换当天那条」因此就是
     * 「覆盖同一个文件」：不用先查再删，也不会漏删出两条同一天的摘要。
     *
     * @param dateKey 日期键（形如 `20261029`）；空 ⇒ 用今天的。
     * @param title 今天的标题（整条替换 —— 标题也换新的，不留旧的）。
     * @param summary 今天的摘要正文。
     * @return 写完的那条（createdAt 是**这次**写入的时刻 —— 它是「今天的摘要」）。
     */
    fun upsertDaily(dateKey: String, title: String, summary: String): MemoryEntry {
        val key = dateKey.trim().ifBlank { dailyKey(System.currentTimeMillis()) }
        val now = System.currentTimeMillis()
        return writeSlot(
            id = dailyId(key),
            createdAt = now,
            title = title.ifBlank { titleOf(summary) },
            summary = summary,
            kind = MemoryKind.DAILY,
        )
    }

    /**
     * ★ 按日期键（形如 `20261029`）写每日摘要；dateKey 决定槽位（每天一个）。
     *
     * 用**本地时区**算「哪一天」—— 用户说的「今天」是本地日历上的今天。
     */
    fun dailyKey(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(epochMillis))

    /**
     * 每日摘要槽的 id —— **可预测**，别的模块要按它找今天的槽位。
     */
    fun dailyId(dateKey: String): String = "$DAILY_ID_PREFIX$dateKey"

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
        val meta = readIndex().ifEmpty { scanMetas() }
        val old = meta.firstOrNull { it.id == id }
        // 索引丢了也能改 —— id 前缀就是时间戳，拿它当创建时间兜底。
        val createdAt = old?.createdAt ?: inferCreatedAt(id, file)
        val kind = old?.kind ?: kindOf(id)
        val title = newTitle ?: titleOf(newSummary)
        return writeSlot(id, createdAt, title, newSummary, kind)
    }

    /**
     * ★ 行级改某条记忆（锚点/行号），返回改完的那条 + diff 描述。
     *
     * 为什么要有行级：整条重写会让 agent 为了改一句话**重打一遍几百字**，
     * 而记忆正文往往很长（一次话题的来龙去脉）。改一个块就够了。
     *
     * 失败（条目不存在 / 锚点找不到 / 行号越界）⇒ 返回 null，调用方要能区分。
     */
    fun patch(id: String, patch: TextPatch): Pair<MemoryEntry, String>? {
        val original = readBody(id) ?: return null
        // 补丁失败（锚点找不到 / 越界）由 TextPatcher 抛异常 —— 这里统一变成 null。
        val patched = try {
            TextPatcher.apply(original, patch)
        } catch (e: Exception) {
            return null
        }
        val meta = readIndex()
        val old = meta.firstOrNull { it.id == id }
        val file = File(entriesDir, "$id.md")
        val createdAt = old?.createdAt ?: inferCreatedAt(id, file)
        val kind = old?.kind ?: kindOf(id)
        // 标题按新正文的第一行走（和 [edit] 同一套规则）—— 改了首行，标题不能还是旧的。
        val stored = writeSlot(id, createdAt, titleOf(patched), patched, kind)
        val diff = runCatching { TextPatcher.describe(original, stored.summary) }.getOrElse { "" }
        return stored to diff
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
     * agent 想找「之前聊过后端那次」时用这个 —— 比自己 grep 目录稳
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
     * 生成进提示词的**记忆块**（两块：今天的摘要 + 日常记忆）。
     *
     * ```
     * ## 你之前和用户聊过什么（长期记忆）
     *
     * ### 今天的摘要
     * <正文>
     *
     * ### 你自己记下的事
     * - [10-29] 标题
     *   正文行
     *   正文行
     * ```
     *
     * 带的是**正文**，不是只有标题：模型得知道当时聊出了什么结论，
     * 才知道现在该不该沿用那个结论。
     *
     * ## 预算不够时砍谁
     *
     * **先砍「你自己记下的事」，再砍摘要正文** —— 日常条目能用
     * `agent-memory search` 取回来，摘要正文是「今天聊了什么」的锚。
     *
     * @param maxChars 整块预算（摘要 + 日常共用）。<= 0 = 不带记忆。
     * @return 一条都没有时返回 null（不注入任何内容）。
     */
    fun digest(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val all = list()
        if (all.isEmpty()) return null

        // 「今天的摘要」= 最新的那个每日槽（upsertDaily 每次都更新当天那条 ⇒ 它就是今天的）。
        val daily = all.filter { it.kind == MemoryKind.DAILY }
            .maxWithOrNull(compareBy<MemoryEntry>({ it.createdAt }, { it.id }))
        // 日常条目：新的在前。
        val notes = all.filter { it.kind == MemoryKind.NOTE }
            .sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenByDescending { it.id })

        val dailyBody = daily?.summary?.trim()?.takeIf { it.isNotEmpty() }
        if (dailyBody == null && notes.isEmpty()) return null

        // 每一段之间空一行（读起来是三段，不是一坨）。
        val sections = mutableListOf<String>()

        // ① 「今天的摘要」优先占位（日常条目能让位，它不能让）。
        var dailyText = dailyBody
        if (dailyText != null) {
            val overhead = SECTION_GAP.length + DIGEST_DAILY_HEADING.length + 1
            if (DIGEST_HEADER.length + overhead + dailyText.length > maxChars) {
                // 摘要正文自己就超预算 ⇒ 砍它（日常条目已经先让位了）。
                val room = maxChars - DIGEST_HEADER.length - overhead
                val keep = (room - TRUNCATION_MARKER.length).coerceAtLeast(0)
                if (keep <= 0) return null
                dailyText = dailyText.take(keep) + TRUNCATION_MARKER
            }
            sections += DIGEST_DAILY_HEADING + "\n" + dailyText
        }

        // ② 剩下的预算给「你自己记下的事」（旧的先让位）。
        // 已占用的字符 = 大标题 + 每段前面的空行 + 各段正文。
        val used = DIGEST_HEADER.length +
            SECTION_GAP.length * sections.size +
            sections.sumOf { it.length }
        val notesText = fitNotes(notes, maxChars - used)
        if (notesText != null) sections += DIGEST_NOTES_HEADING + "\n" + notesText

        val text = (listOf(DIGEST_HEADER) + sections).joinToString(SECTION_GAP)
        // 只剩一个空标题 ⇒ 不注入（给模型一个空壳没有意义）。
        return if (text.length <= DIGEST_HEADER.length) null else text
    }

    /**
     * ★ 容量上限：单条正文 / 总字符 / 条数。
     *
     * 记忆**要进提示词**，而提示词每轮都要重发 ⇒ 不设上限的话，代价是**每轮都付**。
     *
     * 三条都是**机械规则**（超了删最旧的，不问 AI ——
     * 让模型决定删什么 = 不确定性：它可能不删、可能删错、每次还不一样）：
     *
     * 1. 单条正文超 [maxEntryChars] ⇒ **截断**并追加 `…（已截断）`；
     * 2. 总字符超 [maxTotalChars] ⇒ 删**最旧**的条目；
     * 3. 条数超 [maxEntries] ⇒ 删**最旧**的条目。
     *
     * ★ **每日摘要槽永远不删** —— 它是「今天聊了什么」的锚，
     * 删了模型就不知道当下的上下文（哪怕它只剩一条、哪怕它超了总量上限）。
     *
     * 记下这三个数之后，**每次写入**都会执行一遍（追加 / 替换 / 改 / 补丁都算）。
     * 调用它本身也会**立刻**按新上限收拾一遍已有数据 ——
     * 上限是硬线，不是建议值。
     *
     * @param maxEntryChars 单条正文上限；<= 0 = 不限。
     * @param maxTotalChars 全部条目总字符上限；<= 0 = 不限。
     * @param maxEntries 条数上限；<= 0 = 不限。
     */
    fun applyLimits(maxEntryChars: Int, maxTotalChars: Int, maxEntries: Int) {
        this.maxEntryChars = maxEntryChars
        this.maxTotalChars = maxTotalChars
        this.maxEntries = maxEntries
        writeIndex(enforceLimits(readIndex().ifEmpty { scanMetas() }))
    }

    /** 清空全部记忆。 */
    fun clear() {
        entriesDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }

    /** 从 `entries/` 目录重建索引（索引坏了或丢了时用）。 */
    fun rebuildIndex(): Int {
        val found = scanMetas()
        writeIndex(found)
        return found.size
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

    // ---- 内部 ----

    private data class Meta(val id: String, val createdAt: Long, val title: String, val kind: MemoryKind)

    /**
     * 写一条（正文 + 索引），并执行一遍容量上限。
     *
     * 所有写路径都走这里 —— 上限只在一个地方执行，才不会出现
     * 「append 截断了、edit 没截断」这种漏网的情况。
     */
    private fun writeSlot(
        id: String,
        createdAt: Long,
        title: String,
        summary: String,
        kind: MemoryKind,
    ): MemoryEntry {
        entriesDir.mkdirs()
        val body = truncate(summary)
        File(entriesDir, "$id.md").writeText(body)
        val meta = readIndex().ifEmpty { scanMetas() }.filterNot { it.id == id } +
            Meta(id = id, createdAt = createdAt, title = title, kind = kind)
        writeIndex(enforceLimits(meta))
        return MemoryEntry(id = id, createdAt = createdAt, title = title, summary = body, kind = kind)
    }

    /**
     * 把日常条目塞进剩下的预算里。
     *
     * 整块放不下时**只留标题行**（好过什么都不给 —— 模型至少知道「有这么件事」），
     * 再放不下就停（后面的更旧，更该让位）。
     *
     * 一条都放不下时**连小标题都不带** —— 留个空标题只会被模型当成「没有内容」。
     */
    private fun fitNotes(notes: List<MemoryEntry>, budget: Int): String? {
        if (notes.isEmpty() || budget <= 0) return null
        // 这一段自己的开销：段间空行 + 小标题 + 小标题后的换行。
        val overhead = SECTION_GAP.length + DIGEST_NOTES_HEADING.length + 1
        if (overhead > budget) return null
        val picked = mutableListOf<String>()
        var used = 0
        for (e in notes) {
            val block = noteBlock(e)
            val cost = block.length + (if (picked.isEmpty()) 0 else 1)
            if (overhead + used + cost <= budget) {
                picked += block
                used += cost
                continue
            }
            // 整块放不下 ⇒ 只留标题行（好过什么都不给 —— 模型至少知道「有这么件事」）。
            val head = noteHead(e)
            val headCost = head.length + (if (picked.isEmpty()) 0 else 1)
            if (overhead + used + headCost <= budget) picked += head
            break
        }
        if (picked.isEmpty()) return null
        return picked.joinToString("\n")
    }

    /** 日常条目在摘要块里的标题行：`- [10-29] 标题`。 */
    private fun noteHead(e: MemoryEntry): String =
        "- [" + dateOf(e.createdAt) + "] " + e.title.ifBlank { e.summary.take(30) }

    /** 日常条目在摘要块里的整块（标题行 + 缩进的正文行）。 */
    private fun noteBlock(e: MemoryEntry): String = buildString {
        append(noteHead(e))
        e.summary.lines().filter { it.isNotBlank() }.forEach {
            append('\n').append("  ").append(it.trim())
        }
    }

    /**
     * 执行三条容量上限，返回收拾干净之后的索引。
     *
     * 淘汰只删**最旧**的条目，而且**每日摘要槽永远不删**。
     * 删条目 = 删正文文件 + 从索引移除（不能留幽灵条目：
     * 索引里有、正文没了的条目会在下次重建索引时凭空复活）。
     */
    private fun enforceLimits(meta: List<Meta>): List<Meta> {
        if (meta.isEmpty()) return meta
        // ① 单条超限 ⇒ 截断（正文和磁盘上的文件都要跟上）。
        val sizes = HashMap<String, Int>()
        for (m in meta) {
            val body = readBody(m.id) ?: continue // 正文没了 ⇒ 索引里的幽灵条目，清掉
            val clipped = truncate(body)
            if (clipped != body) File(entriesDir, "${m.id}.md").writeText(clipped)
            sizes[m.id] = clipped.length
        }
        var kept = meta.filter { sizes.containsKey(it.id) }
        // ② 总量 / 条数超限 ⇒ 删最旧的（每日摘要槽永远不删）。
        var total = kept.sumOf { sizes[it.id] ?: 0 }
        while ((maxTotalChars > 0 && total > maxTotalChars) ||
            (maxEntries > 0 && kept.size > maxEntries)
        ) {
            val victim = kept.filter { it.kind != MemoryKind.DAILY }
                .minWithOrNull(compareBy<Meta>({ it.createdAt }, { it.id })) ?: break
            File(entriesDir, "${victim.id}.md").delete()
            kept = kept.filterNot { it.id == victim.id }
            total -= (sizes[victim.id] ?: 0)
        }
        return kept
    }

    /**
     * 单条正文截断。
     *
     * ★ 截断**后**仍要满足上限（标记本身算在里头）——
     * 否则「上限」名存实亡：截断完还是超的，等于没截。
     */
    private fun truncate(body: String): String {
        if (maxEntryChars <= 0 || body.length <= maxEntryChars) return body
        if (maxEntryChars <= TRUNCATION_MARKER.length) return body.take(maxEntryChars)
        return body.take(maxEntryChars - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
    }

    /** 从正文目录扫出来的元信息（索引丢了时的兜底）。 */
    private fun scanMetas(): List<Meta> =
        scanEntries().map { Meta(it.id, it.createdAt, it.title, it.kind) }

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
                    // 老索引没有这个字段 ⇒ 按 NOTE 读（向后兼容）。
                    // 只有 daily- 前缀的槽位例外：索引是缓存，正文目录才是本体，
                    // 槽位不能因为索引缺字段就降级成日常条目（那样它会被容量淘汰删掉）。
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull
                        ?.let { name -> runCatching { MemoryKind.valueOf(name) }.getOrNull() }
                        ?: kindOf(id),
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
                            put("kind", m.kind.name)
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
            MemoryEntry(id = id, createdAt = createdAt, title = title, summary = body, kind = kindOf(id))
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * id 前缀是 `daily-` ⇒ 每日摘要槽；否则是日常条目。
     *
     * 靠 id 认种类，是因为**正文文件里没存种类**（正文才是本体，种类是索引里的元数据，
     * 索引丢了还能靠 id 认出来 —— 否则重建索引会把每日槽降级成日常条目，
     * 接着就被容量淘汰删掉了）。
     */
    private fun kindOf(id: String): MemoryKind =
        if (id.startsWith(DAILY_ID_PREFIX)) MemoryKind.DAILY else MemoryKind.NOTE

    /** 索引丢了时的创建时间兜底：id 前缀是时间戳，否则用文件的修改时间。 */
    private fun inferCreatedAt(id: String, file: File): Long =
        id.substringBefore('-').toLongOrNull()?.times(1000L) ?: file.lastModified()

    private fun dateOf(epochMillis: Long): String {
        val d = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        return d.format(java.util.Date(epochMillis))
    }

    companion object {
        const val INDEX_FILE = "index.json"
        const val ENTRIES_DIR = "entries"

        /** 每日摘要槽的 id 前缀。 */
        const val DAILY_ID_PREFIX = "daily-"

        /** 截断标记（正文被削短时标出来，别让人当成完整内容）。 */
        const val TRUNCATION_MARKER = "…（已截断）"

        /** 进提示词的记忆块的小标题（固定文案，W7 的提示词说明要与之一致）。 */
        const val DIGEST_HEADER = "## 你之前和用户聊过什么（长期记忆）"

        /** 每日摘要那一段的小标题。 */
        const val DIGEST_DAILY_HEADING = "### 今天的摘要"

        /** 日常条目那一段的小标题。 */
        const val DIGEST_NOTES_HEADING = "### 你自己记下的事"

        /** 记忆块里两段之间的空行（读起来是三段，不是一坨）。 */
        const val SECTION_GAP = "\n\n"

        /** 按时间生成一个条目 id（同一毫秒内不重复）。 */
        fun newId(now: Long): String {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            return d.format(java.util.Date(now))
        }
    }
}
