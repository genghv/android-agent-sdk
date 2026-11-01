package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.DeviceCapabilityRegistry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `agent-http-*` 能力的测试 —— **纯 JVM 真发 HTTP**（不碰设备）。
 *
 * 这组能力是「让 agent 自己调内部数据 API」的落点，
 * 所以要验的不只是「能发请求」，还有**鉴权怎么带、失败怎么报、响应怎么截断**。
 */
class AgentHttpCapabilityTest {

    /** 能返回任意状态码的极简 HTTP 服务端（裸 ServerSocket，跟 MockLlmServer 同一路子）。 */
    private class TinyServer(
        private val status: Int = 200,
        private val body: String = "{}",
        private val statusLine: String = "OK",
        /** 额外响应头（重定向测试要发 Location）。 */
        private val extraHeaders: Map<String, String> = emptyMap(),
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val bodies = mutableListOf<String>()
        val port: Int get() = socket.localPort
        val baseUrl: String get() = "http://127.0.0.1:$port"

        init {
            Thread({
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { if (socket.isClosed) break else continue }
                    try { handle(client) } catch (_: Exception) {
                    } finally { try { client.close() } catch (_: Exception) {} }
                }
            }, "tiny-http").apply { isDaemon = true; start() }
        }

        private fun handle(client: Socket) {
            val input = client.inputStream
            val head = String(readHeaderBlock(input), Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                headers[name] = line.substring(idx + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = headers[name]?.toIntOrNull() ?: 0
                }
            }
            val bodyText = String(readExactly(input, contentLength), Charsets.UTF_8)
            synchronized(this) {
                requests += (lines[0].split(" ").getOrElse(1) { "/" } to headers)
                bodies += bodyText
            }
            val payload = body.toByteArray(Charsets.UTF_8)
            val out = client.outputStream
            val extra = buildString {
                extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            }
            out.write(
                (
                    "HTTP/1.1 $status $statusLine\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        extra +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            out.write(payload)
            out.flush()
        }

        private fun readHeaderBlock(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                out.write(b)
                matched = when {
                    matched == 0 && b == 13 -> 1
                    matched == 1 && b == 10 -> 2
                    matched == 2 && b == 13 -> 3
                    matched == 3 && b == 10 -> 4
                    b == 13 -> 1
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
    }

    private fun registryOf(vararg caps: ai.agentkernel.device.DeviceCapability) =
        DeviceCapabilityRegistry(caps.toList())

    @Test
    fun `GET 拿到状态码和响应体`() {
        TinyServer(body = """{"temperature":47}""").use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val cmd = reg.parseWhole("agent-http-get /api/weather")
            assertTrue(cmd != null, "agent-http-get 要认得出")

            val result = reg.execute(cmd!!)
            assertTrue(result.ok, "请求应成功：${result.output}")
            assertTrue(result.output.contains("\"status\":200"))
            assertTrue(result.output.contains("temperature"))
        }
    }

    @Test
    fun `相对路径会拼到基地址上`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            reg.execute(reg.parseWhole("agent-http-get /api/data")!!)
            assertEquals("/api/data", server.requests[0].first)
        }
    }

    @Test
    fun `宿主注入的鉴权头会被带上但 agent 看不见`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(
                baseUrl = server.baseUrl,
                defaultHeaders = mapOf("Authorization" to "Bearer secret-token"),
            )
            val reg = registryOf(*caps.capabilities.toTypedArray())

