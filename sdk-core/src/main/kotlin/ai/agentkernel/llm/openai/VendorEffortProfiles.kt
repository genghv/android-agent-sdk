package ai.agentkernel.llm.openai

/**
 * 档位档案的**来源**。
 *
 * 取值优先级：**USER > SERVER > BUILTIN**（见 [VendorEffortProfiles.resolve]）。
 */
enum class EffortProfileSource {
    /** 内置表（离线可用；依据是官方文档 + 我方核对过的口径）。 */
    BUILTIN,

    /** 服务端清单（`GET /efforts`）—— **以服务端的视图为准**（它是校验方）。 */
    SERVER,

    /** 宿主自己给的（最高优先级；宿主最清楚自己接的是什么）。 */
    USER,
}

/**
 * 某个厂商/模型的**档位档案** —— 「支持哪些档、每档叫什么、来源是哪」这件事的完整答案。
 *
 * ## 为什么要有它
 *
 * 档位是**模型级**的，而且会变：`deepseek-flash` 声明 low/high/max（**没有 medium**），
 * glm/qwen 系声明 low/medium/high。**抄一份表 = 迟早跟服务端对不上** ⇒
 * 清单要么问服务端拿（[EffortCatalogClient]），要么用**内置表兜底**（[VendorEffortProfiles]）。
 *
 * ## 三处事实一起记，别只记档位
 *
 * - [docUrl] / [docFetchedAt]：**来源可查**（哪天核的官方文档，一目了然）。
 * - [invalidParamsInThinkingMode]：**思考模式下厂商不收的参数**
 *   （DeepSeek 官方：思考模式**不支持** `temperature` / `presence_penalty` / `frequency_penalty`）——
 *   宿主据此**别在思考模式下发这些参数**（发了会被厂商报错或忽略）。
 *
 * @param vendor 厂商名（`deepseek` / `openai` / `gateway`）。
 * @param levels 档位清单（[ReasoningLevel]；**strength 才是契约**，key 只给调试看）。
 * @param defaultStrength 默认档的强度编码；null = 该模型**不支持思考**。
 * @param levelNames **已知档位**的「强度编码 → 厂商侧档位名」表（不含推导，见 [levelNameFor]）。
 * @param model 本档案针对的模型；null = 不针对具体模型。
 * @param docUrl 官方文档地址。
 * @param docFetchedAt 文档抓取日期（ISO-8601 日期）。
 * @param invalidParamsInThinkingMode 思考模式下**失效**的请求参数名。
 * @param source 这份档案的来源。
 */
data class VendorEffortProfile(
    val vendor: String,
    val levels: List<ReasoningLevel> = emptyList(),
    val defaultStrength: Int? = null,
    val levelNames: Map<Int, String> = emptyMap(),
    val model: String? = null,
    val docUrl: String? = null,
    val docFetchedAt: String? = null,
    val invalidParamsInThinkingMode: Set<String> = emptySet(),
    val source: EffortProfileSource = EffortProfileSource.BUILTIN,
) {

    /**
     * 强度编码 → 厂商侧档位名（**取 ≤ 上限里最高的已知档 ⇒ 只向下**）。
     *
     * ⚠️ **这不是官方那张「用户 effort → 模型实际 effort」的表** ——
     * 官方那张（DeepSeek）是 `minimal→low`、`low→low`、`medium→high`、`high→high`、
     * `xhigh→high`、`max→max`、`ultra→max`：**厂商把用户请求的 effort 归一化**。
     * 我方这张是**另一件事**：「**我方编码（上限）→ 厂商实际档位（只向下）**」。
     *
     * @return 档位名；该编码之下**一个已知档都没有**时返回 null
     *   （例如编码 1 = 关闭思考，而关闭思考在直连时**不发 effort**）。
     */
    fun levelNameFor(strength: Int): String? {
        var best: Int? = null
        for (code in levelNames.keys) {
            if (code <= strength && (best == null || code > best)) best = code
        }
        return best?.let { levelNames[it] }
    }

    /** 按强度编码找档。 */
    fun levelOf(strength: Int): ReasoningLevel? = levels.firstOrNull { it.strength == strength }

    /** 该模型支不支持思考（档位非空）。 */
    val supportsThinking: Boolean get() = levels.isNotEmpty()
}

