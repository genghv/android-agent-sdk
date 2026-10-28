pluginManagement {
    repositories {
        // 与 SDK 工程保持一致：国内网络先走阿里云镜像，缺什么再回落官方源。
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
        // ★ 这个演示 App 走的是**外置接入**：从本机 Maven 仓库按坐标拉 SDK，
        //   和《接入指南.md》里推荐的方式一模一样，不是 includeBuild 源码级联调。
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "AgentSdkDemo"
include(":app")
