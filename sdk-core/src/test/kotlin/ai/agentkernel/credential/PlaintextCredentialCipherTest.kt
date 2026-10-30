package ai.agentkernel.credential

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **明文凭据模式**（`registerPlaintext()`）—— 显式 opt-in 的那一个出口。
 *
 * 这些用例守的是**契约**，不是功能：
 * - **默认仍然安全失败**（没 opt-in ⇒ 照旧抛异常）；
 * - **不静默降级**（已注册任何实现 ⇒ 抛异常，不覆盖）；
 * - **明文模式能被认出来**（按**实际实现类型**判，不是粘滞标志）；
 * - **明文就是明文**（落盘里能读出来 —— 这正是它与加密模式的可观测差别）。
 *
 * 逐条对应 `_spec-register-plaintext.md` §5 的十条用例（⑨ 由全量回归兜住：
 * 既有 `CredentialStoreTest` 全套仍绿 ⇒ 新增 API 没改任何既有行为）。
 */
class PlaintextCredentialCipherTest {

    /**
     * 一个「真的在加密」的假实现（可逆、且明文 ≠ 密文）—— 用来冒充真实加密实现。
     *
     * 为什么要自己写一个而不是复用别的测试文件里的：这条用例的**全部意义**
     * 就是「有个**不是**明文实现的实现」⇒ 类型判定必须认得出差别。
     */
    private class ReversingCipher : CredentialCipher {
        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = plain.reversedArray()
        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = sealed.reversedArray()
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-plain-${System.nanoTime()}").also { it.mkdirs() }

    @BeforeTest
    fun clearLoader() = CredentialCipherLoader.reset()

    @AfterTest
    fun resetLoader() = CredentialCipherLoader.reset()

    // ================= 契约 =================

    /** ① 默认行为不变：没注册任何实现 ⇒ resolve 仍抛（fail-closed 不削弱）。 */
    @Test
    fun `没注册任何实现时 resolve 仍抛异常（fail-closed 不削弱）`() {
        assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
        assertFalse(CredentialCipherLoader.isAvailable())
        assertFalse(CredentialCipherLoader.isPlaintextMode())
    }

    /** ② registerPlaintext() ⇒ resolve 返回明文实现，不抛。 */
    @Test
    fun `registerPlaintext 之后 resolve 返回明文实现不抛`() {
        CredentialCipherLoader.registerPlaintext()
        val resolved = CredentialCipherLoader.resolve()
        assertTrue(resolved is PlaintextCredentialCipher, "resolve 必须返回明文实现，实际是 ${resolved::class}")
    }

    /** ③ isAvailable / isPlaintextMode 都是 true。 */
    @Test
    fun `明文模式下 isAvailable 与 isPlaintextMode 都是 true`() {
        CredentialCipherLoader.registerPlaintext()
        assertTrue(CredentialCipherLoader.isAvailable())
        assertTrue(CredentialCipherLoader.isPlaintextMode())
    }

    /**
     * ④ **落盘可观测**：明文模式落盘后，文件里能直接读到明文（base64 —— base64 不是加密）。
     *
     * ★ 这正是它与加密模式的**可观测差别**：加密模式下凭据文件里
     * **连明文的 base64 都没有**（既有用例钉着）；明文模式下**有** ⇒ 能被人直接读出来。
     */
    @Test
    fun `明文模式落盘后文件里能读到明文（base64 不是加密）`() {
        val dir = tempDir()
        CredentialCipherLoader.registerPlaintext()
        val store = CredentialStore(dir) { CredentialCipherLoader.resolve() }
        store.save("deepseek", "api.deepseek.com", "sk-plain-123456")
        val text = File(dir, "deepseek${CredentialStore.SUFFIX}").readText(Charsets.UTF_8)
        val plainB64 = Base64Codec.encode("sk-plain-123456".toByteArray(Charsets.UTF_8))
        assertTrue(
            text.contains(plainB64),
            "明文模式落盘 ⇒ 文件里必须能找到明文的 base64（可被直接读出）；实际文件：$text",
        )
        assertEquals("sk-plain-123456", store.load("deepseek"), "取回来必须就是明文")
    }

    /** ⑤ open(sealed) 与原字节相同。 */
    @Test
    fun `明文实现的 seal 与 open 都原样返回字节`() {
        val cipher = PlaintextCredentialCipher()
        val plain = "sk-abc".toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(plain, ByteArray(0))
        assertTrue(plain.contentEquals(sealed), "seal 必须原样返回")
        assertTrue(sealed.contentEquals(cipher.open(sealed, ByteArray(0))), "open 必须原样返回")
    }

