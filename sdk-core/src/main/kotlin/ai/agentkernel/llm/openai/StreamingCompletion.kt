package ai.agentkernel.llm.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * **真流式**的 SSE 消费（逐行喂，跨块半行缓冲）。
 *
 * ## 三件事
 *
 * 1. **跨块半行缓冲**：网络块边界可以落在任意位置 ⇒ 半行先留着，等下一块接上。
 * 2. **认注释帧**：心跳是 `: hb` 这种注释帧（`:` 开头）⇒ **跳过**，不喂给解析器。
 * 3. **见 `[DONE]` 立刻停**：上游流**取消**（不等 body 结束）⇒
 *    服务端早就答完了、客户端就立刻返回。
 *
 * ## 为什么用 [Error] 当「流结束」信号
 *
 * `catch (e: Exception)` 是**吞掉信号**的常见写法 ⇒ 用 [Exception] 子类当控制流信号
 * 会被上游吞掉 ⇒ 吞掉就退化成「等 body 结束」⇒ 这次改造就白做了。
 * ⇒ 用 [Error]：上游的 `catch (e: Exception)` 拦不住它。
 */
internal object StreamingCompletion {

    /** 消费结果。 */
    data class Outcome(
        /** 有没有见到 `data: [DONE]`。 */
        val sawDone: Boolean,
        /** 喂给上层的帧数。 */
        val dataCount: Int,
        /**
         * 累积的**原文**（整块 body）。
         *
         * 用途只有一个：**服务端不理会 `stream:true`、仍回一整块 JSON** 时，
         * 客户端要能把它当普通响应解析（老路就有这个兜底）⇒ 流式路径必须**同样**兜住，
         * 否则那种服务端上会「静默丢掉模型说的话」。
         */
        val raw: String,
    )

    /**
     * 消费 SSE 流。
     *
     * @param onData 每条 `data:` 帧（已解析成 JSON 对象）。
     * @return 消费结果；见到 `[DONE]` 时**立即**返回（上游流已被取消）。
     */
    suspend fun consume(flow: Flow<String>, onData: (JsonObject) -> Unit): Outcome {
        var dataCount = 0
        var sawDone = false
        val buffer = StringBuilder()
        val raw = StringBuilder()
        try {
            flow.collect { chunk ->
                raw.append(chunk)
                buffer.append(chunk)
                while (true) {
                    val nl = buffer.indexOf("\n")
                    if (nl < 0) break
                    val line = buffer.substring(0, nl)
                        .let { if (it.endsWith("\r")) it.dropLast(1) else it }
                    buffer.delete(0, nl + 1)
                    if (handleLine(line) {
                            dataCount++
                            onData(it)
                        }
                    ) {
                        sawDone = true
                        throw StreamDone()
                    }
                }
            }
        } catch (e: StreamDone) {
            // 正常终止：上游流已被取消（我们不等 body 结束）。
        }
        return Outcome(sawDone, dataCount, raw.toString())
    }

    /**
     * 处理一行。
     *
     * @return true = 这一行是 `data: [DONE]`（流结束）。
     */
    private fun handleLine(line: String, onData: (JsonObject) -> Unit): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        // 注释帧（心跳 `: hb`）⇒ 跳过。
        if (trimmed.startsWith(":")) return false
        // 只认 data:；event: / id: / retry: 这些字段行一律跳过。
        if (!trimmed.startsWith("data:")) return false
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return false
        if (payload == "[DONE]") return true
        runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()?.let(onData)
        return false
    }
}

/**
 * 「流已结束」的**内部控制流**信号。
 *
 * 用 [Error] 而不是 [Exception] ⇒ 上游的 `catch (e: Exception)` 吞不住它
 * （吞掉就退化成「等 body 结束」，那次改造就白做了）。
 */
private class StreamDone : Error("SSE done") {
    override fun fillInStackTrace(): Throwable = this
}
