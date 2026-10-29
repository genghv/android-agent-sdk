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

    /** 清空注册（测试用；宿主不该在运行期调它）。 */
    fun reset() {
        registered = null
        registeredClassName = null
        resolved = null
    }
}
