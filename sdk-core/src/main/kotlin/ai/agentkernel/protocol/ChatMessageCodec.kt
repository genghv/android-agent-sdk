package ai.agentkernel.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 消息的**归档编解码** —— 把一条 [ChatMessage] 存成 JSON、再原样还原。
 *
 * ## 为什么不用线格式那个 `toJson()`
 *
 * 线格式那份是**给上游看的**，归档是**给人看的**（前端要照常显示）：
 *
 * - 线格式里 assistant 的 content **永远给字符串**（null 会被有些网关拒），
 *   于是「本来就没正文的 tool 回合」和「正文是空串」分不出来了 —— 归档里要分得清；
 * - 线格式**不带** `finish_reason`，但截断（`'length'`）是要显示给人看的。
 *
 * ⇒ 这里单独一套形状，**不动**线格式那份（改它会动到上游请求，风险不对等）。
 */
object ChatMessageCodec {

    /** 一条消息 → JSON。content 的 null 原样保留。 */
    fun toJson(m: ChatMessage): JsonObject = when (m) {
        is SystemMessage -> buildJsonObject {
            put("role", "system")
            put("content", m.content)
        }

        is UserMessage -> buildJsonObject {
            put("role", "user")
            put("content", m.content)
        }

        is ToolMessage -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.toolCallId)
            put("content", m.content)
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "assistant")
            // null 保留：tool-only 的回合要能看出「本来就没正文」
            put("content", m.content)
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray { m.toolCalls.forEach { add(it.toJson()) } })
            }
            m.reasoningContent?.let { put("reasoning_content", it) }
            m.finishReason?.let { put("finish_reason", it) }
        }

        else -> buildJsonObject { put("role", m.role) }
    }

    /** 一批消息 → JSON 数组。 */
    fun toJson(messages: List<ChatMessage>): JsonArray =
        buildJsonArray { messages.forEach { add(toJson(it)) } }

    /**
     * JSON → 一条消息；形状不认识时返回 null（**不抛异常**：归档是历史数据，
     * 一条读不出来不该把整页炸掉）。
     */
    fun fromJson(o: JsonObject): ChatMessage? {
        val role = o["role"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (role) {
            "system" -> SystemMessage(o.string("content"))

            "user" -> UserMessage(o.string("content"))

            "tool" -> ToolMessage(
                toolCallId = o["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: "",
                content = o.string("content"),
            )

            "assistant" -> AssistantMessage(
                content = o["content"]?.jsonPrimitive?.contentOrNull,
                toolCalls = o["tool_calls"]?.jsonArray.orEmpty().mapNotNull { el ->
                    runCatching { ToolCall.fromJson(el.jsonObject) }.getOrNull()
                },
                reasoningContent = o["reasoning_content"]?.jsonPrimitive?.contentOrNull,
                finishReason = o["finish_reason"]?.jsonPrimitive?.contentOrNull,
            )

            else -> null
        }
    }

    /** JSON 数组 → 一批消息（跳过读不出来的）。 */
    fun fromJson(array: JsonArray): List<ChatMessage> =
        array.mapNotNull { el -> runCatching { fromJson(el.jsonObject) }.getOrNull() }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
