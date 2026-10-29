package ai.agentkernel.llm.openai

import ai.agentkernel.llm.BlockAssembler
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.llm.StreamDelta
import ai.agentkernel.llm.ToolCallDelta
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.PrefixReport
import ai.agentkernel.protocol.ToolCall
import ai.agentkernel.protocol.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Chat Completions 兼容客户端。DeepSeek / 各家兼容网关都走这套线格式。
 *
 * 职责（= 内核的「协议级解析」部分，必须留内核）：
 * - 组装请求体（model / messages / tools / max_tokens / 透传字段）；
 * - 解析响应（含 tool_calls 的 arguments 原始字符串）；
 * - 提供流式聚合能力（BlockAssembler）。
 *
 * 不做：历史拼接、system prompt 组装、业务数据加工。
 *
 * ## 换网关要改什么
 *
 * **线格式一个字都不用改** —— 请求体、响应解析、流式聚合全是 OpenAI 那套。
 * 要改的只有两处：
 * - [baseUrl]：服务地址（带 `/v1` 这类前缀就写进 baseUrl）；
 * - [auth]：鉴权方式（见 [LlmAuth]，不一定是 `Authorization: Bearer`）。
 *
 * 路径不是 `/chat/completions` 时用 [chatPath] 覆盖。
 */
