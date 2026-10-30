package ai.agentkernel.credential

/**
 * **明文**凭据实现 —— [CredentialCipherLoader.registerPlaintext] 装进来的那一个。
 *
 * ## 它是给谁用的
 *
 * 设备上**没有 Android Keystore** 的环境（桌面 JVM、CI、单元测试、某些特殊 ROM）
 * ⇒ 闭源加密实现用不了 ⇒ 现在只能 **fail-closed**（凭据功能整个跑不起来）。
 * 本类是给这些环境留的**显式 opt-in** 通道：宿主明确调了
 * [CredentialCipherLoader.registerPlaintext]，凭据功能才跑得起来。
 *
 * ⇒ **默认仍然是安全失败**（没调 [CredentialCipherLoader.registerPlaintext] ⇒
 * [CredentialCipherLoader.resolve] 照旧抛 [CredentialException]）。
 *
 * ## ⚠️ 它弱化了什么（必须如实说）
 *
 * **它不提供机密性，也不提供 AAD 绑定与完整性**：
 *
 * - **不加密**：落盘的就是明文（凭据文件里的 `blob` 是它的 base64 —— base64 **不是**加密）；
 * - **不绑 AAD**：不绑「名字 + 目标 host」⇒ 凭据文件换用途/换 host **照样能用**
 *   （加密模式下这一条是硬约束：换了就解不开）；
 * - **不验完整性**：字节被改过也照样解得开。
 *
 * ⇒ 所以它**只在开发/诊断环境**用。宿主界面必须**红字提示**「当前凭据以明文存储」，
 * 且使用凭据的动作要带**审计标注**（判据：[CredentialCipherLoader.isPlaintextMode]）。
 *
 * ## 它明确不是什么
 *
 * - 不是「默认改用明文」（默认仍是安全失败）；
 * - 不是「自动降级」（已注册任何实现时，[CredentialCipherLoader.registerPlaintext] **抛异常**）；
 * - 不是「假加密」（**不做**「其实还能再包一层」那套 —— 名副其实才是能用来诊断的前提）。
 */
class PlaintextCredentialCipher : CredentialCipher {

    /**
     * **原样返回**（复制一份，避免调用方事后改动数组影响已落盘的内容）。
     *
     * ⚠️ **不加密、不绑 AAD、不验完整性** —— 落盘内容就是明文。
     */
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = plain.copyOf()

    /**
     * **原样返回**。
     *
     * ⚠️ **不做任何校验**：不验 AAD、不验完整性 —— 字节被改过也照样解得开。
     * 这正是「明文模式」的定义（也是它比加密模式弱的地方）。
     */
    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = sealed.copyOf()
}
