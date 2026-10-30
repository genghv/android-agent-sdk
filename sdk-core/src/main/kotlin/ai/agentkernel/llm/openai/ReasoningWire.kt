package ai.agentkernel.llm.openai

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「思考强度」用**哪种线格式**发出去 —— 取决于**对端是谁**。
 *
 * ## 为什么必须有这个开关
 *
 * 宿主本地保存的是**强度编码**（2 的幂，`reasoning_strength`），
 * 但那是**扩展字段**：由自备后端自行解释（不是厂商标准字段）。
 *
 * **直连厂商官方时，这一层根本不存在** ⇒ 编码没人翻译 ⇒
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
 * | [GATEWAY] | `reasoning_strength` = 整数编码 | 用自备后端（**默认**，现有调用方零影响） |
 * | [DEEPSEEK] | `thinking` = `{"type":"enabled｜"disabled"}` **＋** `reasoning_effort` = `"low｜high｜max"` | DeepSeek 官方（**开关与强度是两个独立参数**） |
 * | [OPENAI] | `reasoning_effort` = `"minimal"｜"low"｜"medium"｜"high"` | OpenAI 系（**字符串**档位） |
 *
 * ## 一个方法，两种线格式（用户口径：不单独适配两套逻辑）
 *
 * 宿主本地**只存一个编码**（1 关 / 2 low / 4 medium / 8 high / 16 max / 32 xhigh），
 * 由**本文件这一个方法**按目标翻译成对应线格式 ⇒
 * 「发 8」在后端侧 = `reasoning_strength=8`，在直连 DeepSeek 侧 = `thinking` ＋ `reasoning_effort=high`
 * —— **同一个配置、两种落法，宿主不用管**。
 *
 * ## 强度编码 → 厂商档位（直连时没有目录，只能用内置表）
 *
 * 编码是**上限**（强度 ≤ 你给的值里该模型能达到的最高档，只向下）。
 * 后端有模型目录，所以能精确翻译；**直连时我们只有内置的厂商表**
 * （见 [VendorEffortProfiles]），于是取「≤ 上限」里**该厂商已知档位中最高的那个**：
 *
 * | 编码 | GATEWAY | DEEPSEEK | OPENAI |
 * |---|---|---|---|
 * | 1（关闭思考） | `1` | `thinking.type=disabled`（**不带 effort**） | `minimal`（OpenAI **没有**「关闭思考」这一档，最接近的就是 minimal） |
 * | 2（low） | `2` | `thinking.type=enabled` ＋ `low` | `low` |
 * | 4（medium） | `4` | `thinking.type=enabled` ＋ `low`（见下） | `medium` |
 * | 8（high） | `8` | `thinking.type=enabled` ＋ `high` | `high` |
 * | 16（max） | 原样 | `thinking.type=enabled` ＋ `max` | `high`（OpenAI 最高档就是 high） |
 * | 32（xhigh） | 原样 | `thinking.type=enabled` ＋ `max` | `high` |
 *
 * ## DeepSeek 官方：**开关与强度是两个独立参数**（2026-10-29 按官方文档订正）
 *
 * 官方文档：<https://api-docs.deepseek.com/guides/thinking_mode>
 *
 * | 参数 | OpenAI 兼容格式 |
 * |---|---|
 * | 思考开关 | `{"thinking": {"type": "enabled"｜"disabled"}}` |
 * | 思考强度 | `{"reasoning_effort": "low"｜"high"｜"max"}` |
 *
 * - **默认**：思考模式**默认开启**，默认 effort = **`high`**；
 * - 官方「用户设的 effort → 模型实际 effort」映射表：
 *   `minimal→low`、`low→low`、`medium→high`、`high→high`、`xhigh→high`、`max→max`、`ultra→max`
 *   ⇒ **模型的**实际档位只有 **low / high / max** 三档。
 *
 * ⚠️ **【2026-10-29 订正】** 本文件此前写「DeepSeek 官方目前只有开关 ⇒ 档位无法表达」
 * —— **那是错的，已作废**。错因：我方实测「`reasoning_effort` 四档全 200、非法档 `ultra` 也 200」
 * ⇒ 推断成「厂商**不校验** ⇒ **不支持**」。
 * ⇒ **200 只能证明「不报错」，既不能证明「不支持」，也不能证明「不校验」**
 * （`ultra` 其实**是合法请求值** —— 官方表里它映射到 `max`）。
 * ⇒ **厂商行为以官方文档为准**，实测只能用来验证、不用来否定。
 *
 * ⚠️ 由此还暴露一个**真实缺陷**（v1.1.24 修掉）：
 *    既然厂商默认 effort = `high`，那么只发开关、不发 effort
 *    ⇒ **用户选「低」也实际得到「高」** —— 正是本 SDK 一贯反对的「以为设了、其实没变」。
 *    ⇒ [DEEPSEEK] 现在**同时**发开关与强度。
 *
 * ### 强度编码 → DeepSeek 实际档位（只向下）—— **不是官方那张表**
 *
 * ⚠️ **我方这张表和官方那张表是两件事，别混**：
 *
 * - **官方那张**（「用户 effort → 模型实际 effort」）：`minimal→low`、`medium→high`……
 *   是**厂商把用户请求的 effort 归一化**的结果；
 * - **我方这张**（「我方编码（上限）→ 厂商实际档位」）：编码是**上限** ⇒
 *   取「实际档 ≤ 上限」里最高的那个。
 *
 * 为什么 medium(4) 落到 `low` 而不是发 `"medium"`：
 * 发 `"medium"` 会被厂商按官方表**抬成 high** ⇒ 实际档 **>** 请求档
 * ⇒ **违反「实际 ≤ 请求」**（这是本 SDK 的硬契约）。
 * 发 `"low"` ⇒ 实际 low ⇒ 守住「只向下」。
 *
 * ⇒ 代价是「选『中』实际得到『低』」—— 那是**厂商档位稀疏**造成的，**不是** SDK 吞了它。
 *
 * ## 怎么选
 *
 * - 用自备后端 ⇒ **不用管**（默认 [GATEWAY]）。
 * - 直连 DeepSeek 官方 ⇒ [DEEPSEEK]（`ChatCompletionsClient.deepSeek(...)` 工厂会**按地址自动选**）。
 * - 直连 OpenAI 系 ⇒ [OPENAI]。
 *
 * 拿不准就**显式写出来**：`reasoningWire = ReasoningWire.DEEPSEEK`。
 */
