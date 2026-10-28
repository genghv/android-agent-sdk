package ai.agentkernel

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 OpenAI 兼容 mock 服务端 —— 只依赖 `java.net`，**任何 JVM / Android 都能跑**。
 *
 * 为什么不用 `com.sun.net.httpserver`：那在 Android 上不存在，而这套 mock 将来
 * 要能原样搬到真机 instrumented test 里跑。
 *
 * 它只做三件事：
 * 1. 收 POST，把请求体与请求头按到达顺序记下来（供断言「服务端到底看见了什么」）；
 * 2. 按脚本回一段响应（普通 JSON 或 SSE）；
 * 3. 端口随机（0 = 随机），不占固定端口，可并行跑。
 *
 * 读请求体走**裸字节**而不是 BufferedReader：BufferedReader 会预读，
 * 头和体混在一个缓冲里时容易读串。这里头/body 分开按字节精确读。
 */
class MockLlmServer(
    private val responder: (round: Int, body: String) -> Reply,
) : AutoCloseable {

    /** 一次响应。contentType 区分普通 JSON 与 SSE。 */
    data class Reply(val body: String, val contentType: String = "application/json")

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val round = AtomicInteger(0)
    private val lock = Any()

    /** 每次收到的请求体，按到达顺序。 */
    val requestBodies = mutableListOf<String>()

    /** 每次收到的请求头，按到达顺序。 */
    val requestHeaders = mutableListOf<Map<String, String>>()

    /** 每次收到的请求路径（如 `/v1/chat/completions`），按到达顺序。 */
    val requestPaths = mutableListOf<String>()

    val port: Int get() = socket.localPort

    /** 客户端该用的 baseUrl（末尾不带斜杠）。 */
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    init {
        Thread({ acceptLoop() }, "mock-llm-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (socket.isClosed) break else continue
            }
            try {
                handle(client)
            } catch (_: Exception) {
                // 测试里连接被提前关掉是正常现象，不该炸掉整个 mock
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()

        // 1) 头：一直读到空行（\r\n\r\n）
        val headText = String(readHeaderBlock(input), Charsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        // lines[0] 是请求行：METHOD PATH HTTP/1.1

        val headers = mutableMapOf<String, String>()
        var contentLength = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
            if (name.equals("Content-Length", ignoreCase = true)) {
                contentLength = value.toIntOrNull() ?: 0
            }
        }

        // 2) 体：按 Content-Length 精确读
        val body = String(readExactly(input, contentLength), Charsets.UTF_8)

        val n = round.incrementAndGet()
        synchronized(lock) {
            requestBodies += body
            requestHeaders += headers
            // 请求行是 "METHOD PATH HTTP/1.1"
            requestPaths += lines[0].split(" ").getOrElse(1) { "/" }
        }

        val reply = responder(n, body)
        val payload = reply.body.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${reply.contentType}\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
        )
        out.write(payload)
        out.flush()
    }

    /** 读到 \r\n\r\n 为止（含），返回原始字节。 */
    private fun readHeaderBlock(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
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
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    private companion object {
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}
