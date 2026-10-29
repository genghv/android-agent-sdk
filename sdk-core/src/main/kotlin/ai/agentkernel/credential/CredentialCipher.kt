package ai.agentkernel.credential

/**
 * 凭据的加解密实现 —— **开源仓只留这个接口**。
 *
 * ## 为什么是接口而不是实现
 *
 * 设备上的第三方模型 API 凭据（`base_url` + `api_key`）**明文落盘 = 泄给模型**：
 * 我们的 agent 内置 shell 工具、能读 App 私有目录 ⇒ 一次 `cat` 就把 key 读进上下文
 * ⇒ 泄露给上游模型服务商、会话历史、用量日志。
 *
 * ⇒ 加密核心（Keystore 包裹 + AES-256-GCM）**闭源**，以预编译产物单独分发；
 * 开源仓留**接口 + 加载器**，宿主把实现注进来。
 *
 * ## 实现方必须守住的三条
 *
 * 1. **解不开就抛异常** —— 调用方**不许回退明文**（回退 = 加密没做，白忙）。
 * 2. **AAD 绑住用途** —— 密文包必须绑住「名字 + 目标 host」，
 *    否则抄走密文换个名字/换个 host 就能用（那等于没加密）。
 * 3. **密钥材料不可导出** —— 真保护来自 Keystore 里不可导出的密钥，
 *    不是来自「别人看不到源码」。
 *
 * ## 用法
 *
 * ```
 * // 宿主（App）把实现注进来 —— 闭源 .aar 里的那个类
 * CredentialCipherLoader.register("com.example.KeystoreCredentialCipher")
 *
 * val store = CredentialStore(dir = File(filesDir, "agent/var/credentials"), cipher = CredentialCipherLoader.resolve())
 * store.save("deepseek", "https://api.deepseek.com", apiKeyFromUserInput)
 *
 * // 发请求时：明文只活到生成 header 的那一瞬
 * val auth = store.authFor("deepseek", baseUrl = "https://api.deepseek.com/v1")
 * ```
 */
interface CredentialCipher {

    /**
     * 把明文封成可落盘的密文包。
     *
     * @param plain 明文（例如 `api_key` 的字节）。
     * @param aad **绑定信息**（用途 + 名字 + 目标 host）：解密时必须传**同样的** aad，
     *   否则必须失败 —— 密文被换用途/换 host 后不许还能解开。
     * @return 密文包（含版本、nonce、密文、tag 等，自描述）。
     */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    /**
     * 解密。
     *
     * ⚠️ **解不开必须抛 [CredentialException]**，不许返回明文、不许返回空串。
     * 调用方据此「安全地失败」（不发请求），**绝不**回退到明文。
     */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * 凭据相关的失败。
 *
 * 触发场景：Keystore 不可用 / 密文被篡改 / aad 不匹配（密文被换了用途或 host）/
 * 目标 host 与凭据绑定的不一致 / 实现缺失（宿主没注入闭源实现）。
 *
 * **所有这些都必须是「安全地失败」**：不发请求、不留明文、不降级。
 */
class CredentialException(message: String, cause: Throwable? = null) : Exception(message, cause)
