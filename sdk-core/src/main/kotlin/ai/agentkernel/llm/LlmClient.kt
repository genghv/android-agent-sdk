package ai.agentkernel.llm

import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest

/**
 * 内核眼中的模型客户端：给它一个请求，拿回一条 assistant 消息。
 * 具体走哪家 API、怎么解析 SSE，都在实现类里（见 openai.ChatCompletionsClient）。
 */
interface LlmClient {
    suspend fun complete(request: ChatRequest): AssistantMessage
}