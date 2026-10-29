package com.agentsdk.cipher

import ai.agentkernel.credential.CredentialCipher
import ai.agentkernel.credential.CredentialException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 加密核心的**契约测试**（公开版 · JVM 上跑）。
 *
 * ## 这份测试为什么在开源仓里
 *
 * **实现是闭源的，契约是公开的** —— 密文包的格式与 AEAD 语义属于**接口的一部分**
 * （见《凭据加密模块规格.md》），公开它不会泄露实现。
 * ⇒ 好处：任何人都能对着**已发布的 `.aar`** 独立复现
 *   「契约测试全绿」这句话，而不是只能相信我们的说法。
 *
 * ⚠️ 这里的密钥是**内存里的一把固定 AES 密钥**，不是真 Keystore —— 真 Keystore 只有设备上才有。
 * ⇒ 这里验的是**格式与 AEAD 语义**（换 aad 解不开、改一个字节解不开…），
 *    Keystore 那条（真设备上验）**尚未实测**，别把它当已验。
 *
 * ## ★ 为什么用反射造实例
 *
 * 闭源产物对外**只有无参构造**（走真 Android Keystore）⇒ 在 JVM 上造不出实例。
 * 而「把密钥换成内存里的那把」这个口子（`InMemoryKeyOps`）在闭源产物里是 **`internal`**：
 * - `javap` 看字节码是 `public`（Kotlin 的 `internal` 编译进字节码就是 `public`），
 * - 但 **Kotlin 编译器拒绝外部模块访问** ⇒ 直接写
 *   `KeystoreCredentialCipher(InMemoryKeyOps())` 会**编译不过**
 *   （`Cannot access ...: it is internal in 'com/agentsdk/cipher/KeystoreCredentialCipher'`）。
 *
 * ⇒ 所以这里用反射造实例。
 * ★ **被验的 `seal` / `open` 走的是公开接口 `CredentialCipher`** —— 反射只用来「造一个
 *   不依赖 Keystore 的实例」，**不参与被验行为**。
 *
 * ## ★ 每条「解不开」类用例都**先自证基线是好的**
 *
 * 2026-10-29 实踩：包格式的 nonce 偏移写错了 12 字节 ⇒ **整包解不开**。
 * 而当时 10 条用例里有 9 条是「解不开」类的 ⇒ 它们**照样全绿**
 * （「因为该抛而抛」和「因为全坏了而抛」，它们分不出来）——
 * 只有「密封后能原样解开」那条是红的，**但它红得没人先看见**（先看到的是一堆绿）。
 *
 * ⇒ 现在每条负向用例都先跑一遍**正向往返**（[sealedWithHealthyBaseline]）：
 * 包格式一坏，**正向那条先红**，负向用例跟着红 —— 门禁重新咬得住。
 */
class KeystoreCredentialCipherTest {

    private companion object {
        /** 闭源产物里的实现类（**只按名字引用**，不编译期依赖它的内部结构）。 */
        const val CIPHER_CLASS = "com.agentsdk.cipher.KeystoreCredentialCipher"

        /**
         * 闭源产物里的「内存钥匙」实现（`internal` ⇒ 只能反射拿）。
         *
         * ⚠️ 这是**类名**，不是实现 —— 公开它不泄露任何实现细节。
         */
        const val IN_MEMORY_OPS_CLASS = "com.agentsdk.cipher.KeystoreCredentialCipher\$InMemoryKeyOps"

        /**
         * 「密钥操作」**接口** —— 上面那个构造器的**参数类型**。
         *
         * ⚠️ 反射找构造器时参数类型必须**精确匹配**（不能拿实现类去顶接口），
         *    否则报 `NoSuchMethodException`（2026-10-29 实踩）。
         */
        const val KEY_OPS_CLASS = "com.agentsdk.cipher.KeystoreCredentialCipher\$KeyOps"
    }

    /**
     * 造一个**用内存钥匙**的加密器。
     *
     * ★ 造不出来就**直接失败**（不许悄悄跳过）—— 「造不出实例」和「用例通过」必须分得开。
     */
    private fun cipher(): CredentialCipher {
        val opsClass = Class.forName(IN_MEMORY_OPS_CLASS)
        val ops = opsClass.getDeclaredConstructor().newInstance()
        val keyOpsClass = Class.forName(KEY_OPS_CLASS)
        val cipherClass = Class.forName(CIPHER_CLASS)
        val ctor = cipherClass.getDeclaredConstructor(keyOpsClass)
        ctor.isAccessible = true
        return ctor.newInstance(ops) as CredentialCipher
    }

    private val aad = "android-agent-sdk/credential/v1|deepseek|api.deepseek.com".toByteArray()

