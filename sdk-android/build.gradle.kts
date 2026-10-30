// sdk-android：Android 宿主层。只放「设备能力 + 组装」，不放内核逻辑。
plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "ai.agentkernel.android"
    compileSdk = 34

    defaultConfig {
        // minSdk 24 覆盖绝大多数在用设备；maxSdk 不限制。
        minSdk = 24
        // 真机仪器化测试（设备能力那一层必须在真硬件上验：CameraManager / Vibrator
        // / ClipboardManager 这些在 JVM 上全是假实现）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 自测跑在 JVM 上；个别 android.* 调用（Os.chmod 等）返回默认值而不是抛异常。
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        // 只发 release 变体（宿主要的是可用的库，不是调试产物）。
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 内核以 api 暴露，宿主可直接引用 protocol/kernel 类型。
    api(project(":sdk-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Android 没有 java.net.http，HTTP 走 OkHttp。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 验证用：自测是 JVM 上的 AGP unit test，不需要设备、不需要 Android 模拟器。
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // 验证用：真机仪器化测试（`gradle :sdk-android:connectedDebugAndroidTest`）。
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    // ActivityScenario：给测试 App 弄一个前台焦点（Android 10+ 读剪贴板的前提）。
    androidTestImplementation("androidx.test:core:1.5.0")
}

/**
 * 发到本机 Maven 仓库（`~/.m2/repository`），让**别的工程**能按坐标依赖它。
 *
 * ```
 * gradle :sdk-android:publishToMavenLocal
 * ```
 * 之后宿主 App 就能写：
 * ```
 * repositories { mavenLocal() }
 * dependencies { implementation("ai.agentkernel:sdk-android:1.0.0") }
 * ```
 *
 * 坐标与版本写在这里（不是散在各处），`接入指南.md` 里引用的就是它。
 */
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "ai.agentkernel"
                artifactId = "sdk-android"
                version = "1.0.0"
                afterEvaluate { from(components["release"]) }
                pom {
                    name.set("android-agent-sdk (android)")
                    description.set("端侧 agent 内核 SDK 的 Android 宿主层")
                }
            }
        }
    }
}

/**
 * 用**真实 LLM API** 跑一轮（会真实计费，一次对话几百 token）。
 *
 *   set LLM_BASE_URL=https://your-gateway/v1
 *   set LLM_API_KEY=sk-xxx
 *   set LLM_MODEL=deepseek-chat
 *   gradlew :sdk-android:realLlmCheck
 *
 * 退出码 0 = 真链路跑通：模型真的下发了命令、命令真的执行了、结果真的回喂给了模型。
 * 这一档验的是「和真后端的线格式对不对得上」—— 本地 mock 照单全收，验不出这个。
 */
tasks.register<JavaExec>("realLlmCheck") {
    group = "verification"
    description = "用真实 LLM API 跑一轮（需要 LLM_BASE_URL / LLM_API_KEY 环境变量）"
    // 复用单测任务的 classpath：里面已经有编译好的测试类 + 全部依赖（含 :sdk-core）。
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    classpath = unitTest.get().classpath
    mainClass.set("ai.agentkernel.RealLlmCheckKt")
    dependsOn("compileDebugUnitTestKotlin")
}
