package ai.agentkernel.memory

import ai.agentkernel.llm.LlmClient
import ai.agentkernel.llm.openai.HeaderRedaction
import ai.agentkernel.protocol.AssistantMessage
import ai.agentkernel.protocol.ChatRequest
import ai.agentkernel.protocol.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 摘要器的**失败路径**契约。
 *
 * ## 为什么这组测试必须有
 *
 * 摘要失败以前是**完全静默**的：`summarize()` 返回 null，调用方降级成「不压缩」。
 * 静默的问题不是「失败」，是「**没人知道发生过失败**」——
 * 用户看到的是「上下文怎么越用越贵 / 越压越慢」，日志里一条线索都没有。
 *
 * 这几条守的就是：
 * - **失败仍返回 null**（`ContextMemory` 的两处调用点靠它降级）；
 * - 失败**有可辨识的类型**（超时 vs 模型报错）；
 * - 失败**有留痕**（宿主传 `onFailure` 才有）；
 * - **外层取消不被吞掉**（取消不是失败）；
 * - 失败原因里**不带凭据明文**。
 *
 * ⚠️ 末尾用 `assertIs` 的几条**显式标了 `: Unit`** ——
 * 表达式体的最后一个表达式决定方法的返回类型，而 JUnit 5 对**非 void** 的 `@Test`
 * **直接忽略**（不执行、也不报错）⇒ 必须返回 void（见 `TestVoidReturnTest`）。
 */
class ConversationSummarizerTest {

    private fun toCompress(): List<UserMessage> = listOf(UserMessage("要压的内容"))

    // ---- 成功路径 ----

    @Test
    fun `摘要成功时返回正文`() = runBlocking {
        val llm = FakeLlm(reply = "  事实：用户在配后端  ")
        val summarizer = ConversationSummarizer(llm, "test-model")

        val out = summarizer.summarize(toCompress(), targetChars = 1_200)

        assertEquals("事实：用户在配后端", out)
        assertEquals(1, llm.requests.size)
    }

    // ---- 失败：返回 null + 可辨识原因 ----

    @Test
    fun `模型报错时返回 null 并留下可辨识的原因`(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = FailingLlm(IllegalStateException("LLM HTTP 400: boom"))
        val summarizer = ConversationSummarizer(llm, "test-model", onFailure = { failures += it })

        val out = summarizer.summarize(toCompress(), targetChars = 1_200)

        // ★ 返回 null 的契约不能变 —— ContextMemory 靠它降级成「不压缩」。
        assertNull(out, "失败时必须返回 null")
        val failure = failures.singleOrNull()
        assertNotNull(failure, "失败必须留痕")
        assertIs<SummarizationFailedException>(failure, "模型报错 ⇒ SummarizationFailedException")
    }

    @Test
    fun `模型返回空内容也算失败`(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = FakeLlm(reply = "   ")
        val summarizer = ConversationSummarizer(llm, "test-model", onFailure = { failures += it })

        val out = summarizer.summarize(toCompress(), targetChars = 1_200)

        assertNull(out, "空摘要等于没压 ⇒ 返回 null")
        assertIs<SummarizationFailedException>(failures.single(), "返回空 ⇒ SummarizationFailedException")
    }

    @Test
    fun `超时会返回 null 并留下超时原因`(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = SlowLlm(delayMillis = 30_000)
        val summarizer = ConversationSummarizer(
            llm, "test-model",
            timeoutMillis = 150,
            onFailure = { failures += it },
        )

        val started = System.currentTimeMillis()
        val out = summarizer.summarize(toCompress(), targetChars = 1_200)
        val elapsed = System.currentTimeMillis() - started

        assertNull(out, "超时必须返回 null")
        assertTrue(elapsed < 10_000, "超时要及时返回，实际用了 ${elapsed}ms")
        assertIs<SummarizationTimeoutException>(failures.single(), "超时 ⇒ SummarizationTimeoutException")
    }

    /**
     * ★ **默认值本身必须被钉住**（这条是反向验证补出来的）。
     *
     * 反向验证时把构造参数的默认值从 `60_000` 改成 `null`（= 不限时），
     * **整组用例一条都没红** ⇒ 因为每条超时用例都**自己传了** `timeoutMillis`
     * ⇒ **默认值根本没人管** ⇒「摘要默认会超时」这件事其实**没被测到**。
     *
     * 为什么用反射：默认值是**私有构造参数** ⇒ 想读到它只能反射；
     * 而「等 60 秒看它到底会不会超时」不能进单元测试（一次 60 秒）。
     */
    @Test
    fun `默认超时是 60 秒（不是不限时）`(): Unit {
        val field = ConversationSummarizer::class.java.getDeclaredField("timeoutMillis")
        field.isAccessible = true
        val value = field.get(ConversationSummarizer(FakeLlm(), "test-model"))
        assertEquals(
            60_000L,
            (value as Number).toLong(),
            "默认超时必须是 60_000ms（传输层上限 120s ⇒ 必须更小；改成 null = 不限时 = 卡死风险回来了）",
        )
    }

    @Test
    fun `没有内容可压不算失败`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = FakeLlm()
        val summarizer = ConversationSummarizer(llm, "test-model", onFailure = { failures += it })

