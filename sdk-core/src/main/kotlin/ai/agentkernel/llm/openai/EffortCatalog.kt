package ai.agentkernel.llm.openai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 档位清单里的一档。
 *
 * @param strength **强度编码 —— 这是契约**。回传它、持久化它。
 * @param key 档位 key（`low` / `high` / …）。**只给调试看，别持久化** ——
 *   同一个 key 在不同模型上可能对应不同强度（`deepseek-flash` 没有 medium），
 *   换模型就变。持久化 strength 才对。
 * @param index 显示顺序。**别当契约**。
 * @param bits 同值的二进制形态（展示用）。
 * @param kind `off` = 关闭思考；`effort` = 普通档位。
 */
data class ReasoningLevel(
    val strength: Int,
    val key: String,
    val label: String,
    val hint: String,
    val kind: String,
    val index: Int,
    val bits: String,
) {
    /** 这一档是不是「关闭思考」。 */
    val isOff: Boolean get() = kind == "off"

    /** 强度编码（带 2 的幂校验）。 */
    val strengthValue: ReasoningStrength get() = ReasoningStrength(strength)
}

/**
 * 服务端给的档位清单。
 *
 * @param defaultStrength 服务端默认档的强度编码；null = 该模型**不支持思考**。
 */
data class EffortCatalog(
    val model: String,
    val levels: List<ReasoningLevel>,
    val defaultStrength: Int?,
    val defaultKey: String?,
) {
    /** 该模型支不支持思考（清单非空）。 */
    val supportsThinking: Boolean get() = levels.isNotEmpty()

    /** 按强度编码找档。 */
    fun levelOf(strength: Int): ReasoningLevel? = levels.firstOrNull { it.strength == strength }
}

/** 拉档位清单失败（HTTP 错 / 报文不认 / 强度编码非法）。 */
class EffortCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 拉服务端档位清单（`GET {baseUrl}/efforts`）。
 *
 * ## 为什么要拉，而不是自己抄一份
 *
 * 档位是**模型级**的，而且会变：`deepseek-flash` 声明 low/high/max（**没有 medium**），
 * glm/qwen 系声明 low/medium/high。**抄一份表 = 迟早跟服务端对不上。**
 * 服务端是校验方 ⇒ 清单只能问服务端拿（跟「校验要以校验方的视图为准」同一条）。
 *
 * ## 拉不到怎么办
 *
 * **不编清单给你**：拉不到就抛 [EffortCatalogException]，
 * 让调用方自己决定是「用默认档位」还是「显式提示用户档位不可用」。
 * 悄悄塞一份本地默认表 = 用户以为选的是服务端支持的档，实际不是。
 *
 * ⚠️ 但**档位清单不是启动前提** ⇒ 不想处理异常的调用方用
 * [fetchOrNull]（拉不到返回 null ⇒ 退回内置表，见 [VendorEffortProfiles.resolve]）。
 *
 * ## 典型用法
 * ```
 * val catalog = EffortCatalogClient(baseUrl, OkHttpTransport(), LlmAuth.bearer(token)).fetch()
 * // catalog.levels  → 给用户做选择器
 * // catalog.defaultStrength → 不传时的默认
 * val sdk = AndroidAgentSdk(..., reasoningStrength = catalog.defaultStrength)
 * ```
 */
class EffortCatalogClient(
    private val baseUrl: String,
    private val transport: GetTransport,
    private val auth: LlmAuth = LlmAuth.none(),
    /** 清单路径。后端不是标准路径时改这里。 */
    private val catalogPath: String = "/efforts",
) {

    /**
     * 拉清单。
     *
     * @throws EffortCatalogException 服务端不可用、报文不认、或强度编码非法。
     */
    suspend fun fetch(): EffortCatalog {
        val raw = try {
            transport.get("$baseUrl$catalogPath", auth.headers())
        } catch (e: Exception) {
            throw EffortCatalogException("拉档位清单失败（$baseUrl$catalogPath）：${e.message}", e)
        }
        return parse(raw)
    }

    /**
     * 拉清单；**拉不到返回 null**（不抛）。
     *
     * ## 为什么要有它
     *
     * [fetch] 的行为一个字都没变（仍然抛 [EffortCatalogException]）——
     * 「清单拉不到」这件事本身要**看得见**，否则调用方会以为档位来自服务端。
     *
     * 但**档位清单是锦上添花，不是启动前提**：
     * 服务端不可用 / 报文不认 / 强度编码非法 ⇒ 宿主**不该起不来**
     * （用户定的口径：「云端不依赖云端可得性」）。
     *
     * ⇒ 这个入口给「拉不到就退回内置表」的场景用：
     * ```
     * val catalog = client.fetchOrNull()          // 拉不到 ⇒ null
     * val profile = VendorEffortProfiles.resolve(wire, server = catalog)  // ⇒ BUILTIN
     * ```
     *
     * ⚠️ **取消不是「拉不到」**：调用方把这一轮撤了时，取消异常**原样抛出**
     * （不当成 null —— 那会让上层以为「服务端没给清单」，其实是没问成）。
     */
    suspend fun fetchOrNull(): EffortCatalog? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 解析清单报文。 */
    fun parse(raw: String): EffortCatalog {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw EffortCatalogException("档位清单不是合法 JSON：${raw.take(300)}", e)
        }
        val levelsEl = root["levels"]
        if (levelsEl !is JsonArray) {
            throw EffortCatalogException("档位清单缺少 levels 数组：${raw.take(300)}")
        }
        val levels = levelsEl.jsonArray.mapIndexed { i, el ->
            val o = el.jsonObject
            val strength = o["strength"]?.jsonPrimitive?.intOrNull
                ?: throw EffortCatalogException("档位清单第 ${i + 1} 档缺 strength：${raw.take(300)}")
            // 强度编码非法 → **当场报错**，别把一个错的值带进请求体。
            try {
                ReasoningStrength(strength)
            } catch (e: IllegalArgumentException) {
                throw EffortCatalogException("档位清单第 ${i + 1} 档的强度编码非法：${e.message}", e)
            }
            ReasoningLevel(
                strength = strength,
                key = o["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                label = o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hint = o["hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "effort",
                index = o["index"]?.jsonPrimitive?.intOrNull ?: (i + 1),
                bits = o["bits"]?.jsonPrimitive?.contentOrNull ?: ReasoningStrength(strength).bits,
            )
        }
        val defaultStrength = root["default_strength"]?.jsonPrimitive?.intOrNull
        if (defaultStrength != null) {
            try {
                ReasoningStrength(defaultStrength)
            } catch (e: IllegalArgumentException) {
                throw EffortCatalogException("清单的 default_strength 非法：${e.message}", e)
            }
        }
        return EffortCatalog(
            model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            levels = levels,
            defaultStrength = defaultStrength,
            defaultKey = root["default_key"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
