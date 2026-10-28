// 演示 App 的根构建脚本。
//
// 版本刻意对齐 **SDK 工程**：AGP 8.2.0 + Kotlin 2.0.21。
// 原因：SDK 是用 Kotlin 2.0.21 编译的，**Kotlin 1.9 的编译器读不了 2.0 的元数据**
// （会报 "Class ... was compiled with an incompatible version of Kotlin"），
// 所以这个 App 也不能用 Kotlin 1.9.20 —— 除非 SDK 也降级重编。
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器变成 Kotlin 插件，必须单独挂。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
