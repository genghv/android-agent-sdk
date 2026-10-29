package ai.agentkernel.credential

import ai.agentkernel.llm.openai.LlmAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 凭据的**落盘存储** —— 磁盘上永远只有密文。
 *
 * ## 它守住的五条
 *
 * 1. **磁盘上只有密文**：明文只在两个瞬间存在 ——
 *    宿主写进来那一刻、以及 [authFor] 生成请求头的那一瞬（用完即弃）。
 * 2. **密文绑住用途**：AAD = `名字 + 目标 host` ⇒ 抄走密文换个名字或换个 host **解不开**。
 * 3. **host 绑定**：凭据只允许发给**它被登记的那个 host** ——
 *    否则 agent 能诱导 App 把 key 发到别处（[authFor] 在**建客户端时**就拒，不等到发请求）。
 * 4. **解不开就失败**：[load] 抛 [CredentialException]，**不返回明文、不返回空串**。
 * 5. **没有实现就安全失败**：没注入加密实现时，[save] / [load] 抛异常，
 *    **绝不**退回明文（[list] / [hostOf] 不需要解密，所以仍能列出来）。
 *
 * ## 落盘格式
 *
 * 每个凭据一个文件 `<dir>/<name>.cred.json`：
 * ```
 * {"v":1,"name":"deepseek","host":"api.deepseek.com","blob":"<base64 密文包>"}
 * ```
 * 明文**不进这个文件**，密文包自带版本/nonce/tag（由 [CredentialCipher] 的实现决定）。
 *
 * ## 凭据名的白名单
 *
 * 名字会拼成文件名 ⇒ **必须**挡住路径穿越（`../../x`）与设备上无法表示的字符。
 * 只允许 `字母/数字` 开头，后跟 `字母/数字/点/下划线/连字符`（见 [NAME_PATTERN]）。
 *
 * ## 迁移（存量明文 → 密文）
 *
 * [migratePlaintext] 走**先写新、后删旧**：先写密文 → **再解回来验一遍** → 才删旧。
 * 任何一步失败都**保留旧文件**（宁可多留一份，也别把用户的 key 弄丢）。
 */
