package ai.agentkernel.llm.openai

import kotlinx.coroutines.flow.Flow

/**
 * 支持**真流式**的传输（**可选能力**）。
 *
 * ## 为什么需要它
 *
 * [Transport] 只有一个 `post`，**一次性拿到整个 body** ⇒ 客户端必须等 HTTP body
 * **整个结束**才开始解析 ⇒ 服务端早就把 `[DONE]` 发出来了，客户端也还在干等 ⇒
 * 「假流式」（表现：模型早答完了，界面还转圈）。
 *
 * 本接口是**可选**的：宿主想让 SDK 走真流式，就让自己的传输**同时**实现它。
 * **不实现也不影响任何既有功能** —— [Transport] 一字未改，客户端自动退回整块解析那条老路。
 *
 * ## 传输层**不感知 SSE**
 *
 * 吐的是**原始文本块**；切行、认 `data:`、认 `[DONE]` 都在客户端做 ⇒
 * 传输层只要能「按块吐」，换个协议也能用。
 */
interface StreamingTransport {
    /**
     * 发 POST，响应体**按块**吐出来。
     *
     * - 块边界**可以落在任意位置**（半个行、半个 JSON 都可能）⇒ 客户端负责缓冲；
     * - 流**不一定自己会结束**（服务端发完 `[DONE]` 却不关连接）⇒
     *   客户端见到 `[DONE]` 会**主动取消**本流 ⇒ 不等 body 结束；
     * - HTTP 错误码的处理与 [Transport.post] 一致：抛异常（消息里**先抹凭据**再截断）。
     */
    fun postStream(url: String, headers: Map<String, String>, body: String): Flow<String>
}
