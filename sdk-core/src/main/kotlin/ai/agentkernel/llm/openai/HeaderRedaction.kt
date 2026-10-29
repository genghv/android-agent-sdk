package ai.agentkernel.llm.openai

/**
 * 把**请求头里的凭据**从一段文本里抹掉。
 *
 * ## 它挡的是什么
 *
 * 上游报错的**响应体**会被原样拼进异常消息（`LLM HTTP 400: <响应体前 500 字>`），
 * 而这条异常消息往往会**回灌给模型**（当工具结果 / 错误反馈）⇒
 * 一旦上游把 `Authorization` 原样回显在响应体里，**用户的第三方 API key
 * 就跟着进了模型上下文** —— 正是「凭据加密」这套设计要防的那件事。
 *
 * （同型事故已真实发生过：把解密结果直接塞进 `fetch` 头，密钥含 CR/LF 时
 *   底层库的 TypeError 原文带着**整个头值**，路由兜底 catch 又原样回浏览器
 *   ⇒ 回显明文密钥。那条是**构造**出来的，这里是**回显**出来的 ——
 *   成因不同，危害面一样。）
 *
 * ## 为什么放在这一层
 *
 * 传输层**同时**拿着两样东西：请求头（含凭据）与响应体（可能含回显的凭据）
 * ⇒ 这里是唯一能「就地比对」的位置。上层再抹一遍是**兜底**
 * （宿主自定义的传输不一定抹）。
 *
 * ## 抹哪些
 *
 * - **头名像凭据的**（`authorization` / `token` / `api-key` / `secret` / …）—— 值整个抹掉；
 * - **`Bearer <token>` 还要把 `<token>` 单独抹掉** —— 上游常常只回显 token 本身，
 *   不带 `Bearer ` 前缀。
 *
 * ⚠️ **只抹「凭据」，不抹普通头**（`Content-Type` / `Accept` 之类）：
 *   那些值是常见词，抹掉只会把报错文本弄坏（反而更难排查）。
 *
 * ⚠️ **短于 [MIN_SECRET_LEN] 的值不抹** —— 太容易误伤正常文本，
 *   而且比它短的也不是凭据。
 */
object HeaderRedaction {

    /** 抹掉之后留下的占位（**不长得像 key**，免得占位本身被当成真凭据回传）。 */
    const val MASK: String = "[已抹掉]"

    /** 短于这个长度的值不抹。 */
    const val MIN_SECRET_LEN: Int = 4

    /** 头名里出现这些词 ⇒ 这个头的值按「凭据」处理。 */
    private val SECRET_NAME = Regex("(?i)authorization|token|api[-_]?key|secret|passw|credential|auth")

    /** 这些头的值**永远不抹**（普通头，抹了只会把报错文本弄坏）。 */
    private val NEVER_MASK = setOf("content-type", "accept", "content-length", "user-agent")

    /**
     * 这批请求头里，有哪些**值**要抹。
     *
     * @return 去重后的凭据值（长的在前 ⇒ 先抹长的，避免短的是长的子串时漏掉）。
     */
    fun secretValues(headers: Map<String, String>): List<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in headers) {
            val name = k.trim().lowercase()
            if (name in NEVER_MASK) continue
            if (!SECRET_NAME.containsMatchIn(name)) continue
            val value = v.trim()
            if (value.length < MIN_SECRET_LEN) continue
            out += value
            // `Bearer <token>` ⇒ token 本身也要能抹（上游常常只回显 token，不带前缀）。
            val sp = value.indexOf(' ')
            if (sp >= 0 && sp < value.length - 1) {
                val rest = value.substring(sp + 1).trim()
                if (rest.length >= MIN_SECRET_LEN) out += rest
            }
        }
        // 长的先抹：短的值可能是长的值的子串，先抹长的才不会留下残渣。
        return out.sortedByDescending { it.length }
    }

    /**
     * 把 [headers] 里的凭据从 [text] 里抹掉。
     *
     * ⚠️ **先抹后截** —— 调用方若要取前 N 字，应该在**本方法之后**再截：
     *   先截后抹的话，恰好跨过截断点的凭据会只抹掉一半（剩下半截仍是明文）。
     */
    fun redact(text: String, headers: Map<String, String>): String {
        if (text.isEmpty()) return text
        var out = text
        for (secret in secretValues(headers)) {
            if (out.contains(secret)) out = out.replace(secret, MASK)
        }
        return out
    }
}
