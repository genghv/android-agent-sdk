package ai.agentkernel.llm

import ai.agentkernel.llm.openai.JdkHttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **真流式传输**（`StreamingTransport`）的契约测试 —— 锁的是「流式」这件事本身的语义，
 * 不是「某个后端的线格式」。
 *
 * ## 这组测试守的是什么
 *
 * 1. **按块吐**：响应体分几次到，客户端就分几次拿到（而不是攒到 body 结束）。
 * 2. **取消立刻生效**（最要紧的一条）：客户端见到 `[DONE]` 会**主动取消**上游流
 *    ⇒ 传输层必须**立刻关掉底层 socket**。
 *    ⚠️ 服务端**故意不关连接**（body 由「连接关闭」界定）⇒ 传输层不关 socket，
 *    被取消的协程就永远卡在阻塞读里，客户端永远返回不了 —— 这条是**真卡死**，
 *    所以用 `withTimeout` 兜底：**超时即失败**。
 * 3. **UTF-8 跨块不乱码**：服务端把一个中文字**劈成两块**发 ⇒ 客户端拿到的那个字必须完好。
 * 4. **非 2xx 抛异常**，且异常消息里**不含** `Authorization` 的值（先抹后截）。
 *
 * ## 为什么自己写 mock server
 *
 * sdk-core 的测试目录里没有现成的 mock server（`MockLlmServer` 在 sdk-android 那边），
 * 而这里的 mock 还要能**故意不关连接**、**能把一个字符劈成两块** ⇒ 自己写一个最小的
 * `ServerSocket`（跟 `MockLlmServer` 同一个路子：不依赖 `com.sun.net.httpserver`）。
 */
class StreamingTransportTest {

    /** 一条假凭据（**绝不用真 key**）。 */
    private val secret = "sk-stream-selftest-not-a-real-key"

    private val authHeaders = mapOf("Authorization" to "Bearer $secret")

    // ────────────── 1. 按块吐 ──────────────

    @Test
    fun `postStream 按块吐出响应体`() = runBlocking {
        val parts = listOf("data: {\"i\":1}\n\n", "data: {\"i\":2}\n\n", "data: [DONE]\n\n")
        val body = parts.joinToString("")
        val server = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            out.write(responseHead(body.toByteArray(Charsets.UTF_8).size).toByteArray(Charsets.US_ASCII))
            for (p in parts) {
                out.write(p.toByteArray(Charsets.UTF_8))
                out.flush()
                // 拉开间隔 ⇒ 服务端确实是**分几次**发的，客户端才可能分几次拿到。
                Thread.sleep(150)
            }
        }

        server.use {
            val chunks = mutableListOf<String>()
            withTimeout(20_000) {
                JdkHttpTransport()
                    .postStream(server.url, mapOf("Content-Type" to "application/json"), "{}")
                    .collect { chunks += it }
            }
            assertEquals(body, chunks.joinToString(""), "拼起来必须等于完整响应体")
            assertTrue(chunks.size >= 2, "应该按块吐（实际收到 ${chunks.size} 块：$chunks）")
        }
    }

    // ────────────── 2. 取消立刻生效 ──────────────

    @Test
    fun `取消收集后必须立刻返回（服务端不关连接）`() = runBlocking {
        val clientGone = CompletableDeferred<Boolean>()
        val server = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            // ⚠️ **不写 Content-Length**：body 由「连接关闭」界定 ⇒ 服务端不关连接，
            //    客户端就会一直等下去（这正是要验的场景）。
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.write("data: {\"i\":1}\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
            // 等客户端断开：读返回 -1（或抛异常）= 客户端真的把连接关了。
            val eof = try {
                socket.getInputStream().read()
            } catch (_: Exception) {
                -1
            }
            clientGone.complete(eof == -1)
        }

        server.use {
            // 模拟客户端见到 [DONE] 后取消上游流（`StreamingCompletion` 就是这么干的：
            // 抛一个 Error 子类来中断收集 ⇒ 上游的 catch(Exception) 拦不住）。
            val e = assertFailsWith<StreamDone> {
                withTimeout(20_000) {
                    JdkHttpTransport()
                        .postStream(server.url, emptyMap(), "{}")
                        .collect { throw StreamDone() }
                }
            }
            assertEquals("SSE done", e.message)

            // 光「协程不返回了」还不够：**底层 socket 真的被关掉**才算数
            // （否则下一次请求会把连接池搞脏）。
            withTimeout(20_000) {
                assertTrue(clientGone.await(), "服务端应看到客户端断开连接（= socket 真的被关了）")
            }
        }
    }

    // ────────────── 3. UTF-8 跨块不乱码 ──────────────

    @Test
    fun `UTF-8 跨块不得乱码（服务端把中文字劈成两块）`() = runBlocking {
        val full = "前缀中后缀"
        val bytes = full.toByteArray(Charsets.UTF_8)
        // 劈在「中」的第一个字节之后 ⇒ 前一块的结尾是半个汉字。
        val splitAt = "前缀".toByteArray(Charsets.UTF_8).size + 1
        assertEquals(3, "中".toByteArray(Charsets.UTF_8).size, "「中」是 3 字节（劈得动才有意义）")

        val server = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            out.write(responseHead(bytes.size).toByteArray(Charsets.US_ASCII))
            out.write(bytes.copyOfRange(0, splitAt))
            out.flush()
            Thread.sleep(150)
            out.write(bytes.copyOfRange(splitAt, bytes.size))
            out.flush()
        }

        server.use {
            val sb = StringBuilder()
            withTimeout(20_000) {
                JdkHttpTransport()
                    .postStream(server.url, emptyMap(), "{}")
                    .collect { sb.append(it) }
            }
            val got = sb.toString()
            assertEquals(full, got, "劈开的那个字必须完好")
            assertFalse(got.contains('�'), "不许出现替换字符（说明按块解码了半截 UTF-8）：$got")
        }
    }

    // ────────────── 4. 非 2xx：抛异常 + 先抹后截 ──────────────

    @Test
    fun `非 2xx 抛异常且异常消息里不含凭据`() = runBlocking {
        val body = """{"error":"invalid api key $secret"}"""
        val server = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            val payload = body.toByteArray(Charsets.UTF_8)
            out.write(
                (
                    "HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            out.write(payload)
            out.flush()
        }

        server.use {
            val e = assertFailsWith<IllegalStateException> {
                withTimeout(20_000) {
                    JdkHttpTransport()
                        .postStream(server.url, authHeaders, "{}")
                        .collect { }
                }
            }
            val m = e.message!!
            assertFalse(m.contains(secret), "异常消息里不许有凭据明文：$m")
            assertTrue(m.contains("LLM HTTP 401"), "状态码要保留：$m")
        }
    }

    // ────────────── 5. 既有 post 路径没被改坏 ──────────────

    @Test
    fun `post 仍然照旧（200 返回 body；非 2xx 抛异常且消息里无凭据）`() = runBlocking {
        val ok = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            val payload = """{"choices":[]}""".toByteArray(Charsets.UTF_8)
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            out.write(payload)
            out.flush()
        }
        ok.use {
            val text = JdkHttpTransport().post(ok.url, mapOf("Content-Type" to "application/json"), "{}")
            assertEquals("""{"choices":[]}""", text)
        }

        val bad = ScriptServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            val payload = """{"error":"bad key $secret"}""".toByteArray(Charsets.UTF_8)
            out.write(
                (
                    "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            out.write(payload)
            out.flush()
        }
        bad.use {
            val e = assertFailsWith<IllegalStateException> {
                JdkHttpTransport().post(bad.url, authHeaders, "{}")
            }
            val m = e.message!!
            assertFalse(m.contains(secret), "异常消息里不许有凭据明文：$m")
            assertTrue(m.contains("LLM HTTP 400"), "状态码要保留：$m")
        }
    }
}

