package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP 调用能力 —— **SDK 自制的命令**（前缀 `agent-`）。
 *
 * ## 为什么要它，而不是让 agent 用 `curl`
 *
 * 设备自带 `curl`（Android 8+ 都有 `/system/bin/curl`），能发请求，但有三个问题：
 * 1. **鉴权 header 得写进命令行** —— token 会出现在转录、日志、shell 历史里；
 * 2. **输出不可控** —— 一次拉 5MB 的 JSON 直接塞进模型上下文；
 * 3. **失败不好归类** —— curl 的退出码分不清「连不上」和「对方返回 500」。
 *
 * 做成能力之后：
 * - **默认 header 由宿主注入**（`Authorization: Bearer …`），agent 不用知道 token，
 *   调相对路径就行（`agent-http-get /api/weather`），token 不进转录；
 * - 响应体**有上限**，超了截断并明确标记；
 * - 状态码、超时、连接失败都转成**说清原因**的错误。
 *
 * ## 命令
 *
 * ```
 * agent-http-get  <url|相对路径> [--header "K: V"] [--timeout 15000] [--max-bytes 200000]
 * agent-http-post <url|相对路径> [--header "K: V"] [--data "{...}"] [--timeout 15000]
 * ```
 */
class AgentHttpCapabilities(
    /**
     * 宿主 API 的基地址（如 `https://host.example/api`）。
     * 留空则只能传完整 URL。
     */
    private val baseUrlProvider: () -> String = { "" },
    /** 宿主注入的默认请求头（**含鉴权**）。agent 看不到它们，也不需要知道。 */
    private val defaultHeadersProvider: () -> Map<String, String> = { emptyMap() },
    /** 默认超时（毫秒）。 */
    private val timeoutMs: Long = 15_000L,
    /** 响应体最大字节数；超了截断并标记 `truncated:true`。 */
    private val maxBytes: Int = 200_000,
) {
    /** 固定地址 / 固定头的便捷写法。 */
    constructor(
        baseUrl: String,
        defaultHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L,
        maxBytes: Int = 200_000,
    ) : this({ baseUrl }, { defaultHeaders }, timeoutMs, maxBytes)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    val capabilities: List<DeviceCapability> = listOf(
        HttpGetCapability(client, baseUrlProvider, defaultHeadersProvider, timeoutMs, maxBytes),
        HttpPostCapability(client, baseUrlProvider, defaultHeadersProvider, timeoutMs, maxBytes),
    )
}

/** 解析出来的调用参数。 */
private class HttpArgs(
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val timeoutMs: Long,
    val maxBytes: Int,
)

private fun parseHttpArgs(
    args: List<String>,
    usage: String,
    defaultTimeoutMs: Long,
    defaultMaxBytes: Int,
    allowBody: Boolean,
): HttpArgs {
    var url: String? = null
    var body: String? = null
    var timeout = defaultTimeoutMs
    var maxBytes = defaultMaxBytes
    val headers = mutableListOf<Pair<String, String>>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--header", "-H" -> {
                val raw = args.getOrNull(i + 1) ?: throw CapabilityFailure("用法：$usage")
                val idx = raw.indexOf(':')
                if (idx <= 0) throw CapabilityFailure("header 要写成 `名称: 值`，收到的是「$raw」")
                val name = raw.substring(0, idx).trim()
                val value = raw.substring(idx + 1).trim()
                checkHeaderName(name)
                checkHeaderValue(name, value)
                headers += name to value
                i += 2
            }
            "--data", "-d" -> {
                if (!allowBody) throw CapabilityFailure("用法：$usage")
                body = args.getOrNull(i + 1) ?: throw CapabilityFailure("用法：$usage")
                i += 2
            }
            "--timeout" -> {
                timeout = args.getOrNull(i + 1)?.toLongOrNull()
                    ?: throw CapabilityFailure("用法：$usage")
                i += 2
            }
            "--max-bytes" -> {
                maxBytes = args.getOrNull(i + 1)?.toIntOrNull()
                    ?: throw CapabilityFailure("用法：$usage")
                i += 2
            }
            else -> {
                if (a.startsWith("-")) throw CapabilityFailure("不认识的选项「$a」。用法：$usage")
                if (url == null) {
                    url = a
                    i++
                } else {
                    throw CapabilityFailure("用法：$usage")
                }
            }
        }
    }
    val target = url ?: throw CapabilityFailure("用法：$usage")
    return HttpArgs(target, headers, body, timeout, maxBytes)
}

