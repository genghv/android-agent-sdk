package ai.agentkernel.llm.openai

/**
 * LLM 网关的鉴权方式。
 *
 * ## 为什么要抽出来
 *
 * 官方 OpenAI 是 `Authorization: Bearer <secret key>`，
 * 但**自建服务不一定这样**：可能用自己的头名（`X-Token` / `X-Api-Key`）、
 * 可能用别的方案（`token` 查询参数、网关签名……）。
 * 把鉴权写死在客户端里，等换网关就得改 SDK。
 *
 * 抽出来之后，**线格式一个字都不用改** ——
 * 请求体、响应解析、流式聚合全是 OpenAI 那套，
 * 只有「这个请求带什么头」是各家自定义的。
 *
 * ## 用法
 * ```
 * // 自建服务：Bearer token
 * LlmAuth.bearer(token)
 *
 * // 自建服务：自己的头名
 * LlmAuth.header("X-Token", token)
 *
 * // token 会过期、要动态取（比如从你们自己的登录态里拿）
 * LlmAuth.bearer { tokenProvider.currentToken() }
 * ```
 */
fun interface LlmAuth {
    /** 这次请求要带的鉴权头。返回空 map = 不带鉴权。 */
    fun headers(): Map<String, String>

    companion object {
        /** `Authorization: Bearer <token>` —— 绝大多数兼容网关都吃这个。 */
        fun bearer(token: String): LlmAuth = LlmAuth { mapOf("Authorization" to "Bearer $token") }

        /**
         * `Authorization: Bearer <token>`，token 每次请求现取。
         *
         * token 会过期时用这个（每次请求都重新拿，不用重建客户端）。
         *
         * 方法名**故意不叫 `bearer`**：Kotlin 遇到 `bearer(String)` 与 `bearer(() -> String)`
         * 两个重载时，光靠 lambda 字面量分不出该选哪个（报
         * `Cannot infer type for this parameter`）。
         */
        fun bearerDynamic(tokenProvider: () -> String): LlmAuth =
            LlmAuth { mapOf("Authorization" to "Bearer ${tokenProvider()}") }

        /** 自定义头名 + 值，例如 `X-Token`。 */
        fun header(name: String, value: String): LlmAuth = LlmAuth { mapOf(name to value) }

        /** 不带鉴权（内网免鉴权的网关、或用查询参数带 token 的场景）。 */
        fun none(): LlmAuth = LlmAuth { emptyMap() }
    }
}
