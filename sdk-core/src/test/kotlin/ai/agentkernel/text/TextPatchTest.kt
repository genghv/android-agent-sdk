package ai.agentkernel.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 行级文本补丁 —— 纯 JVM。
 *
 * 测的是**语义**（行号 1 基、锚点唯一、行尾保持、报错能不能让 agent 自己纠正），
 * 不是实现细节。
 */
class TextPatchTest {

    // ---- 四种操作 ----

    @Test
    fun `replace-lines 替换指定行区间`() {
        val src = "第一行\n第二行\n第三行"
        val out = TextPatcher.apply(src, TextPatch.ReplaceLines(2, 2, "新的第二行"))
        assertEquals("第一行\n新的第二行\n第三行", out)
    }

    @Test
    fun `replace-lines 替换一段多行`() {
        val src = "a\nb\nc\nd"
        val out = TextPatcher.apply(src, TextPatch.ReplaceLines(2, 3, "X\nY"))
        assertEquals("a\nX\nY\nd", out)
    }

    @Test
    fun `replace-lines 的结束行小于起始行视为单行替换`() {
        val src = "a\nb\nc"
        val out = TextPatcher.apply(src, TextPatch.ReplaceLines(2, 1, "B"))
        assertEquals("a\nB\nc", out)
    }

    @Test
    fun `replace-lines 内容为空等于删掉这段`() {
        val src = "a\nb\nc"
        val out = TextPatcher.apply(src, TextPatch.ReplaceLines(1, 2, ""))
        assertEquals("c", out)
    }

    @Test
    fun `replace-block 按锚点替换整块`() {
        val src = "标题\n旧的一行\n旧的一行\n结尾"
        val out = TextPatcher.apply(src, TextPatch.ReplaceBlock("旧的一行\n旧的一行", "新的一行"))
        assertEquals("标题\n新的一行\n结尾", out)
    }

    @Test
    fun `replace-block 锚点命中单行时只换那一行`() {
        val src = "一\n目标行\n三"
        val out = TextPatcher.apply(src, TextPatch.ReplaceBlock("目标行", "换掉了"))
        assertEquals("一\n换掉了\n三", out)
    }

    @Test
    fun `insert-after 插到指定行之后`() {
        val src = "a\nb\nc"
        val out = TextPatcher.apply(src, TextPatch.InsertAfter(2, "插入的行"))
        assertEquals("a\nb\n插入的行\nc", out)
    }

    @Test
    fun `insert-after 行号 0 插到最前面`() {
        val src = "a\nb"
        val out = TextPatcher.apply(src, TextPatch.InsertAfter(0, "头"))
        assertEquals("头\na\nb", out)
    }

    @Test
    fun `insert-after 行号等于总行数时追加到末尾`() {
        val src = "a\nb"
        val out = TextPatcher.apply(src, TextPatch.InsertAfter(2, "尾"))
        assertEquals("a\nb\n尾", out)
    }

    @Test
    fun `delete-line 删掉那一行`() {
        val src = "a\nb\nc"
        val out = TextPatcher.apply(src, TextPatch.DeleteLine(2))
        assertEquals("a\nc", out)
    }

    // ---- 报错：agent 要能自己纠正 ----

