package ai.agentkernel.tools.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工具输出截断的契约测试。
 *
 * ## 为什么这组测试必须有
 *
 * 回填给模型的工具输出有一个**声明好的上限**（默认 1200 字符），
 * 这个上限不只是「省 token」—— 它是**跨系统契约的一环**：
 * 下游后端也有一份工具输出上限（服务端默认同样是 1200），
 * 两边都会截。
 *
 * ⇒ 客户端截完的文本**必须 ≤ 上限**，否则后端会再截一次，
 * 后果有两个：
 * 1. 客户端自己写的「已截断 N 字符」标记被切掉、换成后端的标记；
 * 2. 后端报的 N 是按**客户端截完的文本**算的
 *    （真实缺口几千字符，它会写成几十）——
 *    那个数字是给模型看的，模型据此判断自己缺了多少数据，会被误导。
 *
 * 所以这里钉三件事：**总长 ≤ 上限**、**标记里的数字是真的**、**上限极端小时也不给空串**。
 */
class ExecOutputTruncationTest {

    // ---------- 长度契约 ----------

    @Test
    fun `截断后的总长不超过上限（标记行算进预算）`() {
        val result = ExecResult(
            exitCode = 0,
            stdout = "HEAD-MARKER\n" + "x".repeat(5000) + "\nTAIL-MARKER",
            stderr = "",
        )

        val text = result.render(1200)

        assertTrue(
            text.length <= 1200,
            "总长必须 <= 上限（标记行算进预算），实际 ${text.length} —— 超了后端会再截一次",
        )
    }

    @Test
    fun `默认上限也是硬上限`() {
        val result = ExecResult(exitCode = 0, stdout = "y".repeat(5000), stderr = "")

        val text = result.render()

        assertTrue(
            text.length <= ExecResult.DEFAULT_MAX_OUTPUT_CHARS,
            "默认上限 ${ExecResult.DEFAULT_MAX_OUTPUT_CHARS} 同样是硬线，实际 ${text.length}",
        )
    }

    // ---------- 标记里的数字是真的 ----------

    @Test
    fun `标记里的省略字符数等于真正没露给模型的原文数`() {
        val result = ExecResult(exitCode = 0, stdout = "y".repeat(5000), stderr = "")

        val text = result.render(1200)

        assertOmittedCountIsHonest(text, "5000 字符那档")
    }

    @Test
    fun `输出大到省略数的位数变化时，省略字符数仍然准确`() {
        // 省略数会从 4 位变成 6 位 —— 标记长度跟着变 ⇒ 预算跟着变。
        // 这是那个「不动点迭代」要解决的坑：算错一位，模型看到的缺口就差一个数量级。
        val result = ExecResult(exitCode = 0, stdout = "z".repeat(1_000_000), stderr = "")

        val text = result.render(1200)

        assertTrue(text.length <= 1200, "总长必须 <= 上限，实际 ${text.length}")
        assertOmittedCountIsHonest(text, "100 万字符那档")
    }

    // ---------- 极端上限 ----------

    @Test
    fun `上限小到放不下标记时，至少给原文开头，不返回空串`() {
        val result = ExecResult(exitCode = 0, stdout = "abcdefghij", stderr = "")

        val text = result.render(5)

        assertTrue(text.isNotEmpty(), "不能返回空串 —— 那看起来像「命令什么都没输出」")
        assertTrue(text.length <= 5, "还是得守住上限，实际 ${text.length}")
    }

    // ---------- 辅助 ----------

    /**
     * 从渲染结果里把标记抠出来，核对它报的「省略 N 字符」是不是真的：
     * 真正没露出来的原文字符数 = 原文总长 − 实际露出来的字符数。
     */
    private fun assertOmittedCountIsHonest(text: String, label: String) {
        val omitted = Regex("省略 (\\d+) 字符").find(text)
        assertTrue(omitted != null, "$label: 标记里要有「省略 N 字符」，实际=$text")
        val total = Regex("共 (\\d+) 字符").find(text)
        assertTrue(total != null, "$label: 标记里要有「共 M 字符」，实际=$text")

        val start = text.indexOf("\n… [输出已截断")
        val end = text.indexOf("] …\n", start) + "] …\n".length
        assertTrue(start >= 0 && end > start, "$label: 找不到标记边界，实际=$text")
        val markerLen = end - start

        val visible = text.length - markerLen
        val reported = omitted!!.groupValues[1].toInt()
        val original = total!!.groupValues[1].toInt()

        assertEquals(original - visible, reported, "$label: 标记里的省略数必须等于真正没露出来的原文数")
    }
}
