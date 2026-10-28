package ai.agentkernel.workspace

import ai.agentkernel.prefix.PrefixLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 工作区策略的持久化 —— **让 agent 自己能改配额**。
 *
 * 策略落在 `<agentRoot>/var/workspace-policy.json`。
 * 存在就用它（覆盖宿主注入的默认值），不存在就用宿主注入的。
 *
 * 为什么要这个：宿主写死的阈值只是一次性的决定；真正的运行情况是 agent 才知道
 * （它可能就是要存一大份数据）。让 agent 能调，配额才是活的，而不是一个写死的坎。
 */
class WorkspacePolicyStore(private val layout: PrefixLayout) {

    /** 读 agent 改过的策略；没有改过就返回 null（用宿主的）。 */
    fun load(): WorkspacePolicy? {
        val file = file()
        if (!file.isFile) return null
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            WorkspacePolicy(
                quota = parseQuota(root),
                warnRatio = root["warnRatio"]?.jsonPrimitive?.doubleOrNull ?: 0.85,
                keepPatterns = root.strings("keepPatterns") ?: WorkspacePolicy.DEFAULT_KEEP_PATTERNS,
                trashPatterns = root.strings("trashPatterns") ?: WorkspacePolicy.DEFAULT_TRASH_PATTERNS,
                keepMarkerName = root["keepMarkerName"]?.jsonPrimitive?.content ?: ".agent-keep",
                trashMaxAgeDays = root["trashMaxAgeDays"]?.jsonPrimitive?.longOrNull?.toInt() ?: 3,
                useTrash = root["useTrash"]?.jsonPrimitive?.content?.toBoolean() ?: true,
            )
        } catch (_: Exception) {
            // 策略文件坏了不能让 agent 挂掉 —— 退回宿主默认值。
            null
        }
    }

    /** 存下 agent 改过的策略。 */
    fun save(policy: WorkspacePolicy) {
        val file = file()
        file.parentFile?.mkdirs()
        file.writeText(
            buildJsonObject {
                put("quota", buildJsonObject {
                    when (val q = policy.quota) {
                        is Quota.Unlimited -> put("type", "unlimited")
                        is Quota.Bytes -> {
                            put("type", "bytes")
                            put("bytes", q.bytes)
                        }
                        is Quota.PercentOfFreeSpace -> {
                            put("type", "percent_of_free_space")
                            put("percent", q.percent)
                        }
                    }
                })
                put("warnRatio", policy.warnRatio)
                put("keepPatterns", buildJsonArray { policy.keepPatterns.forEach { add(JsonPrimitive(it)) } })
                put("trashPatterns", buildJsonArray { policy.trashPatterns.forEach { add(JsonPrimitive(it)) } })
                put("keepMarkerName", policy.keepMarkerName)
                put("trashMaxAgeDays", policy.trashMaxAgeDays)
                put("useTrash", policy.useTrash)
            }.toString()
        )
    }

    /** agent 改回默认：删掉策略文件即可。 */
    fun clear() {
        file().delete()
    }

    private fun file(): File = File(layout.varDir, POLICY_FILE)

    private fun parseQuota(root: JsonObject): Quota {
        val q = root["quota"]?.jsonObject ?: return Quota.PercentOfFreeSpace(5.0)
        return when (q["type"]?.jsonPrimitive?.content) {
            "unlimited" -> Quota.Unlimited
            "bytes" -> Quota.Bytes(q["bytes"]?.jsonPrimitive?.longOrNull ?: 0L)
            "percent_of_free_space" ->
                Quota.PercentOfFreeSpace(q["percent"]?.jsonPrimitive?.doubleOrNull ?: 5.0)
            else -> Quota.PercentOfFreeSpace(5.0)
        }
    }

    private fun JsonObject.strings(key: String): List<String>? =
        this[key]?.let { (it as? JsonArray)?.jsonArray?.map { e -> e.jsonPrimitive.content } }

    companion object {
        const val POLICY_FILE = "workspace-policy.json"
    }
}