    /** ⑥ ★ 不静默降级：已注册真实实现 ⇒ registerPlaintext() 抛异常。 */
    @Test
    fun `已注册真实实现时 registerPlaintext 抛异常（不静默降级）`() {
        val real = ReversingCipher()
        CredentialCipherLoader.register(real)
        assertFailsWith<CredentialException> { CredentialCipherLoader.registerPlaintext() }
        assertFalse(CredentialCipherLoader.isPlaintextMode(), "抛了也不能偷偷换掉真实现")
        assertEquals(real, CredentialCipherLoader.resolve(), "原来的实现必须还在")
    }

    /** ⑦ 状态跟着**实际实现**走：换成真实实现后 isPlaintextMode() 变 false。 */
    @Test
    fun `换成真实实现后 isPlaintextMode 变 false`() {
        CredentialCipherLoader.registerPlaintext()
        assertTrue(CredentialCipherLoader.isPlaintextMode())
        CredentialCipherLoader.register(ReversingCipher())
        assertFalse(CredentialCipherLoader.isPlaintextMode(), "判定必须看实际实现的类型")
    }

    /** ⑧ reset() 之后回到 fail-closed。 */
    @Test
    fun `reset 之后回到 fail-closed`() {
        CredentialCipherLoader.registerPlaintext()
        CredentialCipherLoader.reset()
        assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
        assertFalse(CredentialCipherLoader.isPlaintextMode())
    }

    /** ⑨-补 已注册明文实现时再 registerPlaintext() ⇒ 抛（不覆盖）。 */
    @Test
    fun `已经是明文模式时再 registerPlaintext 抛异常（不覆盖）`() {
        CredentialCipherLoader.registerPlaintext()
        assertFailsWith<CredentialException> { CredentialCipherLoader.registerPlaintext() }
        assertTrue(CredentialCipherLoader.isPlaintextMode(), "抛了也不能把明文模式悄悄关掉")
    }

    /**
     * ★ 反向验证的**钉子**：判定必须落在**实例的类型**上。
     *
     * 这条用例专门咬住「把明文模式实现成一个粘滞标志（registerPlaintext 时置 true、
     * 之后谁也不清）」那种缺陷 —— 那种实现下，本用例的 [register] 路径
     * （直接塞一个明文实例，**没调** registerPlaintext）会判错。
     */
    @Test
    fun `判定看实例类型：直接注册明文实现也算明文模式`() {
        CredentialCipherLoader.register(PlaintextCredentialCipher())
        assertTrue(
            CredentialCipherLoader.isPlaintextMode(),
            "按类名/实例注册的明文实现也必须被认出来（判定不是粘滞标志）",
        )
    }

    /** ★ 反向验证的**钉子**（二）：按类名注册明文实现也算明文模式。 */
    @Test
    fun `按类名注册明文实现也算明文模式`() {
        CredentialCipherLoader.register("ai.agentkernel.credential.PlaintextCredentialCipher")
        assertTrue(CredentialCipherLoader.isPlaintextMode(), "按类名注册也要认得出明文模式")
    }

    /**
     * ★ **只增不改**守卫：既有公开成员一个没少。
     *
     * 用反射钉住 [CredentialCipherLoader] 的公开方法签名集合 ——
     * 加方法本身安全（纯新增），但**改名/改签名/删成员**会打断已编译的宿主
     * （源码级兼容 ≠ 二进制兼容）。
     */
    @Test
    fun `既有公开成员一个没少（只增不改）`() {
        val methods = CredentialCipherLoader::class.java.declaredMethods
        fun has(name: String, vararg params: Class<*>): Boolean =
            methods.any { it.name == name && it.parameterTypes.contentEquals(params) }

        assertTrue(has("register", CredentialCipher::class.java), "register(实现) 必须还在")
        assertTrue(has("register", String::class.java), "register(类名) 必须还在")
        assertTrue(has("resolve"), "resolve 必须还在")
        assertTrue(has("isAvailable"), "isAvailable 必须还在")
        assertTrue(has("reset"), "reset 必须还在")
        // 新增的两个（纯新增 ⇒ 不影响老宿主）
        assertTrue(has("registerPlaintext"), "registerPlaintext 必须在")
        assertTrue(has("isPlaintextMode"), "isPlaintextMode 必须在")
    }
}