            // 服务端确实收到了 token
            val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)
            assertTrue(result.ok, "请求应成功：${result.output}")
            assertEquals(
                "Bearer secret-token",
                server.requests[0].second["Authorization"],
                "宿主注入的鉴权头必须真的发出去",
            )

            // 但命令的用法说明里不能出现 token —— 模型会照抄 usage
            caps.capabilities.forEach {
                assertFalse(it.usage.contains("secret-token"), "用法说明里不能带 token：${it.usage}")
            }
        }
    }

    @Test
    fun `agent 指定的 header 覆盖宿主的同名 header`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(
                baseUrl = server.baseUrl,
                defaultHeaders = mapOf("X-Tenant" to "default"),
            )
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(
                reg.parseWhole("""agent-http-get /api/data --header "X-Tenant: other"""")!!
            )
            assertTrue(result.ok, "请求应成功：${result.output}")
            assertEquals(1, server.requests.size, "服务端应收到 1 个请求")
            assertEquals("other", server.requests[0].second["X-Tenant"])
        }
    }

    @Test
    fun `header 值含中文要给出能照做的提示而不是原始异常`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(
                reg.parseWhole("""agent-http-get /api/data --header "X-Tenant: 华东区"""")!!
            )
            assertFalse(result.ok, "中文 header 值应该被拦下")
            assertTrue(result.output.contains("ASCII"), "要说清是 ASCII 的限制：${result.output}")
            assertTrue(result.output.contains("%E5"), "要给出百分号编码的出路：${result.output}")
            assertFalse(
                result.output.contains("Unexpected char"),
                "不该把 OkHttp 的原始异常抛给模型：${result.output}",
            )
        }
    }

    @Test
    fun `非 2xx 如实报错并带上响应片段`() {
        TinyServer(status = 403, statusLine = "Forbidden", body = """{"error":"token 已过期"}""").use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)

            assertFalse(result.ok, "403 不能算成功")
            assertEquals(1, result.exitCode)
            assertTrue(result.output.contains("\"ok\":false"))
            assertTrue(result.output.contains("403"), "要带状态码：${result.output}")
            assertTrue(result.output.contains("token 已过期"), "要带响应片段：${result.output}")
        }
    }

    @Test
    fun `响应体超上限会截断并标记`() {
        TinyServer(body = "x".repeat(5000)).use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl, maxBytes = 100)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)

            assertTrue(result.ok)
            assertTrue(result.output.contains("\"truncated\":true"), "截断必须标出来：${result.output.take(300)}")
        }
    }

    @Test
    fun `POST 能带 body`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(
                reg.parseWhole("""agent-http-post /api/query --data "{\"q\":\"天气\"}"""")!!
            )
            assertTrue(result.ok, result.output)
            assertTrue(server.bodies[0].contains("天气"), "body 要原样送到：${server.bodies[0]}")
        }
    }

    @Test
    fun `没有基地址时相对路径要明确报错`() {
        val caps = AgentHttpCapabilities(baseUrl = "")
        val reg = registryOf(*caps.capabilities.toTypedArray())
        val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)
        assertFalse(result.ok)
        assertTrue(result.output.contains("基地址"), "要说清缺什么：${result.output}")
    }

    @Test
    fun `参数用错要给出用法而不是瞎跑`() {
        val caps = AgentHttpCapabilities(baseUrl = "http://127.0.0.1:1")
        val reg = registryOf(*caps.capabilities.toTypedArray())
        val result = reg.execute(reg.parseWhole("agent-http-get")!!)
        assertFalse(result.ok)
        assertTrue(result.output.contains("用法"), "要说清用法：${result.output}")
    }

    @Test
    fun `内部命令的前缀是 agent- 不是 termux-`() {
        val caps = AgentHttpCapabilities(baseUrl = "http://127.0.0.1:1")
        caps.capabilities.forEach {
            assertEquals(ai.agentkernel.device.CapabilityOrigin.INTERNAL, it.origin)
            assertTrue(it.usage.startsWith("agent-"), "内部命令要用 agent- 前缀：${it.usage}")
            assertFalse(it.usage.startsWith("termux-"), "内部命令不能冒充 Termux 命令：${it.usage}")
        }
    }

    @Test
    fun `模型不许自己传鉴权头（否则会把宿主 token 顶掉）`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())

            val result = reg.execute(
                reg.parseWhole("agent-http-get /api/data --header \"Authorization: Bearer stolen\"")!!,
            )
            assertFalse(result.ok, "模型传 Authorization 必须被拒绝")
            assertTrue(
                result.output.contains("由宿主注入"),
                "错误要说清「宿主注入、不用你传」：${result.output}",
            )
            assertTrue(server.requests.isEmpty(), "被拒绝的请求不该真的发出去")
        }
    }

    // ---------------------------------------------------------------- M1 安全修复（token 外泄）

    @Test
    fun `完整 URL 一律拒绝（提示词注入也送不走 token）`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(
                baseUrl = server.baseUrl,
                defaultHeaders = mapOf("Authorization" to "Bearer session-token"),
            )
            val reg = registryOf(*caps.capabilities.toTypedArray())

            listOf(
                "https://evil.example/steal",
                "http://evil.example/steal",
                "HTTPS://evil.example/steal",
                "//evil.example/steal",
                "file:///data/data/x/files",
                // 连「本站的绝对 URL」也不收：规则是**只收相对路径**，不做「同源就放行」的例外。
                "${server.baseUrl}/steal",
            ).forEach { target ->
                val result = reg.execute(reg.parseWhole("agent-http-get $target")!!)
                assertFalse(result.ok, "$target 必须被拒：${result.output}")
                assertEquals(1, result.exitCode)
                assertTrue(
                    result.output.contains("相对路径") || result.output.contains("完整 URL"),
                    "要说清只允许相对路径：${result.output}",
                )
                assertFalse(result.output.contains("session-token"), "报错里不能回显 token")
            }
            assertTrue(server.requests.isEmpty(), "被拒的请求一个都不该发出去")
        }
    }

    @Test
    fun `同源判定按 scheme host port 归一化比较`() {
        assertTrue(isSameOrigin("https://host.example/api", "https://host.example/v1/x"))
        assertTrue(
            isSameOrigin("https://host.example", "HTTPS://Host.Example:443/x"),
            "大小写与默认端口要归一化 —— 否则会误判成跨源",
        )
        assertTrue(isSameOrigin("http://127.0.0.1:8080", "http://127.0.0.1:8080/api"))
        assertFalse(isSameOrigin("https://host.example", "https://evil.example/x"), "换主机不行")
        assertFalse(isSameOrigin("https://host.example", "http://host.example/x"), "换 scheme 不行")
        assertFalse(isSameOrigin("https://host.example", "https://host.example:8443/x"), "换端口不行")
        assertFalse(isSameOrigin("", "https://host.example/x"), "空基地址不算同源")
    }

    @Test
    fun `跳转不跟随 token 不会送到另一台主机`() {
        TinyServer().use { evil ->
            TinyServer(
                status = 302,
                statusLine = "Found",
                extraHeaders = mapOf("Location" to "${evil.baseUrl}/steal"),
            ).use { host ->
                val caps = AgentHttpCapabilities(
                    baseUrl = host.baseUrl,
                    defaultHeaders = mapOf("Authorization" to "Bearer session-token"),
                )
                val reg = registryOf(*caps.capabilities.toTypedArray())
                val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)

                assertFalse(result.ok, "3xx 必须报错而不是跟过去：${result.output}")
                assertTrue(result.output.contains("跳转"), "要说清是不跟随跳转：${result.output}")
                assertFalse(result.output.contains("session-token"), "报错里不能回显 token")
                assertEquals(1, host.requests.size, "本站该收到那一次请求")
                assertTrue(evil.requests.isEmpty(), "另一台主机一个请求都不该收到（token 没外泄）")
            }
        }
    }

    @Test
    fun `max-bytes 超过 2MB 硬上限被拒`() {
        TinyServer().use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())

            listOf("2097153", "99999999", "0", "-1").forEach { value ->
                val result = reg.execute(reg.parseWhole("agent-http-get /api/data --max-bytes $value")!!)
                assertFalse(result.ok, "--max-bytes $value 应被拒：${result.output}")
                assertTrue(result.output.contains("--max-bytes"), "报错要点名参数：${result.output}")
            }
            assertTrue(server.requests.isEmpty(), "参数没过就不该发请求")
        }
    }

    @Test
    fun `响应体超硬上限会中断并标记`() {
        // 3 MB 响应 + 构造参数给 4 MB（会被夹到 2 MB 硬上限）⇒ 只能读到 2 MB 就停。
        TinyServer(body = "y".repeat(3 * 1024 * 1024)).use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl, maxBytes = 4 * 1024 * 1024)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(reg.parseWhole("agent-http-get /api/data")!!)

            assertTrue(result.ok, result.output.take(300))
            assertTrue(result.output.contains("\"truncated\":true"), "必须标截断")
            assertTrue(
                result.output.contains("\"bytes\":${2 * 1024 * 1024}"),
                "读到的字节数应正好等于硬上限 2 MB：${result.output.take(200)}",
            )
        }
    }

    @Test
    fun `截断处不劈开汉字`() {
        // 每个汉字 3 字节；50 字节 = 16 个字 + 半个字，必须退回到字符边界。
        val text = "中文技能说明".repeat(20)
        TinyServer(body = text).use { server ->
            val caps = AgentHttpCapabilities(baseUrl = server.baseUrl)
            val reg = registryOf(*caps.capabilities.toTypedArray())
            val result = reg.execute(reg.parseWhole("agent-http-get /api/data --max-bytes 50")!!)

            assertTrue(result.ok, result.output.take(300))
            assertTrue(result.output.contains("\"truncated\":true"))
            assertFalse(
                result.output.contains("\uFFFD"),
                "不能在汉字中间截断（会变乱码）：${result.output.take(200)}",
            )
        }
    }
}
