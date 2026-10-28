package ai.agentkernel

import ai.agentkernel.android.AndroidAgentSdk
import ai.agentkernel.android.AndroidCommandExecutor
import ai.agentkernel.archive.ArchiveSegmentMeta
import ai.agentkernel.llm.LlmClient
import ai.agentkernel.memory.ContextPolicy
import ai.agentkernel.memory.MemoryEntry
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **宿主自己的提示词文件** + **容量上限接线**。
 *
 * 两个都是「宿主看不见就会出事」的东西：
 *
 * - 提示词文件读不到 ⇒ 提示词少一段（模型看不见宿主的约定）；
 *   读到了但超长 ⇒ 网关**静默砍尾巴**（没人知道提示词被削过）。
 *   ⇒ 所以「截断了没有」必须**看得见**（[SystemBudget.promptFileTruncated]）。
 * - 容量上限接错实例 ⇒ 上限形同虚设（内存态不落盘，接在只读的那个实例上没用）。
 */
class PromptFileTest {

    // ---- ① 提示词文件生效 ---------------------------------------------------

    @Test
    fun `提示词文件存在时内容进提示词并顶替补充说明`() {
        val file = promptFile("宿主自己的提示词文件内容")
        val sdk = sdk(promptFile = file, extraNotes = "额外约定代号-甲")

        val prompt = assertNotNull(sdk.resolveSystemPrompt(), "提示词不该是空的")
        assertTrue(
            prompt.contains("宿主自己的提示词文件内容"),
            "提示词文件的内容必须进提示词",
        )
        assertFalse(
            prompt.contains("额外约定代号-甲"),
            "提示词文件在场时，extraNotes 不该同时出现（两个都进去 = 重复）",
        )
        assertFalse(sdk.systemBudget().promptFileTruncated, "没超长就不该报截断")
    }

    // ---- ② 文件不存在：退回 extraNotes，不炸 ---------------------------------

    @Test
    fun `提示词文件不存在时退回补充说明且不抛异常`() {
        val missing = File(tmpRoot(), "no-such-prompt.md")
        // ★ 补充说明的内容要**有辨识度**：提示词里本来就有「## 宿主补充说明」这个**小标题**，
        //   断言内容用小标题的字样 ⇒ 小标题本身就能让断言通过（假阳性）。
        val sdk = sdk(promptFile = missing, extraNotes = "额外约定代号-乙")

        val prompt = assertNotNull(sdk.resolveSystemPrompt(), "读不到文件不该让提示词整个没了")
        assertTrue(prompt.contains("额外约定代号-乙"), "读不到文件 ⇒ 退回 extraNotes")
        assertFalse(sdk.systemBudget().promptFileTruncated)
    }

    // ---- ③ 文件超长：截断 + 留痕 --------------------------------------------

    @Test
    fun `提示词文件超长时被截断且预算里看得见`() {
        // 开头/结尾各留一个标记：光看「有没有开头」分不清截没截，
        // 必须能证明**尾巴没了**才算截断生效。
        val body = "开头标记" + "填".repeat(4_000) + "结尾标记"
        val sdk = sdk(promptFile = promptFile(body))   // 默认上限 6000 ⇒ 截断上限 3000

        val prompt = assertNotNull(sdk.resolveSystemPrompt())
        assertTrue(prompt.contains("开头标记"), "前半截该留下")
        assertFalse(prompt.contains("结尾标记"), "超长的尾巴该被砍掉")
        assertTrue(
            prompt.contains("填".repeat(2_996)),
            "截断上限 = systemBudgetLimitChars / 2 = 3000（4 字开头 + 2996 个填）",
        )
        assertFalse(prompt.contains("填".repeat(3_000)), "不该留下 3000 个填（超了上限）")
        assertTrue(
            sdk.systemBudget().promptFileTruncated,
            "截断了必须在体检里看得见 —— 否则没人知道提示词被削过",
        )
    }

    // ---- ④ 空文件 = 没有 ---------------------------------------------------

    @Test
    fun `空提示词文件当作没有并用补充说明`() {
        val file = promptFile("   \n\t  ")   // 只有空白
        val sdk = sdk(promptFile = file, extraNotes = "额外约定代号-丙")

        val prompt = assertNotNull(sdk.resolveSystemPrompt())
        assertTrue(prompt.contains("额外约定代号-丙"), "空文件不该把宿主的补充说明顶掉")
        assertFalse(sdk.systemBudget().promptFileTruncated, "空文件谈不上截断")
    }

