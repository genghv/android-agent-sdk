package ai.agentkernel.credential

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用**接收方公钥**封装秘密（信封加密）。客户端只做加密。
 *
 * 与 [CredentialCipher] 的分工：
 * - [CredentialCipher] 管**本地静态**加解密（Keystore 密钥，读写都在本机）；
 * - 本接口管**交给对方**的场景（用对方公钥封，只有对方私钥能解）。
 * 两者互不替代，接口刻意分开（[CredentialCipher] 已冻结，不动它）。
 *
 * 算法 `ECIES-P256-HKDF-SHA256+AES-256-GCM`，包格式固定开销 94 字节：
 *
 * ```
 * 0      1   version = 0x01
 * 1     65   ephPub（未压缩点 0x04‖X‖Y）
 * 66    12   nonce
 * 78     N   ciphertext
 * 78+N  16   tag
 * ```
 */
interface PublicKeySealer {

    /** 用接收方公钥封装明文；`aad` 参与认证（换 host / 名字 / keyId 都要解不开）。 */
    fun seal(plaintext: ByteArray, recipientPublicKey: PublicKey, aad: ByteArray): ByteArray

    /**
     * 解封。
     *
     * 需要**同时**给私钥与公钥：salt 依赖接收方公钥字节（`SHA-256(ephPub ‖ recipientPub)`），
     * 而私钥本身推不出稳定的公钥编码。
     */
    fun unseal(
        sealed: ByteArray,
        recipientPrivateKey: PrivateKey,
        recipientPublicKey: PublicKey,
        aad: ByteArray,
    ): ByteArray
}

/**
 * 标准 JCE 实现（无第三方依赖；Android API 24+ 与 JVM 均可用）。
 *
 * 约束：临时私钥用完即弃，不缓存、不落盘；会话密钥与共享密钥用完清零。
 */
class EciesP256Sealer(
    private val random: SecureRandom = SecureRandom(),
) : PublicKeySealer {

    override fun seal(plaintext: ByteArray, recipientPublicKey: PublicKey, aad: ByteArray): ByteArray {
        val recipient = recipientPublicKey as? ECPublicKey
            ?: throw CredentialException("接收方公钥不是 EC 公钥（需要 P-256）")

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val ephemeral = generator.generateKeyPair()

        val ephPubRaw = encodePoint(ephemeral.public as ECPublicKey)
        val recipientPubRaw = encodePoint(recipient)

        val shared = ecdh(ephemeral.private, recipient)
        val salt = sha256(ephPubRaw + recipientPubRaw)
        val sessionKey = hkdfSha256(shared, salt, INFO, KEY_LEN)

        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)

        sessionKey.fill(0)
        shared.fill(0)

        return byteArrayOf(VERSION.toByte()) + ephPubRaw + nonce + body
    }

    override fun unseal(
        sealed: ByteArray,
        recipientPrivateKey: PrivateKey,
        recipientPublicKey: PublicKey,
        aad: ByteArray,
    ): ByteArray {
        val priv = recipientPrivateKey as? ECPrivateKey
            ?: throw CredentialException("接收方私钥不是 EC 私钥")
        val recipient = recipientPublicKey as? ECPublicKey
            ?: throw CredentialException("接收方公钥不是 EC 公钥")

        if (sealed.size < OVERHEAD_BYTES) throw CredentialException("密文包太短")
        if (sealed[0].toInt() and 0xFF != VERSION) throw CredentialException("未知的封装版本")

        val ephPubRaw = sealed.copyOfRange(1, 66)
        val nonce = sealed.copyOfRange(66, 78)
        val body = sealed.copyOfRange(78, sealed.size)

        val ephPub = decodePoint(ephPubRaw, recipient.params)
        val recipientPubRaw = encodePoint(recipient)

        val shared = ecdh(priv, ephPub)
        val salt = sha256(ephPubRaw + recipientPubRaw)
        val sessionKey = hkdfSha256(shared, salt, INFO, KEY_LEN)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (e: Exception) {
            // 解不开就是解不开：不返回空串、不返回部分明文。
            throw CredentialException("封装解不开（AAD 或密文不匹配）", e)
        } finally {
            sessionKey.fill(0)
            shared.fill(0)
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    private fun encodePoint(key: ECPublicKey): ByteArray {
        val out = ByteArray(1 + FIELD_LEN * 2)
        out[0] = 0x04
        copyFixed(key.w.affineX, out, 1)
        copyFixed(key.w.affineY, out, 1 + FIELD_LEN)
        return out
    }

    /** BigInteger 的字节串可能带符号位、也可能短于字段长度 ⇒ 统一成固定长度大端。 */
    private fun copyFixed(value: BigInteger, dst: ByteArray, offset: Int) {
        val raw = value.toByteArray()
        val usable = if (raw.size > FIELD_LEN) raw.copyOfRange(raw.size - FIELD_LEN, raw.size) else raw
        System.arraycopy(usable, 0, dst, offset + (FIELD_LEN - usable.size), usable.size)
    }

    private fun decodePoint(raw: ByteArray, params: ECParameterSpec): ECPublicKey {
        if (raw.size != 65 || raw[0].toInt() != 0x04) throw CredentialException("临时公钥格式不对")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    companion object {
        const val VERSION: Int = 1
        private const val CURVE = "secp256r1"
        private const val FIELD_LEN = 32
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val KEY_LEN = 32
        private const val HEADER_LEN = 1 + 65 + 12
        private val INFO = "agri-cred/v1".toByteArray(Charsets.UTF_8)

        /** 固定开销：version + ephPub + nonce + tag = 94 字节。 */
        const val OVERHEAD_BYTES: Int = HEADER_LEN + TAG_BYTES

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        /** RFC 5869 HKDF-SHA256（单块输出，长度 ≤ 32）。 */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length <= 32) { "本实现只支持单块 HKDF 输出" }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(0x01)
            val okm = mac.doFinal()
            prk.fill(0)
            return okm.copyOf(length)
        }

        /** 解析接收方公钥（SPKI DER）。 */
        fun parsePublicKey(spkiDer: ByteArray): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer))
    }
}
