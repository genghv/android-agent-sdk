package ai.agentkernel.credential

/**
 * 凭据加密实现的**加载器** —— 开源仓里唯一的「找实现」入口。
 *
 * ## 它解决什么
 *
 * 加密核心是**闭源**的（预编译产物单独分发），所以开源仓里**没有实现**。
 * 宿主（App）拿到那个产物后，用下面任一方式把它**注进来**：
 *
 * ```
 * CredentialCipherLoader.register(KeystoreCredentialCipher())      // 直接给实例
 * CredentialCipherLoader.register("com.example.KeystoreCredentialCipher")  // 给类名（反射）
 * ```
 *
 * ## 找不到实现时怎么办：**安全地失败**
 *
 * 没有实现 ⇒ 抛 [CredentialException] ⇒ 宿主知道「凭据功能不可用」，
 * 而不是**悄悄退回明文**。
 *
 * ⚠️ **这是本设计最重要的一条**：加密没生效却照旧跑，
 * 等于「以为加密了、其实没加密」—— 比明确报错危险得多。
 * （跟我们一贯的「不知道 ≠ 没问题」是同一条纪律。）
 */
object CredentialCipherLoader {

    /** 宿主注册的实例（优先）。 */
    @Volatile
    private var registered: CredentialCipher? = null

    /** 宿主注册的类名（反射实例化；适合「产物里的那个类」）。 */
    @Volatile
    private var registeredClassName: String? = null

    /**
     * 已经解析出来的实现（**只解析一次**）。
     *
     * 为什么要缓存：按类名反射出来的实例得**有状态**（Keystore 里的密钥句柄、
     * 内部的包装密钥…），每次请求现 new 一个既慢又可能状态不一致。
     */
    @Volatile
    private var resolved: CredentialCipher? = null

    /**
     * 注册实现（实例）。
     *
     * 传 null 相当于「撤销注册」（测试与降级用）。
     */
    fun register(cipher: CredentialCipher?) {
        registered = cipher
        if (cipher != null) registeredClassName = null
        resolved = null
    }

    /**
     * 注册实现（类名）—— 闭源产物里的那个类。
     *
     * 用**完全限定名**（例如 `com.example.KeystoreCredentialCipher`），
     * 宿主按自己的包名写；**零依赖**（不需要 SDK 编译期依赖那个产物）。
     */
    fun register(className: String?) {
        registeredClassName = className
        if (className != null) registered = null
        resolved = null
    }

    /**
     * 解析出可用的实现。
     *
     * 解析成功的实例会被**缓存** —— 按类名反射出来的实例是有状态的
     * （密钥句柄之类），不能每次请求现 new 一个。
     *
     * @throws CredentialException 没有注册实现、类名 instantiation 失败、或类不是
     *   [CredentialCipher] —— **一律安全失败**，绝不返回明文路径。
     */
    fun resolve(): CredentialCipher {
        resolved?.let { return it }
        registered?.let {
            resolved = it
            return it
        }
        val className = registeredClassName
            ?: throw CredentialException(
                "凭据加密实现未注入 —— 宿主必须先 register(实现) 或 register(类名)" +
                    "（闭源产物由宿主单独集成；**不会**退回明文）"
            )
        val cls = try {
            Class.forName(className)
        } catch (e: Throwable) {
            throw CredentialException("找不到凭据实现类 $className：${e.message}", e)
        }
        val instance = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            throw CredentialException("凭据实现类 $className 实例化失败：${e.message}", e)
        }
        if (instance !is CredentialCipher) {
            throw CredentialException("凭据实现类 $className 没有实现 CredentialCipher")
        }
        resolved = instance
        return instance
    }

    /** 当前是否已注入实现（宿主 UI 可以据此决定「凭据设置」入口要不要亮）。 */
    fun isAvailable(): Boolean = registered != null || registeredClassName != null

    /**
     * **显式启用明文凭据实现**（`[PlaintextCredentialCipher]`）。
     *
     * ## 什么时候才用
     *
     * 设备上**没有 Android Keystore** 的环境（桌面 JVM、CI、单元测试、某些特殊 ROM）
     * ⇒ 闭源加密实现用不了 ⇒ 凭据功能只能 fail-closed（**整个跑不起来**）。
     * 本方法是给这些环境留的**显式 opt-in** 通道。
     *
     * ## ⚠️ 代价（宿主必须自己扛）
     *
     * 落盘就是明文（凭据文件里的 `blob` 是明文的 base64 —— base64 **不是**加密），
     * 而且**不提供 AAD 绑定与完整性** ⇒ 宿主界面必须**红字提示**、使用凭据的动作要带**审计标注**。
     *
     * ## ★ 不静默降级
     *
     * 已注册**任何**实现（真实加密实现，或已经是明文实现）⇒ 本方法**抛 [CredentialException]**，
     * **不覆盖**。把已加密的凭据降级成明文是最危险的一步 ⇒ 必须响亮失败。
     * 要换实现请先 [reset]。
     *
     * **默认行为不变**：没调本方法 ⇒ [resolve] 照旧抛异常 ⇒ **fail-closed 不削弱**。
     */
    fun registerPlaintext() {
        if (registered != null || registeredClassName != null) {
            throw CredentialException(
                "已注册凭据实现 ⇒ 拒绝切换到明文模式"
                    + "（把已加密的凭据降级成明文是最危险的一步；要换实现请先 reset()）"
            )
        }
        registered = PlaintextCredentialCipher()
        registeredClassName = null
        resolved = null
    }

    /**
     * 当前是否处于**明文模式**（宿主做 UI 红提示与审计标注的唯一判据）。
     *
     * ## 判定口径：**看当前实际用的那个实现是什么类型**，不是粘滞标志
     *
     * - 现在注册的是 [PlaintextCredentialCipher] ⇒ true；
     * - 之后 [register] 了真实加密实现 ⇒ **自动变回 false**（状态跟着实际实现走）；
     * - [reset] 之后 ⇒ false（回到 fail-closed）。
     *
     * ⚠️ **别用「有没有调过 registerPlaintext」来判** —— 那是个粘滞标志：
     * 换成真实实现之后它还写着 true ⇒ 宿主界面会一直挂着「明文存储」的红字（假警报），
     * 或者反过来漏报（更糟）。判定必须落在**实际实例的类型**上。
     */
    fun isPlaintextMode(): Boolean {
        registered?.let { return it is PlaintextCredentialCipher }
        resolved?.let { return it is PlaintextCredentialCipher }
        // 按类名注册的：只有明文实现会走这条路（闭源产物不是它）⇒ 类名对上就是明文模式。
        val className = registeredClassName ?: return false
        return className.substringAfterLast('.') == PlaintextCredentialCipher::class.java.simpleName
    }

    /** 清空注册（测试用；宿主不该在运行期调它）。 */
    fun reset() {
        registered = null
        registeredClassName = null
        resolved = null
    }
}