/**
 * header 名字必须是合法的 HTTP token（不能有空格、冒号、中文）。
 *
 * **提前校验**，别把 OkHttp 的原始异常抛给模型 ——
 * `Unexpected char 0x53e6 at 0 in X-Tenant value` 这种话模型看不懂，也不知道该改什么。
 */
private fun checkHeaderName(name: String) {
    if (name.isEmpty()) throw CapabilityFailure("header 名字不能为空")
    val bad = name.firstOrNull { it.code !in 0x21..0x7E || it == ':' }
    if (bad != null) {
        throw CapabilityFailure(
            "header 名字「$name」含非法字符。header 名字只能有可打印 ASCII 字母数字和 -_. " +
                "要传中文内容请放到请求体（--data）里。"
        )
    }
}

/**
 * header **值**只能是可打印 ASCII（外加 TAB）。
 *
 * 这条规则是**量出来的**，不是照 RFC 背的：实测 OkHttp 4.12 的行为是
 * - 空格（0x20）**允许** —— `Authorization: Bearer xxx` 这种每天都在用；
 * - TAB（0x09）允许；
 * - ≥0x7F 的非 ASCII（中文等）**拒绝**，报 `Unexpected char 0x… at … in X value`。
 *
 * 所以这里用**和 OkHttp 一样的判据**提前拦下来并说清怎么绕，
 * 而不是把 OkHttp 的原始异常抛给模型（那句 `Unexpected char 0x53e6` 模型看不懂，
 * 也不知道该改成什么）。
 */
private fun checkHeaderValue(name: String, value: String) {
    val bad = value.firstOrNull { (it.code < 0x20 && it.code != 0x09) || it.code >= 0x7F }
    if (bad != null) {
        throw CapabilityFailure(
            "header「$name」的值含非 ASCII 字符 —— HTTP 头按 RFC 只能放可打印 ASCII。" +
                "中文内容请改放到请求体（--data）里；确实要放中文头值的话，" +
                "自己写成百分号编码（例如 %E5%8D%8E%E4%B8%9C 形式），服务端按 URL 解码。"
        )
    }
}

/** 把相对路径拼到基地址上；完整 URL 原样用。 */
private fun resolveUrl(base: String, target: String): String {
    if (target.startsWith("http://") || target.startsWith("https://")) return target
    if (base.isBlank()) {
        throw CapabilityFailure("「$target」不是完整 URL，而本环境没有配基地址 —— 请给完整的 http(s) 地址")
    }
    val trimmedBase = base.trimEnd('/')
    return if (target.startsWith("/")) "$trimmedBase$target" else "$trimmedBase/$target"
}

