package ai.agentkernel

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「每条 @Test 都真的跑了」的守卫。
 *
 * ## 为什么要有这条
 *
 * 踩过的坑：**一条测试存在，却从来没执行过，而套件照样全绿。**
 *
 * Kotlin 里这么写：
 * ```kotlin
 * @Test
 * fun `清单不是合法 JSON 时报错`() = runBlocking {
 *     assertFailsWith<EffortCatalogException> { client("not json").fetch() }
 * }
 * ```
 * 表达式体的最后一个表达式是 `assertFailsWith` 的**返回值**（那个异常对象），
 * 于是方法被推断成「返回异常对象」⇒ 编译出的 JVM 方法**不是 `void`**。
 *
 * JUnit 5（Jupiter）对这种方法的处理是：
 * **记一条 WARNING，然后无视它** —— 不执行、不报错、不进报告。
 * 于是「用例数」少一条、断言从来没跑过，而 CI 一片绿。
 * （JUnit 4 会直接报 `should be void` 的初始化错误，所以这个坑专挑 JUnit 5。）
 *
 * 这类漏网是**看不见**的：既不会红，也不会有人注意到用例数对不上。
 * 所以这里把整个测试源码集扫一遍，只要还有非 `void` 的 `@Test`，就当场失败。
 *
 * ## 覆盖范围
 *
 * 扫的是**测试运行时 classpath 上的所有 class 文件** ——
 * 也就是本模块的测试类（`build/classes/kotlin/test`）。
 * `sdk-android` 用的是 JUnit 4（同一处非 void 会**报错**而不是静默跳过），
 * 所以那里不需要这条守卫。
 */
class TestVoidReturnTest {

    @Test
    fun `每个 @Test 方法都必须返回 void`() {
        val offenders = mutableListOf<String>()
        for (cls in testClasses()) {
            for (m in cls.declaredMethods) {
                if (!isTestMethod(m)) continue
                if (Modifier.isAbstract(m.modifiers)) continue
                if (m.returnType == Void.TYPE) continue
                if (isSuspend(m)) continue          // suspend 方法在 JVM 上返回 Object，是合法的
                offenders += "${cls.name}.${m.name}() 返回 ${m.returnType.name}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "这些 @Test 方法不是 void —— JUnit 5 会**直接忽略**它们（不执行、也不报错）：\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n改法：写成块体（`fun x() { ... }`），或显式标注返回类型 `: Unit`。",
        )
    }

    /** JUnit 5 与 JUnit 4 的 `@Test` 都算 —— 按注解全名判断，不依赖 kotlin-test 解析成哪个。 */
    private fun isTestMethod(m: Method): Boolean =
        m.annotations.any {
            it.annotationClass.qualifiedName == "org.junit.jupiter.api.Test" ||
                it.annotationClass.qualifiedName == "org.junit.Test"
        }

    private fun isSuspend(m: Method): Boolean =
        m.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" }

    /**
     * 列出 classpath 上所有能加载的类。
     *
     * 用 `Class.forName(name, false, loader)`：**不初始化**类 ——
     * 否则某些类的静态初始化块会被这里触发（改全局状态 / 起线程），那是副作用，不是检查。
     * 加载不了的类直接跳过：这条守卫只关心 `@Test` 的返回类型。
     */
    private fun testClasses(): List<Class<*>> {
        val loader = javaClass.classLoader
        val out = mutableListOf<Class<*>>()
        val cp = System.getProperty("java.class.path") ?: return out
        for (entry in cp.split(File.pathSeparator)) {
            val dir = File(entry)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { f ->
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '.')
                    val name = rel.removeSuffix(".class")
                    try {
                        out += Class.forName(name, false, loader)
                    } catch (_: Throwable) {
                        // 加载不了就跳过
                    }
                }
        }
        return out
    }
}
