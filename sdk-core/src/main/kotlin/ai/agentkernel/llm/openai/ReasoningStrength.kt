package ai.agentkernel.llm.openai

/**
 * 思考强度编码（**2 的幂**，位序 = 强度序）。
 *
 * ## 为什么不用「第几项」
 *
 * 位置编号会随模型漂移：`deepseek-flash` 没有 medium —— 编号 3 在它是 high、
 * 在别的模型是 medium，**同一个数字在不同模型上指不同档**，用户存下来的值就废了。
 *
 * 强度编码把强度**编码进数值**：`high` 在任何模型上都是 8，
 * **用户上次存的值换模型后仍然有效**；加新档位只追加更高的位（32、64…），不用重排旧值。
 *
 * ## 它是「上限」，不是「档位」
 *
 * 后端取「强度 ≤ 你给的值」里该模型能达到的最高档：
 * - 请求 8(high)、模型只有 low/medium → 实际 medium（**只向下**，并留痕 warn）；
 * - 请求 64、模型最高 max(16) → 实际 max；
 * - 请求 2(low)、模型只有 high/max → **400**（只剩「不思考」可选，那是语义反转，不静默替代）。
 *
 * ## 别拿这里的常量当「这个模型有这档」的证据
 *
 * 下面的 [LOW] / [HIGH] 等只是**常见取值**。某个模型支不支持某档，要问服务端 ——
 * 用 [EffortCatalogClient] 拉清单。
 * **自己抄一份档位表 = 迟早跟服务端对不上**（模型一上新就错）。
 */
@JvmInline
value class ReasoningStrength(val value: Int) {

    init {
        require(value > 0) { "强度编码必须为正：$value" }
        require(value and (value - 1) == 0) { "强度编码必须是 2 的幂：$value（收到 $value）" }
    }

    /** 二进制形态（展示用，如 `0b01000`）。 */
    val bits: String get() = "0b" + value.toString(2).padStart(5, '0')

    override fun toString(): String = "$value ($bits)"

    companion object {
        /** 关闭思考（不发 effort，走内核的 purpose 通道）。强度 1 = 2^0。 */
        val OFF = ReasoningStrength(1)

        /** 低。 */
        val LOW = ReasoningStrength(2)

        /** 中（**不是每个模型都有** —— deepseek-flash 就没有）。 */
        val MEDIUM = ReasoningStrength(4)

        /** 高（官方默认档）。 */
        val HIGH = ReasoningStrength(8)

        /** 最高。 */
        val MAX = ReasoningStrength(16)

        /** 超高（**只有模型自己声明了才有**）。 */
        val XHIGH = ReasoningStrength(32)
    }
}
