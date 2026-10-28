// 根构建脚本：只声明插件版本，不在此处应用。
//
// AGP 版本说明：这里固定在 **8.2.0** —— 它与 wrapper 带的 **Gradle 8.5** 匹配，
// 对 SDK 的 API 与运行行为没有任何影响。
// ★ 想升级 AGP 的话注意：AGP 8.5+ 要求 Gradle ≥ 8.7，得连 wrapper 一起升。
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.library") version "8.2.0" apply false
}