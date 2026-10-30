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
 * OpenAI Chat Completions 兼容客户端。DeepSeek / 各类 OpenAI 兼容服务都走这套线格式。
 *
 * 职责（= 内核的「协议级解析」部分，必须留内核）：
 * - 组装请求体（model / messages / tools / max_tokens / 透传字段）；
 * - 解析响应（含 tool_calls 的 arguments 原始字符串）；
 * - 提供流式聚合能力（BlockAssembler）。
 *
 * 不做：历史拼接、system prompt 组装、业务数据加工。
 *
 * ## 换服务地址要改什么
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
    /** 额外请求头（后端自定义的、跟鉴权无关的头）。 */
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** chat 端点的路径。服务若不是标准路径，改这里。 */
    private val chatPath: String = "/chat/completions",
    /**
     * 「思考强度」用**哪种线格式**发出去（默认 [ReasoningWire.GATEWAY]）。
     *
     * `reasoning_strength` 是**扩展字段**：由自备后端自行解释（不是厂商标准字段）。
     * **直连厂商官方时那一层不存在** ⇒ 编码没人翻译 ⇒ 厂商要么忽略、要么 400
     * （多数厂商忽略 ⇒ **静默不生效**，比报错更糟）。
     *
     * 用自备后端 ⇒ 不用管；直连 DeepSeek 官方 ⇒ [ReasoningWire.DEEPSEEK]；
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
         * **直连厂商官方时要换** —— `reasoning_strength` 是**扩展字段**（由自备后端自行解释），
         * 发给厂商只会被忽略或 400（**静默不生效**）。
         */
        reasoningWire: ReasoningWire = ReasoningWire.GATEWAY,
    ) : this(baseUrl, transport, LlmAuth.bearer(apiKey), extraHeaders, reasoningWire = reasoningWire)

    companion object {
        /** DeepSeek 官方 API 地址（OpenAI 线格式兼容）。 */
        const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com/v1"

        /**
         * DeepSeek 官方的**域名**（判定用；不含路径、不含端口）。
         *
         * 为什么存域名而不是整条地址：地址可能是
         * `https://api.deepseek.com/v1`、`https://api.deepseek.com`、
         * `https://api.deepseek.com:8443/v1`、`https://api.deepseek.com/v1/`……
         * **只按整串相等判 ⇒ 漏掉一大半** ⇒ 「拿了官方地址却忘了选形状」这种误用会静默不生效。
         */
        const val DEEPSEEK_OFFICIAL_HOST: String = "api.deepseek.com"

        /**
         * 地址是不是 **DeepSeek 官方**（**按域名**判，不看路径、不看端口、不看大小写）。
         *
         * - 官方域名 ⇒ 自动用 [ReasoningWire.DEEPSEEK]（**修复**：此前发的是扩展字段 `reasoning_strength`，
         *   厂商不认 ⇒ 忽略 ⇒ 静默不生效）；
         * - 其它一切地址（自建后端、代理、别的厂商）⇒ 保持 [ReasoningWire.GATEWAY]（**零破坏**）。
         *
         * ⚠️ **只影响「没显式指定 `reasoningWire`」的调用方** —— 显式传了的**一律不受影响**。
         */
        internal fun isDeepSeekOfficial(baseUrl: String): Boolean {
            // URI.host 不含端口、路径；大小写不敏感地比。
            val host = try {
                java.net.URI(baseUrl).host
            } catch (e: Exception) {
                null
            } ?: return false
            return host.equals(DEEPSEEK_OFFICIAL_HOST, ignoreCase = true)
        }

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
             * **默认按地址自动选**：地址是 **DeepSeek 官方域名** ⇒ [ReasoningWire.DEEPSEEK]
             * （官方形状发的是 `thinking` ＋ `reasoning_effort`，见 [ReasoningWire]；
             *   `reasoning_strength` 是**自备后端形状**的字段名，直连厂商不认它 ⇒ 忽略或 400）；
             * 换成别的地址（自建后端）⇒ 保持 [ReasoningWire.GATEWAY]（与以前一样）。
             */
            reasoningWire: ReasoningWire = if (isDeepSeekOfficial(baseUrl)) {
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
     * 为什么不是非流式：DeepSeek 官方与各类 OpenAI 兼容服务的**生产链路全程都是
     * `stream: true` + `accept: text/event-stream`** —— 那是唯一被真实服务校验过的形态，
     * SDK 自己发 `stream:false` 属于没验过的路径。
     *
     * 想用非流式可以显式调 [completeNonStreaming]（少数后端只支持它）。
     */
    override suspend fun complete(request: ChatRequest): AssistantMessage = completeStreaming(request)

    /** 非流式：一次性拿到整块 JSON。 */
    suspend fun completeNonStreaming(request: ChatRequest): AssistantMessage {
        val body = buildBody(request, stream = false)
        // ★ 头**只组装一次**：动态取 token 的实现（`bearerDynamic`）每次调用都会现取一次 ——
        //   组装两次就等于取两次 token（第二次的值可能都不同了）。
        val hdrs = headers("application/json")
        val resp = post(request, body, hdrs)
        return parseAssistant(resp, hdrs)
    }

    /**
     * 流式：把 SSE 分片经 BlockAssembler 聚合后再返回（arguments 必须聚合才完整）。
     *
     * ## 真流式 vs 假流式
     *
     * - 传输实现了 [StreamingTransport] ⇒ **真流式**：逐块喂，**见到 `data: [DONE]` 立刻返回**
     *   （不等 HTTP body 结束 ⇒ 服务端早就答完了，客户端就不用再干等）。
     * - 传输只实现了 [Transport] ⇒ **假流式**（老路）：一次性拿到整块 body 再解析
     *   ⇒ 必须等 body **整个结束**。
     *
     * ⇒ [Transport] 一个字都没改 ⇒ **既有调用方零影响**（不实现新接口照样能用）。
     */
    suspend fun completeStreaming(request: ChatRequest): AssistantMessage {
        val body = buildBody(request, stream = true)
        val hdrs = headers("text/event-stream")
        val assembler = BlockAssembler()
        val streaming = transport as? StreamingTransport
        if (streaming != null) {
            val outcome = withShapeSummary(request, hdrs) {
                StreamingCompletion.consume(
                    streaming.postStream("$baseUrl$chatPath", hdrs, body),
                ) { data -> assembler.accept(deltaFromChunk(data)) }
            }
            // 有些服务端不理会 stream:true，仍回一整块 JSON。
            // 那不能当成「一条没有内容的流」—— 那样会静默丢掉模型说的话。
            if (outcome.dataCount == 0) return parseAssistant(outcome.raw, hdrs)
            return assemble(assembler)
        }
        val raw = post(request, body, hdrs)
        if (!looksLikeSse(raw)) return parseAssistant(raw, hdrs)
        SseParser.parse(raw) { data ->
            assembler.accept(deltaFromChunk(data))
        }
        return assemble(assembler)
    }

    /** 聚合器 → 最终消息（流式 / 非流式共用一份口径）。 */
    private fun assemble(assembler: BlockAssembler): AssistantMessage = AssistantMessage(
        content = assembler.content(),
        toolCalls = assembler.toolCalls(),
        reasoningContent = assembler.reasoning(),
        finishReason = assembler.finishReason(),
        usage = assembler.usage(),
        prefix = assembler.prefix(),
    )

    private fun headers(accept: String): Map<String, String> =
        mapOf(
            "Content-Type" to "application/json",
            "Accept" to accept,
        ) + auth.headers() + extraHeaders

    /**
     * 发请求；**上游报错时补上请求形状**。
     *
     * ## 为什么要有形状摘要
     *
     * 上游 400 时，日志里只有厂商那句话（`The reasoning_content in the thinking mode
     * must be passed back to the API`）—— **看不出我们这次到底发了什么形状**，
     * 于是只能靠猜「是我们漏带了、还是上游的问题、还是历史里本来就没有」。
     *
     * 附上形状（消息数 / assistant 条数 / 带思维链条数 / 带工具调用条数 / 工具结果条数 /
     * 本次带不带 tools / 思维链总字符数）之后，**下次复发能直接对形状**，不用再靠猜。
     *
     * ⚠️ **只记数量与布尔**：正文、工具参数、思维链内容**一个字都不进这里**
     * （思维链只记**总字符数** —— 那是尺寸，不是内容）。
     *
     * ## 为什么保留原异常类型
     *
     * 两个传输实现都用 `error(...)` ⇒ 上游 HTTP 错误是 [IllegalStateException]。
     * 这里**换回同类型**（原消息原样留在前缀里），宿主的 `catch` 与日志匹配都不受影响。
     * 其它异常类型（IO / 超时）**原样抛出**，不做包装。
     *
     * ## ⚠️ 为什么**不**把原异常挂在 cause 上
     *
     * 原异常的消息里可能带着**凭据明文**（上游把 `Authorization` 回显在错误体里，
     * 传输层没抹 —— 宿主自定义的传输不一定抹）⇒
     * 把它挂在 cause 上等于**把明文又留在异常链里**，宿主一打日志就漏出去。
     * ⇒ 这里**只搬栈**（`stackTrace` 原样复制过去），**不保留原异常对象**。
     */
    private suspend fun post(request: ChatRequest, body: String, hdrs: Map<String, String>): String =
        withShapeSummary(request, hdrs) {
            transport.post(
                url = "$baseUrl$chatPath",
                headers = hdrs,
                body = body,
            )
        }

    /**
     * 上游报错时**补上请求形状**（凭据先抹、异常类型与栈原样保留）。
     *
     * 流式与非流式**共用**这一套 ⇒ 两条路的错误消息口径完全一致。
     */
    private suspend fun <T> withShapeSummary(
        request: ChatRequest,
        hdrs: Map<String, String>,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            val safe = IllegalStateException(
                "${HeaderRedaction.redact(e.message ?: "", hdrs)}\n请求形状：${shapeSummary(request)}"
            )
            safe.stackTrace = e.stackTrace
            throw safe
        }

    /** 请求形状摘要（**只有数量与布尔**）。 */
    private fun shapeSummary(request: ChatRequest): String {
        val msgs = request.messages
        val assistants = msgs.filterIsInstance<AssistantMessage>()
        val withReasoning = assistants.count { !it.reasoningContent.isNullOrBlank() }
        val withToolCalls = assistants.count { it.toolCalls.isNotEmpty() }
        val toolResults = msgs.count { it.role == "tool" }
        val reasoningChars = assistants.sumOf { it.reasoningContent?.length ?: 0 }
        return "消息 ${msgs.size} · assistant ${assistants.size} · 带思维链 $withReasoning" +
            " · 带工具调用 $withToolCalls · 工具结果 $toolResults" +
            " · tools ${if (request.tools.isNotEmpty()) "有" else "无"}" +
            " · 思维链总字符 $reasoningChars"
    }

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
        // 不传 = 服务端用它的默认档位（省一次往返，也让后端的默认策略生效）。
        // ★ 发出去的**形状**看对端是谁（见 [ReasoningWire]）：
        //   自备后端吃 `reasoning_strength`；直连厂商官方时它是个**没人翻译的私有字段**
        //   ⇒ 厂商要么忽略、要么 400 ⇒ 静默不生效。
        reasoningWire.putStrength(this, request.reasoningStrength)
        // 会话标识:让服务端能独立复核「这一轮是不是上一轮的追加扩展」(前缀缓存的前提)。
        request.sessionId?.let { put("session_id", it) }
        request.extra.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun parseAssistant(resp: String, hdrs: Map<String, String>): AssistantMessage {
        val root = Json.parseToJsonElement(resp).jsonObject
        // ⚠️ 响应体片段也会进异常消息 ⇒ 同样要**先抹凭据**（上游可能把它回显在响应里）。
        val choices = root["choices"]?.jsonArray
            ?: error("响应缺少 choices: ${HeaderRedaction.redact(resp, hdrs).take(500)}")
        val choice = choices[0].jsonObject
        val msg = choice["message"]?.jsonObject
            ?: error("响应缺少 message: ${HeaderRedaction.redact(resp, hdrs).take(500)}")
        val content = msg["content"]?.jsonPrimitive?.contentOrNull
        val calls = msg["tool_calls"]?.jsonArray?.map { ToolCall.fromJson(it.jsonObject) } ?: emptyList()
        // 思考模式的思维链：**有就解析**（回不回调传是线格式那一层的事，见 [AssistantMessage.toJson]）。
        // ⚠️ 官方规则是「**带 tools 的请求**里，历史中**每一条 assistant** 的思维链都要原样回传」
        //    —— 判据是「**请求**带不带 tools」，不是「**这条 assistant** 带没带 tool_calls」。
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
     * - **部分后端**（含内核的 disjoint 约定）：`prompt_tokens` 报的是**未命中**的部分。
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