rootProject.name = "android-agent-sdk"

pluginManagement {
    repositories {
        // 直连 google() / mavenCentral() 在国内网络不稳，优先走阿里云镜像
        // （缺什么再回落官方源）。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

include(":sdk-core")
include(":sdk-android")

// ★ 闭源加密核心的**契约测试**模块（公开版）。
//   它需要闭源产物（`cipher-contract-test/libs/cipher-classes.jar`）才能编译，
//   所以**默认不 include** ⇒ 默认构建既不需要闭源产物、也不受影响。
//   要跑它：`gradlew -PwithCipherContract=true :cipher-contract-test:test`
//   （或直接用 `scripts/verify-cipher-contract.ps1`）。
if (providers.gradleProperty("withCipherContract").orNull == "true") {
    include(":cipher-contract-test")
}
