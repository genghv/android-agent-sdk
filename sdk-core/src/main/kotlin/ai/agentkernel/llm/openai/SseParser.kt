package ai.agentkernel.llm.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 极简 SSE 解析：逐行处理 `data: {json}`，遇到 `data: [DONE]` 结束。
 * 只做协议层的事，回调里再交给 BlockAssembler。
 */
object SseParser {
    fun parse(raw: String, onData: (JsonObject) -> Unit) {
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()?.let(onData)
        }
    }
}