class ChatCompletionsClient(
    private val baseUrl: String,
    private val transport: Transport,
    /** 鉴权方式。默认不带鉴权头；用 [LlmAuth.bearer] 等工厂方法构造。 */
    private val auth: LlmAuth = LlmAuth.none(),
    /** 额外请求头（网关自定义的、跟鉴权无关的头）。 */
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** chat 端点的路径。服务若不是标准路径，改这里。 */
    private val chatPath: String = "/chat/completions",
    /**
     * 「思考强度」用**哪种线格式**发出去（默认 [ReasoningWire.GATEWAY]）。
     *
     * `reasoning_strength` 是**我们网关的私有字段**：网关按该模型的目录把编码翻译成厂商档位。
     * **直连厂商官方时那一层不存在** ⇒ 编码没人翻译 ⇒ 厂商要么忽略、要么 400
     * （多数厂商忽略 ⇒ **静默不生效**，比报错更糟）。
     *
     * 走我们的网关 ⇒ 不用管；直连 DeepSeek 官方 ⇒ [ReasoningWire.DEEPSEEK]；
     * 直连 OpenAI 系 ⇒ [ReasoningWire.OPENAI]。拿不准就**显式写出来**。
     */
    private val reasoningWire: ReasoningWire = ReasoningWire.GATEWAY,
) : LlmClient {

    /**
     * 兼容 OpenAI 官方写法：`Authorization: Bearer <apiKey>`。
     *
     * ```
     * ChatCompletionsClient(baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-...", transport = OkHttpTransport())
     * ```
     */
    constructor(
        baseUrl: String,
        apiKey: String,
        transport: Transport,
        extraHeaders: Map<String, String> = emptyMap(),
        /**
         * 思考强度的线格式（见 [ReasoningWire]）。
         *
         * **直连厂商官方时要换** —— `reasoning_strength` 是网关私有字段，
         * 发给厂商只会被忽略或 400（**静默不生效**）。
         */
        reasoningWire: ReasoningWire = ReasoningWire.GATEWAY,
    ) : this(baseUrl, transport, LlmAuth.bearer(apiKey), extraHeaders, reasoningWire = reasoningWire)

    companion object {
        /** DeepSeek 官方 API 地址（OpenAI 线格式兼容）。 */
        const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com/v1"

        /**
         * 一行接上 DeepSeek 官方（地址用官方的，鉴权走 `Authorization: Bearer`）。
         *
         * ```
         * val client = ChatCompletionsClient.deepSeek(apiKey = "sk-...", transport = OkHttpTransport())
         * ```
         */
        fun deepSeek(
            apiKey: String,
            transport: Transport,
            baseUrl: String = DEEPSEEK_BASE_URL,
            extraHeaders: Map<String, String> = emptyMap(),
            /**
             * 思考强度的线格式。
             *
             * **默认按地址自动选**：地址是 DeepSeek 官方 ⇒ [ReasoningWire.DEEPSEEK]
             * （官方只认 `thinking`；发网关的 `reasoning_strength` 会被忽略 ⇒ 静默不生效）；
             * 换成别的地址（自建网关）⇒ 保持 [ReasoningWire.GATEWAY]（与以前一样）。
             */
            reasoningWire: ReasoningWire = if (baseUrl == DEEPSEEK_BASE_URL) {
                ReasoningWire.DEEPSEEK
            } else {
                ReasoningWire.GATEWAY
            },
        ): ChatCompletionsClient = ChatCompletionsClient(
            baseUrl = baseUrl,
            transport = transport,
            auth = LlmAuth.bearer(apiKey),
            extraHeaders = extraHeaders,
            reasoningWire = reasoningWire,
        )
    }


    /**
     * 内核走**流式**。
     *
     * 为什么不是非流式：DeepSeek 官方与各家兼容网关的**生产链路全程都是
     * `stream: true` + `accept: text/event-stream`** —— 那是唯一被真实服务校验过的形态，
     * SDK 自己发 `stream:false` 属于没验过的路径。
     *
     * 想用非流式可以显式调 [completeNonStreaming]（少数网关只支持它）。
     */
    override suspend fun complete(request: ChatRequest): AssistantMessage = completeStreaming(request)

    /** 非流式：一次性拿到整块 JSON。 */
    suspend fun completeNonStreaming(request: ChatRequest): AssistantMessage {
        val body = buildBody(request, stream = false)
        val resp = transport.post(
            url = "$baseUrl$chatPath",
            headers = headers(accept = "application/json"),
            body = body,
        )
        return parseAssistant(resp)
    }

    /** 流式：把 SSE 分片经 BlockAssembler 聚合后再返回（arguments 必须聚合才完整）。 */
    suspend fun completeStreaming(request: ChatRequest): AssistantMessage {
        val body = buildBody(request, stream = true)
        val raw = transport.post(
            url = "$baseUrl$chatPath",
            headers = headers(accept = "text/event-stream"),
            body = body,
        )
        // 有些服务端不理会 stream:true，仍回一整块 JSON。
        // 那不能当成「一条没有内容的流」—— 那样会静默丢掉模型说的话。
        if (!looksLikeSse(raw)) return parseAssistant(raw)
        val assembler = BlockAssembler()
        SseParser.parse(raw) { data ->
            assembler.accept(deltaFromChunk(data))
        }
        return AssistantMessage(
            content = assembler.content(),
            toolCalls = assembler.toolCalls(),
            reasoningContent = assembler.reasoning(),
            finishReason = assembler.finishReason(),
            usage = assembler.usage(),
            prefix = assembler.prefix(),
        )
    }

    private fun headers(accept: String): Map<String, String> =
        mapOf(
            "Content-Type" to "application/json",
            "Accept" to accept,
        ) + auth.headers() + extraHeaders

    private fun looksLikeSse(raw: String): Boolean =
        raw.lineSequence().any { it.trimStart().startsWith("data:") }

    private fun buildBody(request: ChatRequest, stream: Boolean): String = buildJsonObject {
        put("model", request.model)
        put("stream", stream)
        put("messages", buildJsonArray { request.messages.forEach { add(it.toJson()) } })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray { request.tools.forEach { add(it) } })
        }
        request.temperature?.let { put("temperature", it) }
        // max_tokens 不填就不带这个字段 —— 让服务端用自己的默认值。
        request.maxTokens?.let { put("max_tokens", it) }
        // 流式要**显式要**用量：不带这个字段，服务端不会在最后一帧回 usage。
        // 少了它就看不到缓存命中数（也就没法验证前缀缓存到底生效没有）。
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        // 思考强度：**主契约是强度编码**（2 的幂，跨模型稳定 —— 换模型不用改用户存的值）。
        // 不传 = 服务端用它的默认档位（省一次往返，也让网关的默认策略生效）。
        // ★ 发出去的**形状**看对端是谁（见 [ReasoningWire]）：
        //   网关吃 `reasoning_strength`；直连厂商官方时它是个**没人翻译的私有字段**
        //   ⇒ 厂商要么忽略、要么 400 ⇒ 静默不生效。
        reasoningWire.putStrength(this, request.reasoningStrength)
        // 会话标识:让服务端能独立复核「这一轮是不是上一轮的追加扩展」(前缀缓存的前提)。
        request.sessionId?.let { put("session_id", it) }
        request.extra.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun parseAssistant(resp: String): AssistantMessage {
        val root = Json.parseToJsonElement(resp).jsonObject
        val choices = root["choices"]?.jsonArray ?: error("响应缺少 choices: ${resp.take(500)}")
        val choice = choices[0].jsonObject
        val msg = choice["message"]?.jsonObject ?: error("响应缺少 message: ${resp.take(500)}")
        val content = msg["content"]?.jsonPrimitive?.contentOrNull
        val calls = msg["tool_calls"]?.jsonArray?.map { ToolCall.fromJson(it.jsonObject) } ?: emptyList()
        // 思考模式的思维链：带 tool_calls 的回合必须原样回传，否则思考链断掉。
        val reasoning = msg["reasoning_content"]?.jsonPrimitive?.contentOrNull
        // 结束原因：'length' = 被 max_tokens 截断，内容不完整（别把半句当完整回答）。
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        return AssistantMessage(
            content = content,
            toolCalls = calls,
            reasoningContent = reasoning,
            finishReason = finish,
            usage = parseUsage(root),
            prefix = parsePrefix(root),
        )
    }

    /**
     * 解析**服务端视角**的前缀体检报告（响应根上的 `prefix` 字段）。
     *
     * 不是 OpenAI 标准字段 —— 服务端没给就返回 null（**不猜、不兜底成「完好」**）：
     * 「没检查过」和「检查过没问题」是两回事，混起来会让人以为前缀被验过了。
     */
    private fun parsePrefix(root: JsonObject): PrefixReport? {
        val p = root["prefix"] as? JsonObject ?: return null
        return PrefixReport(
            session = p["session"]?.jsonPrimitive?.contentOrNull ?: "",
            tracked = p["tracked"]?.jsonPrimitive?.booleanOrNull ?: false,
            preserved = p["preserved"]?.jsonPrimitive?.booleanOrNull ?: true,
            firstChangedIndex = p["firstChangedIndex"]?.jsonPrimitive?.intOrNull ?: -1,
            commonPrefixMessages = p["commonPrefixMessages"]?.jsonPrimitive?.intOrNull ?: 0,
            commonPrefixChars = p["commonPrefixChars"]?.jsonPrimitive?.intOrNull ?: 0,
            addedMessages = p["addedMessages"]?.jsonPrimitive?.intOrNull ?: 0,
            headChars = p["headChars"]?.jsonPrimitive?.intOrNull ?: 0,
            toolsChars = p["toolsChars"]?.jsonPrimitive?.intOrNull ?: 0,
            headChanged = p["headChanged"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        )
    }

    /**
     * 解析响应里的 `usage`（**缓存命中数就在这里**）。
     *
     * ## 两种线上口径都要认
     *
     * - **OpenAI / DeepSeek 原生**：`prompt_tokens` 是**输入总量**，
     *   命中数在 `prompt_cache_hit_tokens`（或 `prompt_tokens_details.cached_tokens`）；
     * - **部分网关**（含内核的 disjoint 约定）：`prompt_tokens` 报的是**未命中**的部分。
     *
     * 两种都见过，所以这里做归一：命中数 > `prompt_tokens` 时，说明后者是「未命中」口径，
     * 总量 = 两者之和。**宁可算保守，也不谎报命中率。**
     */
    private fun parseUsage(root: JsonObject): Usage? {
        val u = root["usage"] as? JsonObject ?: return null
        val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val total = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: (prompt + completion)
        val details = u["prompt_tokens_details"] as? JsonObject
        val hit = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
            ?: details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: 0
        val rawMiss = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        val miss: Int
        val promptTotal: Int
        when {
            rawMiss != null -> {
                miss = rawMiss
                promptTotal = prompt
            }
            hit > prompt -> {
                // prompt_tokens 是「未命中」口径 → 总量 = 未命中 + 命中。
                miss = prompt
                promptTotal = prompt + hit
            }
            else -> {
                miss = (prompt - hit).coerceAtLeast(0)
                promptTotal = prompt
            }
        }
        return Usage(
            promptTokens = promptTotal,
            completionTokens = completion,
            totalTokens = total,
            cacheHitTokens = hit,
            cacheMissTokens = miss,
        )
    }

    /** 从一条 SSE data 里抽取增量（choices[0].delta）。 */
    private fun deltaFromChunk(data: JsonObject): StreamDelta {
        // usage 挂在**帧的根上**(带用量那一帧的 choices 是空数组)——
        // 必须先取出来,不然会被下面那个早退吃掉,用量就永远丢。
        // 前缀报告(prefix)跟 usage 在同一帧,同理。
        val usage = parseUsage(data)
        val prefix = parsePrefix(data)
        val choice = data["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = choice?.get("delta")?.jsonObject ?: return StreamDelta(usage = usage, prefix = prefix)
        val content = delta["content"]?.jsonPrimitive?.contentOrNull
        val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val tc = delta["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject
        val frag = tc?.let {
            ToolCallDelta(
                index = it["index"]?.jsonPrimitive?.intOrNull ?: 0,
                id = it["id"]?.jsonPrimitive?.contentOrNull,
                name = it["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
                argumentsFragment = it["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull,
            )
        }
        return StreamDelta(
            content = content,
            reasoning = reasoning,
            toolCall = frag,
            finishReason = finish,
            usage = usage,
            prefix = prefix,
        )
    }
}