    /**
     * 造一个**基线健康**的密文包：先确认同一把钥匙、同一份 aad 下**能原样解开**，
     * 再交给「解不开」类用例去改。
     *
     * ★ 不这么做的话，「解不开」类用例在包格式整个坏掉时**照样全绿**（见类注释）。
     */
    private fun sealedWithHealthyBaseline(
        c: CredentialCipher,
        plain: ByteArray = "sk-secret".toByteArray(),
    ): ByteArray {
        val sealed = c.seal(plain, aad)
        assertContentEquals(plain, c.open(sealed, aad), "基线就该能原样解开（包格式坏了？）")
        return sealed
    }

    @Test
    fun `密封后能原样解开`() {
        val c = cipher()
        val plain = "sk-0123456789abcdef".toByteArray()
        val sealed = c.seal(plain, aad)
        assertContentEquals(plain, c.open(sealed, aad))
    }

    @Test
    fun `包格式的字节偏移是钉死的`() {
        val c = cipher()
        val plain = "sk-secret".toByteArray()
        val sealed = c.seal(plain, aad)

        // 头：magic(4) + 版本(1) + nonce 长度(1) ⇒ nonce 从第 6 字节开始
        assertEquals("ASDC", String(sealed, 0, 4, Charsets.ISO_8859_1), "magic 不对")
        assertEquals(1, sealed[4].toInt(), "版本号的位置/值不对")
        assertEquals(12, sealed[5].toInt(), "nonce 长度字段的位置/值不对")

        // nonce 占 12 字节，密文从第 18 字节开始
        val nonce = sealed.copyOfRange(6, 18)
        val body = sealed.copyOfRange(18, sealed.size)

        // ★ 最直接的偏移证据：密文体长度必须 = 明文 + GCM 标签(16 字节)。
        //   头部长度若写成「含 nonce 的 18」，body 就会少 12 字节 ⇒ 这条立刻红。
        assertEquals(plain.size + 16, body.size, "密文体长度不对（nonce/body 的偏移错了？）")

        // nonce 不该是密文的一部分（当年正是把密文开头当 nonce 读了 ⇒ Tag mismatch）
        assertFalse(
            body.copyOfRange(0, 4).contentEquals(nonce.copyOfRange(0, 4)),
            "nonce 不该和密文开头一样（当年正是读错了这一段）",
        )
    }

    @Test
    fun `换目标 host 解不开`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        val other = "android-agent-sdk/credential/v1|deepseek|evil.example".toByteArray()
        val e = assertFailsWith<CredentialException> { c.open(sealed, other) }
        assertTrue(e.message!!.isNotEmpty(), "失败要说清为什么")
    }

    @Test
    fun `换凭据名解不开`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        val other = "android-agent-sdk/credential/v1|openai|api.deepseek.com".toByteArray()
        assertFailsWith<CredentialException> { c.open(sealed, other) }
    }

    @Test
    fun `密文被改一个字节就解不开`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertFailsWith<CredentialException> { c.open(tampered, aad) }
    }

    @Test
    fun `nonce 段被改也解不开`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        val tampered = sealed.copyOf()
        // magic(4) + 版本(1) + nonce 长度(1) ⇒ nonce 从第 6 字节开始
        tampered[6] = (tampered[6] + 1).toByte()
        assertFailsWith<CredentialException> { c.open(tampered, aad) }
    }

    @Test
    fun `同一份明文两次密封的 nonce 不一样`() {
        val c = cipher()
        val plain = "sk-secret".toByteArray()
        val a = c.seal(plain, aad)
        val b = c.seal(plain, aad)
        assertFalse(a.contentEquals(b), "每次加密都必须换新 nonce")
        // ★ 差别必须**落在 nonce 段**（第 6..17 字节）——否则「nonce 不一样」可能只是密文不同。
        assertFalse(
            a.copyOfRange(6, 18).contentEquals(b.copyOfRange(6, 18)),
            "nonce 段本身必须不一样（不只是整包不一样）",
        )
    }

    @Test
    fun `密文包里没有明文`() {
        val c = cipher()
        val plain = "sk-plain-marker".toByteArray()
        val sealed = c.seal(plain, aad)
        val asText = String(sealed, Charsets.ISO_8859_1)
        assertFalse(asText.contains("sk-plain-marker"), "密文包里不该出现明文")
    }

    @Test
    fun `不是本模块的包要报错`() {
        val c = cipher()
        assertFailsWith<CredentialException> { c.open(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), aad) }
    }

    @Test
    fun `版本不认识要报错`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        val bad = sealed.copyOf()
        bad[4] = 99   // 版本号
        assertFailsWith<CredentialException> { c.open(bad, aad) }
    }

    @Test
    fun `包被截断要报错`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        assertFailsWith<CredentialException> { c.open(sealed.copyOf(5), aad) }
    }

    @Test
    fun `截断到刚好不够一个认证标签也要报错`() {
        val c = cipher()
        val sealed = sealedWithHealthyBaseline(c)
        // 头(6) + nonce(12) = 18 字节，正好少一个 GCM 标签(16 字节)
        assertFailsWith<CredentialException> { c.open(sealed.copyOf(18), aad) }
    }
}