private fun perform(
    client: OkHttpClient,
    method: String,
    url: String,
    headers: List<Pair<String, String>>,
    defaultHeaders: Map<String, String>,
    body: String?,
    timeoutMs: Long,
    maxBytes: Int,
): String {
    val builder = Request.Builder().url(url)
    // 宿主注入的默认 header 先放，随后 agent 指定的同名 header 覆盖它。
    defaultHeaders.forEach { (k, v) ->
        checkHeaderName(k)
        checkHeaderValue(k, v)
        builder.header(k, v)
    }
    headers.forEach { (k, v) -> builder.header(k, v) }

    when (method) {
        "GET" -> builder.get()
        "POST" -> builder.post(
            (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        )
    }

    val started = System.currentTimeMillis()
    val scoped = client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    val response = try {
        scoped.newCall(builder.build()).execute()
    } catch (e: Exception) {
        throw CapabilityFailure(explainFailure(method, url, e))
    }

    response.use { resp ->
        val text = resp.body?.string().orEmpty()
        val elapsed = System.currentTimeMillis() - started
        val truncated = text.length > maxBytes
        val payload = if (truncated) text.take(maxBytes) else text

        if (!resp.isSuccessful) {
            // 非 2xx：请求本身成功了，但**这次取数据失败了** —— 明确报错，带上状态码和响应片段。
            throw CapabilityFailure(
                "HTTP ${resp.code} ${resp.message}（$method $url）：${payload.take(500)}"
            )
        }

        // 响应头先在 okJson 外面拼好 —— 嵌进去会出现两个隐式接收者（JsonObjectBuilder 打架）。
        val headersJson = buildJsonArray {
            resp.headers.names().forEach { name ->
                add(
                    buildJsonObject {
                        put("name", name)
                        put("value", resp.headers[name] ?: "")
                    }
                )
            }
        }

        return okJson {
            put("status", resp.code)
            put("content_type", resp.body?.contentType()?.toString() ?: "")
            put("bytes", text.length)
            put("truncated", truncated)
            put("elapsed_ms", elapsed)
            put("body", payload)
            put("headers", headersJson)
        }
    }
}

/**
 * 把 OkHttp 的异常翻译成**模型能照做**的话。
 *
 * 最常见的一个坑：Android 9（API 28）起默认**禁止明文 HTTP**，
 * OkHttp 抛的是 `CLEARTEXT communication to xxx not permitted by network security policy`。
 * 这句话模型看不懂、也不知道该改哪儿，所以这里翻译成「宿主要配 networkSecurityConfig，或改用 https」。
 */
private fun explainFailure(method: String, url: String, e: Exception): String {
    val raw = e.message ?: e::class.java.simpleName
    return if (raw.contains("CLEARTEXT communication")) {
        "明文 HTTP 被 Android 的网络安全策略拒绝了（$url）。" +
            "这是**宿主 App** 的配置问题，不是命令写错了：宿主要在 AndroidManifest.xml 里配 " +
            "android:networkSecurityConfig（放行明文域名），或者让接口提供 https。"
    } else if (raw.contains("Unable to resolve host")) {
        "解析不了域名（$url）。检查网络/DNS，或确认这台设备能不能访问该地址。"
    } else if (raw.contains("timeout") || raw.contains("timed out")) {
        "请求超时（$method $url）。设备可能不在能访问该地址的网络里（WiFi/移动数据）。"
    } else {
        "请求失败（$method $url）：$raw"
    }
}

/** `agent-http-get`。 */
private class HttpGetCapability(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val defaultHeadersProvider: () -> Map<String, String>,
    private val defaultTimeoutMs: Long,
    private val defaultMaxBytes: Int,
) : DeviceCapability {
    override val name: String = "http-get"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-http-get <url|相对路径> [--header \"K: V\"] [--timeout 15000]"

    override fun run(args: List<String>): String {
        val parsed = parseHttpArgs(args, usage, defaultTimeoutMs, defaultMaxBytes, allowBody = false)
        val url = resolveUrl(baseUrlProvider(), parsed.url)
        return runBlocking {
            perform(
                client, "GET", url, parsed.headers, defaultHeadersProvider(), null,
                parsed.timeoutMs, parsed.maxBytes,
            )
        }
    }
}

/** `agent-http-post`。 */
private class HttpPostCapability(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val defaultHeadersProvider: () -> Map<String, String>,
    private val defaultTimeoutMs: Long,
    private val defaultMaxBytes: Int,
) : DeviceCapability {
    override val name: String = "http-post"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL
    override val usage: String = "agent-http-post <url|相对路径> [--header \"K: V\"] [--data \"{...}\"]"

    override fun run(args: List<String>): String {
        val parsed = parseHttpArgs(args, usage, defaultTimeoutMs, defaultMaxBytes, allowBody = true)
        val url = resolveUrl(baseUrlProvider(), parsed.url)
        return runBlocking {
            perform(
                client, "POST", url, parsed.headers, defaultHeadersProvider(), parsed.body,
                parsed.timeoutMs, parsed.maxBytes,
            )
        }
    }
}
