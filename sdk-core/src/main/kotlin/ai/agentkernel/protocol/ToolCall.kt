package ai.agentkernel.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模型请求调用某个工具。
 *
 * 关键坑（OpenAI 线格式的既定语义）：
 * - `arguments` 是**原始 JSON 字符串**，不是对象。流式返回时它是按 index 分片的碎片，
 *   必须聚合后才能 parse（见 [ai.agentkernel.llm.BlockAssembler]）。
 * - 只有真正要执行时，才用 [argsOrEmpty] 把它 parse 成 JsonObject 取参数。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    /** 执行前才解析；解析失败一律退化为空对象，不抛异常打断循环。 */
    fun argsOrEmpty(): JsonObject =
        runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
    }

    companion object {
        fun fromJson(o: JsonObject): ToolCall {
            val fn = o["function"]!!.jsonObject
            return ToolCall(
                id = o["id"]!!.jsonPrimitive.content,
                name = fn["name"]!!.jsonPrimitive.content,
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }
    }
}