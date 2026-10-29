package ai.agentkernel.credential

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 凭据的**命令入口**（前缀 `agent-`）与**宿主侧 API**。
 *
 * ## ⚠️ 一条硬规矩：**命令面永远不吐明文**
 *
 * 这套能力存在的全部意义，就是让**模型读不到**用户的第三方 API key。
 * 若命令面能 `get` 出明文，那 agent 一句 `agent-cred get deepseek` 就把 key
 * 读进上下文 ⇒ **泄露给上游模型服务商、会话历史、用量日志** ——
 * 等于把我们要防的东西又送回去了。
 *
 * ⇒ 因此：
 * - **命令面（agent 能调的）**：只有 `list` / `status` ——
 *   只回答「有没有、绑在哪个 host、现在能不能解密」，**没有明文**；
 * - **宿主侧 API（App 调的）**：[setSecret] / [deleteSecret] / [secretFor] —
 *   写入与读取明文**只走这条**，不进命令面、不进模型可见的任何通道。
 *
 * ## 命令
 *
 * ```
 * agent-cred list          # 有哪些凭据、各自绑在哪个 host（没有明文）
 * agent-cred status <name>  # 这一条的状态（没有明文）
 * ```
 *
 * ## 为什么写入不做成命令
 *
 * 命令的**参数会进模型上下文**（命令回显、转录、工具历史）。
 * 把 key 当参数传 ⇒ key 立刻进上下文 ⇒ 加密白做。
 * ⇒ 写入只能由**宿主**直接调 [setSecret]。
 */
class AgentCredentialCapabilities(private val store: CredentialStore) {

    val capabilities: List<DeviceCapability> = listOf(CredentialCapability(store))

    // ---------- 宿主侧 API（**明文只走这里**）----------

    /**
     * 写入一个凭据（宿主调用：用户在 App 里填了 API key）。
     *
     * @param host 这个 key **只允许**发给哪个 host。
     */
    fun setSecret(name: String, host: String, plaintext: String) {
        store.save(name, host, plaintext)
    }

    /**
     * 取出明文（**仅宿主调用**，例如宿主自己要用它做一次校验）。
     *
     * ⚠️ 调用方**不许**把结果写进日志、提示词、命令参数或任何会被模型看到的地方。
     */
    fun secretFor(name: String): String? = store.load(name)

    /** 删掉一个凭据（宿主调用）。 */
    fun deleteSecret(name: String): Boolean = store.delete(name)

    /** 已存的凭据名（宿主调用）。 */
    fun listSecrets(): List<String> = store.list()
}

/** `agent-cred` —— **只读**，且**永远不吐明文**。 */
private class CredentialCapability(private val store: CredentialStore) : DeviceCapability {
    override val name: String = "cred"
    override val origin: CapabilityOrigin = CapabilityOrigin.INTERNAL

    /**
     * 用法里**故意不出现**任何「取值」的子命令 ——
     * 用法文本本身会进提示词（进模型上下文），
     * 写着「怎么把 key 读出来」就是在教模型怎么读。
     */
    override val usage: String = "agent-cred <list|status <name>>"

    /** 短用法（核心模式用）。 */
    override val shortUsage: String = "agent-cred <list|status>"

    override fun run(args: List<String>): String {
        val action = args.firstOrNull() ?: throw CapabilityFailure("用法：$usage")
        return when (action) {
            "list" -> listAll()
            "status" -> status(args.getOrNull(1))
            else -> throw CapabilityFailure("不认识的子命令「$action」。用法：$usage")
        }
    }

    private fun listAll(): String = okJson {
        put("ok", true)
        put("credentials", buildJsonArray {
            store.list().forEach { name ->
                add(
                    buildJsonObject {
                        put("name", name)
                        // 文件坏了也不要连累整条命令 —— 这里拿不到 host 就留空。
                        put("host", runCatching { store.hostOf(name) }.getOrNull() ?: "")
                    }
                )
            }
        })
        put("note", "这里只有名字和目标 host —— **凭据内容永远不通过命令面返回**。")
    }

    private fun status(name: String?): String {
        if (name.isNullOrBlank()) throw CapabilityFailure("用法：$usage")
        // 用 list() 判存在性：目录里只可能有合法名 ⇒ 不在列表里就是「没有这个凭据」
        // （顺带把「名字本身不合法」也归到这一类 —— 模型看到的都是同一句人话）。
        if (!store.list().contains(name)) throw CapabilityFailure("没有这个凭据：$name")
        val host = try {
            store.hostOf(name)
        } catch (e: CredentialException) {
            throw CapabilityFailure("凭据「$name」读不出来：${e.message}")
        } ?: throw CapabilityFailure("没有这个凭据：$name")
        return okJson {
            put("ok", true)
            put("name", name)
            put("host", host)
            put("state", "present")
            // 没有注入加密实现 ⇒ 这条凭据现在**解不开**（安全失败，不是「读不出来」）。
            put("decryptable", store.isDecryptable())
            put("note", "凭据内容**不通过命令面返回**；要改用宿主 App 的设置入口。")
        }
    }
}
