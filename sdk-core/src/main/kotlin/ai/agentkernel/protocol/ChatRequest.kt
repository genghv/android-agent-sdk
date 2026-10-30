package ai.agentkernel.protocol

import kotlinx.serialization.json.JsonObject

/**
 * 一次模型请求。tools 直接放已序列化的 function schema（见 tools.Tool.schema()）。
 *
 * @param maxTokens 本次回答的 token 上限（发给上游的 `max_tokens`）。
 *   **不填就不带这个字段**，由服务端用它的默认值。
 *   ⚠️ 思考型模型（deepseek-reasoner 等）会把**推理 token 也算在这个额度里** ——
 *   额度给小了，正文就只剩几个 token，答案会是半句。
 *   所以：**要限制，就给一个够正文写完的数**（比如 2048 / 4096），别给几十。
 * @param reasoningStrength 思考强度编码（发给上游的 `reasoning_strength`）。
 *   **不填就不带这个字段**，由服务端用它的默认档位。
 *   编码规则见 [ai.agentkernel.llm.openai.ReasoningStrength]（2 的幂，跨模型稳定）。
 *   ⚠️ 它是**上限**：服务端取「强度 ≤ 你给的值」里该模型能达到的最高档（只向下）。
 * @param extra 透传给上游的**任意** JSON 字段（后端/厂商的自定义参数）。
 *   OpenAI 线格式之外的参数（厂商扩展的思考开关之类）走这里。
 * @param sessionId 会话标识（可选，但**强烈建议带**）。
 *   发给后端的是 `session_id`。服务端据此记住「这个会话上一次请求长什么样」，
 *   从而独立复核「这一轮是不是上一轮的追加扩展」—— 也就是上游前缀缓存还剩多少。
 *   不带也能跑（服务端会按「用户 + 第一条 user 消息」派生一个键），
 *   但派生键**扛不住裁头**：裁掉最早的对话之后键会变，服务端就失去基线了。
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<JsonObject> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningStrength: Int? = null,
    val stream: Boolean = false,
    val extra: JsonObject = JsonObject(emptyMap()),
    val sessionId: String? = null,
)