        assertNull(summarizer.summarize(emptyList(), targetChars = 1_200))
        assertTrue(failures.isEmpty(), "没有内容可压不是失败 ⇒ 不该留失败痕迹")
        assertEquals(0, llm.requests.size, "不该调 API")
    }

    // ---- 重试（默认关）----

    @Test
    fun `默认不重试`() = runBlocking {
        val llm = FailingLlm(IllegalStateException("boom"))
        val summarizer = ConversationSummarizer(llm, "test-model")

        assertNull(summarizer.summarize(toCompress(), targetChars = 1_200))
        assertEquals(1, llm.calls, "默认 retries = 0（重试 = 多一次模型调用 = 多花用户的钱）")
    }

    @Test
    fun `开了重试就再试一次`() = runBlocking {
        val llm = FlakyLlm(failuresBeforeSuccess = 1)
        val summarizer = ConversationSummarizer(llm, "test-model", retries = 1)

        val out = summarizer.summarize(toCompress(), targetChars = 1_200)

        assertEquals("摘要正文", out)
        assertEquals(2, llm.calls, "失败一次 ⇒ 应该再试一次")
    }

    @Test
    fun `重试用完仍然失败就返回 null`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = FailingLlm(IllegalStateException("boom"))
        val summarizer = ConversationSummarizer(
            llm, "test-model",
            retries = 2,
            onFailure = { failures += it },
        )

        assertNull(summarizer.summarize(toCompress(), targetChars = 1_200))
        assertEquals(3, llm.calls, "重试 2 次 ⇒ 一共 3 次调用")
        assertEquals(3, failures.size, "每次失败都要留痕（留痕要能看出试了几次）")
    }

    @Test
    fun `超时上限是总上限不是每次尝试重开`() = runBlocking {
        // 每次尝试都要 1s，但总预算只有 300ms ⇒ 不能每次尝试都重开一份预算。
        val llm = SlowLlm(delayMillis = 1_000)
        val summarizer = ConversationSummarizer(llm, "test-model", timeoutMillis = 300, retries = 3)

        val started = System.currentTimeMillis()
        assertNull(summarizer.summarize(toCompress(), targetChars = 1_200))
        val elapsed = System.currentTimeMillis() - started

        assertEquals(1, llm.calls, "总预算用完就不该再试")
        assertTrue(elapsed < 900, "总耗时要受总预算约束，实际 ${elapsed}ms")
    }

    // ---- 取消不是失败 ----

    @Test
    fun `外层取消不能被吞掉`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val llm = SlowLlm(delayMillis = 30_000)
        val summarizer = ConversationSummarizer(
            llm, "test-model",
            timeoutMillis = 60_000,
            onFailure = { failures += it },
        )

        var caught: Throwable? = null
        var out: String? = "没执行"
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                out = summarizer.summarize(toCompress(), targetChars = 1_200)
            } catch (e: Throwable) {
                caught = e
            }
        }
        job.cancel()
        job.join()

        assertTrue(
            caught is CancellationException,
            "外层取消必须原样抛出，不能被当成摘要失败：$caught",
        )
        assertEquals("没执行", out, "取消不是失败 ⇒ 不能返回 null 冒充降级")
        assertTrue(failures.isEmpty(), "取消不算失败 ⇒ 不该留失败痕迹：${failures.map { it.message }}")
    }

    // ---- 失败原因不能带凭据明文 ----

    @Test
    fun `失败原因里不能带凭据明文`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val secret = "sk-selftest-not-a-real-key-1234"
        val llm = FailingLlm(
            IllegalStateException("LLM HTTP 401: {\"error\":\"Authorization: Bearer $secret\"}")
        )
        val summarizer = ConversationSummarizer(llm, "test-model", onFailure = { failures += it })

        assertNull(summarizer.summarize(toCompress(), targetChars = 1_200))

        val text = failures.single().message.orEmpty()
        assertFalse(text.contains(secret), "失败原因里不能有凭据明文：$text")
        assertTrue(text.contains(HeaderRedaction.MASK), "应该抹掉而不是删掉：$text")
        assertTrue(text.contains("IllegalStateException"), "类型名要留着（排查要用）：$text")
    }
}

/** 假模型客户端：每次都抛同一个异常。 */
private class FailingLlm(private val error: Throwable) : LlmClient {
    var calls: Int = 0
        private set

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        calls++
        throw error
    }
}

/** 假模型客户端：先失败若干次，然后成功。 */
private class FlakyLlm(private val failuresBeforeSuccess: Int) : LlmClient {
    var calls: Int = 0
        private set

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        calls++
        if (calls <= failuresBeforeSuccess) throw IllegalStateException("boom")
        return AssistantMessage(content = "摘要正文")
    }
}

/** 假模型客户端：睡很久（模拟上游卡住）。 */
private class SlowLlm(private val delayMillis: Long) : LlmClient {
    var calls: Int = 0
        private set

    override suspend fun complete(request: ChatRequest): AssistantMessage {
        calls++
        delay(delayMillis)
        return AssistantMessage(content = "摘要正文")
    }
}