    // ---- ⑤ 宿主自己拼的提示词优先级最高 -------------------------------------

    @Test
    fun `宿主指定提示词时提示词文件不生效`() {
        val file = promptFile("提示词文件里的内容")
        val sdk = sdk(prompt = "宿主自己拼的提示词", promptFile = file)

        assertEquals("宿主自己拼的提示词", sdk.resolveSystemPrompt())
        assertFalse(sdk.systemBudget().promptFileTruncated, "文件压根没用上，谈不上截断")
    }

    // ---- ⑥ 容量上限：归档段数 -----------------------------------------------

    @Test
    fun `归档超过段数上限后最旧的段被删掉`() {
        val sdk = sdk(
            memoryPolicy = ContextPolicy(
                archiveMaxSegments = 2,
                archiveMaxChars = 0,   // 字符上限不限，只测段数
            ),
        )
        val store = assertNotNull(sdk.archive(), "启用了 memoryPolicy 就有归档仓库")

        val ids = (1..3).map { i ->
            store.append(
                kind = ArchiveSegmentMeta.KIND_COMPACT,
                title = "第${i}段",
                summary = "第${i}段的摘要",
                messages = listOf(UserMessage("第${i}段的消息")),
            ).id
        }

        assertEquals(2, store.size(), "超了段数上限就该只剩 2 段")
        assertTrue(store.read(ids[0]) == null, "最旧的那段该被删掉（正文文件也该没了）")
        assertNotNull(store.read(ids[2]), "最新的一段永远不删")
    }

    // ---- ⑦ 容量上限：记忆条数 -----------------------------------------------

    @Test
    fun `记忆超过条数上限后最旧的条目被删掉`() {
        val sdk = sdk(
            memoryPolicy = ContextPolicy(
                maxMemoryEntries = 2,
                memoryMaxEntryChars = 0,   // 单条不截，只测条数
                memoryMaxTotalChars = 0,   // 总量不限，只测条数
            ),
        )
        val store = assertNotNull(sdk.memory(), "启用了 memoryPolicy 就有记忆").memoryStore()

        (1..3).forEach { i ->
            store.append(
                MemoryEntry(
                    id = "20260101-00000$i",
                    createdAt = 1_700_000_000_000L + i,
                    title = "标题$i",
                    summary = "正文$i",
                )
            )
        }

        assertEquals(2, store.list().size, "超了条数上限就该只剩 2 条")
        assertTrue(store.readBody("20260101-000001") == null, "最旧的那条该被删掉")
        assertNotNull(store.readBody("20260101-000003"), "最新的一条永远不删")
    }

    // ---- 替身 ---------------------------------------------------------------

    /** 不接模型的假客户端（本文件只测提示词与容量，不跑对话）。 */
    private class RecordingLlm(private val reply: String = "ok") : LlmClient {
        override suspend fun complete(request: ChatRequest): AssistantMessage =
            AssistantMessage(content = reply)
    }

    private fun sdk(
        prompt: String? = null,
        promptFile: File? = null,
        extraNotes: String = "",
        limit: Int? = 6_000,
        memoryPolicy: ContextPolicy? = null,
    ): AndroidAgentSdk {
        val base = File(System.getProperty("java.io.tmpdir"), "agentkernel-promptfile-${System.nanoTime()}")
        File(base, "home").mkdirs()
        File(base, "usr/tmp").mkdirs()
        val layout = PrefixLayout(base.path)
        return AndroidAgentSdk(
            llm = RecordingLlm(),
            model = "test-model",
            executor = AndroidCommandExecutor(shellPath = "sh", layout = layout),
            systemPrompt = prompt,
            systemBudgetLimitChars = limit,
            extraNotes = extraNotes,
            promptFile = promptFile,
            memoryPolicy = memoryPolicy,
        )
    }

    /** 造一个提示词文件（内容按 UTF-8 写，与读取口径一致）。 */
    private fun promptFile(content: String): File {
        val dir = File(tmpRoot(), "prompt-src")
        dir.mkdirs()
        return File(dir, "prompt.md").apply { writeText(content) }
    }

    private fun tmpRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-promptfile-${System.nanoTime()}")
}