/**
 * 「流已结束」的**内部控制流**信号 —— 与 `StreamingCompletion` 用的同一个套路
 * （用 [Error] 而不是 [Exception]：上游的 `catch (e: Exception)` 吞不住它）。
 */
private class StreamDone : Error("SSE done") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * 极简测试服务器 —— 裸 `ServerSocket`，端口随机（0 = 随机），不占固定端口。
 *
 * 能做三件 `MockLlmServer` 做不到的事：
 * 1. **故意不关连接**（body 由「连接关闭」界定）；
 * 2. **把一个字符劈成两块**发；
 * 3. 知道**客户端什么时候断开了**（读 socket 返回 -1）。
 */
private class ScriptServer(
    private val handle: (Socket) -> Unit,
) : AutoCloseable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val sockets = mutableListOf<Socket>()

    val port: Int get() = server.localPort

    /** 客户端该用的 url。 */
    val url: String get() = "http://127.0.0.1:$port/v1/chat/completions"

    init {
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (server.isClosed) break else continue
                }
                synchronized(sockets) { sockets += client }
                Thread({
                    try {
                        handle(client)
                    } catch (_: Exception) {
                        // 客户端提前断开是正常现象，不该炸掉整个 mock。
                    }
                }, "script-server-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "script-server").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        try { server.close() } catch (_: Exception) {}
        synchronized(sockets) {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            sockets.clear()
        }
    }
}

/** 读掉整个请求（头 + 体）。 */
private fun readRequest(socket: Socket) {
    val input = socket.getInputStream()
    val head = ByteArrayOutputStream()
    var matched = 0
    var contentLength = 0
    while (true) {
        val b = input.read()
        if (b < 0) break
        head.write(b)
        matched = when {
            matched == 0 && b == CR -> 1
            matched == 1 && b == LF -> 2
            matched == 2 && b == CR -> 3
            matched == 3 && b == LF -> 4
            b == CR -> 1
            else -> 0
        }
        if (matched == 4) break
    }
    val headText = String(head.toByteArray(), Charsets.ISO_8859_1)
    for (line in headText.split("\r\n")) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        if (line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
            contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
        }
    }
    var read = 0
    val buf = ByteArray(contentLength)
    while (read < contentLength) {
        val r = input.read(buf, read, contentLength - read)
        if (r < 0) break
        read += r
    }
}

/** 带 Content-Length 的响应头。 */
private fun responseHead(bodyLength: Int): String =
    "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/event-stream\r\n" +
        "Content-Length: $bodyLength\r\n" +
        "Connection: close\r\n\r\n"

private const val CR = '\r'.code
private const val LF = '\n'.code