    @Test
    fun `锚点找不到时报错并说明没找到`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.apply("a\nb", TextPatch.ReplaceBlock("不存在的锚点", "x"))
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("没找到"), msg)
        // 没找到时也得说清文件有多大，agent 才知道自己是不是数错了行
        assertTrue(msg.contains("2"), msg)
    }

    @Test
    fun `锚点命中多处时报错并列出候选行号`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.apply("重复\n中间\n重复", TextPatch.ReplaceBlock("重复", "x"))
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("2"), "要给出命中数量：$msg")
        assertTrue(msg.contains("行号"), "要给出候选行号：$msg")
        assertTrue(msg.contains("1") && msg.contains("3"), "要列出 1 和 3：$msg")
    }

    @Test
    fun `行号越界时报错并给出总行数`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.apply("a\nb\nc", TextPatch.DeleteLine(9))
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("越界"), msg)
        assertTrue(msg.contains("3"), "要给出总行数：$msg")
    }

    @Test
    fun `行号从 0 开始时报错`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.apply("a\nb", TextPatch.ReplaceLines(0, 0, "x"))
        }
        assertTrue((e.message ?: "").contains("1"), e.message)
    }

    @Test
    fun `replace-block 缺竖线时报错并给示例`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.parse("replace-block 旧标题 新标题")
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("|"), msg)
        assertTrue(msg.contains("replace-block"), msg)
    }

    @Test
    fun `不认识的命令报错`() {
        assertFailsWith<TextPatchException> { TextPatcher.parse("replace-everything 1 2") }
    }

    // ---- 转义与行尾 ----

    @Test
    fun `规格里的反斜杠 n 还原成真换行`() {
        val patch = TextPatcher.parse("replace-lines 2 2 第一段\\n第二段")
        assertEquals(TextPatch.ReplaceLines(2, 2, "第一段\n第二段"), patch)
        val out = TextPatcher.apply("a\nb\nc", patch)
        assertEquals("a\n第一段\n第二段\nc", out)
    }

    @Test
    fun `insert-after 的转义也还原成真换行`() {
        val patch = TextPatcher.parse("insert-after 1 一\\n二")
        val out = TextPatcher.apply("a\nb", patch)
        assertEquals("a\n一\n二\nb", out)
    }

    @Test
    fun `除 n 之外的反斜杠原样保留`() {
        val patch = TextPatcher.parse("replace-lines 1 1 a\\tb")
        assertEquals(TextPatch.ReplaceLines(1, 1, "a\\tb"), patch)
    }

    @Test
    fun `CRLF 原文保持 CRLF`() {
        val src = "第一行\r\n第二行\r\n第三行"
        val out = TextPatcher.apply(src, TextPatch.ReplaceLines(2, 2, "新的第二行"))
        assertEquals("第一行\r\n新的第二行\r\n第三行", out)
    }

    @Test
    fun `CRLF 原文里补丁的换行也按 CRLF 拼回`() {
        val src = "a\r\nb\r\n"
        val out = TextPatcher.apply(src, TextPatch.InsertAfter(1, "x\ny"))
        assertEquals("a\r\nx\r\ny\r\nb\r\n", out)
    }

    @Test
    fun `LF 原文不会被改成 CRLF`() {
        val src = "a\nb\n"
        val out = TextPatcher.apply(src, TextPatch.InsertAfter(1, "x"))
        assertEquals("a\nx\nb\n", out)
    }

    // ---- 规格切分 ----

    @Test
    fun `parse replace-lines 剩余全部是新内容`() {
        assertEquals(
            TextPatch.ReplaceLines(3, 4, "新的 第四行"),
            TextPatcher.parse("replace-lines 3 4 新的 第四行")
        )
    }

    @Test
    fun `parse 新内容里的竖线原样保留`() {
        assertEquals(
            TextPatch.ReplaceLines(1, 1, "a | b"),
            TextPatcher.parse("replace-lines 1 1 a | b")
        )
    }

    @Test
    fun `parse replace-block 在第一个竖线处切`() {
        assertEquals(
            TextPatch.ReplaceBlock("旧标题", "新标题|带竖线"),
            TextPatcher.parse("replace-block|旧标题|新标题|带竖线")
        )
    }

    @Test
    fun `parse insert-after 行号与内容`() {
        assertEquals(
            TextPatch.InsertAfter(2, "新内容"),
            TextPatcher.parse("insert-after 2 新内容")
        )
    }

    @Test
    fun `parse delete-line 只认整数`() {
        assertEquals(TextPatch.DeleteLine(5), TextPatcher.parse("delete-line 5"))
        assertFailsWith<TextPatchException> { TextPatcher.parse("delete-line 5 6") }
    }

    @Test
    fun `parse 行号不是整数时报错`() {
        assertFailsWith<TextPatchException> { TextPatcher.parse("replace-lines a 4 内容") }
        assertFailsWith<TextPatchException> { TextPatcher.parse("insert-after x 内容") }
    }

    @Test
    fun `多行锚点用转义写法也能匹配`() {
        val patch = TextPatcher.parse("replace-block|第一行\\n第二行|新的块")
        assertEquals(TextPatch.ReplaceBlock("第一行\n第二行", "新的块"), patch)
        val out = TextPatcher.apply("x\n第一行\n第二行\ny", patch)
        assertEquals("x\n新的块\ny", out)
    }

    // ---- describe ----

    @Test
    fun `describe 给出改动的行号`() {
        val d = TextPatcher.describe("a\nb\nc\nd", "a\nB\nc\nd")
        assertTrue(d.contains("2"), d)
    }

    @Test
    fun `describe 覆盖多行时给出行号区间`() {
        val d = TextPatcher.describe("a\nb\nc\nd\ne", "a\nx\ny\ne")
        assertTrue(d.contains("2-4"), d)
        assertTrue(d.contains("2"), d)
    }

    @Test
    fun `describe 完全相同返回无变化`() {
        assertEquals("无变化", TextPatcher.describe("a\nb", "a\nb"))
    }

    @Test
    fun `describe 删除也要带行号`() {
        val d = TextPatcher.describe("a\nb\nc", "a\nc")
        assertTrue(d.contains("2"), d)
    }

    @Test
    fun `describe 插入也要带行号`() {
        val d = TextPatcher.describe("a\nb", "a\n新\nb")
        assertTrue(d.contains("1"), d)
    }

    // ---- 边界 ----

    @Test
    fun `空文件按没有行处理`() {
        assertEquals("第一行", TextPatcher.apply("", TextPatch.InsertAfter(0, "第一行")))
    }

    @Test
    fun `空文件上按行号改会越界报错`() {
        assertFailsWith<TextPatchException> {
            TextPatcher.apply("", TextPatch.ReplaceLines(1, 1, "x"))
        }
        assertFailsWith<TextPatchException> {
            TextPatcher.apply("", TextPatch.DeleteLine(1))
        }
    }

    @Test
    fun `删除最后一行`() {
        val src = "a\nb\nc"
        assertEquals("a\nb", TextPatcher.apply(src, TextPatch.DeleteLine(3)))
    }

    @Test
    fun `删除以换行结尾的文件的最后一行不会多出空行`() {
        val src = "a\nb\n"
        assertEquals("a\n", TextPatcher.apply(src, TextPatch.DeleteLine(2)))
    }

    @Test
    fun `锚点在空文件里必然找不到`() {
        val e = assertFailsWith<TextPatchException> {
            TextPatcher.apply("", TextPatch.ReplaceBlock("任意", "x"))
        }
        assertTrue((e.message ?: "").contains("没找到"), e.message)
    }
}