/**
 * 档位档案的**内置表**与**解析规则**。
 *
 * ## 三态来源，优先级 USER > SERVER > BUILTIN
 *
 * | 来源 | 什么时候用 | 拿不到怎么办 |
 * |---|---|---|
 * | [EffortProfileSource.USER] | 宿主自己有权威来源 | —— |
 * | [EffortProfileSource.SERVER] | 网关给了模型级清单（`GET /efforts`） | **落回 BUILTIN** |
 * | [EffortProfileSource.BUILTIN] | 兜底（离线可用） | —— |
 *
 * ⚠️ **服务端拉不到时必须落回 BUILTIN，不能让宿主起不来** ——
 * 用户定的口径是「**云端不依赖**宿主配置、也不依赖云端可得性」：
 * 档位清单是**锦上添花**，不是**启动前提**。
 * ⇒ 拉取入口用 [EffortCatalogClient.fetchOrNull]（拉不到返回 null），再用 [resolve] 解析。
 *
 * ## 内置表从哪来
 *
 * - DeepSeek：[DEEPSEEK_DOC_URL]（思考模式官方文档），抓取日期 [DEEPSEEK_DOC_FETCHED_AT]；
 *   官方**默认**思考开 + effort **high** ⇒ 内置表的默认档 = high。
 *   官方思考模式**不支持** `temperature` / `presence_penalty` / `frequency_penalty`
 *   ⇒ 记在 [DEEPSEEK_INVALID_IN_THINKING]。
 * - OpenAI：档位是**字符串**（`minimal` / `low` / `medium` / `high`），
 *   **没有**「关闭思考」这一档 ⇒ 最接近 `minimal`（见 [ReasoningWire] 的表）。
 */
object VendorEffortProfiles {

    /** DeepSeek 官方「思考模式」文档。 */
    const val DEEPSEEK_DOC_URL: String = "https://api-docs.deepseek.com/guides/thinking_mode"

    /** 上面那份文档的**抓取日期**（我方核对官方口径的那天）。 */
    const val DEEPSEEK_DOC_FETCHED_AT: String = "2026-10-29"

    /** OpenAI Chat Completions 文档。 */
    const val OPENAI_DOC_URL: String = "https://platform.openai.com/docs/api-reference/chat/create"

    /** 上面那份文档的抓取日期。 */
    const val OPENAI_DOC_FETCHED_AT: String = "2026-10-29"

    /**
     * 思考模式下 DeepSeek **不支持**的请求参数（官方文档明列）。
     *
     * 宿主据此**别在思考模式下发这些参数**。
     */
    val DEEPSEEK_INVALID_IN_THINKING: Set<String> =
        setOf("temperature", "presence_penalty", "frequency_penalty")

    /** DeepSeek 官方档位档案（离线内置）。 */
    val DEEPSEEK: VendorEffortProfile = VendorEffortProfile(
        vendor = "deepseek",
        levels = listOf(
            ReasoningLevel(
                strength = ReasoningStrength.OFF.value,
                key = "off",
                label = "关闭思考",
                hint = "只发 thinking.type=disabled，不发 effort",
                kind = "off",
                index = 0,
                bits = ReasoningStrength.OFF.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.LOW.value,
                key = "low",
                label = "低",
                hint = "reasoning_effort=low",
                kind = "effort",
                index = 1,
                bits = ReasoningStrength.LOW.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.HIGH.value,
                key = "high",
                label = "高",
                hint = "厂商默认档（reasoning_effort=high）",
                kind = "effort",
                index = 2,
                bits = ReasoningStrength.HIGH.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.MAX.value,
                key = "max",
                label = "最高",
                hint = "reasoning_effort=max",
                kind = "effort",
                index = 3,
                bits = ReasoningStrength.MAX.bits,
            ),
        ),
        defaultStrength = ReasoningStrength.HIGH.value,
        // 只记**已知档位**：DeepSeek 的实际档位只有 low / high / max（中间没有位置）。
        levelNames = mapOf(
            ReasoningStrength.LOW.value to "low",
            ReasoningStrength.HIGH.value to "high",
            ReasoningStrength.MAX.value to "max",
        ),
        docUrl = DEEPSEEK_DOC_URL,
        docFetchedAt = DEEPSEEK_DOC_FETCHED_AT,
        invalidParamsInThinkingMode = DEEPSEEK_INVALID_IN_THINKING,
        source = EffortProfileSource.BUILTIN,
    )

