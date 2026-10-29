package ai.agentkernel.llm.openai

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「思考强度」用**哪种线格式**发出去 —— 取决于**对端是谁**。
 *
 * ## 为什么必须有这个开关
 *
 * 我们的主契约是**强度编码**（2 的幂，`reasoning_strength`），
 * 但那是**我们网关的私有字段**：网关按该模型的目录，把编码翻译成厂商档位。
 *
 * **直连厂商官方时，网关那一层根本不存在** ⇒ 编码没人翻译 ⇒
 * 厂商要么忽略这个未知字段、要么 400。
 * 而多数厂商**忽略**未知字段 ⇒ **不报错、用户拖了滑块以为设了、其实没变** ——
 * 这比报错更糟（报错至少有人知道）。
 *
 * ⇒ 所以「目标形状」必须**显式**，不能由「反正发过去再说」蒙混过关。
 *
 * ## 三种形状
 *
 * | 形状 | 发出去的字段 | 适用 |
 * |---|---|---|
 * | [GATEWAY] | `reasoning_strength` = 整数编码 | 走我们网关（**默认**，现有调用方零影响） |
 * | [DEEPSEEK] | `thinking` = `{"type":"enabled"｜"disabled"}` | DeepSeek 官方（**目前只有开关**） |
 * | [OPENAI] | `reasoning_effort` = `"minimal"｜"low"｜"medium"｜"high"` | OpenAI 系（**字符串**档位） |
 *
 * ## 强度编码 → 厂商档位（直连时没有目录，只能用内置表）
 *
 * 编码是**上限**（强度 ≤ 你给的值里该模型能达到的最高档，只向下）。
 * 网关有模型目录，所以能精确翻译；**直连时我们只有内置的厂商表**，
 * 于是取「≤ 上限」里**该厂商已知档位中最高的那个**：
 *
 * | 编码 | GATEWAY | DEEPSEEK | OPENAI |
 * |---|---|---|---|
 * | 1（关闭思考） | `1` | `thinking.type=disabled` | `minimal`（OpenAI **没有**「关闭思考」这一档，最接近的就是 minimal） |
 * | 2（low） | `2` | `thinking.type=enabled` | `low` |
 * | 4（medium） | `4` | 同上 | `medium` |
 * | 8（high） | `8` | 同上 | `high` |
 * | 16 / 32（max） | 原样 | 同上 | `high`（OpenAI 最高档就是 high） |
 *
 * ⚠️ **DeepSeek 官方目前只有开关**（`thinking.type`）⇒ 直连 DeepSeek 时，
 * 「强度上限」这个语义**无法表达**。
 * ⚠️ **准确说法是「未证实厂商支持档位」，不是「厂商限制」**
 * （实测 2026-10-29，`deepseek-flash` 与 `deepseek-v4-pro`）：
 * `reasoning_effort` 的四档**全部 200**，但**非法档 `ultra` 也 200** ⇒ 厂商**不校验**它
 * ⇒ 与「厂商认识并应用」不相容；且各档思维链长度又**全部撞 `max_tokens` 上限**
 * ⇒ **读不出「档位是否生效」** ⇒ **表现与「静默忽略」一致**
 * ⇒ SDK **不据此扩能力**（宁可少发一个字段，也不要让用户以为设了、其实没变）。
 * ⚠️ 另注：官方文档当前示例里 `reasoning_effort` 与 `thinking` **并用** ——
 * **文档写了 ≠ 厂商真支持**。
 *
 * ## 怎么选
 *
 * - 走我们的网关 ⇒ **不用管**（默认 [GATEWAY]）。
 * - 直连 DeepSeek 官方 ⇒ [DEEPSEEK]（`ChatCompletionsClient.deepSeek(...)` 工厂会**按地址自动选**）。
 * - 直连 OpenAI 系 ⇒ [OPENAI]。
 *
 * 拿不准就**显式写出来**：`reasoningWire = ReasoningWire.DEEPSEEK`。
 */
enum class ReasoningWire {
    /** 我们网关的私有形状：`reasoning_strength` = 整数强度编码。**默认**。 */
    GATEWAY,

    /** DeepSeek 官方：`thinking` = `{"type":"enabled"｜"disabled"}`（**只有开关**）。 */
    DEEPSEEK,

    /** OpenAI 系：`reasoning_effort` = 字符串档位（`minimal` / `low` / `medium` / `high`）。 */
    OPENAI,
    ;

    /**
     * 把强度编码按**本形状**写进请求体。
     *
     * @param strength 强度编码；**null ⇒ 什么都不写**（让对端用它自己的默认档位）。
     */
    internal fun putStrength(builder: JsonObjectBuilder, strength: Int?) {
        if (strength == null) return
        when (this) {
            GATEWAY -> builder.put("reasoning_strength", strength)
            DEEPSEEK -> builder.put(
                "thinking",
                buildJsonObject {
                    put("type", if (strength <= ReasoningStrength.OFF.value) "disabled" else "enabled")
                },
            )
            OPENAI -> builder.put("reasoning_effort", openAiLevel(strength))
        }
    }

    /**
     * 强度编码 → OpenAI 档位名（**取 ≤ 上限的最高已知档**，只向下）。
     *
     * OpenAI 系的已知档位是 `minimal` < `low` < `medium` < `high`（没有更高的），
     * 所以 16/32（max）落到 `high` —— 与网关那套「只向下」的语义一致。
     */
    private fun openAiLevel(strength: Int): String = when {
        strength <= ReasoningStrength.OFF.value -> "minimal"
        strength <= ReasoningStrength.LOW.value -> "low"
        strength <= ReasoningStrength.MEDIUM.value -> "medium"
        else -> "high"
    }
}
