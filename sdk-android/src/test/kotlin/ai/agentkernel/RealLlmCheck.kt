package ai.agentkernel

import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.kernel.AgentKernel
import ai.agentkernel.kernel.TurnStop
import ai.agentkernel.llm.openai.ChatCompletionsClient
import ai.agentkernel.llm.openai.JdkHttpTransport
import ai.agentkernel.llm.openai.ReasoningWire
import ai.agentkernel.llm.openai.Transport
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.SystemMessage
import ai.agentkernel.protocol.UserMessage
import ai.agentkernel.tools.ToolRegistry
import ai.agentkernel.tools.exec.ShellTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * 用**真实 LLM API** 跑一轮，把内核和真网关之间最后一段坐实。
 *
 * 本地 mock 验的是「内核自己的逻辑」；这一档验的是「**和真网关的线格式对不对得上**」——
 * 而后者才是真机上第一个会炸的地方（content 给 null、tool_calls 不回传 reasoning_content、
 * 非流式被拒……）。这些坑在 mock 上全都看不出来，因为 mock 照单全收。
 *
 * 用法（三个环境变量）：
 * ```
 * set LLM_BASE_URL=https://your-gateway/v1
 * set LLM_API_KEY=sk-xxx
 * set LLM_MODEL=deepseek-chat
 * gradlew :sdk-android:realLlmCheck
 * ```
 *
 * 可选的两个（用来验「思考强度」这条线）：
 * ```
 * set LLM_REASONING_STRENGTH=8      # 强度编码；不设 = 不带这个字段
 * set LLM_REASONING_WIRE=deepseek   # gateway（默认）/ deepseek / openai
 * ```
 * ⚠️ **直连厂商官方时 `wire` 一定要选对**：`reasoning_strength` 是**网关私有字段**，
 * 发给厂商只会被忽略或 400 ⇒ **静默不生效**（用户以为设了，其实没变）。
 * 这一档会把**实际发出去的请求体**里跟思考相关的字段打出来（**不打印任何凭据**）。
 *
 * 退出码 0 = 真链路跑通（模型真的下发了命令、命令真的执行了、结果真的回喂给了模型）。
 *
 * 注意：这一档会**真实计费**（一次对话的几百 token）。
 */
