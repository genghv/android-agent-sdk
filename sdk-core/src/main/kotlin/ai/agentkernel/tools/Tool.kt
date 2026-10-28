package ai.agentkernel.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 工具契约。一个工具 = 名字 + 描述 + 参数 JSON Schema + 执行体。
 * 暴露给模型的 schema 由 [schema] 统一投影（函数式 tool 的标准形状）。
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** 执行并返回给模型看的文本结果（成功或错误都以字符串回填）。 */
    suspend fun execute(arguments: JsonObject): String

    fun schema(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }
}

/** 工具注册表：提供 schema 列表与按名查找。 */
class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val map = LinkedHashMap<String, Tool>()

    init {
        tools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        map[tool.name] = tool
    }

    operator fun get(name: String): Tool? = map[name]

    /**
     * 工具 schema 列表 —— **按工具名字的字典序**。
     *
     * ## 为什么必须排序（这是前缀缓存的硬要求）
     *
     * dsh 对每个包都写了同一条规定（见 `packages/core/tools/README.md:122`）：
     * 工具 schema 必须**字节级稳定**。上游的前缀缓存认的是「逐字相同的前缀」，
     * 而 `tools` 字段就排在提示词后面 —— 工具列表的**顺序**一变，
     * 那一段字节就变了，**上游前缀缓存当场清零**。
     *
     * ## 为什么是字典序、不是注册顺序
     *
     * 注册顺序依赖宿主把工具放进注册表的顺序（可能随 App 版本、随配置、随插件加载顺序变）；
     * 字典序只依赖**工具名** —— 同一组工具永远得到同一串字节，
     * 与谁先注册、什么时候注册完全无关。
     *
     * 代价为零，收益是「工具集没变 ⇒ 前缀必然对得上」。
     */
    fun schemas(): List<JsonObject> =
        map.values.map { it.schema() }.sortedBy { schemaName(it) }

    /** 从 schema 里取工具名（排序键）。 */
    private fun schemaName(schema: JsonObject): String =
        schema["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""

    fun names(): Set<String> = map.keys
}