enum class ReasoningWire {
    /** 自备后端形状：`reasoning_strength` = 整数强度编码，**由自备后端解释该字段**。**默认**。 */
    GATEWAY,

    /**
     * DeepSeek 官方：`thinking` = `{"type":"enabled"｜"disabled"}`
     * **＋** `reasoning_effort` = `"low"｜"high"｜"max"`（**开关与强度是两个独立参数**）。
     */
    DEEPSEEK,

    /** OpenAI 系：`reasoning_effort` = 字符串档位（`minimal` / `low` / `medium` / `high`）。 */
    OPENAI,
    ;

    /**
     * 把强度编码按**本形状**写进请求体。
     *
     * @param strength 强度编码；**null ⇒ 什么都不写**（让对端用它自己的默认档位）。
     *
     * ⚠️ **「什么都不写」不是「没开思考」** ——
     * 对端默认就是「**思考开 + 强度 high**」（DeepSeek 官方如此；自备后端的服务端默认档也是 high）
     * ⇒ 不写字段 = 开 + 高。**别让人把「没发」读成「没开」。**
     */
    internal fun putStrength(builder: JsonObjectBuilder, strength: Int?) {
        if (strength == null) return
        when (this) {
            GATEWAY -> builder.put("reasoning_strength", strength)
            DEEPSEEK -> {
                val off = strength <= ReasoningStrength.OFF.value
                builder.put(
                    "thinking",
                    buildJsonObject {
                        put("type", if (off) "disabled" else "enabled")
                    },
                )
                // ⚠️ 官方默认 effort = high ⇒ **不发就等于发 high** ⇒ 用户选「低」会被静默抬成「高」。
                //    所以关闭时只发开关、开启时**必须**把强度也带上（档位见 [deepSeekLevel]）。
                if (!off) builder.put("reasoning_effort", deepSeekLevel(strength))
            }
            OPENAI -> builder.put("reasoning_effort", openAiLevel(strength))
        }
    }

    /**
     * 强度编码 → **DeepSeek 的实际档位**（`low` / `high` / `max`，**只向下**）。
     *
     * ⚠️ **这不是官方那张「用户 effort → 模型实际 effort」的表**（那张是厂商做归一化用的；
     * 官方：minimal→low / medium→high / …）。我方这张是「**我方编码（上限）→ 厂商实际档位**」。
     *
     * 为什么 medium(4) 落到 `low` 而不是发 `"medium"`：
     * 发 `"medium"` 会被厂商抬成 **high** ⇒ 实际档 **>** 请求档 ⇒ **违反「实际 ≤ 请求」**。
     * 发 `"low"` ⇒ 实际 low ⇒ 守住「只向下」。
     *
     * ⇒ 「选『中』实际得到『低』」是**厂商档位稀疏**造成的，**不是** SDK 吞了它。
     *
     * 档位表收在 [VendorEffortProfiles]（内置表的**唯一真源**）。
     */
    private fun deepSeekLevel(strength: Int): String =
        VendorEffortProfiles.DEEPSEEK.levelNameFor(strength)
            ?: error("DeepSeek 内置档位表没覆盖强度编码 $strength")

    /**
     * 强度编码 → OpenAI 档位名（**取 ≤ 上限的最高已知档**，只向下）。
     *
     * OpenAI 系的已知档位是 `minimal` < `low` < `medium` < `high`（没有更高的），
     * 所以 16/32（max）落到 `high` —— 与后端那套「只向下」的语义一致。
     *
     * 档位表收在 [VendorEffortProfiles]（内置表的**唯一真源**）。
     */
    private fun openAiLevel(strength: Int): String =
        VendorEffortProfiles.OPENAI.levelNameFor(strength)
            ?: error("OpenAI 内置档位表没覆盖强度编码 $strength")
}
