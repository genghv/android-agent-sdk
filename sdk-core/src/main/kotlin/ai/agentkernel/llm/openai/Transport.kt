package ai.agentkernel.llm.openai

/**
 * HTTP 传输抽象。内核不绑死 HTTP 客户端：
 * - JVM / 单测：用 [JdkHttpTransport]（java.net.http）；
 * - Android：用 OkHttp 版本（见 sdk-android 模块），因为 Android 没有 java.net.http。
 */
fun interface Transport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): String
}

/**
 * 支持 GET 的传输（**可选能力**）。
 *
 * [Transport] 只有 POST —— agent 循环本身只发 POST，够了。
 * 但「拉服务端档位清单」是 GET（`GET /efforts`），所以另开一个接口：
 * 宿主想用清单能力，就让自己的传输**同时**实现这两个接口。
 *
 * **不实现也不影响任何既有功能** —— [Transport] 一字未改，
 * SDK 里凡是依赖清单的入口都只在你显式调用时才需要它。
 */
interface GetTransport {
    suspend fun get(url: String, headers: Map<String, String>): String
}