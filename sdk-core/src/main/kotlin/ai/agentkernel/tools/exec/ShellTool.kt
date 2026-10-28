package ai.agentkernel.tools.exec

import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.tools.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 把「模型输出的 tool_call」接到「设备执行」的那一根线。
 *
 * 链路：模型返回 tool_calls[].function.arguments = {"command":"..."}
 *   → 内核 parse 出 JSON → 这里取出 command
 *   → 交给 CommandExecutor 跑 → render 成文本 → 内核回填 role:"tool"。
 *
 * ## 为什么要把能力清单写进描述里
 *
 * 模型只看得见工具的 `description`。能力实现了但没写进描述，等于没实现 ——
 * 模型不会凭空猜出 `termux-torch` 这个词，它只会写 `ls`、`cat`、`echo`。
 * 所以注册了能力，就把 [DeviceCapabilityRegistry.describeLines] 拼进来。
 */
class ShellTool(
    private val executor: CommandExecutor,
    override val name: String = "bash",
    private val defaultTimeoutMs: Long = 60_000L,
    /**
     * 设备能力表（可选）。填了就会把能力命令清单写进工具描述，
     * 让模型知道除了普通 shell 命令还能用哪些设备能力。
     */
    private val capabilities: DeviceCapabilityRegistry? = null,
) : Tool {

    override val description: String = buildString {
        append("在 Android 设备上执行一条 shell 命令，返回 stdout / stderr / 退出码。")
        append("命令运行在精简的 Linux 用户态环境中，可用于文件、进程、网络等操作。")
        val caps = capabilities
        val lines = caps?.describeLines().orEmpty()
        if (lines.isNotEmpty()) {
            append("\n\n除普通命令外，还可以用以下设备能力命令（由宿主在进程内执行，")
            append("不需要 PATH 里有对应二进制；输出是 JSON）：")
            lines.forEach { append("\n- ").append(it) }
        }
    }

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "要执行的 shell 命令")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "超时毫秒数，默认 $defaultTimeoutMs")
            })
        })
        putJsonArray("required") { add("command") }
    }

    override suspend fun execute(arguments: JsonObject): String {
        val command = arguments["command"]?.jsonPrimitive?.contentOrNull
            ?: return "error: 缺少 `command` 参数"
        val timeout = arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: defaultTimeoutMs
        return executor.execute(command, timeout).render()
    }
}