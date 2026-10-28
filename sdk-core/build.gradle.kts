// sdk-core：纯 Kotlin/JVM 模块，**不含任何 Android 依赖**。
// 内核（agent loop / LLM 协议 / 工具调度）全部放这里，可独立跑单测。
plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    // 协议级 JSON 解析用成熟库，不手写 parser。
    //
    // 这两个必须是 **api** 而不是 implementation：
    // SDK 的公开 API 本身就暴露了 kotlinx.serialization 的类型
    // （Tool.parameters: JsonObject、ToolCall.argsOrEmpty(): JsonObject），
    // 宿主只要自己实现一个 Tool 就要用到它们 —— 声明成 implementation 的话，
    // 宿主那边根本编译不过（依赖不在它们的编译类路径上）。
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 同理：AgentKernel.runTurn 是 suspend，宿主要在自己的协程里调它。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * 发到本机 Maven 仓库，坐标与 `sdk-android` 同组同版本
 * —— `sdk-android` 的 POM 会引用它，两个必须一起发。
 */
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "ai.agentkernel"
            artifactId = "sdk-core"
            version = "1.0.0"
            from(components["java"])
            pom {
                name.set("android-agent-sdk (core)")
                description.set("端侧 agent 内核 SDK：协议 / 单轮循环 / 工具调度")
            }
        }
    }
}