    /** OpenAI 系档位档案（离线内置）。档位是**字符串**，且**没有**「关闭思考」这一档。 */
    val OPENAI: VendorEffortProfile = VendorEffortProfile(
        vendor = "openai",
        levels = listOf(
            ReasoningLevel(
                strength = ReasoningStrength.OFF.value,
                key = "minimal",
                label = "最小",
                hint = "OpenAI 没有「关闭思考」这一档，最接近的是 minimal",
                kind = "effort",
                index = 0,
                bits = ReasoningStrength.OFF.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.LOW.value,
                key = "low",
                label = "低",
                hint = "reasoning_effort=low",
                kind = "effort",
                index = 1,
                bits = ReasoningStrength.LOW.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.MEDIUM.value,
                key = "medium",
                label = "中",
                hint = "reasoning_effort=medium",
                kind = "effort",
                index = 2,
                bits = ReasoningStrength.MEDIUM.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.HIGH.value,
                key = "high",
                label = "高",
                hint = "reasoning_effort=high",
                kind = "effort",
                index = 3,
                bits = ReasoningStrength.HIGH.bits,
            ),
        ),
        defaultStrength = ReasoningStrength.HIGH.value,
        levelNames = mapOf(
            ReasoningStrength.OFF.value to "minimal",
            ReasoningStrength.LOW.value to "low",
            ReasoningStrength.MEDIUM.value to "medium",
            ReasoningStrength.HIGH.value to "high",
        ),
        docUrl = OPENAI_DOC_URL,
        docFetchedAt = OPENAI_DOC_FETCHED_AT,
        source = EffortProfileSource.BUILTIN,
    )

    /**
     * 我方网关的档案。
     *
     * 网关**自己**按模型目录把编码翻译成厂商档位 ⇒ 这边**没有厂商侧档位名**
     * ⇒ [VendorEffortProfile.levelNames] 是空的（编码原样转发，翻译发生在服务端）。
     */
    val GATEWAY: VendorEffortProfile = VendorEffortProfile(
        vendor = "gateway",
        levels = listOf(
            ReasoningLevel(
                strength = ReasoningStrength.OFF.value,
                key = "off",
                label = "关闭思考",
                hint = "reasoning_strength=1",
                kind = "off",
                index = 0,
                bits = ReasoningStrength.OFF.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.LOW.value,
                key = "low",
                label = "低",
                hint = "reasoning_strength=2",
                kind = "effort",
                index = 1,
                bits = ReasoningStrength.LOW.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.MEDIUM.value,
                key = "medium",
                label = "中",
                hint = "reasoning_strength=4（不是每个模型都有）",
                kind = "effort",
                index = 2,
                bits = ReasoningStrength.MEDIUM.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.HIGH.value,
                key = "high",
                label = "高",
                hint = "reasoning_strength=8（服务端默认档）",
                kind = "effort",
                index = 3,
                bits = ReasoningStrength.HIGH.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.MAX.value,
                key = "max",
                label = "最高",
                hint = "reasoning_strength=16",
                kind = "effort",
                index = 4,
                bits = ReasoningStrength.MAX.bits,
            ),
            ReasoningLevel(
                strength = ReasoningStrength.XHIGH.value,
                key = "xhigh",
                label = "超高",
                hint = "reasoning_strength=32（只有模型自己声明了才有）",
                kind = "effort",
                index = 5,
                bits = ReasoningStrength.XHIGH.bits,
            ),
        ),
        defaultStrength = ReasoningStrength.HIGH.value,
        docUrl = null,
        docFetchedAt = null,
        source = EffortProfileSource.BUILTIN,
    )

    /** 按线格式取内置档案。 */
    fun builtinFor(wire: ReasoningWire): VendorEffortProfile = when (wire) {
        ReasoningWire.GATEWAY -> GATEWAY
        ReasoningWire.DEEPSEEK -> DEEPSEEK
        ReasoningWire.OPENAI -> OPENAI
    }

    /**
     * 解析出**最终生效**的档案：**USER > SERVER > BUILTIN**。
     *
     * - [user] 不为 null ⇒ 直接用它（宿主最清楚自己接的是什么）；
     * - [server] 不为 null ⇒ 用服务端的档位清单（**以服务端的视图为准**），来源标 [EffortProfileSource.SERVER]；
     * - 都没有 ⇒ 内置表（来源标 [EffortProfileSource.BUILTIN]）。
     *
     * ⚠️ **服务端拉不到时传 null 即可 —— 不抛异常、不让宿主起不来**。
     */
    fun resolve(
        wire: ReasoningWire,
        server: EffortCatalog? = null,
        user: VendorEffortProfile? = null,
    ): VendorEffortProfile {
        user?.let { return it }
        val builtin = builtinFor(wire)
        if (server == null) return builtin
        return builtin.copy(
            model = server.model,
            levels = server.levels,
            defaultStrength = server.defaultStrength,
            source = EffortProfileSource.SERVER,
        )
    }
}