fun main(): Unit = runBlocking {
    val baseUrl = env("LLM_BASE_URL")
    val apiKey = env("LLM_API_KEY")
    if (baseUrl == null || apiKey == null) {
        println("Need two env vars: LLM_BASE_URL / LLM_API_KEY (LLM_MODEL optional, default deepseek-chat)")
        exitProcess(2)
    }
    val model = env("LLM_MODEL") ?: "deepseek-chat"

    // 思考强度：编码 + 线格式（对端是谁决定用哪种形状）。
    val strength = env("LLM_REASONING_STRENGTH")?.toIntOrNull()
    val wire = when (env("LLM_REASONING_WIRE")?.lowercase()) {
        "deepseek" -> ReasoningWire.DEEPSEEK
        "openai" -> ReasoningWire.OPENAI
        else -> ReasoningWire.GATEWAY
    }

    val shell = listOf(
        "C:/Program Files/Git/bin/bash.exe",
        "C:/Program Files/Git/usr/bin/bash.exe",
        "/bin/bash",
        "/usr/bin/bash",
    ).firstOrNull { File(it).exists() }
    if (shell == null) {
        println("No bash found on this machine; run this where a shell exists")
        exitProcess(2)
    }

    println("== real LLM check ==")
    println("baseUrl = $baseUrl")
    println("model   = $model")
    println("shell   = $shell")
    println("reasoning: strength=$strength wire=$wire")

    val layout = PrefixLayout(
        File(System.getProperty("java.io.tmpdir"), "agentkernel-real-${System.nanoTime()}").apply {
            File(this, "home").mkdirs()
            File(this, "usr/tmp").mkdirs()
        }.path
    )
    // 记录**实际发出去的请求体**（只留思考相关的字段 —— **不碰任何凭据**）。
    val recording = RecordingTransport(JdkHttpTransport())
    val client = ChatCompletionsClient(baseUrl, apiKey, recording, reasoningWire = wire)
    val executor = AndroidCommandExecutor(shellPath = shell, layout = layout)
    val kernel = AgentKernel(
        llm = client,
        tools = ToolRegistry(listOf(ShellTool(executor))),
        model = model,
        maxTurns = 8,
        reasoningStrength = strength,
    )

    val marker = "agent-kernel-alive"
    val result = kernel.runTurn(
        listOf(
            SystemMessage("你是运行在设备上的智能体。用 bash 工具执行命令来回答问题。"),
            UserMessage("用 bash 执行 `echo $marker`，然后把命令的输出原样告诉我。"),
        )
    )

    println()
    println("stopReason = ${result.stopReason}")
    println("finalText  = ${result.finalText}")

    // 结束原因：'length' = 被 max_tokens 截断 → **答案不完整**。
    // 这一条必须查：思考链会把 max_tokens 预算吃光，正文只剩几个 token，
    // 而网关如果还回报 'stop'，那就是谎报成功（客户端以为答案完整）。
    // 实测踩过：思考链用掉 4090/4096，模型只答了「结果：」就没了。
    val finishReasons = result.messages.filterIsInstance<AssistantMessage>()
        .mapNotNull { it.finishReason }
    val truncated = finishReasons.any { it == "length" }
    println("finishReasons = $finishReasons" + if (truncated) "   <-- 有截断，答案不完整" else "")

    println()
    println("--- transcript (history + this turn) ---")
    result.messages.forEach { msg ->
        val json = msg.toJson().toString()
        println("  ${msg.role}: ${if (json.length > 400) json.take(400) + "…" else json}")
    }

    // ★ 思考相关：**实际发出去的形状**（不含任何凭据）。
    val sent = recording.lastBody?.let { Json.parseToJsonElement(it).jsonObject }
    if (sent != null) {
        val keys = sent.keys.sorted()
        val thinkingType = sent["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        println()
        println("--- what we actually sent (thinking-related only) ---")
        println("body keys          = $keys")
        println("reasoning_strength = ${sent["reasoning_strength"]?.jsonPrimitive?.content ?: "(none)"}")
        println("reasoning_effort   = ${sent["reasoning_effort"]?.jsonPrimitive?.content ?: "(none)"}")
        println("thinking.type      = ${thinkingType ?: "(none)"}")
        // 线格式一致性：厂商形状下**绝不能**出现网关私有字段。
        val wireOk = when (wire) {
            ReasoningWire.GATEWAY -> true
            ReasoningWire.DEEPSEEK -> keys.contains("thinking") && !keys.contains("reasoning_strength")
            ReasoningWire.OPENAI -> keys.contains("reasoning_effort") && !keys.contains("reasoning_strength")
        }
        println("wire consistent    = $wireOk")
        println(
            "model reasoned     = " +
                result.messages.filterIsInstance<AssistantMessage>()
                    .any { !it.reasoningContent.isNullOrBlank() }
        )
    }

    val toolRan = result.messages.any {
        it.role == "tool" && it.toJson().toString().contains(marker)
    }
    val ok = result.stopReason == TurnStop.COMPLETED &&
        result.finalText?.contains(marker) == true &&
        toolRan &&
        !truncated

    println()
    println(
        when {
            truncated -> "RESULT = FAIL (finish_reason=length: the answer was truncated by max_tokens — incomplete)"
            ok -> "RESULT = PASS (model issued a command -> it really ran -> result really fed back -> model answered)"
            !toolRan -> "RESULT = FAIL (marker not found in tool result: model issued no command, or the command failed)"
            else -> "RESULT = FAIL (loop did not complete; see the transcript above)"
        }
    )
    exitProcess(if (ok) 0 else 1)
}

/**
 * 记录**实际发出去的请求体**的传输层包装。
 *
 * 只用它来看「我们到底发了什么形状的思考字段」——
 * **请求体里不含任何凭据**（key 在 header 里，这里不碰 header）。
 */
private class RecordingTransport(private val inner: Transport) : Transport {
    var lastBody: String? = null
        private set

    override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
        lastBody = body
        return inner.post(url, headers, body)
    }
}

private fun env(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