class CredentialStore(
    /** 凭据目录。**建议放在 agent 私有根之外**（agent 连密文文件都读不到）。 */
    private val dir: File,
    /**
     * 取加密实现的方式。
     *
     * 默认走全局加载器 [CredentialCipherLoader] —— 宿主把闭源实现注进去即可。
     * **惰性**求值是故意的：没有实现时，[list] / [hostOf] 仍然能用
     * （宿主界面要能显示「有哪些凭据」），只有真的要读写密文时才失败。
     */
    private val cipherProvider: () -> CredentialCipher = { CredentialCipherLoader.resolve() },
) {

    /** 目录准备就绪（没有就建）。 */
    fun ensureDir() {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw CredentialException("建不了凭据目录：${dir.absolutePath}")
        }
    }

    /**
     * 存一个凭据（覆盖同名）。
     *
     * @param host 这个 key **只允许**发给哪个 host（形如 `api.deepseek.com` 或完整 baseUrl）。
     */
    fun save(name: String, host: String, plaintext: String) {
        val safeName = requireName(name)
        val safeHost = host.trim()
        if (safeHost.isEmpty()) {
            throw CredentialException("凭据「$safeName」必须绑定一个目标 host（否则 key 可能被发到别处）")
        }
        ensureDir()
        val aad = aadOf(safeName, safeHost)
        val blob = cipher().seal(plaintext.toByteArray(Charsets.UTF_8), aad)
        val json = buildJsonObject {
            put("v", 1)
            put("name", safeName)
            put("host", safeHost)
            put("blob", Base64Codec.encode(blob))
        }.toString()
        val target = fileOf(safeName)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // 某些文件系统上 rename 到已存在的文件会失败 ⇒ 删掉再重命名。
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw CredentialException("写凭据文件失败：${target.absolutePath}")
            }
        }
    }

    /**
     * 读出明文。
     *
     * @return null = **没有这个凭据**（不是失败）。
     * @throws CredentialException 密文损坏 / aad 不匹配 / 解不开 / 没有加密实现 —— **不回退明文**。
     */
    fun load(name: String): String? {
        val safeName = requireName(name)
        val file = fileOf(safeName)
        if (!file.exists()) return null
        val entry = readEntry(file)
        if (entry.name != safeName) {
            // 文件被改名过（或被换成了别的凭据）⇒ 名字对不上，直接拒绝。
            // 这一条挡的是「把 A 的密文文件复制成 B 的名字」这种换名重放。
            throw CredentialException("凭据文件里的名字（${entry.name}）与请求的名字（$safeName）对不上")
        }
        val aad = aadOf(entry.name, entry.host)
        val plain = try {
            cipher().open(Base64Codec.decode(entry.blob), aad)
        } catch (e: CredentialException) {
            throw e
        } catch (e: Throwable) {
            throw CredentialException("凭据「$safeName」解不开：${e.message}", e)
        }
        return String(plain, Charsets.UTF_8)
    }

    /** 这个凭据登记的 host（**不解密**就能读）。null = 没有这个凭据。 */
    fun hostOf(name: String): String? {
        val safeName = requireName(name)
        val file = fileOf(safeName)
        if (!file.exists()) return null
        return readEntry(file).host
    }

    /** 删掉一个凭据。true = 确实删了。 */
    fun delete(name: String): Boolean {
        val file = fileOf(requireName(name))
        return if (file.exists()) file.delete() else false
    }

    /** 已存的凭据名（按名字排序）。 */
    fun list(): List<String> =
        if (!dir.exists()) emptyList()
        else dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            // 只列合法名：目录里可能被塞进别的东西（agent 有 shell 权限），
            // 那些名字不该出现在宿主界面上，也不该被当成可用的凭据。
            .filter { NAME_PATTERN.matches(it) }
            .sorted()

    /**
     * 现在**能不能解密**（有没有注入加密实现）。
     *
     * 不抛异常 —— 宿主界面用它决定「凭据功能是不是可用」。
     */
    fun isDecryptable(): Boolean = try {
        cipher()
        true
    } catch (e: Throwable) {
        false
    }

    /**
     * 给「用这个凭据访问某个 host」的场景造一个 [LlmAuth]。
     *
     * - **host 必须对得上**：凭据登记的 host 与 [baseUrl] 的 host 不一致 ⇒
     *   **当场抛异常**（fail-closed，在建客户端时就拒，不等到发请求）。
     * - **取不出 host 也拒**（⚠️ fail-closed）：`baseUrl` 或登记的 host **解析不出 host** ⇒
     *   **同样当场抛异常**。安全检查**取不到结果就当不通过**，绝不「比不了就放行」。
     * - **明文用完即弃**：[LlmAuth.bearerDynamic] 每次请求现取现用，
     *   明文只活到生成 header 的那一瞬，**不进任何字段**。
     * - **解不开就失败**：解密抛异常 ⇒ 请求**发不出去**（不会带着空 token 去问上游）。
     */
    fun authFor(name: String, baseUrl: String): LlmAuth {
        val safeName = requireName(name)
        val bound = hostOf(safeName) ?: throw CredentialException("凭据不存在：$safeName")
        // ⚠️ fail-closed：host 比不出来 ⇒ 当作「比不过」⇒ 拒。
        //    （旧写法是「两个 host 都取得到才比」⇒ 有一个取不到就**整个跳过比对**，
        //      等于放行 —— 那是一条安全检查被绕过。）
        //    异常信息里**不回显 baseUrl**：URL 里可能带 userinfo（user:口令@host）。
        val want = hostOfUrl(baseUrl) ?: throw CredentialException(
            "取不出 baseUrl 的 host ⇒ 没法确认凭据「$safeName」能发给谁，拒绝建客户端" +
                "（host 绑定是安全检查，取不到结果就当不通过）"
        )
        val have = hostOfUrl(bound) ?: throw CredentialException(
            "凭据「$safeName」登记的目标 host 取不出来 ⇒ 没法确认它能发给谁，拒绝建客户端" +
                "（host 绑定是安全检查，取不到结果就当不通过）"
        )
        if (want != have) {
            throw CredentialException(
                "凭据「$safeName」只允许发给 $have，不能发给 $want" +
                    "（host 绑定 —— 否则 key 可能被发到别处）"
            )
        }
        return LlmAuth.bearerDynamic {
            val plain = load(safeName) ?: throw CredentialException("凭据不存在：$safeName")
            plain
        }
    }

    /**
     * 迁移：把一份**明文**配置迁成密文（**先写新、后删旧**）。
     *
     * 步骤：读旧明文 → 写密文 → **解回来验一遍**（确认真的能解）→ 才删旧文件。
     * 任何一步失败都**保留旧文件** —— 宁可多留一份明文，也别把用户的 key 弄丢。
     *
     * @return true = 迁移完成（旧文件已删）；false = 旧文件本来就不存在。
     */
    fun migratePlaintext(legacy: File, name: String, host: String): Boolean {
        if (!legacy.exists()) return false
        val plaintext = try {
            legacy.readText(Charsets.UTF_8).trim()
        } catch (e: Throwable) {
            throw CredentialException("读旧的明文配置失败：${legacy.absolutePath}", e)
        }
        if (plaintext.isEmpty()) {
            legacy.delete()
            return true
        }
        // ① 写新（密文）
        save(name, host, plaintext)
        // ② 验：新密文必须能解回来，且内容一致 —— 不一致就不删旧的。
        val check = try {
            load(name)
        } catch (e: CredentialException) {
            throw CredentialException("迁移校验没过（密文解不开），保留旧的明文文件不动", e)
        }
        if (check == null || check != plaintext) {
            throw CredentialException("迁移校验没过（密文解出来对不上），保留旧的明文文件不动")
        }
        // ③ 才删旧
        legacy.delete()
        return true
    }

    // ---------- 内部 ----------

    private data class Entry(val name: String, val host: String, val blob: String)

    private fun fileOf(name: String): File = File(dir, "$name$SUFFIX")

    /** 取加密实现；拿不到就抛（**绝不**退回明文）。 */
    private fun cipher(): CredentialCipher = try {
        cipherProvider()
    } catch (e: CredentialException) {
        throw e
    } catch (e: Throwable) {
        throw CredentialException("取不到凭据加密实现：${e.message}", e)
    }

    /** 凭据名白名单：挡住路径穿越与设备上无法表示的字符。 */
    private fun requireName(name: String): String {
        val s = name.trim()
        if (!NAME_PATTERN.matches(s)) {
            throw CredentialException(
                "凭据名不合法：$name（只允许字母或数字开头，后面跟字母/数字/点/下划线/连字符，最长 64 位）"
            )
        }
        return s
    }

    private fun readEntry(file: File): Entry {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Throwable) {
            throw CredentialException("读凭据文件失败：${file.absolutePath}", e)
        }
        val obj = try {
            JSON.parseToJsonElement(text).jsonObject
        } catch (e: Throwable) {
            throw CredentialException("凭据文件不是合法 JSON：${file.absolutePath}", e)
        }
        fun field(key: String): String =
            obj[key]?.jsonPrimitive?.contentOrNull
                ?: throw CredentialException("凭据文件缺 $key：${file.absolutePath}")
        return Entry(field("name"), field("host"), field("blob"))
    }

    /** AAD：绑住「名字 + 目标 host」⇒ 密文换用途/换 host 就解不开。 */
    private fun aadOf(name: String, host: String): ByteArray =
        "android-agent-sdk/credential/v1|$name|$host".toByteArray(Charsets.UTF_8)

    companion object {
        /** 凭据文件后缀。 */
        const val SUFFIX: String = ".cred.json"

        /** 凭据名白名单（会拼进文件名 ⇒ 必须挡住路径穿越）。 */
        private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * 从一个 URL 里取 host（`https://api.deepseek.com/v1` ⇒ `api.deepseek.com`）。
         * 拿不出就 null —— ⚠️ 调用方按「**不通过**」处理（**拒绝建客户端**），
         * **不是**「无法比较就跳过比对」⇒ 那正是 v1.1.8 修掉的那个洞（fail-open）。
         */
        fun hostOfUrl(url: String): String? {
            var s = url.trim()
            if (s.isEmpty()) return null
            // 去掉 userinfo（`https://user:pass@host`）
            s = s.substringAfterLast('@')
            // 去掉协议
            val afterScheme = s.substringAfter("://", s)
            // 去掉路径 / 查询 / 片段
            var hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            if (hostPort.isEmpty()) return null
            hostPort = if (hostPort.startsWith("[")) {
                // IPv6：`[::1]:8443`
                hostPort.substringBefore(']').removePrefix("[")
            } else {
                hostPort.substringBeforeLast(':')
            }
            val host = hostPort.lowercase()
            return host.ifBlank { null }
        }
    }
}
