package ai.agentkernel.credential

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * 信封加密（模式 B）的线格式与互操作性。
 *
 * 关键一条：**与后端实现逐字节对齐** —— 这里用一份由 Node/OpenSSL 侧生成的固定向量
 * （sealed + 接收方私钥）做交叉验证，避免"两边各自跑通、彼此解不开"。
 */
class EciesP256SealerTest {

    private val sealer = EciesP256Sealer()

    // 由 node:crypto 生成的固定向量（私钥仅供测试）
    private val vectorPublicKey = keyFactoryPublicKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELWW8TD7YFD2kM88b+1WevewGFUJEw/fnDMc6HgIx1oIttTNihpST5FmBYqrc7houzA3GUVKzxRaevCPG3zr3pQ==",
    )
    private val vectorPrivateKey = keyFactoryPrivateKey(
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgEQLdTB6yU8E1/6HKImgyg6703cMJB9LftJvaZHYft46hRANCAAQtZbxMPtgUPaQzzxv7VZ697AYVQkTD9+cMxzoeAjHWgi21M2KGlJPkWYFiqtzuGi7MDcZRUrPFFp68I8bfOvel",
    )
    private val vectorAad = "agri-cred/v1|ck_TESTVECTOR0000000000|deepseek|api.deepseek.com"
    private val vectorPlaintext = "{\"api_key\":\"sk-vector-0001\"}"
    private val vectorSealed =
        "AQT5bFrkTxKZR7tmo4FzLNNb+YvUkeASS7SVj1nxl824lcrTS3zjnD4ncEdu+Zms+egPJnACKX/PT3VdHagq7bZpYcU77/cX+9KAbnwHK2ZETPZYr52Du/VWYVNKvEZFJLDWIvkXrPi9HQN2594sfzrsoPVkiDm8oGY="

    @Test
    fun `能解开 node 侧生成的固定向量`() {
        val sealed = Base64.getDecoder().decode(vectorSealed)
        val plain = sealer.unseal(
            sealed,
            vectorPrivateKey,
            vectorPublicKey,
            vectorAad.toByteArray(Charsets.UTF_8),
        )
        assertEquals(vectorPlaintext, String(plain, Charsets.UTF_8))
    }

    @Test
    fun `包格式固定开销 94 字节且版本为 1`() {
        val (pub, _) = newKeyPair()
        val plain = ByteArray(28) { it.toByte() }
        val sealed = sealer.seal(plain, pub, vectorAad.toByteArray(Charsets.UTF_8))

        assertEquals(94, sealed.size - plain.size, "固定开销必须是 94 字节")
        assertEquals(1, sealed[0].toInt(), "版本号必须是 1")
        assertEquals(0x04, sealed[1].toInt(), "未压缩点标识")
    }

    @Test
    fun `自己封自己解能往返`() {
        val (pub, priv) = newKeyPair()
        val aad = "agri-cred/v1|k1|name|host".toByteArray(Charsets.UTF_8)
        val plain = "会话-token-密文往返测试".toByteArray(Charsets.UTF_8)

        val sealed = sealer.seal(plain, pub, aad)
        val back = sealer.unseal(sealed, priv, pub, aad)

        assertEquals(String(plain, Charsets.UTF_8), String(back, Charsets.UTF_8))
    }

    @Test
    fun `AAD 不一致必须解不开`() {
        val (pub, priv) = newKeyPair()
        val sealed = sealer.seal("x".toByteArray(), pub, "aad-1".toByteArray(Charsets.UTF_8))

        val result = runCatching { sealer.unseal(sealed, priv, pub, "aad-2".toByteArray(Charsets.UTF_8)) }
        assertTrue(result.isFailure, "换了 AAD 还能解开，等于没绑上下文")
    }

    @Test
    fun `密文改一个字节必须解不开`() {
        val (pub, priv) = newKeyPair()
        val sealed = sealer.seal("payload".toByteArray(), pub, "aad".toByteArray(Charsets.UTF_8))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        val result = runCatching { sealer.unseal(sealed, priv, pub, "aad".toByteArray(Charsets.UTF_8)) }
        assertTrue(result.isFailure, "密文被改还能解开")
    }

    @Test
    fun `换一把私钥必须解不开`() {
        val (pub, _) = newKeyPair()
        val (_, otherPriv) = newKeyPair()
        val sealed = sealer.seal("payload".toByteArray(), pub, "aad".toByteArray(Charsets.UTF_8))

        val result = runCatching { sealer.unseal(sealed, otherPriv, pub, "aad".toByteArray(Charsets.UTF_8)) }
        assertTrue(result.isFailure, "换了私钥还能解开")
    }

    @Test
    fun `HKDF 与 RFC 5869 测试向量一致`() {
        // RFC 5869 Test Case 1（SHA-256）
        val ikm = ByteArray(22) { 0x0b }
        val salt = fromHex("000102030405060708090a0b0c")
        val info = fromHex("f0f1f2f3f4f5f6f7f8f9")
        val okm = EciesP256Sealer.hkdfSha256(ikm, salt, info, 32)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            okm.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private fun newKeyPair(): Pair<java.security.PublicKey, java.security.PrivateKey> {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        return kp.public to kp.private
    }

    private fun keyFactoryPublicKey(spkiB64: String): java.security.PublicKey =
        java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))

    private fun keyFactoryPrivateKey(pkcs8B64: String): java.security.PrivateKey =
        java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8B64)))

    private fun fromHex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * 互通取证：把 Kotlin 封好的包写到 INTEROP_OUT 指定的文件里，
     * 交给后端（TypeScript 实现）去解 —— 只在这个环境变量存在时才跑。
     */
    @Test
    fun `按需导出 Kotlin 封装结果供后端交叉验证`() {
        val outPath = System.getenv("INTEROP_OUT") ?: return
        val aad = "agri-cred/v1|ck_TESTVECTOR0000000000|deepseek|api.deepseek.com"
        val plain = "{\"api_key\":\"sk-vector-0001\"}"
        val sealed = sealer.seal(plain.toByteArray(Charsets.UTF_8), vectorPublicKey, aad.toByteArray(Charsets.UTF_8))
        java.io.File(outPath).writeText(
            buildString {
                append("{\n")
                append("  \"aad\": \"").append(java.util.Base64.getEncoder().encodeToString(aad.toByteArray(Charsets.UTF_8))).append("\",\n")
                append("  \"sealed\": \"").append(java.util.Base64.getEncoder().encodeToString(sealed)).append("\"\n")
                append("}\n")
            },
        )
        assertEquals(94, sealed.size - plain.toByteArray(Charsets.UTF_8).size)
    }
}
