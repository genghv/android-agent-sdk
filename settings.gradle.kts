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
