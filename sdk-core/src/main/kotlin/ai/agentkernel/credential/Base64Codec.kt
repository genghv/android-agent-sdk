package ai.agentkernel.credential

/**
 * Base64 编码 —— **自己写一份，不依赖 `java.util.Base64`**。
 *
 * ## 为什么不用 `java.util.Base64`
 *
 * 它是 **API 26**（Android 8.0）才有的；本 SDK 的 `minSdk = 24`（Android 7.0）。
 * ⇒ 在 API 24/25 的机器上引用它，直接 **`NoClassDefFoundError`** ——
 * 而且是**类加载期**炸的，不是可捕获的异常，整个凭据功能直接起不来。
 *
 * core library desugaring 也救不了：`java.util.Base64` **不在** desugar 名单里
 * （desugar 覆盖的是 `java.time` / `java.util.stream` / `java.util.Optional` 那些）。
 *
 * ⇒ 自己实现。
 *
 * ## 为什么这不是「自己造轮子造出安全问题」
 *
 * base64 是**编码**，不是加密 —— 写错了不会造成安全后果（最坏是文件读不出来）。
 * 但实现必须**严格**：
 * - 编码结果必须与标准 base64 **逐字一致**（否则别的工具解不开）；
 * - 解码遇到非法字符 / 长度不对 / 填充不对 ⇒ **抛异常**，
 *   绝不「尽力解一点」—— 半解出来的密文比解不开更危险（会当成真密文用）。
 *
 * 加密的安全性**不来自**这里，来自闭源实现里的 Keystore 包裹 + AES-256-GCM。
 * 本文件只负责「把字节搬进 JSON / 搬出 JSON」。
 */
internal object Base64Codec {

    /** 标准 base64 字母表（RFC 4648 §4，带 `+` / `/`，带 `=` 填充）。 */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 字符 → 6 位值；-1 = 非法。 */
    private val DECODE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    /** 编码（带 `=` 填充）。 */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
            out.append(ALPHABET[(n ushr 12) and 0x3F])
            out.append(ALPHABET[(n ushr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append(ALPHABET[(n ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /**
     * 解码（**严格**）。
     *
     * @throws CredentialException 长度不是 4 的倍数 / 出现非法字符 / 填充位置不对 ——
     *   **绝不**尽力解一半。
     */
    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 != 0) {
            throw CredentialException("base64 长度不合法：${text.length}（不是 4 的倍数）")
        }
        val groups = text.length / 4
        val last = text.substring((groups - 1) * 4)
        val pad = when {
            last.endsWith("==") -> 2
            last.endsWith("=") -> 1
            else -> 0
        }
        // 填充只允许出现在最后一组；中间出现 '=' 一律非法（valueOf 会兜住，这里先给个清楚的原因）。
        if (text.substring(0, (groups - 1) * 4).indexOf('=') >= 0) {
            throw CredentialException("base64 里有非法的填充位置")
        }
        val out = ByteArray(groups * 3 - pad)
        var oi = 0
        for (g in 0 until groups - 1) {
            val base = g * 4
            val v0 = valueOf(text[base])
            val v1 = valueOf(text[base + 1])
            val v2 = valueOf(text[base + 2])
            val v3 = valueOf(text[base + 3])
            val n = (v0 shl 18) or (v1 shl 12) or (v2 shl 6) or v3
            out[oi++] = ((n ushr 16) and 0xFF).toByte()
            out[oi++] = ((n ushr 8) and 0xFF).toByte()
            out[oi++] = (n and 0xFF).toByte()
        }
        val base = (groups - 1) * 4
        val v0 = valueOf(text[base])
        val v1 = valueOf(text[base + 1])
        when (pad) {
            0 -> {
                val v2 = valueOf(text[base + 2])
                val v3 = valueOf(text[base + 3])
                val n = (v0 shl 18) or (v1 shl 12) or (v2 shl 6) or v3
                out[oi++] = ((n ushr 16) and 0xFF).toByte()
                out[oi++] = ((n ushr 8) and 0xFF).toByte()
                out[oi++] = (n and 0xFF).toByte()
            }
            1 -> {
                if (text[base + 3] != '=') throw CredentialException("base64 填充位置不对")
                val v2 = valueOf(text[base + 2])
                val n = (v0 shl 18) or (v1 shl 12) or (v2 shl 6)
                out[oi++] = ((n ushr 16) and 0xFF).toByte()
                out[oi++] = ((n ushr 8) and 0xFF).toByte()
            }
            2 -> {
                if (text[base + 2] != '=' || text[base + 3] != '=') {
                    throw CredentialException("base64 填充位置不对")
                }
                val n = (v0 shl 18) or (v1 shl 12)
                out[oi++] = ((n ushr 16) and 0xFF).toByte()
            }
        }
        return out
    }

    /** 单个字符 → 6 位值；非法字符抛异常（不猜、不跳过）。 */
    private fun valueOf(c: Char): Int {
        val v = if (c.code < 128) DECODE[c.code] else -1
        if (v < 0) throw CredentialException("base64 里有非法字符：'$c'")
        return v
    }
}
