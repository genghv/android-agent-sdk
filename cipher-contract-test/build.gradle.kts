plugins {
    id("com.android.library")
    kotlin("android")
}

/**
 * **闭源加密核心的契约测试**（公开版）。
 *
 * 这个模块**没有主源码**，只有一个测试源集：契约测试。
 * 契约（密文包的格式与 AEAD 语义）是**公开**的；**实现是闭源的**。
 * ⇒ 第三方可以对着**已发布的 `.aar`** 跑这份测试，独立复现
 *   「12 条契约测试全绿」这句话。
 *
 * ⚠️ **默认构建不包含这个模块** —— 它需要闭源产物才能编译。
 *   要跑它，见 `scripts/verify-cipher-contract.ps1`（或手动）：
 *   1. 把 `.aar` 里的 `classes.jar` 放到 `cipher-contract-test/libs/cipher-classes.jar`；
 *   2. `gradlew -PwithCipherContract=true :cipher-contract-test:test`
 *   `settings.gradle.kts` 里是**条件 include**（按 `-PwithCipherContract=true` 开关），
 *   所以没开关时整个模块不存在 ⇒ 默认构建不受影响。
 */
android {
    namespace = "ai.agentkernel.ciphercontract"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 契约测试只需要 SDK 的**接口与异常类型**（`CredentialCipher` / `CredentialException`）——
    // 不碰实现，也不碰任何设备能力。
    testImplementation(project(":sdk-core"))
    testImplementation(kotlin("test"))
    // 闭源产物的 classes.jar（由 `scripts/verify-cipher-contract.ps1` 从 release 资产里解出）。
    // ⚠️ **不进版本库**（.gitignore 已排除 *.jar；打包规则也排除 *.jar）。
    testImplementation(files("libs/cipher-classes.jar"))
}
