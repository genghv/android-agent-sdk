package ai.agentkernel.memory

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import ai.agentkernel.text.TextPatchException
import ai.agentkernel.text.TextPatcher
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 长期记忆的**命令入口** —— **SDK 自制的命令**（前缀 `agent-`）。
 *
 * 记忆本来是「只增不改」的归档产物；现在 agent 自己能增删改查了。
 *
 * ## 为什么不是让 agent 用终端 `cat` / `grep`
 *
 * 那些文件确实在磁盘上，但**布局是实现细节**：`index.json` 的字段名、
 * `entries/` 的目录名、id 的命名规则 —— 这些一变，agent 写死的命令就全废了。
 * 做成一条命令之后：
 *
 * - agent 只认 `agent-memory`，磁盘布局改了它也不知道；
 * - 输出是**结构化 JSON**（id / 标题 / 字数），模型能直接拿去用；
 * - 增删改有**明确的成功/失败语义**（失败抛 [CapabilityFailure]，退出码 1），
 *   不会像 `rm` 那样「路径拼错了也返回 0」。
 *
 * ## 为什么只做**一条**命令
 *
 * 拆成 `agent-memory-list` / `agent-memory-add` / … 六条，提示词里就多六行。
 * 而后端对客户端 system 段有 2000 字符上限（真机实测设备提示词已占 1809）——
 * 每多一行都在挤占那条上限。所以合成一条带子命令的 `agent-memory`，
 * 用法只占一行。
 *
 * ## 命令
 *
 * ```
 * agent-memory list
 * agent-memory get <id>
 * agent-memory add "<正文>"
 * agent-memory edit <id> "<正文>"
 * agent-memory patch <id> <补丁规格>
 * agent-memory delete <id>
 * agent-memory search "<关键词>"
 * ```
 */
class AgentMemoryCapabilities(private val store: MemoryStore) {

    val capabilities: List<DeviceCapability> = listOf(MemoryCapability(store))
}

/** `agent-memory` —— 长期记忆的增删改查。 */
private class MemoryCapability(private val store: MemoryStore) : DeviceCapability {
    override val name: String = "memory"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String =
        "agent-memory <list|get <id>|add \"<正文>\"|edit <id> \"<正文>\"|patch <id> <补丁规格>|delete <id>|search \"<关键词>\">"

    /**
     * 短用法 —— 只列子命令名，不列参数形态。
     *
     * 完整用法一百多字符，而提示词「核心模式」的预算只有两千字符：
     * 参数形态写在这里，核心模式直接装不下；删掉这条命令更糟（模型压根不知道有它）。
     * ⇒ 核心模式给短用法，完整形态让模型按需问 `agent-memory` 或查技能文档。
     */
    override val shortUsage: String = "agent-memory <list|get|add|edit|patch|delete|search>"

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: throw CapabilityFailure("用法：$usage")
        return when (action) {
            "list" -> listAll()
            "get" -> get(args.getOrNull(1))
            "add" -> add(args.drop(1))
            "edit" -> edit(args.getOrNull(1), args.drop(2))
            "patch" -> patch(args.getOrNull(1), args.drop(2))
            "delete" -> delete(args.getOrNull(1))
            "search" -> search(args.drop(1))
            else -> throw CapabilityFailure("不认识的子命令「$action」。用法：$usage")
        }
    }

    private fun listAll(): String {
        val entries = store.list()
        val entriesJson = buildJsonArray {
            entries.forEach { e ->
                add(
                    buildJsonObject {
                        put("id", e.id)
                        put("createdAt", e.createdAt)
                        put("title", e.title)
                        put("chars", e.chars)
                    }
                )
            }
        }
        return okJson {
            put("count", entries.size)
            put("entries", entriesJson)
        }
    }

    private fun get(id: String?): String {
        val target = id ?: throw CapabilityFailure("用法：$usage")
        val entry = store.list().firstOrNull { it.id == target }
            ?: throw CapabilityFailure("没有这条记忆（id=$target）。先 `agent-memory list` 看有哪些 id。")
        return okJson {
            put("id", entry.id)
            put("title", entry.title)
            put("createdAt", entry.createdAt)
            put("summary", entry.summary)
        }
    }

    private fun add(rest: List<String>): String {
        val body = rest.joinToString(" ").trim()
        if (body.isEmpty()) throw CapabilityFailure("用法：$usage")
        val now = System.currentTimeMillis()
        val stored = store.append(
            MemoryEntry(
                id = MemoryStore.newId(now),
                createdAt = now,
                title = store.titleOf(body).ifBlank { "一次记录" },
                summary = body,
            )
        )
        return okJson {
            put("id", stored.id)
            put("title", stored.title)
            put("chars", stored.chars)
        }
    }

    private fun edit(id: String?, rest: List<String>): String {
        val target = id ?: throw CapabilityFailure("用法：$usage")
        val body = rest.joinToString(" ").trim()
        if (body.isEmpty()) throw CapabilityFailure("用法：$usage")
        val updated = store.edit(target, body)
            ?: throw CapabilityFailure("没有这条记忆（id=$target）。先 `agent-memory list` 看有哪些 id。")
        return okJson {
            put("id", updated.id)
            put("title", updated.title)
            put("chars", updated.chars)
        }
    }

    /**
     * 行级改某条记忆 —— **只贴改动部分**，不用把整条正文重打一遍。
     *
     * ## 为什么整条 `edit` 不够用
     *
     * 记忆正文动辄几十行；让模型为了改一行把全文重新输出一遍，
     * 既费 token，又容易在重贴时把**没想改的行**也改了。
     * `patch` 让模型只说「哪一行换成什么」，改完回一段 **diff** 给它自己核对。
     *
     * ## 参数怎么收
     *
     * 规格可能被拆成**好几个参数**（`replace-lines 3 4 新内容` 就是 4 个词），
     * 也可能整体带引号（`"replace-block|旧|新"`）——
     * 所以这里把 id 之后的参数**用空格拼回一个规格串**，再交给 [TextPatcher.parse]。
     * 拼的时候 `|` 原样保留（`replace-block` 靠它分锚点和新内容）。
     *
     * ## 失败怎么区分
     *
     * - 没有这条 ⇒ 「没有这条记忆（id=…）」；
     * - 规格写错 ⇒ [TextPatcher.parse] 抛的 [TextPatchException] 原话透传
     *   （模型靠它自我纠正，所以**不改写**它，只补一句用法）；
     * - 锚点 0 处 / 命中多处 / 行号越界 ⇒ 存储层只说「没成」，
     *   这里用同一份补丁本地跑一遍，把带行号的失败原因带出来。
     */
    private fun patch(id: String?, rest: List<String>): String {
        val target = id ?: throw CapabilityFailure("用法：$usage")
        val spec = rest.joinToString(" ").trim()
        if (spec.isEmpty()) throw CapabilityFailure("用法：$usage")
        val textPatch = try {
            TextPatcher.parse(spec)
        } catch (e: TextPatchException) {
            throw CapabilityFailure("补丁规格不对：${e.message}。用法：$usage")
        }
        val original = store.readBody(target)
            ?: throw CapabilityFailure("没有这条记忆（id=$target）。先 `agent-memory list` 看有哪些 id。")
        val applied = store.patch(target, textPatch)
        if (applied == null) {
            // 存储层只说「没成」—— 原因（锚点 0 处 / 命中多处 / 行号越界）得说清楚，
            // 否则模型只能瞎猜。用同一份补丁本地跑一遍，把 TextPatchException 的原话带出来。
            val reason = runCatching { TextPatcher.apply(original, textPatch) }
                .exceptionOrNull()
                ?.message
                ?: "补丁没能应用"
            throw CapabilityFailure("补丁没打上：$reason")
        }
        val (entry, diff) = applied
        return okJson {
            put("id", entry.id)
            put("chars", entry.chars)
            put("diff", diff)
        }
    }

    private fun delete(id: String?): String {
        val target = id ?: throw CapabilityFailure("用法：$usage")
        val deleted = store.delete(target)
        if (!deleted) throw CapabilityFailure("没有这条记忆（id=$target）。先 `agent-memory list` 看有哪些 id。")
        return okJson {
            put("deleted", true)
            put("id", target)
        }
    }

    private fun search(rest: List<String>): String {
        val keyword = rest.joinToString(" ").trim()
        if (keyword.isEmpty()) throw CapabilityFailure("用法：$usage")
        val hits = store.search(keyword)
        val hitsJson = buildJsonArray {
            hits.forEach { e ->
                add(
                    buildJsonObject {
                        put("id", e.id)
                        put("title", e.title)
                        put("chars", e.chars)
                    }
                )
            }
        }
        return okJson {
            put("count", hits.size)
            put("keyword", keyword)
            put("entries", hitsJson)
        }
    }
}
